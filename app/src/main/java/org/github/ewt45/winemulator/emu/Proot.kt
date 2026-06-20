package org.github.ewt45.winemulator.emu

import android.content.Context
import android.util.Log
import androidx.compose.ui.text.toLowerCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import org.apache.commons.io.FileUtils
import org.github.ewt45.winemulator.Consts
import org.github.ewt45.winemulator.Consts.Pref.general_shared_ext_path
import org.github.ewt45.winemulator.Consts.Pref.proot_bool_options
import org.github.ewt45.winemulator.Consts.rootfsCurrL2sDir
import org.github.ewt45.winemulator.Utils.chmod
import org.github.ewt45.winemulator.emu.ProotHelper.DEFAULT_FAKE_KERNEL_VERSION
import org.github.ewt45.winemulator.glibc.ImageFs
import org.github.ewt45.winemulator.glibc.GlibcProgramLauncher
import org.github.ewt45.winemulator.glibc.GlibcWineBridge
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 连接linux的终端。输入命令或获取输出
 *
 * [v2 — glibc-bridge]
 * 在原版基础上,把"在 proot 里跑 wine"升级为"proot 跑桌面 + Android 进程
 * 通过 box64 跑 wine"。具体机制见 [org.github.ewt45.winemulator.glibc]
 * 包的文档;在用户视角,这只是新增了一个 `glibc-run <args>` 命令。
 *
 * 接口兼容性:
 *  - `Proot()` / `attach(): ProcessBuilder` 跟原版完全一致。
 *  - `Proot.lastTimeCmd` 静态字段保持(供终端显示上一次启动命令)。
 *  - **新增** `attach(ctx: Context): ProcessBuilder` 重载,带 context
 *    才能拉起 glibc-bridge。建议调用方迁移到新重载。
 */
class Proot {
    private val TAG = "Proot"

    companion object {
        /** 上次执行proot时的完整命令, 仅用于显示，可能无法真正用于执行 */
        var lastTimeCmd = ""

        /** 当前活跃的 glibc-bridge 实例。由 [attach] 设置,Activity onDestroy 时清理。 */
        @Volatile var activeBridge: GlibcWineBridge? = null

        /** glibc-run 桥脚本内容(嵌入在代码里,启动 proot 时写到 rootfs)。 */
        val GLIBC_RUN_SH: String = """
            #!/bin/sh
            # /usr/local/bin/glibc-run — invoked inside the proot container.
            # Forwards a wine command to the Android-side GlibcWineBridge.
            set -e
            ENDPOINT="${'$'}{LINBOX_GLIBC_ENDPOINT:-abstract:linbox-glibc-bridge}"
            KEY="glibc-${'$'}{$}-$(date +%s%N)"
            if [ "$#" -eq 0 ]; then
                echo "usage: $0 <wine-args...>" >&2
                exit 2
            fi
            PAYLOAD="EXEC	${'$'}{KEY}	${'$'}{*}"
            USE_SOCKET=0
            case "$ENDPOINT" in
                abstract:*|/*)
                    if command -v socat >/dev/null 2>&1; then USE_SOCKET=1
                    elif command -v ncat >/dev/null 2>&1; then USE_SOCKET=1
                    fi
                    ;;
            esac
            if [ "$USE_SOCKET" -eq 1 ]; then
                SOCK="$ENDPOINT"
                case "$SOCK" in abstract:*) SOCK="abstract:${'$'}{SOCK#abstract:}";; esac
                if command -v socat >/dev/null 2>&1; then
                    printf '%s\n' "$PAYLOAD" | socat - "UNIX-CONNECT:$SOCK"
                    exit ${'$'}?
                fi
                if command -v ncat >/dev/null 2>&1; then
                    printf '%s\n' "$PAYLOAD" | ncat -U -- "$SOCK"
                    exit ${'$'}?
                fi
                echo "no socat/ncat available" >&2
                exit 3
            fi
            IN_FIFO="${'$'}{ENDPOINT%|*}"
            OUT_FIFO="${'$'}{ENDPOINT#*|}"
            exec 3>"$OUT_FIFO"
            exec 4<"$IN_FIFO"
            printf '%s\n' "$PAYLOAD" >&4
            EXIT_CODE=0
            while IFS= read -r line <&4; do
                verb=$(printf '%s' "$line" | cut -f1)
                rest=$(printf '%s' "$line" | cut -f3-)
                case "$verb" in
                    OK)  : ;;
                    OUT) printf '%s\n' "$rest" ;;
                    END) EXIT_CODE="$rest"; break ;;
                    ERR) printf 'ERROR: %s\n' "$rest" >&2; EXIT_CODE=1; break ;;
                    *)   printf '%s\n' "$line" ;;
                esac
            done
            exec 4<&-
            exec 3>&-
            exit "$EXIT_CODE"
        """.trimIndent()
    }

    /**
     * 无 context 版本(向后兼容原 API)。**不会启动 glibc-bridge**;
     * 如果调用方需要 bridge,请用 [attach] 的 context 重载。
     */
    suspend fun attach(): ProcessBuilder = withContext(Dispatchers.IO) {
        return@withContext attachInternal(null)
    }

    /**
     * 带 context 版本。**推荐使用** —— 启动 proot 前会同时拉起
     * glibc-bridge,把 endpoint 注入 proot 环境变量,自动装 glibc-run
     * sh 脚本到 rootfs。
     */
    suspend fun attach(ctx: Context): ProcessBuilder = withContext(Dispatchers.IO) {
        return@withContext attachInternal(ctx)
    }

    private fun attachInternal(ctx: Context?): ProcessBuilder {
        val rootfs = Consts.rootfsCurrDir
        val tmpdir = Consts.tmpDir
        val lang = Consts.Pref.general_rootfs_lang.get()

        rootfsCurrL2sDir.mkdirs()
        chmod(rootfsCurrL2sDir, "755")
        ProotHelper.setup_fake_data()
        editEtcLocaleGen(rootfs, lang)
        installGlibcRunSh(rootfs)

        val userInfo = ProotRootfs.getPreferredUser(rootfs.canonicalFile.name)

        val prootCmd = mutableListOf(
            Consts.prootBin.absolutePath,
            *proot_bool_options.get().toTypedArray(),
            "--kernel-release=$DEFAULT_FAKE_KERNEL_VERSION",
            "--rootfs=${rootfs.absolutePath}",
            "--change-id=${userInfo.uid}:${userInfo.gid}",
            "--cwd=${userInfo.home}",
            "--bind=${tmpdir.absolutePath}:/tmp",
            "--bind=${rootfs.absolutePath}/tmp:/dev/shm",
            "--bind=/sys",
            "--bind=/proc/self/fd:/dev/fd",
            "--bind=/proc",
            "--bind=/dev/urandom:/dev/random",
            "--bind=/dev",
        )

        File("/dev/stderr").takeIf { !it.exists() }?.let {
            prootCmd.add("--bind=/proc/self/fd/2:/dev/stderr")
        }
        File("/dev/stdout").takeIf { !it.exists() }?.let {
            prootCmd.add("--bind=/proc/self/fd/1:/dev/stdout")
        }
        File("/dev/stdin").takeIf { !it.exists() }?.let {
            prootCmd.add("--bind=/proc/self/fd/0:/dev/stdin")
        }

        ProotHelper.setup_fake_data()
        prootCmd.add("--bind=${rootfs.absolutePath}/sys/.empty:/sys/fs/selinux")
        prootCmd.addAll(
            mapOf(
                "/proc/.loadavg" to "/proc/loadavg",
                "/proc/.stat" to "/proc/stat",
                "/proc/.uptime" to "/proc/uptime",
                "/proc/.version" to "/proc/version",
                "/proc/.vmstat" to "/proc/vmstat",
                "/proc/.sysctl_entry_cap_last_cap" to "/proc/sys/kernel/cap_last_cap",
                "/proc/.sysctl_inotify_max_user_watches" to "/proc/sys/fs/inotify/max_user_watches",
            ).mapNotNull { bindIfNotReadable(rootfs, it.key, it.value) })

        prootCmd.addAll(general_shared_ext_path.get().map { bindPath ->
            File(rootfs, bindPath).runCatching { takeIf { FileUtils.isSymlink(it) }?.delete() }
            "--bind=$bindPath"
        })

        // ============================================================
        // glibc-bridge: 启动 box64+wine 守护, 暴露 endpoint 给 proot
        // ============================================================
        var bridgeEndpoint = ""
        if (ctx != null) {
            try {
                val imagefs = ImageFs.find(ctx)
                ImageFs.ensureLayout(imagefs)
                val launcher = GlibcProgramLauncher(ctx, imagefs)
                runCatching { launcher.ensureInstalled() }
                        .onFailure { Log.w(TAG, "glibcfs install failed: ${it.message}") }

                val bridgeDir = File(tmpdir, "linbox-glibc")
                bridgeDir.mkdirs()
                val mode = when (Consts.Pref.glibc_bridge_mode.get()) {
                    "unix_socket" -> GlibcWineBridge.Mode.UNIX_SOCKET
                    "fifo" -> GlibcWineBridge.Mode.FIFO
                    else -> GlibcWineBridge.Mode.AUTO
                }
                val bridge = GlibcWineBridge(
                    fs = imagefs,
                    launcher = launcher,
                    prootEndpointDir = bridgeDir,
                    mode = mode
                )
                bridge.start()
                activeBridge = bridge
                bridgeEndpoint = bridge.prootEndpoint
                Log.i(TAG, "glibc-bridge started, endpoint=$bridgeEndpoint")

                // 把 bridge 端点目录挂进 proot(FIFO 模式时需要,socket 模式只是保险)
                prootCmd.add("--bind=${bridgeDir.absolutePath}:/tmp/linbox-glibc")
            } catch (e: Exception) {
                Log.e(TAG, "failed to start glibc-bridge (continuing without it)", e)
            }
        }

        val loginEnvs = EnvMap()
        readEtcEnvironment(rootfs, loginEnvs)
        loginEnvs.put("LANG", lang, true)
        loginEnvs.put("HOME", userInfo.home, true)
        loginEnvs.put("USER", userInfo.name, true)
        loginEnvs.put("TMPDIR", "/tmp", true)
        loginEnvs.put("DISPLAY", ":13", true)
        loginEnvs.put("PULSE_SERVER", "tcp:127.0.0.1:4713", true)
        if (bridgeEndpoint.isNotEmpty()) {
            loginEnvs.put("LINBOX_GLIBC_ENDPOINT", bridgeEndpoint, true)
        }
        // 把 /usr/local/bin 加进 PATH(覆盖 etc/environment),让 glibc-run 默认可用
        loginEnvs.put("PATH",
                "/usr/local/bin:/usr/local/sbin:/usr/bin:/usr/sbin:/bin:/sbin", true)

        prootCmd.addAll(
                listOf(
                        "/usr/bin/env",
                        "-i",
                        *loginEnvs.toArray(),
                        userInfo.shell, "-l",
                )
        )

        val prootCmdProotPart = prootCmd.toMutableList()
        prootCmd.clear()
        prootCmd.addAll(listOf("sh", "-c", prootCmdProotPart.joinToString(" ")))
        lastTimeCmd = "sh -c \\\n" + prootCmdProotPart.joinToString(" \\\n")
        Log.d(TAG, "attach: 最终prootcmd=$lastTimeCmd")

        val processBuilder = ProcessBuilder(prootCmd)
            .directory(rootfs)
            .also {
                it.environment()["PROOT_TMP_DIR"] = Consts.tmpDir.absolutePath
                it.environment()["LD_PRELOAD"] = ""
            }
            .redirectErrorStream(true)

        return processBuilder
    }

    /**
     * 把 glibc-run sh 脚本写到 rootfs/usr/local/bin/,只在文件长度变化时
     * 才重写,避免每次启动都改时间戳触发 rootfs 同步。
     */
    private fun installGlibcRunSh(rootfs: File) {
        try {
            val target = File(rootfs, "usr/local/bin/glibc-run")
            if (target.exists() && target.length() == GLIBC_RUN_SH.length.toLong()) return
            target.parentFile?.mkdirs()
            target.writeText(GLIBC_RUN_SH, Charsets.UTF_8)
            target.setExecutable(true, false)
        } catch (e: Exception) {
            Log.w(TAG, "installGlibcRunSh failed: ${e.message}")
        }
    }

    /**
     * 读取/etc/environment下的环境变量 并添加到 [envMap]
     */
    private fun readEtcEnvironment(rootfs: File, envMap: EnvMap) {
        try {
            for (l in File(rootfs, "/etc/environment").readLines()) {
                val line = l.trim()
                line.takeIf { !line.startsWith('#') && line.contains('=') }?.let {
                    val split = line.split("=", limit = 2)
                    envMap.put(split[0], split[1].trim('\"'))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 编辑/etc/locale.gen,后续会执行locale-gen 生成对应语言文件 */
    private fun editEtcLocaleGen(rootfs: File, targetLocale: String) {
        try {
            val file = File(rootfs, "/etc/locale.gen").takeIf { it.exists() } ?: return
            val regexCharNum = "[^a-zA-Z0-9]".toRegex()
            val lines = FileUtils.readLines(file, StandardCharsets.UTF_8).map {
                val uncommentLine = it.trimStart('#').trim()
                val locale = uncommentLine.split(' ').takeIf { parts -> parts.size == 2 }?.get(0) ?: return@map it
                val comp1 = locale.replace(regexCharNum, "").lowercase()
                val comp2 = targetLocale.replace(regexCharNum, "").lowercase()
                return@map if (comp1 == comp2) uncommentLine else it
            }
            FileUtils.writeLines(file, lines)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 如果文件存在且可以读取内容. 返回null [filePath]为相对rootfs的路径 */
    private fun File.takeIfCantRead(): File? {
        return try {
            takeUnless { it.exists() && it.canRead() }
        } catch (e: Exception) {
            this
        }
    }

    /**
     * 如果[bindTo]无法读取的话. 绑定 File(rootfsCurrDir, [bindFrom]):filePath.
     * @param bindTo 安卓上的绝对路径. 如果该文件不可读，则作为proot 绑定到的rootfs目标路径
     * @param bindFrom rootfs中某个文件的安卓绝对路径
     * @return --bind 的字符串，未绑定时返回null
     */
    private fun bindIfNotReadable(rootfs: File, bindFrom: String, bindTo: String): String? {
        return File(bindTo).takeIfCantRead()?.let { "--bind=${File(rootfs, bindFrom).absolutePath}:$bindTo" }
    }
}

class EnvMap {
    val map = mutableMapOf<String, String>()

    /**
     * 新增/更改环境变量。将value放在现有value之前。如果override为true则替换现有value
     */
    fun put(k: String, v: String, override: Boolean = false) {
        val k1 = k.trim()
        val v1 = v.trim()
        if (k1.contains("=")) Log.w("TAG", "key不应包含=: key=$k1  value=$v1")
        val oldV = map[k1]
        map[k1] = if (oldV != null && !override) "$v1:$oldV" else v1
    }

    fun get(k: String): String = map.getOrDefault(k, "")

    /** 返回一个数组，包含当前所有环境变量，每个元素是 字符串 k=v */
    fun toArray(): Array<String> = map.toList().map { "${it.first}=${it.second}" }.toTypedArray()
}

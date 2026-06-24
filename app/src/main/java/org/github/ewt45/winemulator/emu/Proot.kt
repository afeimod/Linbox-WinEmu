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
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 连接 linux 的终端。v5.4 架构: **proot 内跑 fifo server + xfce4 桌面**
 *
 * 用户原话 (v5.4 最终版):
 *   "app 创建 FIFO 管道和锁文件并启动 fifo 服务"
 *   "启动 proot 时能够正确启动 fifo_exec_server"
 *   "proot 终端才可以使用 startexec 执行文件进行定义 FIFO 管道路径
 *    和将命令参数拼接后写入 FIFO"
 *   "只有这样写入 fifo 后 proot 终端才可以访问我 glibc 环境
 *    /data/data/a.io.github.ewt45.winemulator/files/imagefs"
 *
 * 架构 (跟用户在 termux 的原型一致):
 *   [Android linbox 进程]
 *     └── 启 proot (自带的静态 proot 二进制)
 *           - --bind=Consts.tmpDir:/tmp  (让 proot 内的 /tmp = host tmp, 含 X11 socket)
 *           - --bind=imagefs:/imagefs    (让 proot 内的 /imagefs 可见)
 *           - 启 login shell, shell 跑 start.sh:
 *                 a) locale-gen + LANG
 *                 b) chmod imagefs 脚本 (+x)
 *                 c) 后台启 fifo_exec_server.sh (创建 FIFO + lock, 监听)
 *                 d) 启 xfce4 桌面 (linbox alias 启 startxfce4)
 *
 *   [proot 内 fifo_exec_server.sh (后台)]
 *     └── while [ -e lock ]; do
 *           read cmd < FIFO
 *           /imagefs/usr/local/bin/glibc-run.sh $cmd &   # proot 内直接 exec
 *         done
 *
 *   [proot 内 xfce4 桌面 terminal]
 *     └── 用户跑: startexec "cmd"
 *           └── echo "$*" > /tmp/.exec.fifo
 *             (proot /tmp = host tmpDir, 跟 server 创建的 FIFO 同目录)
 *
 * 关键:
 *   - FIFO 路径 = /tmp/.exec.fifo (proot 内的 /tmp = host tmpDir, --bind 实现)
 *   - fifo server 跑在 proot 内 (跟用户在 termux 的原型一致, sh 派发)
 *   - startexec / glibc-run / fifo_exec_server 都装在 /imagefs/usr/local/bin
 *   - box64+wine 跑在 proot 内, 共享 host /tmp 里的 X11 socket
 */
class Proot {
    private val TAG = "Proot"

    companion object {
        /** 上次执行 proot 时的完整命令, 仅用于显示,可能无法真正用于执行 */
        var lastTimeCmd = ""

        /**
         * Asset 路径
         */
        const val FIFO_EXEC_SERVER_ASSET_PATH = "glibc/fifo_exec_server.sh"
        const val STARTEXEC_ASSET_PATH = "glibc/startexec.sh"
        const val GLIBC_RUN_ASSET_PATH = "glibc/glibc-run.sh"

        /** imagefs 内 FIFO server 的安装路径 (proot 内跑) */
        const val FIFO_EXEC_SERVER_INSTALLED = "/imagefs/usr/local/bin/fifo_exec_server.sh"
        const val STARTEXEC_INSTALLED = "/imagefs/usr/local/bin/startexec.sh"
        const val GLIBC_RUN_INSTALLED = "/imagefs/usr/local/bin/glibc-run.sh"

        /** imagefs bind mount 名 */
        const val IMAGEFS_BIND_NAME = "/imagefs"

        /** Android 端 tmp 路径, FIFO 用 */
        const val LINBOX_TMP = "/data/data/a.io.github.ewt45.winemulator/cache/tmp"
    }

    /** 无 context 版本 (向后兼容原 API) */
    suspend fun attach(): ProcessBuilder = withContext(Dispatchers.IO) {
        return@withContext attachInternal(null)
    }

    /** 带 context 版本。**推荐使用** —— 装 sh 脚本到 imagefs + 启 xfce4 桌面 */
    suspend fun attach(ctx: Context): ProcessBuilder = withContext(Dispatchers.IO) {
        return@withContext attachInternal(ctx)
    }

    private suspend fun attachInternal(ctx: Context?): ProcessBuilder {
        val rootfs = Consts.rootfsCurrDir
        val tmpdir = Consts.tmpDir

        // 启动前的环境准备
        rootfsCurrL2sDir.mkdirs()
        chmod(rootfsCurrL2sDir, "755")
        ProotHelper.setup_fake_data()
        editEtcLocaleGen(rootfs, Consts.Pref.general_rootfs_lang.get())
        val lang = Consts.Pref.general_rootfs_lang.get()
        val userInfo = ProotRootfs.getPreferredUser(rootfs.canonicalFile.name)

        // ============================================================
        // 1) 装 sh 脚本到 imagefs
        // ============================================================
        var imagefs: ImageFs? = null
        if (ctx != null) {
            try {
                val err = GlibcProgramLauncher.ensureReady(ctx)
                if (err != null) {
                    Log.w(TAG, "imagefs not ready: $err")
                } else {
                    imagefs = ImageFs.find(ctx)
                    // startexec 装到 imagefs (proot 内跑, xfce4 terminal 调用)
                    installAsset(ctx, STARTEXEC_ASSET_PATH,
                        File(imagefs.rootDir, "usr/local/bin/startexec.sh"))
                    // glibc-run 装到 imagefs (proot 内跑, 被 fifo server 派发)
                    installAsset(ctx, GLIBC_RUN_ASSET_PATH,
                        File(imagefs.rootDir, "usr/local/bin/glibc-run.sh"))
                    // fifo_exec_server 装到 imagefs (proot 内跑, start.sh 里启)
                    installAsset(ctx, FIFO_EXEC_SERVER_ASSET_PATH,
                        File(imagefs.rootDir, "usr/local/bin/fifo_exec_server.sh"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "failed to setup scripts (continuing)", e)
            }
        }

        // ============================================================
        // 2) 组装 proot 命令
        // ============================================================
        val prootCmd = mutableListOf(
            Consts.prootBin.absolutePath,
            *proot_bool_options.get().toTypedArray(),
            "--kernel-release=$DEFAULT_FAKE_KERNEL_VERSION",
            "--rootfs=${rootfs.absolutePath}",
            "--change-id=${userInfo.uid}:${userInfo.gid}",
            "--cwd=${userInfo.home}",
            // /tmp = host 的 Consts.tmpDir (内含 X11 socket)
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

        // imagefs bind
        if (imagefs != null) {
            prootCmd.add("--bind=${imagefs.rootDir.absolutePath}:${IMAGEFS_BIND_NAME}")
            Log.i(TAG, "imagefs bound at ${IMAGEFS_BIND_NAME}")
        }

        // ============================================================
        // 3) env (注入到 proot 内的 shell)
        // ============================================================
        val loginEnvs = EnvMap()
        readEtcEnvironment(rootfs, loginEnvs)
        loginEnvs.put("LANG", lang, true)
        loginEnvs.put("HOME", userInfo.home, true)
        loginEnvs.put("USER", userInfo.name, true)
        loginEnvs.put("XDG_RUNTIME_DIR", "/tmp", true)
        loginEnvs.put("TMPDIR", "/tmp", true)
        loginEnvs.put("DISPLAY", ":13", true)
        loginEnvs.put("PULSE_SERVER", "tcp:127.0.0.1:4713", true)
        try {
            val preset = org.github.ewt45.winemulator.Consts.Pref.box64_preset.get()
            loginEnvs.put("LINBOX_GLIBC_PRESET", preset, true)
        } catch (_: Exception) {
            loginEnvs.put("LINBOX_GLIBC_PRESET", "compatibility", true)
        }
        // PATH 加 imagefs bin 目录
        loginEnvs.put("PATH",
                "/imagefs/usr/local/bin:/imagefs/usr/bin:/imagefs/opt/wine/bin:" +
                "/usr/local/bin:/usr/local/sbin:/usr/bin:/usr/sbin:/bin:/sbin", true)

        // ============================================================
        // 4) 写 start.sh
        //    shell 启时跑: locale + chmod imagefs 脚本 + PATH + 用户的 startup cmd
        //    (注意: fifo server 是在 proot 内 start.sh 启的, 不是 Android 端)
        //
        // 为什么要 chmod 一次? imagefs 是 Android 侧的文件, root 通过 proot
        // 看到的 /imagefs/... 是 nobody 用户, owner-only 权限可能不让 exec,
        // start.sh 启时 chmod 一次是兑底。setExecutable 在 Android 侧 installAsset
        // 时已经设过 (File API), 但 proot 内看到的 mode 可能因为 uid 不匹配不可执行。
        // ============================================================
        val startScript = Consts.rootfsCurrStartSh
        val sb = StringBuilder()
        sb.appendLine("#!/bin/sh")
        sb.appendLine("# auto-generated by Proot.kt (v5.4) — proot shell startup")
        sb.appendLine()
        sb.appendLine("# a) locale")
        sb.appendLine("""if ! locale -a | grep -qi "zh_CN"; then locale-gen zh_CN.utf8; fi""")
        sb.appendLine("""export LANG=zh_CN.utf8""")
        sb.appendLine()
        sb.appendLine("# b) chmod imagefs 脚本 (startexec / glibc-run / fifo_exec_server)")
        sb.appendLine("#    imagefs 是 Android 侧文件, proot 内普通用户可能看不到 +x")
        sb.appendLine("""chmod +x /imagefs/usr/local/bin/startexec.sh /imagefs/usr/local/bin/glibc-run.sh /imagefs/usr/local/bin/fifo_exec_server.sh 2>/dev/null || true""")
        sb.appendLine("""export PATH="/imagefs/usr/local/bin:$PATH"""")
        sb.appendLine()
        // c) 启 fifo_exec_server (后台), 跟 termux 脚本里 . fifo_exec_server & 一致
        //    它会创建 /tmp/.exec.fifo + /tmp/.exec-lock, 监听 FIFO
        //    proot 内 xfce4 terminal 跑 startexec 就能通过 FIFO 让 server 派发
        sb.appendLine("# c) fifo exec server (创建 FIFO + lock, 监听, 派发到 glibc-run)")
        sb.appendLine("""if [ -x /imagefs/usr/local/bin/fifo_exec_server.sh ]; then""")
        sb.appendLine("""    /imagefs/usr/local/bin/fifo_exec_server.sh >/dev/null 2>&1 &""")
        sb.appendLine("""fi""")
        sb.appendLine()
        // d) 用户的 startup cmd (默认 "linbox" 启 xfce4)
        val startupCmd = Consts.Pref.proot_startup_cmd.get().trim()
        if (startupCmd.isNotEmpty()) {
            sb.appendLine("# d) user startup cmd: $startupCmd")
            sb.appendLine(startupCmd)
            sb.appendLine()
        }
        try {
            startScript.parentFile?.mkdirs()
            startScript.writeText(sb.toString())
            startScript.setExecutable(true, false)
            Log.i(TAG, "wrote start.sh: $startScript")
        } catch (e: Exception) {
            Log.w(TAG, "failed to write start.sh: ${e.message}")
        }

        // ============================================================
        // 5) proot 启 shell, shell 跑 start.sh
        // ============================================================
        val finalCmd = mutableListOf<String>()
        finalCmd.addAll(prootCmd)
        val startScriptProotPath = "/" + startScript.absolutePath.removePrefix(rootfs.absolutePath)
        val shellCmd = "sh $startScriptProotPath; exec ${userInfo.shell} -l"
        finalCmd.addAll(listOf(
            "/usr/bin/env",
            "-i",
            *loginEnvs.toArray(),
            userInfo.shell, "-l", "-c", shellCmd,
        ))

        lastTimeCmd = "sh -c \\\n" + finalCmd.joinToString(" \\\n")
        Log.d(TAG, "attach: 最终 proot cmd=$lastTimeCmd")

        val processBuilder = ProcessBuilder("sh", "-c", finalCmd.joinToString(" "))
            .directory(rootfs)
            .also {
                it.environment()["PROOT_TMP_DIR"] = Consts.tmpDir.absolutePath
                it.environment()["LD_PRELOAD"] = ""
            }
            .redirectErrorStream(true)

        return processBuilder
    }

    private fun installAsset(ctx: Context, assetPath: String, target: File) {
        try {
            target.parentFile?.mkdirs()
            val content = ctx.assets.open(assetPath).use { it.readBytes() }
            if (target.exists() && target.length() == content.size.toLong()) return
            target.writeBytes(content)
            target.setExecutable(true, false)
            Log.i(TAG, "installed asset $assetPath → ${target.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "installAsset $assetPath failed: ${e.message}")
        }
    }

    /** 读取 /etc/environment 下的环境变量 并添加到 [envMap] */
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

    /** 编辑 /etc/locale.gen, 后续会执行 locale-gen 生成对应语言文件 */
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

    /** 如果文件存在且可以读取内容. 返回 null [filePath] 为相对 rootfs 的路径 */
    private fun File.takeIfCantRead(): File? {
        return try {
            takeUnless { it.exists() && it.canRead() }
        } catch (e: Exception) {
            this
        }
    }

    /**
     * 如果[bindTo]无法读取的话. 绑定 File(rootfsCurrDir, [bindFrom]):filePath.
     */
    private fun bindIfNotReadable(rootfs: File, bindFrom: String, bindTo: String): String? {
        return File(bindTo).takeIfCantRead()?.let { "--bind=${File(rootfs, bindFrom).absolutePath}:$bindTo" }
    }
}

class EnvMap {
    val map = mutableMapOf<String, String>()

    fun put(k: String, v: String, override: Boolean = false) {
        val k1 = k.trim()
        val v1 = v.trim()
        if (k1.contains('=')) Log.w("TAG", "key 不应包含 =: key=$k1  value=$v1")
        val oldV = map[k1]
        map[k1] = if (oldV != null && !override) "$v1:$oldV" else v1
    }

    fun get(k: String): String = map.getOrDefault(k, "")

    fun toArray(): Array<String> = map.toList().map { "${it.first}=${it.second}" }.toTypedArray()
}

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
 * 连接 linux 的终端。v5.2 架构: **保留 v3 shell + 后台 fifo server**
 *
 * 用户原话: "安卓启动 proot 的桌面 linbox, 然后进行 fifo 的建立,
 *         而在 proot 的终端里启动 startexec 进行 fifo 管道通讯
 *         进行可以运行 glibc 的 imagefs 里的 box64 wine"
 *
 * 关键修正 (v5 → v5.2):
 *   v5 把 fifo server 替换掉 shell, 导致 xfce4 桌面没了
 *   v5.2 保留 shell 模式 (v3 风格), fifo server 跟 xfce4 一起跑
 *   v5.1 用了复杂 start.sh, v5.2 简化
 *
 * 架构 (跟 mobox 一致, 但 linbox 没 termux):
 *   [Android linbox 进程]
 *     └── 启 proot (自带的静态 proot 二进制, --bind=Consts.tmpDir:/tmp)
 *   [外层 proot (linbox rootfs, busybox/ash 系, Debian-ish)]
 *     ├── 启 login shell
 *     │     ├── shell 启动 xfce4 桌面 (用户 "linbox" alias 启 startxfce4)
 *     │     └── xfce4 桌面的 terminal 是外层 proot 内的 sh
 *     │           └── 用户跑 "startexec X" 写一行到 .exec.fifo
 *     └── [fifo server 后台跑] 装到 imagefs, shell 启动时调
 *           └── 派发: 外层 proot 内 sh -c "X" &
 *             X 形如 "/imagefs/usr/bin/box64 /imagefs/opt/wine/bin/wine foo.exe"
 *             box64 启动 wine 时, wine 的 linterp 指向
 *             /imagefs/usr/lib/ld-linux-aarch64.so.1 (glibc loader),
 *             进入 glibc env 跑 wine 的 .so 库
 *
 * 调用方:
 *   - Android 进程: GlibcProgramLauncher.runInProot() 写 .exec.fifo
 *   - proot 内的 shell: 用户跑 "startexec 'X'" 写 .exec.fifo
 *   - 两条入口到同一个外层 proot 内的 fifo server
 */
class Proot {
    private val TAG = "Proot"

    companion object {
        /** 上次执行 proot 时的完整命令, 仅用于显示,可能无法真正用于执行 */
        var lastTimeCmd = ""

        /**
         * Asset 路径: FIFO server 脚本 (装到 imagefs 后通过 bind mount 在 proot 内可见)
         * 同样 startexec.sh 也装到 imagefs 里
         */
        const val FIFO_EXEC_SERVER_ASSET_PATH = "glibc/fifo_exec_server.sh"
        const val STARTEXEC_ASSET_PATH = "glibc/startexec.sh"

        /** imagefs 内 FIFO server 的安装路径 (proot 内绝对路径) */
        const val FIFO_EXEC_SERVER_INSTALLED = "/imagefs/usr/local/bin/fifo_exec_server.sh"

        /** imagefs 内 startexec 的安装路径 (proot 内绝对路径) */
        const val STARTEXEC_INSTALLED = "/imagefs/usr/local/bin/startexec.sh"

        /** imagefs bind mount 名 (跟 --bind 配合) */
        const val IMAGEFS_BIND_NAME = "/imagefs"
    }

    /** 无 context 版本 (向后兼容原 API)。不会启动 FIFO server。 */
    suspend fun attach(): ProcessBuilder = withContext(Dispatchers.IO) {
        return@withContext attachInternal(null)
    }

    /** 带 context 版本。**推荐使用** —— 装 fifo server 脚本到 imagefs。 */
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
        // 1) 把 FIFO server + startexec 装到 imagefs (从 assets 复制)
        //    imagefs 在 proot 内被 bind 到 /imagefs, 两个脚本在
        //    proot shell 启动后能直接 exec。
        // ============================================================
        var imagefs: ImageFs? = null
        if (ctx != null) {
            try {
                val err = GlibcProgramLauncher.ensureReady(ctx)
                if (err != null) {
                    Log.w(TAG, "imagefs not ready: $err")
                } else {
                    imagefs = ImageFs.find(ctx)
                    installFifoScripts(ctx, imagefs)
                }
            } catch (e: Exception) {
                Log.e(TAG, "failed to setup imagefs (continuing without FIFO server)", e)
            }
        }

        // ============================================================
        // 2) 组装 proot 命令
        //    关键: proot 启 login shell (v3 模式), shell 启动时
        //    用户的 startup cmd (例如 "linbox") 启 xfce4
        //    fifo server 在 start.sh 里后台启 (跟 startxfce4 平行)
        // ============================================================
        val prootCmd = mutableListOf(
            Consts.prootBin.absolutePath,
            *proot_bool_options.get().toTypedArray(),
            "--kernel-release=$DEFAULT_FAKE_KERNEL_VERSION",
            "--rootfs=${rootfs.absolutePath}",
            "--change-id=${userInfo.uid}:${userInfo.gid}",
            "--cwd=${userInfo.home}",
            // /tmp = host 的 Consts.tmpDir, 内含 FIFO + X11 socket
            "--bind=${tmpdir.absolutePath}:/tmp",
            "--bind=${rootfs.absolutePath}/tmp:/dev/shm",
            "--bind=/sys",
            "--bind=/proc/self/fd:/dev/fd",
            "--bind=/proc",
            "--bind=/dev/urandom:/dev/random",
            "--bind=/dev",
        )

        // 标准 fd 绑定
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

        // imagefs bind (关键! 让外层 proot 内 /imagefs 可见)
        if (imagefs != null) {
            prootCmd.add("--bind=${imagefs.rootDir.absolutePath}:${IMAGEFS_BIND_NAME}")
            Log.i(TAG, "imagefs bound at ${IMAGEFS_BIND_NAME} (rootDir=${imagefs.rootDir.absolutePath})")
        }

        // ============================================================
        // 3) env
        // ============================================================
        val loginEnvs = EnvMap()
        readEtcEnvironment(rootfs, loginEnvs)
        loginEnvs.put("LANG", lang, true)
        loginEnvs.put("HOME", userInfo.home, true)
        loginEnvs.put("USER", userInfo.name, true)
        // TMPDIR/XDG_RUNTIME_DIR 都指向 proot 内 /tmp = host tmpdir
        loginEnvs.put("XDG_RUNTIME_DIR", "/tmp", true)
        loginEnvs.put("TMPDIR", "/tmp", true)
        loginEnvs.put("DISPLAY", ":13", true)
        loginEnvs.put("PULSE_SERVER", "tcp:127.0.0.1:4713", true)
        // box64 preset
        try {
            val preset = org.github.ewt45.winemulator.Consts.Pref.box64_preset.get()
            loginEnvs.put("LINBOX_GLIBC_PRESET", preset, true)
        } catch (_: Exception) {
            loginEnvs.put("LINBOX_GLIBC_PRESET", "compatibility", true)
        }
        // PATH: 加 imagefs bin 目录 (让 box64/wine 在 PATH 里能找到)
        loginEnvs.put("PATH",
                "/imagefs/usr/local/bin:/imagefs/usr/bin:/imagefs/opt/wine/bin:" +
                "/usr/local/bin:/usr/local/sbin:/usr/bin:/usr/sbin:/bin:/sbin", true)

        // ============================================================
        // 4) 写 start.sh (proot shell 启动时跑)
        //    内容:
        //      a) locale-gen + LANG
        //      b) [imagefs 装好] 后台启 fifo server
        //      c) 用户的 startup cmd (默认 "linbox" 启 xfce4)
        // ============================================================
        val startScript = Consts.rootfsCurrStartSh
        val sb = StringBuilder()
        sb.appendLine("#!/bin/sh")
        sb.appendLine("# auto-generated by Proot.kt (v5.2) — proot shell startup")
        sb.appendLine()
        sb.appendLine("# a) locale")
        sb.appendLine("""if ! locale -a | grep -qi "zh_CN"; then locale-gen zh_CN.utf8; fi""")
        sb.appendLine("""export LANG=zh_CN.utf8""")
        sb.appendLine()
        if (imagefs != null) {
            sb.appendLine("# b) 后台启 fifo server (让 Android 端能通过 FIFO 派发 box64+wine)")
            sb.appendLine("if [ -x \"$FIFO_EXEC_SERVER_INSTALLED\" ]; then")
            sb.appendLine("    \"$FIFO_EXEC_SERVER_INSTALLED\" &")
            sb.appendLine("fi")
            sb.appendLine()
        }
        // c) startup cmd (默认 "linbox" 启 xfce4)
        val startupCmd = Consts.Pref.proot_startup_cmd.get().trim()
        if (startupCmd.isNotEmpty()) {
            sb.appendLine("# c) user startup cmd: $startupCmd")
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
        // 5) proot 启 login shell, shell 启时跑 start.sh
        //    shell 跑 start.sh 后 exec 替换成 user login shell,
        //    user login shell 接管 stdin/stdout
        // ============================================================
        val finalCmd = mutableListOf<String>()
        finalCmd.addAll(prootCmd)
        val startScriptProotPath = "/" + startScript.absolutePath.removePrefix(rootfs.absolutePath)
        // 在 start.sh 末尾追加 exec 接管: proot 启的 shell 跑 start.sh,
        // start.sh 跑完启 fifo + startup cmd 后, exec bash -l 接管
        // (user 看到的 prompt 是 bash -l 的, 不是 start.sh 用的 sh)
        val startScriptFinalShellCmd = "sh $startScriptProotPath; exec ${userInfo.shell} -l"
        finalCmd.addAll(listOf(
            "/usr/bin/env",
            "-i",
            *loginEnvs.toArray(),
            userInfo.shell, "-l", "-c", startScriptFinalShellCmd,
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

    /**
     * 把 FIFO server 脚本 + startexec 脚本从 APK assets 写到 imagefs。
     * 只在文件内容变化时才重写。
     */
    private fun installFifoScripts(ctx: Context, imagefs: ImageFs) {
        installAsset(ctx, FIFO_EXEC_SERVER_ASSET_PATH,
            File(imagefs.rootDir, "usr/local/bin/fifo_exec_server.sh"))
        installAsset(ctx, STARTEXEC_ASSET_PATH,
            File(imagefs.rootDir, "usr/local/bin/startexec.sh"))
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
     * @param bindTo 安卓上的绝对路径. 如果该文件不可读，则作为 proot 绑定到的 rootfs 目标路径
     * @param bindFrom rootfs 中某个文件的安卓绝对路径
     * @return --bind 的字符串，未绑定时返回 null
     */
    private fun bindIfNotReadable(rootfs: File, bindFrom: String, bindTo: String): String? {
        return File(bindTo).takeIfCantRead()?.let { "--bind=${File(rootfs, bindFrom).absolutePath}:$bindTo" }
    }
}

class EnvMap {
    val map = mutableMapOf<String, String>()

    /**
     * 新增/更改环境变量。将 value 放在现有 value 之前。如果 override 为 true 则替换现有 value
     */
    fun put(k: String, v: String, override: Boolean = false) {
        val k1 = k.trim()
        val v1 = v.trim()
        if (k1.contains('=')) Log.w("TAG", "key 不应包含 =: key=$k1  value=$v1")
        val oldV = map[k1]
        map[k1] = if (oldV != null && !override) "$v1:$oldV" else v1
    }

    fun get(k: String): String = map.getOrDefault(k, "")

    /** 返回一个数组，包含当前所有环境变量，每个元素是 字符串 k=v */
    fun toArray(): Array<String> = map.toList().map { "${it.first}=${it.second}" }.toTypedArray()
}

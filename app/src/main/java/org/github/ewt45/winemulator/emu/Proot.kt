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
 * 连接 linux 的终端。v5.4 架构: **Android 跑 fifo server, proot 内启 xfce4**
 *
 * 用户原话 (v5.4 最终版):
 *   "fifo_exec_server 是安卓进行创建 FIFO 管道和锁文件的也可以说是发送 FIFO 服务"
 *   "proot 终端里利用 startexec 进行定义 FIFO 管道路径
 *    (安卓发的 fifo 的 tmp 路径而不是 proot 内部 tmp)"
 *   "fifo_exec_server 是安卓进行创建 FIFO 管道和锁文件的"
 *
 * 架构:
 *   [Android linbox 进程]
 *     ├── 启 proot (自带的静态 proot 二进制)
 *     │     - --bind=Consts.tmpDir:/tmp     (让 proot 内的 /tmp = host tmp)
 *     │     - --bind=imagefs:/imagefs       (让 proot 内的 /imagefs 可见)
 *     │     - 启 login shell, shell 跑 start.sh:
 *     │           - 启 xfce4 桌面 (linbox alias 启 startxfce4)
 *     │           - xfce4 桌面起来了, 用户可以从 xfce4 terminal 跑命令
 *     └── fork fifo_exec_server.sh (Android 视角, 用 /system/bin/sh)
 *           - 创建 Consts.tmpDir/.exec.fifo (Android 视角)
 *           - 创建 Consts.tmpDir/.exec-lock
 *           - 监听 FIFO, 收到命令 sh -c 派发
 *           - 派发时, 命令如果是 "proot --bind=imagefs:/imagefs ... -- /imagefs/glibc-run.sh ..."
 *             这种包装, 就会启新 proot 跑 box64+wine
 *
 *   [proot 内 xfce4 桌面 terminal]
 *     └── 用户跑: startexec "cmd"
 *           - startexec 用 Android 视角的 tmp 绝对路径写 FIFO
 *           - 不是 proot 内部的 /tmp, 是 host 的 Consts.tmpDir
 *             (虽然 proot --bind 让两者是同一个文件, 但 startexec 显式用 Android 路径)
 *
 * 关键:
 *   - FIFO 路径 = Android 视角 = /data/data/.../cache/tmp/.exec.fifo
 *   - fifo server 跑在 Android 进程空间
 *   - startexec 跑在 proot 内的 xfce4 terminal
 *   - 派发执行: cmd 在 Android 进程空间 sh -c 跑, 通常 cmd 包含 proot 包装
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

        /** imagefs 内 FIFO server 的安装路径 (Android 端用, 不在 proot 内跑) */
        const val FIFO_EXEC_SERVER_INSTALLED = "/data/data/a.io.github.ewt45.winemulator/cache/tmp/fifo_exec_server.sh"
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
                    // startexec 装到 imagefs (proot 内跑)
                    installAsset(ctx, STARTEXEC_ASSET_PATH,
                        File(imagefs.rootDir, "usr/local/bin/startexec.sh"))
                    // glibc-run 装到 imagefs (proot 内跑, 被 fifo server 派发)
                    installAsset(ctx, GLIBC_RUN_ASSET_PATH,
                        File(imagefs.rootDir, "usr/local/bin/glibc-run.sh"))
                    // fifo_exec_server 装到 Android 端 tmp (Android 端跑)
                    installAsset(ctx, FIFO_EXEC_SERVER_ASSET_PATH,
                        File(tmpdir, "fifo_exec_server.sh"))
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
        //    shell 启时跑: locale + 用户的 startup cmd ("linbox" 启 xfce4)
        //    (注意: fifo server 已经在 Android 端跑了, 不在 start.sh 里)
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
        // b) 用户的 startup cmd (默认 "linbox" 启 xfce4)
        val startupCmd = Consts.Pref.proot_startup_cmd.get().trim()
        if (startupCmd.isNotEmpty()) {
            sb.appendLine("# b) user startup cmd: $startupCmd")
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

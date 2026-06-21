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
 * 连接linux的终端。输入命令或获取输出
 *
 * [v3 — imagefs bind, no bridge]
 * proot 容器通过 `--bind=.../files/imagefs:/imagefs` 访问外部 glibc
 * 资产目录,内部 sh 直接 `box64 wine64 <args>`。不需要 Android 端
 * bridge / fifo / socket —— 全部 box64+wine 在 proot 内 fork。
 * 详见 [org.github.ewt45.winemulator.glibc] 包。
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

        /**
         * Tracks the Context used by [installGlibcRunSh] to read the
         * glibc-run.sh asset. Set by [attach]; cleared in onDestroy().
         *
         * v3 no longer needs a separate bridge handle because box64+wine
         * run entirely inside the proot container — there's nothing to
         * start or stop on the Android side.
         */
        @Volatile var currentProotContext: android.content.Context? = null

        /**
         * glibc-run 桥脚本,存在 APK assets 里 (assets/glibc/glibc-run.sh)。
         * 启动 proot 时会从 assets 读出来写到 rootfs/usr/local/bin/glibc-run。
         * 这个独立 sh 文件容易修改和调试，避免把 shell 代码嵌进 Kotlin。
         */
        const val GLIBC_RUN_ASSET_PATH = "glibc/glibc-run.sh"
        const val IMAGEFS_BIND_NAME = "/imagefs"
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

    private suspend fun attachInternal(ctx: Context?): ProcessBuilder {
        val rootfs = Consts.rootfsCurrDir
        val tmpdir = Consts.tmpDir
        // Set the context so installGlibcRunSh can read the asset.
        currentProotContext = ctx
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
        // glibc imagefs: bind-mount the extracted imagefs as /imagefs
        // so sh scripts inside proot can launch box64+wine directly.
        //
        // v3 design (modeled after winlator-glibc):
        //   - No Android-side box64 daemon.
        //   - No fifo / unix socket / IPC.
        //   - Just `box64 wine64 <args>` from inside proot, where
        //     /imagefs/usr/local/bin/box64 is the musl-built box64 and
        //     /imagefs/opt/wine/bin/wine64 is the glibc-built wine64.
        //   - box64 intercepts the execve of wine64, loads the glibc
        //     loader from /imagefs/usr/lib/x86_64-linux-gnu, and runs
        //     wine64 against that loader.
        //
        // This requires no extra code on the Android side beyond the
        // asset extraction step (handled by ImageFsInstaller) and the
        // --bind below.
        // ============================================================
        if (ctx != null) {
            try {
                val nonNullCtx: android.content.Context = ctx!!
                val imagefs = org.github.ewt45.winemulator.glibc.ImageFs.find(nonNullCtx)
                // Install on demand. ensureReady() extracts the .tzst on
                // first run and verifies the critical binaries are in
                // place on subsequent runs. Errors are logged but never
                // crash proot startup — imagefs is optional.
                val err = org.github.ewt45.winemulator.glibc.GlibcProgramLauncher.ensureReady(nonNullCtx)
                if (err != null) {
                    Log.w(TAG, "imagefs not ready: $err")
                } else {
                    prootCmd.add("--bind=${imagefs.rootDir.absolutePath}:${IMAGEFS_BIND_NAME}")
                    Log.i(TAG, "imagefs bound at ${IMAGEFS_BIND_NAME} (rootDir=${imagefs.rootDir.absolutePath})")
                }
            } catch (e: Exception) {
                Log.e(TAG, "failed to bind imagefs (continuing without it)", e)
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
        // Signal to glibc-run.sh which box64 preset the user selected.
        // Default to "compatibility" if no preference is set.
        try {
            val preset = org.github.ewt45.winemulator.Consts.Pref.box64_preset.get()
            loginEnvs.put("LINBOX_GLIBC_PRESET", preset, true)
        } catch (_: Exception) {
            loginEnvs.put("LINBOX_GLIBC_PRESET", "compatibility", true)
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
     * 把 glibc-run sh 脚本从 APK assets 写到 rootfs/usr/local/bin/。
     * 只在文件内容变化时才重写,避免每次启动都改时间戳触发 rootfs 同步。
     */
    private fun installGlibcRunSh(rootfs: File) {
        try {
            val target = File(rootfs, "usr/local/bin/glibc-run")
            target.parentFile?.mkdirs()
            // Read from the current process's assets; if not available (called
            // from a non-Context thread), fall back to skipping install.
            val ctx = currentProotContext ?: return
            val content = ctx.assets.open(GLIBC_RUN_ASSET_PATH).use { it.readBytes() }
            if (target.exists() && target.length() == content.size.toLong()) return
            target.writeBytes(content)
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

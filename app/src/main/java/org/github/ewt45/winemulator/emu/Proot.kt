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
 * 连接 linux 的终端。v5 架构: **proot 内 FIFO server 派发模式**
 * (mobox 风格, 但 linbox 没有 termux, 所以 fifo server 直接跑在 proot 内)
 *
 * 调用链 (mobox 原始 vs linbox v5):
 *
 *   [mobox, 有 termux]
 *     termux 进程
 *       ├── termux 跑 fifo server          (常驻)
 *       ├── termux 跑 proot-distro debian  (xfce4 桌面)
 *     proot 内 debian 进程
 *       ├── xfce4 桌面
 *       └── terminal 跑 startexec 写 FIFO  (termux 的 $PREFIX/tmp/.exec.fifo, proot --shared-tmp)
 *     termux fifo server 读到
 *       └── sh -c "proot-distro ... -- bash -c 'box64 wine ...'" 派发
 *         (termux 内 sh, 但启动新 proot 让 box64 进 glibc env)
 *
 *   [linbox v5, 没有 termux]
 *     linbox Android 进程
 *       ├── 启 proot (自带的静态 proot 二进制)         (直接, 不经过 termux)
 *     proot 内 (linbox rootfs, busybox/ash 系)
 *       ├── fifo server 跑 (常驻, 装到 imagefs 后 proot 内可见)  ← 关键
 *       └── (未来) xfce4 桌面的 terminal 跑 startexec 写 FIFO
 *     proot fifo server 读到
 *       └── sh -c "box64 wine ..." & 派发                ← proot 内的 sh, box64 在 proot 内
 *         (box64 是 musl 链接的, 跑得起来; 但要 wine 在 glibc env,
 *          所以 box64 启动 wine 时, wine 的 linterp 指向
 *          /imagefs/usr/lib/ld-linux-aarch64.so.1, 进入 glibc loader)
 *
 *   关键差异:
 *     - mobox: termux 派发 → 启 proot → box64 → wine
 *     - linbox: proot 派发 → box64 → wine (proot 已经在跑, 不需要再启)
 *
 * 两条 FIFO 派发入口 (v5):
 *   1) Android 端调 [GlibcProgramLauncher.runInProot] → 直接写
 *      $Consts.tmpDir/.exec.fifo (proot 视角下 = /tmp/.exec.fifo)
 *   2) proot 内 (xfce4 terminal) 跑 `startexec "cmd"` → 写
 *      /tmp/.exec.fifo (proot 视角, 跟 1 同一个文件)
 *
 * 两条入口都到同一个 proot 内的 fifo server, server 派发时用
 * proot 内的 sh -c "$cmd" &, cmd 在 proot 内执行。
 */
class Proot {
    private val TAG = "Proot"

    companion object {
        /** 上次执行 proot 时的完整命令, 仅用于显示,可能无法真正用于执行 */
        var lastTimeCmd = ""

        /**
         * Asset 路径: FIFO server 脚本 (装到 imagefs 后通过 bind mount 在 proot 内可见)
         * 同样 startexec.sh 也装到 imagefs 里 (但 startexec.sh 走 proot 内调用)
         */
        const val FIFO_EXEC_SERVER_ASSET_PATH = "glibc/fifo_exec_server.sh"
        const val STARTEXEC_ASSET_PATH = "glibc/startexec.sh"

        /** imagefs 内 FIFO server 的安装路径 (proot 内绝对路径) */
        const val FIFO_EXEC_SERVER_INSTALLED = "/imagefs/usr/local/bin/fifo_exec_server.sh"

        /** imagefs 内 startexec 的安装路径 (proot 内绝对路径, xfce4 terminal 调) */
        const val STARTEXEC_INSTALLED = "/imagefs/usr/local/bin/startexec.sh"

        /** imagefs bind mount 名 (跟 --bind 配合) */
        const val IMAGEFS_BIND_NAME = "/imagefs"
    }

    /** 无 context 版本 (向后兼容原 API)。不会启动 FIFO server。 */
    suspend fun attach(): ProcessBuilder = withContext(Dispatchers.IO) {
        return@withContext attachInternal(null, null)
    }

    /** 带 context 版本。**推荐使用** —— 启动 FIFO server + 装 FIFO server 脚本到 imagefs。 */
    suspend fun attach(ctx: Context): ProcessBuilder = withContext(Dispatchers.IO) {
        return@withContext attachInternal(ctx, null)
    }

    /** 显式传 startup cmd 的版本。startup cmd 通过 FIFO 派发, 不写 stdin。 */
    suspend fun attach(ctx: Context, startupCmd: String?): ProcessBuilder = withContext(Dispatchers.IO) {
        return@withContext attachInternal(ctx, startupCmd)
    }

    private suspend fun attachInternal(ctx: Context?, startupCmd: String?): ProcessBuilder {
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
        //    proot 启动时就能直接 exec。
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
        //    关键: proot 的最后不是启 login shell, 而是 exec FIFO server
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
            // --shared-tmp 等价 (FIFO 跨 proot 共享)
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

        // imagefs bind
        if (imagefs != null) {
            prootCmd.add("--bind=${imagefs.rootDir.absolutePath}:${IMAGEFS_BIND_NAME}")
            Log.i(TAG, "imagefs bound at ${IMAGEFS_BIND_NAME} (rootDir=${imagefs.rootDir.absolutePath})")
        }

        // ============================================================
        // 3) env: FIFO server 需要的关键 env
        // ============================================================
        val loginEnvs = EnvMap()
        readEtcEnvironment(rootfs, loginEnvs)
        loginEnvs.put("LANG", lang, true)
        loginEnvs.put("HOME", userInfo.home, true)
        loginEnvs.put("USER", userInfo.name, true)
        // TMPDIR/XDG_RUNTIME_DIR 都指向 proot 内 /tmp = host tmpdir
        // FIFO server 默认从 $XDG_RUNTIME_DIR 找 .exec.fifo
        // 但我们的 fifo server 用 $TMPDIR (跟 mobox 的 TMP 一致)
        loginEnvs.put("XDG_RUNTIME_DIR", "/tmp", true)
        loginEnvs.put("TMPDIR", "/tmp", true)
        loginEnvs.put("DISPLAY", ":13", true)
        loginEnvs.put("PULSE_SERVER", "tcp:127.0.0.1:4713", true)
        // box64 preset, glibc-run.sh 会读
        try {
            val preset = org.github.ewt45.winemulator.Consts.Pref.box64_preset.get()
            loginEnvs.put("LINBOX_GLIBC_PRESET", preset, true)
        } catch (_: Exception) {
            loginEnvs.put("LINBOX_GLIBC_PRESET", "compatibility", true)
        }
        // PATH: 让 box64/wine 在默认 PATH 里能找到
        loginEnvs.put("PATH",
                "/usr/local/bin:/usr/local/sbin:/usr/bin:/usr/sbin:/bin:/sbin", true)

        // ============================================================
        // 4) 启动 FIFO server (不是 shell!)
        //    如果 imagefs 没装好, 退回到原来的 shell 模式 (向后兼容)
        // ============================================================
        val finalCmd = mutableListOf<String>()
        finalCmd.addAll(prootCmd)
        if (imagefs != null) {
            // FIFO server 模式
            finalCmd.addAll(listOf(
                "/usr/bin/env",
                "-i",
                *loginEnvs.toArray(),
                FIFO_EXEC_SERVER_INSTALLED,
            ))
        } else {
            // 向后兼容: 没有 imagefs 时启 shell
            finalCmd.addAll(listOf(
                "/usr/bin/env",
                "-i",
                *loginEnvs.toArray(),
                userInfo.shell, "-l",
            ))
        }

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

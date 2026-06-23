package org.github.ewt45.winemulator.glibc

import android.content.Context
import android.util.Log
import org.github.ewt45.winemulator.Consts
import org.github.ewt45.winemulator.viewmodel.TerminalViewModel
import java.io.File

/**
 * Launches a Windows .exe via box64+wine.
 *
 * 两条路径:
 *
 *   1) [runInProot]  (主路径,推荐)
 *      通过 [TerminalViewModel.runCommand] 把 `glibc-run <args>` 写到
 *      proot 容器内 shell 的 stdin。box64/wine 在 proot 内启动,直接
 *      共享 host 的 /tmp → Termux:X11 的 socket 在 proot 里看就是
 *      `/tmp/.X11-unix/X13`,X11 通信正常。
 *
 *   2) [runDirect]  (legacy fallback,主要用于命令行调试)
 *      Android 进程直接 fork box64+wine。wine 启动后看到的 X server
 *      socket 路径虽然对得上, 但因为不在 linbox 主进程的 X11 server
 *      namespace 里,Termux:X11 可能不允许连接。保留这个入口方便
 *      adb 调试 (`am start -n .../.glibc.GlibcLauncherActivity`),
 *      不再是默认路径。
 *
 * 对应 winlator 的 GuestProgramLauncherComponent: winlator 用的是
 * 路径 2 (Android 进程直接 fork),但它的 X server 是自己实现的
 * epoll Unix socket,允许跨进程连接。linbox 用的是 Termux:X11,
 * 路径 1 (proot 内部) 才稳。
 */
class GlibcProgramLauncher(
    val imageFs: ImageFs,
) {
    fun isInstalled(): Boolean = imageFs.isValid

    fun verifyReady(): String? = ImageFsInstaller.smokeTest(imageFs)

    /**
     * 返回 imagefs 里 box64 的 proot 内部绝对路径 (即 /imagefs/usr/...),
     * 用于调试日志。Android 侧路径见 [androidBox64Path]。
     */
    fun prootBox64Path(): String? {
        val candidates = listOf(
            "${imageFs.prootMountPath}/usr/local/bin/box64",
            "${imageFs.prootMountPath}/usr/bin/box64",
            "${imageFs.prootMountPath}/bin/box64",
        )
        return candidates.firstOrNull { File(it.replace(imageFs.prootMountPath, imageFs.rootDir.absolutePath)).exists() }
    }

    /**
     * 返回 imagefs 里 wine 的 proot 内部绝对路径。
     */
    fun prootWinePath(): String? {
        val candidates = listOf(
            "${imageFs.prootMountPath}/opt/wine/bin/wine",
            "${imageFs.prootMountPath}/opt/wine/bin/wine64",
            "${imageFs.prootMountPath}/usr/bin/wine",
            "${imageFs.prootMountPath}/usr/bin/wine64",
        )
        return candidates.firstOrNull { File(it.replace(imageFs.prootMountPath, imageFs.rootDir.absolutePath)).exists() }
    }

    /** Android 侧 (非 proot) box64 路径, 给 legacy 入口用。 */
    fun androidBox64Path(): String? {
        val candidates = listOf(
            "${imageFs.rootDir.absolutePath}/usr/local/bin/box64",
            "${imageFs.rootDir.absolutePath}/usr/bin/box64",
            "${imageFs.rootDir.absolutePath}/bin/box64",
        )
        return candidates.firstOrNull { File(it).exists() }
    }

    /** Android 侧 wine 路径, 给 legacy 入口用。 */
    fun androidWinePath(): String? {
        val candidates = listOf(
            "${imageFs.rootDir.absolutePath}/opt/wine/bin/wine",
            "${imageFs.rootDir.absolutePath}/opt/wine/bin/wine64",
            "${imageFs.rootDir.absolutePath}/usr/bin/wine",
            "${imageFs.rootDir.absolutePath}/usr/bin/wine64",
        )
        return candidates.firstOrNull { File(it).exists() }
    }

    /**
     * 主入口: 通过 proot 容器内 shell 启动 box64+wine。
     *
     * 工作方式: proot 启动时已经把 host 的 Consts.tmpDir bind 到 rootfs
     * 的 /tmp,Termux:X11 的 X server socket (`<Consts.tmpDir>/.X11-unix/X13`)
     * 在 proot 里就是 `/tmp/.X11-unix/X13`。我们在 proot 里跑
     * `glibc-run <args>` (assets/glibc/glibc-run.sh, 启动时被 Proot.kt
     * 写到 rootfs/usr/local/bin/glibc-run),脚本会 exec box64+wine。
     *
     * 因为 proot 进程本身就是 linbox 主进程 (uid 一致),Termux:X11
     * 允许它连接 X server,窗口就能渲染到屏幕上。
     *
     * @param terminal 已经在跑的 TerminalViewModel (proot shell 已经启动)
     * @param guestExecutableOrCmd 用户要跑的 wine 命令。可以是:
     *   - 绝对路径 (proot 内, 比如 /home/xuser/.wine/drive_c/foo.exe)
     *   - wine 子命令 (winecfg, winefile, regedit...)
     *   - 已经是 `glibc-run <args>` 完整命令
     * @param background true = 末尾加 `&` 让它在 shell 后台跑 (wine
     *   本身就是常驻),false = 前台跑 (调试用)
     */
    fun runInProot(
        terminal: TerminalViewModel,
        guestExecutableOrCmd: String,
        background: Boolean = true,
    ) {
        val raw = guestExecutableOrCmd.trim()
        val cmd = when {
            // 已经是完整 glibc-run 命令 — 透传
            raw.startsWith("glibc-run") -> raw
            // Android 侧绝对路径 (imagefs 下的某文件) — 转成 proot 内部路径
            raw.startsWith(imageFs.rootDir.absolutePath) -> {
                val prootExe = imageFs.toProotPath(raw)
                "glibc-run ${shellQuote(prootExe)}"
            }
            // 其他 (含绝对路径、exe 子命令字面量、wine 子命令) — 直接拼 glibc-run,
            // 让 glibc-run.sh 脚本自己决定如何分流 (winlator 风格)。
            // 整体用 shellQuote 包起来,避免含空格/特殊字符时断开。
            else -> "glibc-run ${shellQuote(raw)}"
        }
        val finalCmd = if (background) "$cmd &" else cmd
        Log.i(TAG, "runInProot: $finalCmd")
        terminal.runCommand(finalCmd)
    }

    /**
     * 把一个字符串里需要 shell 引号保护的字符包起来。
     * 简单实现: 含空格/引号/特殊字符时用单引号包,内部单引号用 '\'' 转义。
     */
    private fun shellQuote(s: String): String {
        if (s.isEmpty()) return "''"
        if (s.all { it.isLetterOrDigit() || it in "/._-+=:,@" }) return s
        val escaped = s.replace("'", "'\\''")
        return "'$escaped'"
    }

    // ============================================================
    // Legacy 入口: Android 进程直接 fork box64+wine。
    // 保留给 adb 调试 (GlibcLauncherActivity 用这个),不推荐普通用户用。
    // ============================================================

    fun buildCommand(vararg args: String): List<String>? {
        val box64 = androidBox64Path()
        val wine = androidWinePath()
        if (box64 == null) return null
        if (wine == null) return null
        return listOf(box64, wine) + args.toList()
    }

    fun buildEnv(linboxTmpDir: String? = null): Map<String, String> {
        val root = imageFs.rootDir.absolutePath
        val winePath = imageFs.winePath
        val map = HashMap<String, String>()
        map["HOME"] = "${root}/home/xuser"
        map["USER"] = "xuser"
        val tmp = linboxTmpDir ?: "${root}/tmp"
        map["TMPDIR"] = tmp
        map["XDG_RUNTIME_DIR"] = tmp
        map["PATH"] = "$winePath/bin:$root/usr/local/bin:$root/usr/bin:$root/bin:/system/bin:/system/xbin"
        map["LD_LIBRARY_PATH"] = "$root/usr/lib/aarch64-linux-gnu:$root/usr/lib"
        map["BOX64_LD_LIBRARY_PATH"] = "$root/usr/lib/x86_64-linux-gnu:$root/lib/x86_64-linux-gnu"
        map["FONTCONFIG_PATH"] = "$root/usr/etc/fonts"
        map["WINEPREFIX"] = "${root}/home/xuser/.wine"
        map["DISPLAY"] = ":13"
        map["PULSE_SERVER"] = "tcp:127.0.0.1:4713"
        map["BOX64_DYNAREC"] = "1"
        return map
    }

    fun runDirect(linboxTmpDir: String, vararg args: String): java.lang.Process? {
        val cmd = buildCommand(*args) ?: return null
        val env = buildEnv(linboxTmpDir)
        val pb = ProcessBuilder(cmd)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(imageFs.rootDir)
        pb.redirectErrorStream(true)
        return pb.start()
    }

    companion object {
        private const val TAG = "GlibcProgramLauncher"

        fun forContext(context: Context): GlibcProgramLauncher {
            val imageFs = ImageFs.find(context)
            return GlibcProgramLauncher(imageFs)
        }

        fun ensureReady(context: Context): String? {
            val launcher = forContext(context)
            Log.i(TAG, "ensureReady: launcher.isInstalled=${launcher.isInstalled()}")
            if (!launcher.isInstalled()) {
                val ok = ImageFsInstaller.installIfNeeded(context)
                Log.i(TAG, "ensureReady: installIfNeeded=$ok")
                if (!ok) return "imagefs 资产解压失败,请检查 assets/imagefs/imagefs.tzst 是否存在"
            }
            val verify = launcher.verifyReady()
            Log.i(TAG, "ensureReady: verifyReady=$verify, box64=${launcher.androidBox64Path()}, wine=${launcher.androidWinePath()}")
            return verify
        }
    }
}

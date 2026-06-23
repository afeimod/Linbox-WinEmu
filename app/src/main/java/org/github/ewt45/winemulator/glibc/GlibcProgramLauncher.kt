package org.github.ewt45.winemulator.glibc

import android.content.Context
import android.os.Process
import android.system.Os
import android.util.Log
import java.io.File
import org.github.ewt45.winemulator.Consts

/**
 * Launches a Windows .exe by forking `box64 wine <args>` directly on the
 * Android side (NOT inside the proot container).
 *
 * Why direct, not proot:
 *
 *   Box64 compiled with musl libc does a handful of syscalls at startup
 *   (set_robust_list, set_tid_address, prctl, mprotect...) that the
 *   proot ptrace layer cannot translate correctly inside its chroot.
 *   The result is a SIGSEGV before box64 even reaches main(). This
 *   affects every statically-linked musl binary in proot, not just
 *   box64 — we observed it with sh -c, head, stat, etc.
 *
 *   Running box64 directly from the linbox app's own process uid lets
 *   the Android kernel handle syscalls normally. The box64 binary's
 *   linterp points at `<filesDir>/imagefs/usr/lib/ld-linux-aarch64.so.1`,
 *   which is a real path on the Android filesystem, so the kernel
 *   resolves it without any chroot trickery.
 *
 *   Wine, once running, talks to its own internal libc and box64
 *   translates syscalls back to Android. The wine prefix lives inside
 *   the imagefs so that winlator-managed wine stays self-contained.
 *
 * This mirrors winlator's GuestProgramLauncherComponent architecture
 * minus the NDK libproot.so (we don't need proot here because the
 * box64 binary already runs on Android directly).
 */
class GlibcProgramLauncher(
    val imageFs: ImageFs,
) {
    fun isInstalled(): Boolean = imageFs.isValid

    fun verifyReady(): String? = ImageFsInstaller.smokeTest(imageFs)

    /**
     * Return the absolute Android-side path of the box64 binary, or null
     * if it cannot be located in any known imagefs layout.
     */
    fun androidBox64Path(): String? {
        val candidates = listOf(
            "${imageFs.rootDir.absolutePath}/usr/local/bin/box64",
            "${imageFs.rootDir.absolutePath}/usr/bin/box64",
            "${imageFs.rootDir.absolutePath}/bin/box64",
        )
        // Android's File.canExecute() can falsely return false even when
        // we can read+write the file (sdcardfs quirks). Trust .exists()
        // and let the kernel reject on exec if the mode bits are wrong.
        return candidates.firstOrNull { File(it).exists() }
    }

    /**
     * Return the absolute Android-side path of the wine binary, or null
     * if not found. Winlator layout uses plain `wine` (not `wine64`).
     */
    fun androidWinePath(): String? {
        val candidates = listOf(
            "${imageFs.winePath}/bin/wine",
            "${imageFs.rootDir.absolutePath}/usr/bin/wine",
            "${imageFs.rootDir.absolutePath}/bin/wine",
            "${imageFs.winePath}/bin/wine64",
            "${imageFs.rootDir.absolutePath}/usr/bin/wine64",
        )
        return candidates.firstOrNull { File(it).exists() }
    }

    /**
     * Build the argv list that ProcessBuilder will hand to fork+exec.
     *
     *   argv[0] = box64
     *   argv[1] = wine
     *   argv[2..] = <args>
     */
    fun buildCommand(vararg args: String): List<String>? {
        val box64 = androidBox64Path()
        val wine = androidWinePath()
        if (box64 == null) return null
        if (wine == null) return null
        return listOf(box64, wine) + args.toList()
    }

    /**
     * Environment block for the spawned box64+wine process.
     */
    fun buildEnv(linboxTmpDir: String? = null): Map<String, String> {
        val root = imageFs.rootDir.absolutePath
        val winePath = imageFs.winePath
        val map = HashMap<String, String>()
        map["HOME"] = "${root}/home/xuser"
        map["USER"] = "xuser"
        // Use linbox's own cacheDir/tmp, NOT imagefs/tmp. Termux:X11
        // exposes its X server socket at $XDG_RUNTIME_DIR/.X11-unix/X<n>
        // where XDG_RUNTIME_DIR is set to TMPDIR (see Consts.kt and
        // DisplayManager.kt: env TMPDIR = <cacheDir>/tmp). box64+wine
        // forks from the Android side; to reach Termux:X11 they MUST
        // see the same $XDG_RUNTIME_DIR as the X server.
        val tmp = linboxTmpDir ?: "${root}/tmp"
        map["TMPDIR"] = tmp
        map["XDG_RUNTIME_DIR"] = tmp
        map["PATH"] = "$winePath/bin:$root/usr/local/bin:$root/usr/bin:$root/bin:/system/bin:/system/xbin"
        map["LD_LIBRARY_PATH"] = "$root/usr/lib/aarch64-linux-gnu:$root/usr/lib"
        map["BOX64_LD_LIBRARY_PATH"] = "$root/usr/lib/x86_64-linux-gnu:$root/lib/x86_64-linux-gnu"
        map["FONTCONFIG_PATH"] = "$root/usr/etc/fonts"
        map["WINEPREFIX"] = "${root}/home/xuser/.wine"
        // linbox starts Termux:X11 with display :13 (see
        // DisplayManager.kt: cmdLine = "... :13").
        map["DISPLAY"] = ":13"
        map["PULSE_SERVER"] = "tcp:127.0.0.1:4713"
        map["BOX64_DYNAREC"] = "1"
        return map
    }

    /**
     * Fork box64 wine on the Android side (NOT inside proot). The
     * resulting Process handles stdout/stderr; the caller is
     * responsible for forwarding I/O to whatever UI surface they want.
     *
     * Note: wine started from the Android side will NOT see linbox's
     * proot-launched X server unless the env DISPLAY points at the
     * Termux:X11 socket. linbox's X server runs on :13 inside proot,
     * which is invisible to Android processes — so this launcher is
     * only useful for non-GUI programs (e.g. `wine cmd.exe /c dir`)
     * or for tests where we just want to confirm box64 can start.
     */
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

    /**
     * Launch box64+wine from the host Android process so the new
     * process inherits the same Termux:X11 server already running
     * on :13 in linbox's MainEmuActivity. Modelled after winlator's
     * GlibcProgramLauncherComponent.execGuestProgram().
     */
    fun runInBackground(context: Context, guestExecutable: String, extraArgs: Array<String>): java.lang.Process? {
        // guestExecutable is expected to be relative to imageFs.rootDir
        // (e.g. "home/xuser/.wine/drive_c/windows/system32/winecfg.exe").
        // We resolve it to an absolute Android-side path so box64
        // can find it.
        val exeAbs = if (guestExecutable.startsWith("/")) {
            guestExecutable
        } else {
            imageFs.rootDir.absolutePath + "/" + guestExecutable
        }
        val box64 = androidBox64Path() ?: return null.also {
            android.util.Log.e(TAG, "runInBackground: box64 not found")
        }
        val wine = androidWinePath() ?: return null.also {
            android.util.Log.e(TAG, "runInBackground: wine not found")
        }
        val root = imageFs.rootDir.absolutePath
        val env = HashMap<String, String>()
        env["HOME"] = "$root/home/xuser"
        env["USER"] = ImageFs.USER
        // 关键:Termux:X11 的 X server socket 在 linbox 的 cacheDir/tmp/.X11-unix/X<n>,
        // 不是 imagefs/tmp。DisplayManager 启动 xserver 时把 TMPDIR 设成了
        // Consts.tmpDir.absolutePath,这里必须用同一个目录,否则 wine 连不上 X。
        val x11Tmp = runCatching { Consts.tmpDir.absolutePath }
            .getOrDefault("$root/tmp")
        env["TMPDIR"] = x11Tmp
        env["XDG_RUNTIME_DIR"] = x11Tmp
        env["WINEPREFIX"] = "$root/home/xuser/.wine"
        env["LC_ALL"] = "zh_CN.UTF-8"
        env["PATH"] = "$root/opt/wine/bin:$root/usr/local/bin:$root/usr/bin:$root/bin:/system/bin:/system/xbin"
        // Match winlator's loader search order so winex11drv.so,
        // libX11.so.6, libxcb.so.1 are all found.
        env["LD_LIBRARY_PATH"] = "$root/usr/lib/x86_64-linux-gnu:$root/lib/x86_64-linux-gnu:$root/usr/lib/aarch64-linux-gnu:$root/usr/lib"
        env["BOX64_LD_LIBRARY_PATH"] = env["LD_LIBRARY_PATH"]!!
        env["ANDROID_SYSVSHM_SERVER"] = "$root/tmp/.sysvshm/SM0"
        env["FONTCONFIG_PATH"] = "$root/usr/etc/fonts"
        // linbox runs Termux:X11 on :13 (see DisplayManager.kt);
        // winlator uses :0 — same X11 protocol, same client libs.
        env["DISPLAY"] = ":13"
        env["BOX64_X11GLX"] = "1"
        env["BOX64_DYNAREC"] = "1"
        env["BOX64_NOBANNER"] = "1"
        env["BOX64_MMAP32"] = "1"
        env["WINEESYNC"] = "1"
        env["WINE_HOST_XDG_CURRENT_DESKTOP"] = "1"
        // If winlator's libandroid-sysvshm.so is bundled into the
        // APK (jniLibs/arm64-v8a/...), preload it so wine's IPC with
        // the sysvshm server goes through winlator's protocol.
        val sysvshm = java.io.File(root + "/usr/lib/aarch64-linux-gnu/libandroid-sysvshm.so")
        if (sysvshm.exists()) env["LD_PRELOAD"] = "libandroid-sysvshm.so"

        val cmdList = mutableListOf(box64, wine, exeAbs)
        cmdList.addAll(extraArgs)
        android.util.Log.i(TAG, "runInBackground: ${cmdList.joinToString(" ")}")
        android.util.Log.i(TAG, "runInBackground: DISPLAY=${env["DISPLAY"]} BOX64_X11GLX=${env["BOX64_X11GLX"]} LD_PRELOAD=${env["LD_PRELOAD"]}")
        val pb = ProcessBuilder(cmdList)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(imageFs.rootDir)
        pb.redirectErrorStream(true)
        return try {
            pb.start()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "runInBackground: start failed: ${e.message}", e)
            null
        }
    }

    companion object {
        private const val TAG = "GlibcProgramLauncher"

        fun forContext(context: Context): GlibcProgramLauncher {
            val imageFs = ImageFs.find(context)
            return GlibcProgramLauncher(imageFs)
        }

        fun ensureReady(context: Context): String? {
            val launcher = forContext(context)
            android.util.Log.i(TAG, "ensureReady: launcher.isInstalled=${launcher.isInstalled()}")
            if (!launcher.isInstalled()) {
                val ok = ImageFsInstaller.installIfNeeded(context)
                android.util.Log.i(TAG, "ensureReady: installIfNeeded=$ok")
                if (!ok) return "imagefs 资产解压失败,请检查 assets/imagefs/imagefs.tzst 是否存在"
            }
            val verify = launcher.verifyReady()
            android.util.Log.i(TAG, "ensureReady: verifyReady=$verify, box64=${launcher.androidBox64Path()}, wine=${launcher.androidWinePath()}")
            return verify
        }
    }
}

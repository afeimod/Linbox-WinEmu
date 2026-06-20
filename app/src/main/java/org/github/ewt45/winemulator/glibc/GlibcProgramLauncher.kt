package org.github.ewt45.winemulator.glibc

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Launches a guest (typically wine) under box64 + native glibc, completely
 * outside the proot container.
 *
 * This is the linbox equivalent of winlator-glibc's
 * GlibcProgramLauncherComponent.java. The key winlator trick we mirror:
 *
 *   box64 is a Linux ELF interpreter. When you run
 *
 *     box64 /path/to/wine64 some.exe
 *
 *   box64 does NOT ask the kernel to load wine64. Instead it open()s wine64,
 *   parses its PT_INTERP, locates the x86_64 ld-linux.so, and resolves all
 *   symbols itself. Every x86_64 libc call (open/read/write/mmap/...) is
 *   dynarec'd into aarch64 instructions that call into the aarch64 glibc
 *   that ships in the imagefs.
 *
 *   So we just need to make sure:
 *     - the aarch64 glibc is findable (LD_LIBRARY_PATH)
 *     - the x86_64 glibc is findable (BOX64_LD_LIBRARY_PATH) so box64 can
 *       satisfy wine's NEEDED entries
 *     - wine can find its PE dlls (WINEDLLPATH)
 *     - wine can find its data (HOME, WINEPREFIX)
 *
 *   That's it. No proot, no ptrace, no chroot.
 */
class GlibcProgramLauncher(
    private val ctx: Context,
    private val fs: ImageFs
) {
    private val TAG = "GlibcLauncher"

    @Volatile private var currentPid: Int = -1

    fun isRunning(): Boolean = currentPid > 0

    /** Public accessor for [fs], used by bridge and service code. */
    fun fs(): ImageFs = fs

    /**
     * Make sure imagefs is populated. Returns false if the .tzst asset is
     * missing or extraction failed.
     *
     * @param onProgress bytes-extracted callback for UI progress (optional).
     * @param onStatus human-readable status (for the UI status line).
     */
    fun ensureInstalled(onProgress: ((Long) -> Unit)? = null,
                         onStatus: ((String) -> Unit)? = null): Boolean =
            ImageFsInstaller.installIfNeeded(ctx, fs, onProgress, onStatus)

    /**
     * Build the envp that should be set when exec'ing box64.
     *
     * Mirrors GlibcProgramLauncherComponent#execGuestProgram env assembly
     * (lines 110-180 in winlator-glibc). winlator keeps x86_64 glibc in
     * usr/lib/x86_64-linux-gnu/; the linbox imagefs follows the same
     * convention, so we point BOX64_LD_LIBRARY_PATH at both the aarch64
     * lib path and the x86_64 subdir.
     */
    fun buildEnv(extraEnv: EnvVars? = null): EnvVars {
        val env = EnvVars()
        // box64 dynarec + preset
        env.put("BOX64_NOBANNER", "1")
        env.put("BOX64_X11GLX", "1")
        env.put("BOX64_DYNAREC", "1")
        env.put("BOX64_MMAP32", "1")
        val preset = Box64Preset.fromKey(currentPresetKey)
        env.mergeAll(preset.env)

        // X11 / locale / wine runtime
        env.put("HOME", fs.homeDir.absolutePath)
        env.put("USER", "xuser")
        env.put("TMPDIR", "${fs.root.absolutePath}/tmp")
        env.put("DISPLAY", ":0")
        env.put("WINE_HOST_XDG_CURRENT_DESKTOP", "1")
        env.put("WINEDEBUG", "-all")

        // LD_LIBRARY_PATH — what wine will see when box64 forwards to host libc.
        // Put wine's lib first so wine's own shims win over the system ones.
        val ldPath = StringBuilder()
        ldPath.append("${fs.wineLib64Dir.absolutePath}:")
        ldPath.append("${fs.wineLibDir.absolutePath}:")
        ldPath.append("${fs.libDir.absolutePath}:")
        // WINEDLLPATH — wine's PE unixlibs
        val wineDll = fs.wineDllDir()
        if (wineDll.exists()) env.put("WINEDLLPATH", wineDll.absolutePath)
        env.put("LD_LIBRARY_PATH", ldPath.toString())
        // BOX64_LD_LIBRARY_PATH — what box64 itself uses to resolve x86_64 NEEDED
        // entries. Imagefs typically carries x86_64 glibc in usr/lib/x86_64-linux-gnu/
        val x86Lib = File(fs.libDir, "x86_64-linux-gnu")
        env.put("BOX64_LD_LIBRARY_PATH",
                "${if (x86Lib.exists()) x86Lib.absolutePath else fs.libDir.absolutePath}:$ldPath")
        // fontconfig
        env.put("FONTCONFIG_PATH", fs.fontDir.absolutePath)
        // path so xdg-open, etc. work inside wine
        env.put("PATH",
                "${fs.wineBinDir.absolutePath}:${fs.localBinDir.absolutePath}:" +
                "${fs.binDir.absolutePath}:" +
                "${fs.root.absolutePath}/usr/bin:${fs.root.absolutePath}/usr/local/bin")

        // optional libandroid-sysvshm preload (Termux-style shim for SysV shm)
        val sysvshm = File(fs.libDir, "libandroid-sysvshm.so")
        if (sysvshm.exists()) env.put("LD_PRELOAD", "libandroid-sysvshm.so")

        if (extraEnv != null) env.mergeAll(extraEnv)
        return env
    }

    /**
     * Build the final command line. If [args] starts with "wine " or "wine64 "
     * we strip it (just like winlator does) so the caller can pass either
     * "wine game.exe" or "game.exe".
     */
    fun buildCommand(args: String): String {
        var finalArgs = args.trim()
        when {
            finalArgs.startsWith("wine64 ") -> finalArgs = finalArgs.substring(7).trim()
            finalArgs.startsWith("wine ") -> finalArgs = finalArgs.substring(5).trim()
        }
        val wineBin = fs.resolveWineBin().absolutePath
        return "${fs.box64Bin.absolutePath} $wineBin $finalArgs".trim()
    }

    /**
     * Launch the given wine command. Returns the child pid, or -1 on failure.
     *
     * @param onExit called on a worker thread when the process terminates.
     */
    fun launch(args: String, extraEnv: EnvVars? = null, workingDir: File? = null,
               logFilePath: String? = null, onExit: ((Int) -> Unit)? = null): Int {
        if (!ensureInstalled()) {
            // The caller (GlibcWineBridge) needs a specific reason to surface
            // to the proot shell, not just a silent -1. We log heavily here
            // and let launchJob format the reason from the log.
            val reason = buildString {
                append("imagefs not installed (")
                append("rootDir=${fs.root} exists=${fs.root.isDirectory}, ")
                append("box64=${fs.box64Bin} exists=${fs.box64Bin.exists()} exec=${fs.box64Bin.canExecute()}, ")
                append("wine64=${fs.wine64Bin} exists=${fs.wine64Bin.exists()}, ")
                append("aarch64-ld=${File(fs.libDir, "ld-linux-aarch64.so.1")} exists=${File(fs.libDir, "ld-linux-aarch64.so.1").exists()}, ")
                append("marker=${fs.versionFile()} exists=${fs.versionFile().exists()}")
                append(")")
            }
            Log.e(TAG, "launch: $reason")
            lastLaunchError = reason
            return -1
        }
        // Pre-flight: box64 and wine must be executable. Otherwise wine crashes
        // silently with "exec format error" or "permission denied" and the
        // proot side never gets a useful error.
        if (!fs.box64Bin.canExecute()) {
            val reason = "box64 not executable: ${fs.box64Bin.absolutePath} (mode=${fs.box64Bin.canRead()})"
            Log.e(TAG, "launch: $reason")
            lastLaunchError = reason
            return -1
        }
        val wineBin = fs.resolveWineBin()
        if (!wineBin.canExecute()) {
            val reason = "wine not executable: ${wineBin.absolutePath}"
            Log.e(TAG, "launch: $reason")
            lastLaunchError = reason
            return -1
        }
        val cmd = buildCommand(args)
        val env = buildEnv(extraEnv)
        Log.i(TAG, "launching: $cmd")
        Log.d(TAG, "env: ${env.toStringArray().joinToString(" ")}")
        currentPid = ProcessHelper.exec(cmd, env.toStringArray(), workingDir, onExit, logFilePath)
        if (currentPid <= 0) {
            lastLaunchError = "ProcessHelper.exec returned $currentPid for: $cmd"
        } else {
            lastLaunchError = null
        }
        return currentPid
    }

    /**
     * Sanity-test: just exec box64 itself with --version. Returns true if
     * box64 actually runs (we get exit code 0 and its version string).
     * Use this from the bridge to give the user a useful "is box64
     * functional?" answer before blaming wine.
     */
    fun smokeTestBox64(): String? {
        return try {
            val pb = ProcessBuilder(fs.box64Bin.absolutePath, "--version")
            pb.environment()["LD_LIBRARY_PATH"] = fs.libDir.absolutePath
            pb.environment()["BOX64_LD_LIBRARY_PATH"] = fs.libDir.absolutePath
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            if (code == 0) out.trim() else "box64 exit $code: ${out.trim()}"
        } catch (e: Exception) {
            "box64 failed to start: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /**
     * Detailed failure reason for the last [launch] that returned -1. Read by
     * [GlibcWineBridge.launchJob] to surface to the proot shell.
     */
    @Volatile var lastLaunchError: String? = null
        private set

    fun stop() {
        if (currentPid > 0) {
            Log.i(TAG, "stop: killing pid=$currentPid")
            ProcessHelper.killPid(currentPid)
            currentPid = -1
        }
    }

    companion object {
        /**
         * Currently selected box64 preset. Kept at companion level because
         * it's a per-app setting, not per-launcher.
         */
        @Volatile var currentPresetKey: String = Box64Preset.COMPATIBILITY.key
    }
}

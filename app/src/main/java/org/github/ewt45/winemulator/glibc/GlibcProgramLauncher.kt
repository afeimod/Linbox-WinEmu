package org.github.ewt45.winemulator.glibc

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Lightweight helper that decides whether the glibc imagefs is installed
 * and ready for use by proot-launched programs.
 *
 * We do NOT spawn any Android-side box64/wine process — that's the whole
 * point of the v3 redesign. Everything happens inside the proot container
 * (see `assets/glibc/glibc-run.sh`). The launcher class only validates
 * that imagefs is in place so we can give the user a clear error message
 * if they try to run a .exe without installing the bundle first.
 */
class GlibcProgramLauncher(
    val imageFs: ImageFs,
) {
    fun isInstalled(): Boolean = imageFs.isValid

    /**
     * Run the smoke test that verifies the critical imagefs files exist.
     * Returns a human-readable error message or null on success.
     */
    fun verifyReady(): String? = ImageFsInstaller.smokeTest(imageFs)

    /**
     * Return the proot-internal absolute path of `box64` (so sh scripts
     * inside proot can exec it). Useful for the MainEmuActivity logcat
     * dump that shows users which binary is being used.
     */
    fun prootBox64Path(): String =
        "${imageFs.prootMountPath}/usr/local/bin/box64"

    fun prootWine64Path(): String =
        "${imageFs.winePath}/bin/wine64"

    companion object {
        private const val TAG = "GlibcProgramLauncher"

        /**
         * Returns a launcher bound to the standard on-disk imagefs, or
         * null if the imagefs bundle has not been extracted yet.
         */
        fun forContext(context: Context): GlibcProgramLauncher {
            val imageFs = ImageFs.find(context)
            return GlibcProgramLauncher(imageFs)
        }

        /**
         * Top-level helper used by MainEmuActivity and Proot to make sure
         * imagefs is ready. Returns null on success, error message on
         * failure. Logs the outcome.
         */
        fun ensureReady(context: Context): String? {
            val launcher = forContext(context)
            if (!launcher.isInstalled()) {
                val ok = ImageFsInstaller.installIfNeeded(context)
                if (!ok) return "imagefs 资产解压失败,请检查 assets/imagefs/imagefs.tzst 是否存在"
            }
            // verifyReady() returns an error if any of the must-have
            // binaries is missing. If the user pre-installed a partial
            // imagefs (e.g. just box64 from winlator's bundle), we fall
            // back to the same smoke-test we use after install — the
            // proot-side glibc-run.sh will report a clear error if a
            // specific binary is missing at runtime.
            return launcher.verifyReady()
        }
    }
}

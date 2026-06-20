package org.github.ewt45.winemulator.glibc

import android.content.Context
import android.util.Log
import java.io.File

/**
 * ImageFs — the userland rootfs used for native-glibc box64+wine execution.
 *
 * Layout (a fully unpacked imagefs/. directory, identical to winlator-glibc):
 *
 *   <rootfs>/
 *     .winlator/.img_version          # marker; bumped when on-disk image is rebuilt
 *     usr/
 *       bin/                           # busybox / coreutils (used by sh bridge)
 *       lib/                           # aarch64 native glibc  (ld-linux-aarch64.so.1, libc.so.6, libm, libpthread, libdl, librt, libutil, libresolv, libnss_*)
 *       etc/locale.alias, gconv/, ...  # glibc support files
 *       local/bin/box64                # aarch64 box64 binary
 *       etc/fonts/                     # fontconfig
 *     opt/
 *       wine/
 *         bin/wine, wine64
 *         lib/, lib64/
 *         lib/wine/x86_64-unix/        # wine PE unixlibs
 *     home/xuser/.wine/                # WINEPREFIX
 *     storage/                         # mount point for /storage bind from android
 *     contents/                        # turnip / dxvk / virgl / proot patches (winlator convention)
 *     tmp/                             # WINEPREFIX tmp
 *
 * The on-disk image is populated from `assets/imagefs/imagefs.tzst` (a tar+zstd
 * bundle) by [ImageFsInstaller.installIfNeeded] on first launch. Subsequent
 * launches see `.img_version` and skip the extract.
 *
 * The naming "ImageFs" + path `files/imagefs/` is intentionally winlator
 * compatible: a user who already has a winlator installation can symlink
 * its imagefs into linbox's filesDir and it just works.
 */
class ImageFs private constructor(private val rootDir: File) {

    val root: File get() = rootDir
    val binDir: File get() = File(rootDir, "usr/bin")
    val localBinDir: File get() = File(rootDir, "usr/local/bin")
    val libDir: File get() = File(rootDir, "usr/lib")
    val etcDir: File get() = File(rootDir, "usr/etc")
    val fontDir: File get() = File(etcDir, "fonts")
    val wineDir: File get() = File(rootDir, "opt/wine")
    val wineBinDir: File get() = File(wineDir, "bin")
    val wineLibDir: File get() = File(wineDir, "lib")
    val wineLib64Dir: File get() = File(wineDir, "lib64")
    val wineDllDir: File get() = File(wineLibDir, "wine")
        get() {
            // winlator resolution order: lib/wine/ first, then lib64/wine/
            val p = field
            if (p.exists()) return p
            return File(wineLib64Dir, "wine")
        }
    val homeDir: File get() = File(rootDir, "home/xuser")
    val winePrefixDir: File get() = File(homeDir, ".wine")
    val box64Bin: File get() = File(localBinDir, "box64")
    val wineBin: File get() = File(wineBinDir, "wine")
    val wine64Bin: File get() = File(wineBinDir, "wine64")

    /** True once the imagefs is fully extracted and ready to be used. */
    fun isInstalled(): Boolean =
            rootDir.isDirectory
                    && versionFile().exists()
                    && box64Bin.exists()
                    && libDir.exists()
                    && File(libDir, "ld-linux-aarch64.so.1").exists()
                    && wineBinDir.exists()

    fun versionFile(): File = File(File(rootDir, ".winlator"), ".img_version")

    fun createMarker(version: Int) {
        rootDir.mkdirs()
        binDir.mkdirs()
        localBinDir.mkdirs()
        libDir.mkdirs()
        etcDir.mkdirs()
        fontDir.mkdirs()
        wineDir.mkdirs()
        homeDir.mkdirs()
        winePrefixDir.mkdirs()
        File(rootDir, ".winlator").mkdirs()
        versionFile().writeText(version.toString())
    }

    /** Resolve which wine binary to launch (wine64 first, wine fallback). */
    fun resolveWineBin(): File = if (wine64Bin.exists()) wine64Bin else wineBin

    companion object {
        /** Bumped when we need to force re-extract of imagefs. */
        const val CURRENT_VERSION = 1
        private const val TAG = "ImageFs"

        /** Path of the bundled imagefs tzst in APK assets. */
        const val ASSET_TZST_PATH = "imagefs/imagefs.tzst"

        fun find(ctx: Context): ImageFs =
                ImageFs(File(ctx.filesDir, "imagefs"))

        /** Make sure the dir tree exists. The actual extract is done by [ImageFsInstaller]. */
        fun ensureLayout(fs: ImageFs) {
            fs.root.mkdirs()
            fs.binDir.mkdirs()
            fs.localBinDir.mkdirs()
            fs.libDir.mkdirs()
            fs.etcDir.mkdirs()
            fs.fontDir.mkdirs()
            fs.wineDir.mkdirs()
            fs.homeDir.mkdirs()
            fs.winePrefixDir.mkdirs()
            Log.d(TAG, "ensureLayout done at ${fs.root}")
        }
    }
}

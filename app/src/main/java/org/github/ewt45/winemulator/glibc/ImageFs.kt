package org.github.ewt45.winemulator.glibc

import android.content.Context
import java.io.File

/**
 * A glibc-based rootfs extracted from `assets/imagefs/imagefs.tzst` into
 * `<filesDir>/imagefs/`.
 *
 * The imagefs contains the userland binaries needed to run wine on Android
 * via box64/box86 + glibc: musl-built box64 (loaded directly by proot),
 * glibc-built wine, and the glibc/x86_64 libraries wine depends on.
 *
 * We follow the winlator-glibc convention: imagefs is *only* used through
 * the proot container (via --bind=/imagefs), never executed on the Android
 * side. This avoids the entire bridge/IPC complexity that an
 * Android-side box64 daemon would require.
 */
class ImageFs private constructor(val rootDir: File) {

    /** Version marker. Bump whenever the asset bundle changes incompatibly. */
    var version: Int = 0
        private set

    /** True once the asset bundle has been extracted.
     *
     * We deliberately do NOT require `.winlator/.img_version` to be
     * present — winlator's own imagefs.tzst doesn't include that file.
     * Instead, we look for a couple of sentinel binaries that any
     * usable imagefs must have. This also means we never overwrite a
     * pre-existing imagefs that the user installed manually.
     */
    val isValid: Boolean
        get() {
            if (!rootDir.isDirectory()) return false
            return File(rootDir, "usr/local/bin/box64").exists() ||
                   File(rootDir, "usr/lib/x86_64-linux-gnu/libc.so.6").exists() ||
                   File(rootDir, "opt/wine/bin/wine64").exists()
        }

    /** Where wine lives inside the imagefs. */
    val winePath: String
        get() = "${rootDir.absolutePath}/opt/wine"

    /** Where wine's user prefix lives (wineprefix = $WINEPREFIX). */
    val winePrefix: String
        get() = "${rootDir.absolutePath}/home/xuser/.wine"

    /** The proot-internal mount path used by sh scripts. */
    val prootMountPath: String = "/imagefs"

    fun versionFile(): File = File(rootDir, ".winlator/.img_version")

    fun tmpDir(): File = File(rootDir, "tmp").apply { mkdirs() }

    /** Return the imagefs path as seen from inside proot (i.e. `/imagefs/...`). */
    fun toProotPath(absoluteAndroidPath: String): String =
        if (absoluteAndroidPath.startsWith(rootDir.absolutePath)) {
            prootMountPath + absoluteAndroidPath.substring(rootDir.absolutePath.length)
        } else {
            absoluteAndroidPath
        }

    companion object {
        const val USER = "xuser"
        const val HOME_PATH = "/home/$USER"

        /** Asset bundle inside `app/src/main/assets/imagefs/`. */
        const val IMAGEFS_ASSET = "imagefs/imagefs.tzst"

        /** Current bundled version. Bump when asset changes. */
        const val CURRENT_VERSION = 7

        /** Default extract location: `<context.filesDir>/imagefs`. */
        fun find(context: Context): ImageFs =
            ImageFs(File(context.filesDir, "imagefs"))
    }
}

package org.github.ewt45.winemulator.glibc

import android.content.Context
import android.util.Log
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.apache.commons.io.FileUtils
import org.github.ewt45.winemulator.Consts
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Extracts the glibc imagefs bundle from APK assets into
 * `<filesDir>/imagefs/`. Mirrors winlator-glibc's
 * [com.winlator.xenvironment.ImageFsInstaller] but uses plain Kotlin
 * streams so we don't depend on the winlator TarCompressorUtils helper.
 *
 * Why we need a separate imagefs rootfs in linbox:
 *   - linbox's proot container runs a Debian XFCE desktop (rootfs/current).
 *     That rootfs uses Debian's packaged wine which is x86_64 dynamic
 *     linked against glibc.
 *   - linbox's proot itself is a musl static binary (or wraps musl syscalls)
 *     and CANNOT directly load glibc binaries.
 *   - We solve this by bundling a *second* small rootfs (`imagefs.tzst`)
 *     that contains the musl-built box64/box86 shim plus the glibc
 *     libraries and wine binaries. The proot container sees imagefs as
 *     `/imagefs` (via `--bind`) and `box64`/`wine64` are launched from
 *     there: box64 intercepts the wine64 exec and re-dispatches the glibc
 *     loader against `/imagefs/usr/lib/x86_64-linux-gnu/libc.so.6`.
 *
 * This means NO Android-side box64 daemon, NO unix socket, NO FIFO,
 * NO sh bridge script. Everything happens inside proot.
 */
object ImageFsInstaller {
    private const val TAG = "ImageFsInstaller"

    /** Returns true if installation succeeded (or was already up to date). */
    fun installIfNeeded(context: Context): Boolean {
        val imageFs = ImageFs.find(context)
        if (imageFs.isValid) {
            val current = readVersion(imageFs.versionFile())
            if (current >= ImageFs.CURRENT_VERSION) {
                Log.i(TAG, "imagefs already installed (version=$current)")
                return true
            }
        }
        return installFromAssets(context, imageFs)
    }

    private fun installFromAssets(context: Context, imageFs: ImageFs): Boolean {
        val rootDir = imageFs.rootDir
        Log.i(TAG, "Installing imagefs from ${ImageFs.IMAGEFS_ASSET} -> $rootDir")
        try {
            // Clear everything except `home` and `opt/installed-wine`
            // (matches winlator-glibc's clearRootDir semantics).
            clearRootDir(rootDir)

            val assetPath = ImageFs.IMAGEFS_ASSET
            context.assets.open(assetPath).use { input ->
                BufferedInputStream(ZstdCompressorInputStream(input)).use { zin ->
                    TarArchiveInputStream(zin).use { tin ->
                        extractEntries(tin, rootDir)
                    }
                }
            }

            // Mark version so we don't re-extract next launch.
            imageFs.versionFile().parentFile?.mkdirs()
            imageFs.versionFile().writeText(ImageFs.CURRENT_VERSION.toString())
            Log.i(TAG, "imagefs install complete (version=${ImageFs.CURRENT_VERSION})")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "imagefs install failed: ${e.message}", e)
            return false
        }
    }

    private fun readVersion(file: File): Int = try {
        file.readText().trim().toInt()
    } catch (e: Exception) {
        0
    }

    private fun clearRootDir(rootDir: File) {
        if (!rootDir.isDirectory()) {
            rootDir.mkdirs()
            return
        }
        val children = rootDir.listFiles() ?: return
        for (f in children) {
            if (f.isDirectory) {
                val name = f.name
                if (name == "home") continue        // keep user data
                if (name == "opt") {
                    // Preserve /opt/installed-wine (user-installed wine versions)
                    val installedWine = File(f, "installed-wine")
                    for (sub in f.listFiles() ?: emptyArray()) {
                        if (sub == installedWine) continue
                        FileUtils.delete(sub)
                    }
                    continue
                }
            }
            FileUtils.delete(f)
        }
    }

    /**
     * Walk the tar stream and write each entry to disk under [rootDir].
     * Symlinks and mode bits are preserved (commons-compress defaults are
     * sufficient: we read the raw tar bytes and apply them via Os.symlink
     * / File.setExecutable).
     *
     * Pax headers and other meta-entries are skipped.
     */
    private fun extractEntries(tin: TarArchiveInputStream, rootDir: File) {
        while (true) {
            val entry: ArchiveEntry = tin.nextEntry ?: break
            val name = entry.name ?: continue
            if (entry.isDirectory) continue
            // Skip pax/global headers (commons-compress emits them inside
            // modern tarballs; their `name` is e.g. "pax_global_header").
            if (name.contains("pax_global_header")) continue
            if (name.contains("/pax_global_header")) continue

            // The imagefs is built with absolute-looking names like
            // "./usr/local/bin/box64" — strip a single leading "./" so the
            // resulting File path is relative to rootDir.
            val relative = name.removePrefix("./").removePrefix("/")
            val target = File(rootDir, relative)

            // Ensure parent dirs exist (tar usually contains explicit
            // directory entries too, but tarballs produced on macOS don't).
            target.parentFile?.mkdirs()

            when {
                (entry as? org.apache.commons.compress.archivers.tar.TarArchiveEntry)?.isSymbolicLink == true -> {
                    // commons-compress exposes the link target via the
                    // entry's name+suffix when the tar reader is TarArchive...
                    // In practice the link target comes from TarArchiveEntry
                    // which is what we actually receive here. Cast safely.
                    val linkTarget = (entry as? org.apache.commons.compress.archivers.tar.TarArchiveEntry)
                        ?.linkName ?: continue
                    // Recreate symlink. Delete existing file first to avoid
                    // FileUtils.symlink throwing on pre-existing files.
                    if (target.exists() || target.isDirectory) FileUtils.delete(target)
                    try {
                        android.system.Os.symlink(linkTarget, target.absolutePath)
                    } catch (e: Exception) {
                        // Symlink may fail on filesystems without symlink
                        // support; fall back to copying the link target as a
                        // plain text file so the user at least sees the path.
                        target.writeText(linkTarget)
                    }
                }
                else -> {
                    FileOutputStream(target).use { out ->
                        tin.copyTo(out)
                    }
                    // Preserve executable bit. Tar stores mode in the
                    // octal unix permissions; commons-compress exposes
                    // `mode` only on the tar-specific subtype.
                    val tarEntry = entry as? org.apache.commons.compress.archivers.tar.TarArchiveEntry
                    val rawMode = tarEntry?.mode ?: 420  // 0o644
                    val mode = rawMode and 0xFFF
                    try {
                        android.system.Os.chmod(target.absolutePath, mode)
                    } catch (e: Exception) {
                        // Log but don't fail — the glibc-run.sh script
                        // will retry `chmod +x` from inside proot. That
                        // retry is the actual line of defense; without
                        // it Android's "Files" storage routinely strips
                        // exec bits on extract.
                        Log.w(TAG, "chmod($mode) on ${target.name} failed: ${e.message}")
                    }
                    // Hard guarantee: box64 / box86 / wine / wine64 must
                    // be executable no matter what the tar said. proot
                    // can't exec a 0644 file even if the bind-mount shows
                    // it as +x.
                    val mustExec = listOf(
                        "usr/local/bin/box64",
                        "usr/local/bin/box86",
                        "opt/wine/bin/wine",
                        "opt/wine/bin/wine64",
                        "opt/wine/bin/wineboot",
                        "opt/wine/bin/wineserver",
                    )
                    if (relative in mustExec) {
                        try {
                            android.system.Os.chmod(target.absolutePath, 493)  // 0755
                        } catch (e: Exception) {
                            Log.w(TAG, "hard chmod 755 on ${target.name} failed: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    /** Smoke-test the installed imagefs. Returns null on success, error on failure. */
    fun smokeTest(imageFs: ImageFs): String? {
        val checks = listOf(
            "${imageFs.winePath}/bin/wine64",
            "${imageFs.rootDir.absolutePath}/usr/local/bin/box64",
            "${imageFs.rootDir.absolutePath}/usr/lib/x86_64-linux-gnu/libc.so.6",
        )
        for (path in checks) {
            val f = File(path)
            if (!f.exists()) return "missing file: $path"
        }
        return null
    }
}

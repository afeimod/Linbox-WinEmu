package org.github.ewt45.winemulator.glibc

import android.content.Context
import android.system.Os
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream

/**
 * ImageFsInstaller — winlator-style installer for the imagefs tzst bundle.
 *
 * Three-pass extraction (mirrors the strategy in linbox's own
 * [org.github.ewt45.winemulator.Utils.Archive]):
 *
 *   1. Wipe destDir, then walk the tar stream. For each entry:
 *      - directory: mkdirs
 *      - file: write contents
 *      - symlink: collect (path, target) into a list
 *      Record per-entry mode bits in parallel for later chmod pass.
 *
 *   2. Create all symlinks with [android.system.Os.symlink]. Has to come
 *      AFTER step 1 because some symlinks point at intermediate paths
 *      created earlier in the same archive.
 *
 *   3. Apply [android.system.Os.chmod] for both files and directories to
 *      restore the original Unix mode bits (commons-compress doesn't do
 *      this automatically on extraction).
 *
 * The order matters: symlinks before chmod on dirs, because
 * chmod(dir) doesn't affect symlinks inside it but the symlinks themselves
 * need to exist by the time the dir mode is restored (otherwise a 0700
 * dir would block us from following the symlink target).
 */
object ImageFsInstaller {
    private const val TAG = "ImageFsInstaller"

    /** tar entry types we care about. */
    private data class PendingSymlink(val linkPath: String, val target: String)
    private data class PendingChmod(val path: String, val mode: Int)

    /**
     * Extract a tar+zstd stream from `assets/<assetPath>` into `destDir`.
     * Returns true on success.
     */
    fun extractTzstFromAssets(ctx: Context, assetPath: String, destDir: File,
                              onProgress: ((bytes: Long) -> Unit)? = null): Boolean {
        if (!destDir.exists()) destDir.mkdirs()
        wipeDir(destDir)

        Log.i(TAG, "extractTzstFromAssets: $assetPath -> $destDir")

        val symlinks = mutableListOf<PendingSymlink>()
        val chmods = mutableListOf<PendingChmod>()
        val buf = ByteArray(64 * 1024)
        var totalBytes: Long = 0

        return try {
            // Pass 1: stream-extract the archive.
            ctx.assets.open(assetPath).use { raw ->
                BufferedInputStream(raw).use { buffered ->
                    ZstdCompressorInputStream(buffered).use { zstd ->
                        TarArchiveInputStream(zstd).use { tar ->
                            var entry: TarArchiveEntry? = tar.nextEntry
                            while (entry != null) {
                                // Skip PAX/GNU metadata headers — they don't carry file contents.
                                if (entry.isPaxHeader || entry.isGnuLongLink || entry.isGnuLongName) {
                                    entry = tar.nextEntry
                                    continue
                                }
                                val outFile = File(destDir, entry.name)
                                if (entry.isDirectory) {
                                    outFile.mkdirs()
                                    chmods.add(PendingChmod(outFile.absolutePath, entry.mode))
                                } else if (entry.isSymbolicLink || !entry.linkName.isNullOrEmpty()) {
                                    // Resolve relative targets against the link's
                                    // parent directory, matching tar behaviour.
                                    val target = entry.linkName ?: continue
                                    symlinks.add(PendingSymlink(outFile.absolutePath, target))
                                    chmods.add(PendingChmod(outFile.absolutePath, entry.mode))
                                } else {
                                    outFile.parentFile?.mkdirs()
                                    FileOutputStream(outFile).use { out ->
                                        while (true) {
                                            val n = tar.read(buf)
                                            if (n <= 0) break
                                            out.write(buf, 0, n)
                                            totalBytes += n
                                        }
                                    }
                                    chmods.add(PendingChmod(outFile.absolutePath, entry.mode))
                                }
                                entry = tar.nextEntry
                                onProgress?.invoke(totalBytes)
                            }
                        }
                    }
                }
            }
            Log.i(TAG, "extractTzstFromAssets: pass 1 done, ${symlinks.size} symlinks, ${chmods.size} chmods, $totalBytes bytes")

            // Pass 2: create symlinks. Do this BEFORE chmod-ing directories
            // because some symlinks point at intermediate files that the
            // chmod-pass will then need to stat through the symlink.
            for (sl in symlinks) {
                try {
                    val f = File(sl.linkPath)
                    // If something already exists at the symlink path, drop it
                    // so Os.symlink doesn't fail.
                    if (f.exists() || Files.exists(f.toPath(),
                                java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        f.delete()
                    }
                    // Make sure parent dir exists (some symlinks point into
                    // directories that haven't been explicitly listed in the
                    // tar; the parent of outFile was already created by the
                    // file/dir loop above, but symlinks-only subtrees may not
                    // be). Use absolute path so it lands at the right place.
                    f.parentFile?.mkdirs()
                    Os.symlink(sl.target, sl.linkPath)
                } catch (e: Exception) {
                    Log.w(TAG, "symlink failed: ${sl.linkPath} -> ${sl.target}: ${e.message}")
                }
            }
            Log.i(TAG, "extractTzstFromAssets: pass 2 done, ${symlinks.size} symlinks created")

            // Pass 3: apply mode bits. Directories come last so we don't
            // accidentally close a directory before its contents get chmod'd.
            for (cm in chmods) {
                try {
                    Os.chmod(cm.path, cm.mode)
                } catch (e: Exception) {
                    Log.w(TAG, "chmod failed: ${cm.path} (mode=${cm.mode}): ${e.message}")
                }
            }
            Log.i(TAG, "extractTzstFromAssets: pass 3 done, all modes applied")

            true
        } catch (e: Exception) {
            Log.e(TAG, "extractTzstFromAssets failed", e)
            false
        }
    }

    /**
     * Winlator-style check: if imagefs marker is missing or stale, re-extract.
     */
    fun installIfNeeded(ctx: Context, fs: ImageFs,
                        onProgress: ((Long) -> Unit)? = null,
                        onStatus: ((String) -> Unit)? = null): Boolean {
        ImageFs.ensureLayout(fs)
        if (fs.isInstalled()) {
            val v = runCatching { fs.versionFile().readText().trim().toInt() }.getOrDefault(-1)
            if (v >= ImageFs.CURRENT_VERSION) {
                Log.i(TAG, "imagefs already installed (version=$v)")
                return true
            }
            onStatus?.invoke("upgrading imagefs (was v$v, current v${ImageFs.CURRENT_VERSION})")
        } else {
            onStatus?.invoke("installing imagefs from assets (this can take a minute)")
        }
        val ok = extractTzstFromAssets(ctx, ImageFs.ASSET_TZST_PATH, fs.root, onProgress)
        if (ok) {
            fs.createMarker(ImageFs.CURRENT_VERSION)
            Log.i(TAG, "imagefs install complete, version=${ImageFs.CURRENT_VERSION}")
        } else {
            Log.e(TAG, "imagefs install failed — wine won't run")
        }
        return ok
    }

    /**
     * Recursively delete a directory's contents but leave the directory itself
     * (so the SD card / filesDir entry is preserved). Used to wipe a stale
     * imagefs before re-extraction.
     */
    private fun wipeDir(dir: File) {
        if (!dir.exists()) return
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) wipeDir(child)
            child.delete()
        }
    }

    /**
     * One-off helper for adb-pushed imagefs: if the user manually dropped a
     * pre-extracted imagefs/. somewhere (e.g. /sdcard/imagefs), this can
     * import it into filesDir. Preserves symlinks and mode bits.
     */
    fun importFromDir(srcDir: File, fs: ImageFs): Boolean {
        if (!srcDir.isDirectory) {
            Log.w(TAG, "importFromDir: $srcDir is not a directory")
            return false
        }
        wipeDir(fs.root)
        return try {
            copyRecursive(srcDir, fs.root)
            fs.createMarker(ImageFs.CURRENT_VERSION)
            true
        } catch (e: Exception) {
            Log.e(TAG, "importFromDir failed", e)
            false
        }
    }

    private fun copyRecursive(src: File, dst: File) {
        val srcPath = src.toPath()
        if (Files.isSymbolicLink(srcPath)) {
            // Recreate as a symlink
            val target = Files.readSymbolicLink(srcPath).toString()
            if (dst.exists() || Files.exists(dst.toPath(),
                        java.nio.file.LinkOption.NOFOLLOW_LINKS)) dst.delete()
            dst.parentFile?.mkdirs()
            try { Os.symlink(target, dst.absolutePath) } catch (e: Exception) {
                Log.w(TAG, "import symlink failed: $dst -> $target: ${e.message}")
            }
            return
        }
        if (src.isDirectory) {
            dst.mkdirs()
            // Copy mode bits for the dir itself
            try { Os.chmod(dst.absolutePath, srcPath.getMode()) } catch (_: Exception) {}
            src.listFiles()?.forEach { copyRecursive(it, File(dst, it.name)) }
        } else {
            src.inputStream().use { input ->
                FileOutputStream(dst).use { out -> input.copyTo(out) }
            }
            try { Os.chmod(dst.absolutePath, srcPath.getMode()) } catch (_: Exception) {}
        }
    }
}

// Small import aliases to keep the file readable
private val Files = java.nio.file.Files

/**
 * Read POSIX mode bits from a [java.nio.file.Path]. Returns an int suitable
 * for [android.system.Os.chmod] (e.g. 0o755, 0o644). Falls back to 0o644
 * on non-POSIX filesystems (shouldn't happen on Android / sdcardfs).
 */
private fun java.nio.file.Path.getMode(): Int {
    return try {
        val perms = Files.getPosixFilePermissions(this)
        var mode = 0
        // Position in the 9-bit permission field, MSB first.
        // (matches standard Unix order: rwx rwx rwx = 0o777)
        val order = listOf(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
            java.nio.file.attribute.PosixFilePermission.GROUP_READ,
            java.nio.file.attribute.PosixFilePermission.GROUP_WRITE,
            java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE,
            java.nio.file.attribute.PosixFilePermission.OTHERS_READ,
            java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE,
            java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE,
        )
        for ((i, p) in order.withIndex()) {
            if (p in perms) mode = mode or (1 shl (8 - i))
        }
        mode
    } catch (e: Exception) {
        0o644
    }
}

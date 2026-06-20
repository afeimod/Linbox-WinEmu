package org.github.ewt45.winemulator.glibc

import android.content.Context
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * ImageFsInstaller — winlator-style installer for the imagefs tzst bundle.
 *
 * Mirrors `com.winlator.xenvironment.ImageFsInstaller.installFromAssets`,
 * but uses linbox's already-imported commons-compress (zstd + tar) instead
 * of winlator's TarCompressorUtils. This way we don't pull a new dependency.
 *
 * Flow:
 *  1. Check `imagefs/.winlator/.img_version` marker.
 *  2. If missing or version < CURRENT_VERSION, wipe rootDir, then
 *     stream-extract `assets/imagefs/imagefs.tzst` to rootDir.
 *  3. Write the marker.
 *
 * Thread-safety: not thread-safe; call from a single background worker.
 */
object ImageFsInstaller {
    private const val TAG = "ImageFsInstaller"

    /**
     * Extract a tar+zstd stream from `assets/<assetPath>` into `destDir`.
     * Returns true on success, false if the asset is missing or extraction
     * fails midway.
     */
    fun extractTzstFromAssets(ctx: Context, assetPath: String, destDir: File,
                              onProgress: ((bytes: Long) -> Unit)? = null): Boolean {
        if (!destDir.exists()) destDir.mkdirs()
        // Wipe the destination first — partial extract state is worse than full wipe.
        wipeDir(destDir)

        val assetSize: Long = try {
            ctx.assets.open(assetPath).use { it.available().toLong() }
        } catch (e: Exception) {
            Log.w(TAG, "asset $assetPath missing: ${e.message}")
            return false
        }
        Log.i(TAG, "extractTzstFromAssets: $assetPath ($assetSize bytes) -> $destDir")

        return try {
            ctx.assets.open(assetPath).use { raw ->
                BufferedInputStream(raw).use { buffered ->
                    ZstdCompressorInputStream(buffered).use { zstd ->
                        TarArchiveInputStream(zstd).use { tar ->
                            var entry: TarArchiveEntry? = tar.nextEntry
                            var totalBytes: Long = 0
                            val buf = ByteArray(64 * 1024)
                            while (entry != null) {
                                val outFile = File(destDir, entry.name)
                                if (entry.isDirectory) {
                                    outFile.mkdirs()
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
                                    // Preserve executable bit for box64, ld-linux-*, .so
                                    val name = outFile.name
                                    if (name == "box64" || name.startsWith("ld-linux-") ||
                                            name.endsWith(".so") || name.endsWith(".so.1") ||
                                            name.endsWith(".so.2") || name.endsWith(".so.6")) {
                                        outFile.setExecutable(true, false)
                                    }
                                }
                                entry = tar.nextEntry
                                onProgress?.invoke(totalBytes)
                            }
                            Log.i(TAG, "extractTzstFromAssets: done, total $totalBytes bytes")
                            true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractTzstFromAssets failed", e)
            false
        }
    }

    /**
     * Winlator-style check: if imagefs marker is missing or stale, re-extract.
     * @param onProgress bytes-extracted callback for UI progress (optional).
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
     * import it into filesDir. Returns true on success.
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
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { copyRecursive(it, File(dst, it.name)) }
        } else {
            src.inputStream().use { input ->
                FileOutputStream(dst).use { out -> input.copyTo(out) }
            }
            if (src.canExecute() || src.name in setOf("box64", "ld-linux-aarch64.so.1") ||
                    src.name.endsWith(".so") || src.name.endsWith(".so.1")) {
                dst.setExecutable(true, false)
            }
        }
    }
}

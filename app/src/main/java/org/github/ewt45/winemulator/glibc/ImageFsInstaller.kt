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
                Log.i(TAG, "imagefs already installed (version=$current), re-running linterp patch")
                // Even when the bundle is already extracted, we must still
                // rewrite PT_INTERP because the user might have copied
                // an imagefs in by hand (no chance for the tar extract
                // hook to fire). patchelf is idempotent so calling it
                // again is safe.
                rewriteLinterps(imageFs.rootDir)
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

            // Fix known broken symlinks in winlator's imagefs layout.
            // wine starts up looking for libc.so (a Debian-shipped
            // symlink); winlator's imagefs ships it as
            //     usr/lib/libc.so -> libc-2.31.so
            // but libc-2.31.so is NOT in the tarball. So the symlink
            // is broken and wine aborts with
            //     wine: .../libc.so: bad ELF magic: 2f2a2047
            // when box64 tries to mmap it. Rewrite the symlink to
            // libc.so.6 (the only real libc in this imagefs).
            // Same for libc.so.6 -> libc-2.31.so if we see it.
            forceRewriteLibcSymlink(rootDir)
            // Re-patchelf every binary so its PT_INTERP points at
            // /imagefs/usr/lib/ld-linux-aarch64.so.1 instead of
            // /data/data/<pkg>/files/imagefs/usr/lib/.... proot's
            // chroot rewrite doesn't touch ELF linterps, so without
            // this box64 can't load from inside proot.
            rewriteLinterps(rootDir)

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
        var written = 0
        var skipped = 0
        var symlinks = 0
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
                    symlinks++
                    // commons-compress exposes the link target via the
                    // entry's name+suffix when the tar reader is TarArchive...
                    // In practice the link target comes from TarArchiveEntry
                    // which is what we actually receive here. Cast safely.
                    val rawLinkTarget = (entry as? org.apache.commons.compress.archivers.tar.TarArchiveEntry)
                        ?.linkName ?: continue

                    // CRITICAL: winlator's imagefs.tzst contains symlinks
                    // like `bin -> usr/bin` (relative). When the user copies
                    // that imagefs into our `--bind=/imagefs` proot mount,
                    // proot's `--link2symlink` resolves relative symlinks
                    // against the proot's ROOTFS, not against /imagefs.
                    // So `bin -> usr/bin` in the imagefs would resolve to
                    // `<rootfs>/usr/bin` (Debian XFCE binaries) instead of
                    // `<imagefs>/usr/bin`. This breaks everything.
                    //
                    // Fix: rewrite relative symlinks to absolute paths
                    // anchored under /imagefs, OR — even safer — leave the
                    // symlink alone but document that the user must run
                    // glibc-run with absolute paths.
                    //
                    // We rewrite symlink targets to absolute paths
                    // anchored under the Android-side imagefs rootDir,
                    // because box64 reads symlinks from inside the imagefs
                    // (its own ELF loader, not Linux's) and Linux
                    // readlink() returns whatever we wrote at extract time.
                    //
                    // Critically, we DO NOT prepend `/imagefs/` here.
                    // /imagefs is a proot-internal path that only exists
                    // inside linbox's proot container. When box64 reads the
                    // symlink via its own loader from outside proot (which
                    // is how the Android UI launcher starts box64), /imagefs
                    // resolves to nothing. Use the Android-side absolute
                    // path instead.
                    //
                    // Important: resolve relative targets against the
                    // symlink's PARENT directory (the standard symlink
                    // semantics), not against imagefs root.
                    val linkTarget: String = if (rawLinkTarget.startsWith("/")) {
                        // rawLinkTarget is absolute (e.g. /lib/foo).
                        // Anchor it under rootDir so it's resolvable from
                        // Android-side processes.
                        rootDir.absolutePath + rawLinkTarget
                    } else {
                        // Resolve the relative path against the symlink's
                        // parent dir. `relative` is the path of the symlink
                        // itself, e.g. "usr/bin".
                        val parentRel = File(relative).parent ?: ""
                        // Use java.nio.file.Path to resolve "../" segments
                        // cleanly instead of string concat.
                        val resolvedRel = if (rawLinkTarget.startsWith("./")) {
                            rawLinkTarget.removePrefix("./")
                        } else {
                            rawLinkTarget
                        }
                        val parentPath = java.nio.file.Paths.get(parentRel)
                        val combined = parentPath.resolve(resolvedRel).normalize()
                        rootDir.absolutePath + "/" + combined.toString().trimStart('/')
                    }

                    // Recreate symlink. Delete existing file first to avoid
                    // FileUtils.symlink throwing on pre-existing files.
                    if (target.exists() || target.isDirectory) FileUtils.delete(target)
                    try {
                        // Try multiple symlink APIs because Android sdcardfs
                        // sometimes blocks Os.symlink() with EPERM even when
                        // java.nio can create the link.
                        try {
                            android.system.Os.symlink(linkTarget, target.absolutePath)
                        } catch (_: Exception) {
                            java.nio.file.Files.createSymbolicLink(
                                target.toPath(),
                                java.nio.file.Paths.get(linkTarget)
                            )
                        }
                    } catch (symlinkErr: Exception) {
                        // Symlink creation truly failed (filesystem doesn't
                        // support it). DO NOT fall back to writing the link
                        // target as text — that creates a non-ELF file with
                        // garbage bytes that breaks wine/box64 ld.so loading.
                        // Instead, try to physically copy the link target's
                        // contents into place. This inflates the imagefs by
                        // deduplicating libc.so.6 etc., but it's the only
                        // way to make box64/wine load on filesystems that
                        // refuse symlinks.
                        val linkPath = File(target.parentFile, linkTarget.removePrefix("./"))
                        if (linkPath.isFile && linkPath.canRead()) {
                            try {
                                linkPath.copyTo(target, overwrite = true)
                                Log.i(TAG, "  copied ${target.name} <- ${linkPath.name} (symlink unavailable)")
                            } catch (copyErr: Exception) {
                                Log.w(TAG, "failed to symlink AND copy ${target.name}: symlink=${symlinkErr.message}, copy=${copyErr.message}")
                            }
                        } else {
                            Log.w(TAG, "failed to symlink ${target.name} -> $linkTarget: ${symlinkErr.message}")
                        }
                    }
                }
                else -> {
                    try {
                        FileOutputStream(target).use { out ->
                            tin.copyTo(out)
                        }
                        written++
                    } catch (e: Exception) {
                        skipped++
                        Log.w(TAG, "  failed to write $relative: ${e.message}")
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
        Log.i(TAG, "extractEntries done: written=$written skipped=$skipped symlinks=$symlinks")
    }

    /**
     * Re-patchelf every ELF binary we extracted so its PT_INTERP points
     * at the proot-side mount path `/imagefs/usr/lib/ld-linux-aarch64.so.1`
     * instead of the Android-side absolute path
     * `/data/data/<pkg>/files/imagefs/usr/lib/ld-linux-aarch64.so.1`.
     *
     * Why: proot `--bind=/data/data/<pkg>/files/imagefs:/imagefs` mounts
     * imagefs into the container under `/imagefs`, but proot's chroot
     * rewrite only applies to argv paths, NOT to linterp paths the
     * kernel reads from the ELF header. So a box64 binary whose linterp
     * still says `/data/data/.../imagefs/usr/lib/ld-linux-aarch64.so.1`
     * will ENOENT when exec'd from inside proot (kernel looks in the
     * chroot's view of `/data/data/...` which doesn't exist).
     *
     * If we patchelf the linterp to `/imagefs/...`, proot's bind mount
     * makes that path resolvable inside the chroot.
     *
     * We only do this when the linterp currently points at our
     * Android-side imagefs rootDir; binaries with other linterps (e.g.
     * a vanilla Debian linterp) are left alone because rewriting them
     * would break the binary's own ELF loader assumptions.
     */
    private fun rewriteLinterps(rootDir: File) {
        val rootPath = rootDir.absolutePath
        val targetLinterp = "/imagefs/usr/lib/ld-linux-aarch64.so.1"
        var rewrote = 0
        var scanned = 0
        rootDir.walkTopDown()
            .onEnter { !it.name.startsWith("proc") && !it.name.startsWith("sys") }
            .filter { it.isFile && it.canExecute() }
            .forEach { f ->
                scanned++
                try {
                    val current = readLinterp(f) ?: return@forEach
                    if (current.startsWith("$rootPath/") && current.endsWith("ld-linux-aarch64.so.1")) {
                        if (writeLinterp(f, targetLinterp)) {
                            rewrote++
                        }
                    }
                } catch (_: Exception) {
                    // Not an ELF or unreadable; skip silently.
                }
            }
        Log.i(TAG, "rewriteLinterps: scanned $scanned executables, rewrote $rewrote")
    }

    /**
     * Winlator's imagefs ships `usr/lib/libc.so` as a symlink to
     * `libc-2.31.so` (the Debian package convention) but the
     * imagefs.tzst does NOT contain a real `libc-2.31.so` file —
     * only the unversioned `libc.so.6` ELF. The result: the symlink
     * is broken and any program (wine, ldd, ...) that reads
     * `libc.so` gets a 0-byte / garbage result that fails ELF
     * parsing. wine reports this as
     *     ".../libc.so has bad ELF magic: 2f2a2047"
     * where 2f2a2047 is ASCII for "/ * G" (the prefix of a broken
     * symlink target string when read as raw bytes).
     *
     * We force-fix this by deleting the broken symlink and creating
     * a fresh one pointing at `libc.so.6` (the real ELF in this
     * imagefs).
     */
    private fun forceRewriteLibcSymlink(rootDir: File) {
        val candidates = listOf(
            File(rootDir, "usr/lib/libc.so"),
            File(rootDir, "usr/lib/x86_64-linux-gnu/libc.so"),
            File(rootDir, "lib/x86_64-linux-gnu/libc.so"),
            File(rootDir, "lib/libc.so"),
        )
        val targets = listOf(
            "libc.so.6",
            "../libc.so.6",
            "x86_64-linux-gnu/libc.so.6",
            "../x86_64-linux-gnu/libc.so.6",
        )
        for (libcSo in candidates) {
            // Check whether the symlink target resolves to a real file.
            // If it does, leave it alone — Debian packages sometimes
            // do include libc-2.X.so. If it doesn't (broken symlink,
            // 0-byte file, or wrong ELF magic), rewrite.
            val targetOk = try {
                val real = libcSo.canonicalFile
                real.exists() && real.length() > 1000
            } catch (_: Exception) { false }
            if (targetOk) {
                Log.i(TAG, "libc.so OK: ${libcSo.absolutePath} -> ${libcSo.canonicalFile}")
                continue
            }
            // Find a working target.
            var bestTarget: String? = null
            for (t in targets) {
                val test = File(libcSo.parentFile, t.removePrefix("../"))
                if (test.exists() && test.length() > 1000) {
                    bestTarget = t
                    break
                }
            }
            if (bestTarget == null) {
                Log.w(TAG, "no real libc.so.6 found near ${libcSo.absolutePath}, skipping")
                continue
            }
            try {
                if (libcSo.exists()) libcSo.delete()
                android.system.Os.symlink(bestTarget, libcSo.absolutePath)
                Log.i(TAG, "rewrote broken libc.so: ${libcSo.absolutePath} -> $bestTarget")
            } catch (e: Exception) {
                Log.w(TAG, "failed to rewrite libc.so: ${e.message}")
            }
        }
    }

    private fun readLinterp(file: File): String? {
        return try {
            // ELF header: e_ident[EI_MAG0..3] = 0x7f 'E' 'L' 'F', then
            // ei_class (1=32, 2=64). We accept both. After the 16-byte
            // e_ident comes e_type (2), e_machine (2), e_version (4),
            // e_entry (4/8), e_phoff (4/8), then we seek to e_phoff and
            // read program headers until we find PT_INTERP (type=3).
            val bytes = file.readBytes()
            if (bytes.size < 52) return null
            if (bytes[0] != 0x7F.toByte() || bytes[1] != 'E'.code.toByte() ||
                bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()) return null
            val is64 = bytes[4] == 2.toByte()
            val ePhoff = if (is64) {
                java.nio.ByteBuffer.wrap(bytes, 32, 8).long
            } else {
                java.nio.ByteBuffer.wrap(bytes, 28, 4).int.toLong() and 0xFFFFFFFFL
            }
            val ePhentsize: Int
            val ePhnum: Int
            if (is64) {
                ePhentsize = java.nio.ByteBuffer.wrap(bytes, 54, 2).short.toInt() and 0xFFFF
                ePhnum = java.nio.ByteBuffer.wrap(bytes, 56, 2).short.toInt() and 0xFFFF
            } else {
                ePhentsize = java.nio.ByteBuffer.wrap(bytes, 42, 2).short.toInt() and 0xFFFF
                ePhnum = java.nio.ByteBuffer.wrap(bytes, 44, 2).short.toInt() and 0xFFFF
            }
            val pOffset: Int
            val pFilesz: Int
            if (is64) {
                pOffset = 4
                pFilesz = 32
            } else {
                pOffset = 4
                pFilesz = 16
            }
            for (i in 0 until ePhnum) {
                val phStart = (ePhoff + i * ePhentsize).toInt()
                if (phStart + pFilesz > bytes.size) return null
                val pType = if (is64) {
                    java.nio.ByteBuffer.wrap(bytes, phStart, 4).int
                } else {
                    java.nio.ByteBuffer.wrap(bytes, phStart, 4).int
                }
                if (pType == 3) { // PT_INTERP
                    val pOff = if (is64) {
                        java.nio.ByteBuffer.wrap(bytes, phStart + 8, 8).long
                    } else {
                        java.nio.ByteBuffer.wrap(bytes, phStart + 4, 4).int.toLong() and 0xFFFFFFFFL
                    }
                    val pSz = if (is64) {
                        java.nio.ByteBuffer.wrap(bytes, phStart + 32, 8).long
                    } else {
                        java.nio.ByteBuffer.wrap(bytes, phStart + 16, 4).int.toLong() and 0xFFFFFFFFL
                    }
                    val end = (pOff + pSz).toInt().coerceAtMost(bytes.size)
                    val start = pOff.toInt().coerceAtLeast(0)
                    return String(bytes, start, end - start, Charsets.US_ASCII).trimEnd('\u0000')
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun writeLinterp(file: File, newLinterp: String): Boolean {
        return try {
            val bytes = file.readBytes()
            if (bytes.size < 52) return false
            if (bytes[0] != 0x7F.toByte()) return false
            val is64 = bytes[4] == 2.toByte()
            val ePhoff = if (is64) {
                java.nio.ByteBuffer.wrap(bytes, 32, 8).long
            } else {
                java.nio.ByteBuffer.wrap(bytes, 28, 4).int.toLong() and 0xFFFFFFFFL
            }
            val ePhentsize: Int
            val ePhnum: Int
            if (is64) {
                ePhentsize = java.nio.ByteBuffer.wrap(bytes, 54, 2).short.toInt() and 0xFFFF
                ePhnum = java.nio.ByteBuffer.wrap(bytes, 56, 2).short.toInt() and 0xFFFF
            } else {
                ePhentsize = java.nio.ByteBuffer.wrap(bytes, 42, 2).short.toInt() and 0xFFFF
                ePhnum = java.nio.ByteBuffer.wrap(bytes, 44, 2).short.toInt() and 0xFFFF
            }
            val pInterpOffsetInPhdr: Int
            val pFileszOffsetInPhdr: Int
            if (is64) {
                pInterpOffsetInPhdr = 8
                pFileszOffsetInPhdr = 32
            } else {
                pInterpOffsetInPhdr = 4
                pFileszOffsetInPhdr = 16
            }
            for (i in 0 until ePhnum) {
                val phStart = (ePhoff + i * ePhentsize).toInt()
                if (phStart + pFileszOffsetInPhdr + 8 > bytes.size) return false
                val pType = java.nio.ByteBuffer.wrap(bytes, phStart, 4).int
                if (pType == 3) { // PT_INTERP
                    val pOff = if (is64) {
                        java.nio.ByteBuffer.wrap(bytes, phStart + pInterpOffsetInPhdr, 8).long
                    } else {
                        java.nio.ByteBuffer.wrap(bytes, phStart + pInterpOffsetInPhdr, 4).int.toLong() and 0xFFFFFFFFL
                    }
                    val interpStart = pOff.toInt()
                    // Read existing size
                    val oldSz = if (is64) {
                        java.nio.ByteBuffer.wrap(bytes, phStart + pFileszOffsetInPhdr, 8).long
                    } else {
                        java.nio.ByteBuffer.wrap(bytes, phStart + pFileszOffsetInPhdr, 4).int.toLong() and 0xFFFFFFFFL
                    }.toInt()
                    val newBytes = newLinterp.toByteArray(Charsets.US_ASCII) + 0  // NUL terminator
                    if (newBytes.size > oldSz) {
                        // Padding with zeros won't fit; need to extend the file.
                        Log.w(TAG, "writeLinterp: new linterp '$newLinterp' (${newBytes.size}B) " +
                            "doesn't fit in existing PT_INTERP segment (${oldSz}B); skipping ${file.name}")
                        return false
                    }
                    // Zero out old interp region, then write new bytes
                    for (k in 0 until oldSz) bytes[interpStart + k] = 0
                    System.arraycopy(newBytes, 0, bytes, interpStart, newBytes.size)
                    file.writeBytes(bytes)
                    // chmod +x in case writeBytes reset it
                    try { android.system.Os.chmod(file.absolutePath, 493) } catch (_: Exception) {}
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "writeLinterp on ${file.name} failed: ${e.message}")
            false
        }
    }

    /** Smoke-test the installed imagefs.
     *
     * Returns null if *any* one of box64 / wine64 / libc.so.6 is present.
     * We don't require all three because some users (e.g. those copying
     * winlator's stock imagefs) may have a subset. The actual bind will
     * succeed regardless; if a specific binary is missing, glibc-run.sh
     * reports a clear error at runtime.
     */
    fun smokeTest(imageFs: ImageFs): String? {
        val candidates = listOf(
            "${imageFs.rootDir.absolutePath}/usr/local/bin/box64",
            "${imageFs.winePath}/bin/wine64",
            "${imageFs.rootDir.absolutePath}/usr/lib/x86_64-linux-gnu/libc.so.6",
        )
        val anyFound = candidates.any { File(it).exists() }
        if (!anyFound) {
            return "imagefs 里没找到任何 box64 / wine64 / libc.so.6," +
                " 请确认 assets/imagefs/imagefs.tzst 是 winlator-glibc 风格"
        }
        return null
    }
}

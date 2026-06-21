package org.github.ewt45.winemulator.glibc

import android.content.Context
import android.os.Process
import android.system.Os
import android.util.Log
import java.io.File

/**
 * Launches a Windows .exe by forking `box64 wine <args>` directly on the
 * Android side (NOT inside the proot container).
 *
 * Why direct, not proot:
 *
 *   winlator's box64 binary is patched with
 *     patchelf --set-interpreter <imagefs>/usr/lib/ld-linux-aarch64.so.1
 *   so its linterp points at an Android-side absolute path. proot does
 *   NOT rewrite paths that the kernel reads from the ELF interpreter
 *   field — it only rewrites argv paths. So when the proot-launched
 *   box64 tries to exec, the kernel looks for the linterp at
 *   `/data/user/0/<pkg>/files/imagefs/usr/lib/ld-linux...so.1` — which
 *   is NOT inside proot's rootfs tree and the kernel returns ENOENT.
 *
 *   Running box64 directly from the Android process (the linbox app's
 *   own uid) means the kernel can resolve the linterp normally,
 *   because the path is a real path under `<filesDir>/imagefs/`.
 *
 *   box64 itself loads wine through its own ELF loader (it doesn't use
 *   /lib/ld-linux.so.1 of the host) so the glibc-loaded wine binary
 *   runs inside box64's emulated process with the imagefs lib path.
 *
 * This is the same architecture winlator uses (see winlator-glibc's
 * `GuestProgramLauncherComponent.execGuestProgram`).
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
        for (c in candidates) if (File(c).canExecute()) return c
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
        for (c in candidates) if (File(c).canExecute()) return c
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
     * Mirrors winlator-glibc's GlibcProgramLauncherComponent.execGuestProgram.
     */
    fun buildEnv(): Map<String, String> {
        val root = imageFs.rootDir.absolutePath
        val winePath = imageFs.winePath
        val map = HashMap<String, String>()
        map["HOME"] = "${root}/home/xuser"
        map["USER"] = "xuser"
        map["TMPDIR"] = "${root}/tmp"
        map["PATH"] = "$winePath/bin:$root/usr/local/bin:$root/usr/bin:$root/bin:/system/bin:/system/xbin"
        map["LD_LIBRARY_PATH"] = "$root/usr/lib/aarch64-linux-gnu:$root/usr/lib"
        map["BOX64_LD_LIBRARY_PATH"] = "$root/usr/lib/x86_64-linux-gnu:$root/lib/x86_64-linux-gnu"
        map["ANDROID_SYSVSHM_SERVER"] = "/data/data/${imageFs.rootDir.name}/files/imagefs/.sysvshm"
        map["FONTCONFIG_PATH"] = "$root/usr/etc/fonts"
        map["WINEPREFIX"] = "${root}/home/xuser/.wine"
        map["DISPLAY"] = ":0"           // winlator sets ":0" but linbox X server runs on ":13" — set by parent
        map["PULSE_SERVER"] = "tcp:127.0.0.1:4713"
        map["BOX64_DYNAREC"] = "1"
        return map
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
            // CRITICAL: rewrite PT_INTERP on every binary so box64/wine
            // can be exec'd from inside proot. proot's chroot rewrite
            // only applies to argv paths, NOT to linterp paths the
            // kernel reads from the ELF header. Without this, the
            // kernel looks for the linterp at the original Android-side
            // absolute path which doesn't exist in the chroot root.
            runCatching { rewriteLinterps(launcher.imageFs.rootDir) }
                .onSuccess { (rewrote, scanned) ->
                    android.util.Log.i(TAG, "rewriteLinterps: scanned=$scanned rewrote=$rewrote")
                }
                .onFailure { android.util.Log.w(TAG, "rewriteLinterps failed: ${it.message}") }

            val verify = launcher.verifyReady()
            android.util.Log.i(TAG, "ensureReady: verifyReady=$verify, box64=${launcher.androidBox64Path()}, wine=${launcher.androidWinePath()}")
            return verify
        }

        /**
         * Walk the imagefs directory and rewrite the PT_INTERP of every
         * ELF binary whose linterp currently points at our Android-side
         * imagefs rootDir, replacing it with the proot-side mount path
         * `/imagefs/usr/lib/ld-linux-aarch64.so.1`.
         *
         * Idempotent: once a linterp already starts with `/imagefs/`,
         * it's left alone.
         */
        private fun rewriteLinterps(rootDir: File): Pair<Int, Int> {
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
                        if (current.startsWith("$rootPath/") &&
                            current.endsWith("ld-linux-aarch64.so.1")) {
                            if (writeLinterp(f, targetLinterp)) rewrote++
                        }
                    } catch (_: Exception) {
                        // Not an ELF or unreadable; skip.
                    }
                }
            return rewrote to scanned
        }

        private fun readLinterp(file: File): String? {
            return try {
                val bytes = file.readBytes()
                if (bytes.size < 52) return null
                if (bytes[0] != 0x7F.toByte() ||
                    bytes[1] != 'E'.code.toByte() ||
                    bytes[2] != 'L'.code.toByte() ||
                    bytes[3] != 'F'.code.toByte()) return null
                val is64 = bytes[4] == 2.toByte()
                val ePhoff: Long = if (is64) {
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
                for (i in 0 until ePhnum) {
                    val phStart = (ePhoff + i * ePhentsize).toInt()
                    if (phStart + 56 > bytes.size) return null
                    val pType = java.nio.ByteBuffer.wrap(bytes, phStart, 4).int
                    if (pType == 3) {
                        val pOff: Long = if (is64) {
                            java.nio.ByteBuffer.wrap(bytes, phStart + 8, 8).long
                        } else {
                            java.nio.ByteBuffer.wrap(bytes, phStart + 4, 4).int.toLong() and 0xFFFFFFFFL
                        }
                        val pSz: Long = if (is64) {
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
            } catch (_: Exception) { null }
        }

        private fun writeLinterp(file: File, newLinterp: String): Boolean {
            return try {
                val bytes = file.readBytes()
                if (bytes.size < 52) return false
                if (bytes[0] != 0x7F.toByte()) return false
                val is64 = bytes[4] == 2.toByte()
                val ePhoff: Long = if (is64) {
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
                if (is64) { pInterpOffsetInPhdr = 8; pFileszOffsetInPhdr = 32 }
                else { pInterpOffsetInPhdr = 4; pFileszOffsetInPhdr = 16 }
                for (i in 0 until ePhnum) {
                    val phStart = (ePhoff + i * ePhentsize).toInt()
                    if (phStart + pFileszOffsetInPhdr + 8 > bytes.size) return false
                    val pType = java.nio.ByteBuffer.wrap(bytes, phStart, 4).int
                    if (pType == 3) {
                        val pOff: Long = if (is64) {
                            java.nio.ByteBuffer.wrap(bytes, phStart + pInterpOffsetInPhdr, 8).long
                        } else {
                            java.nio.ByteBuffer.wrap(bytes, phStart + pInterpOffsetInPhdr, 4).int.toLong() and 0xFFFFFFFFL
                        }
                        val interpStart = pOff.toInt()
                        val oldSz: Long = if (is64) {
                            java.nio.ByteBuffer.wrap(bytes, phStart + pFileszOffsetInPhdr, 8).long
                        } else {
                            java.nio.ByteBuffer.wrap(bytes, phStart + pFileszOffsetInPhdr, 4).int.toLong() and 0xFFFFFFFFL
                        }
                        val newBytes = newLinterp.toByteArray(Charsets.US_ASCII) + 0
                        if (newBytes.size > oldSz.toInt()) return false
                        for (k in 0 until oldSz.toInt()) bytes[interpStart + k] = 0
                        System.arraycopy(newBytes, 0, bytes, interpStart, newBytes.size)
                        file.writeBytes(bytes)
                        try { android.system.Os.chmod(file.absolutePath, 493) } catch (_: Exception) {}
                        return true
                    }
                }
                false
            } catch (_: Exception) { false }
        }
    }
}

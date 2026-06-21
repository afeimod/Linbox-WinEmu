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
            if (!launcher.isInstalled()) {
                val ok = ImageFsInstaller.installIfNeeded(context)
                if (!ok) return "imagefs 资产解压失败,请检查 assets/imagefs/imagefs.tzst 是否存在"
            }
            return launcher.verifyReady()
        }
    }
}

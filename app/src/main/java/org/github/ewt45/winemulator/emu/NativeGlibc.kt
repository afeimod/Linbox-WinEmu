package org.github.ewt45.winemulator.emu

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.github.ewt45.winemulator.Consts
import org.github.ewt45.winemulator.Utils.chmod
import java.io.File

/**
 * 原生 glibc 启动器。
 *
 * 不经过 PRoot，直接使用 rootfs 中的 glibc 动态链接器 (ld-linux-aarch64.so.1)
 * 启动目标二进制。glibc 自身已通过补丁处理 Android 适配（syscall 伪造、
 * 共享内存、NSS、路径翻译等），无需 ptrace 拦截，性能最优。
 *
 * 用途：专门运行 box64 + wine + Windows 程序。
 */
class NativeGlibc(private val glibcSelfCheck: Boolean = false) : Launcher {
    private val TAG = "NativeGlibc"
    private val nativeUser = "xuser"

    override var lastTimeCmd = ""

    override suspend fun attach(): ProcessBuilder = withContext(Dispatchers.IO) {
        val rootfs = Consts.rootfsCurrDir
        val lang = Consts.Pref.general_rootfs_lang.get()

        val linkerPaths = listOf(
            File(rootfs, "usr/lib/ld-linux-aarch64.so.1"),
            File(rootfs, "lib/ld-linux-aarch64.so.1"),
            File(rootfs, "usr/lib/ld-linux.so.1"),
            File(rootfs, "lib/ld-linux.so.1"),
        )
        val linker = linkerPaths.find { it.isFile }
            ?: throw RuntimeException(
                "未找到 glibc 动态链接器 (ld-linux-aarch64.so.1)。" +
                    "请确认 rootfs 中已安装修补过的 glibc。" +
                    "查找路径: ${linkerPaths.joinToString(", ") { it.absolutePath }}"
            )

        val libPaths = listOf(
            File(rootfs, "usr/lib"),
            File(rootfs, "lib"),
        ).filter { it.isDirectory }.joinToString(":") { it.absolutePath }
        if (libPaths.isBlank()) {
            throw RuntimeException("rootfs 中没有可用的 glibc 库目录")
        }

        val home = File(rootfs, "home/$nativeUser")
        val shell = File(rootfs, "bin/sh")
        val selfCheck = File(rootfs, "usr/bin/getconf")
        val target = if (glibcSelfCheck) selfCheck else shell
        if (!target.isFile) {
            throw RuntimeException("rootfs 中未找到启动目标: ${target.absolutePath}")
        }

        home.mkdirs()
        Consts.tmpDir.mkdirs()
        chmod(Consts.tmpDir, "777")

        val loginEnvs = EnvMap()
        readEtcEnvironment(rootfs, loginEnvs)
        loginEnvs.put("LD_LIBRARY_PATH", libPaths, true)
        loginEnvs.put("GLIBC_LD_LIBRARY_PATH", libPaths, true)
        loginEnvs.put(
            "PATH",
            listOf("usr/bin", "bin", "usr/sbin", "sbin")
                .map { File(rootfs, it).absolutePath }
                .joinToString(":"),
            true
        )
        loginEnvs.put("HOME", home.absolutePath, true)
        loginEnvs.put("LANG", lang, true)
        loginEnvs.put("USER", nativeUser, true)
        loginEnvs.put("TMPDIR", Consts.tmpDir.absolutePath, true)
        loginEnvs.put("DISPLAY", ":13", true)
        loginEnvs.put("PULSE_SERVER", "tcp:127.0.0.1:4713", true)

        val sysvshm = listOf(
            File(rootfs, "usr/lib/libandroid-sysvshm.so"),
            File(rootfs, "lib/libandroid-sysvshm.so"),
        ).find { it.isFile }
        if (sysvshm != null) {
            loginEnvs.put("LD_PRELOAD", sysvshm.absolutePath, true)
            loginEnvs.put("ANDROID_SYSVSHM_SERVER", File(rootfs, "tmp/.sysvshm/SM0").absolutePath, true)
        }

        val cmd = mutableListOf(linker.absolutePath, "--library-path", libPaths, target.absolutePath)
        if (glibcSelfCheck) {
            cmd += "GNU_LIBC_VERSION"
        } else {
            cmd += "-l"
        }

        lastTimeCmd = cmd.joinToString(" ")
        Log.d(TAG, "attach: 启动命令=$lastTimeCmd")

        ProcessBuilder(cmd)
            .directory(home)
            .also {
                loginEnvs.map.forEach { (k, v) -> it.environment()[k] = v }
            }
            .redirectErrorStream(true)
    }

    private fun readEtcEnvironment(rootfs: File, envMap: EnvMap) {
        val environmentFile = File(rootfs, "etc/environment")
        if (!environmentFile.isFile) return
        runCatching {
            for (line in environmentFile.readLines()) {
                val trimmed = line.trim()
                if (!trimmed.startsWith('#') && trimmed.contains('=')) {
                    val split = trimmed.split("=", limit = 2)
                    envMap.put(split[0], split[1].trim('"'))
                }
            }
        }.onFailure { error ->
            Log.d(TAG, "读取 ${environmentFile.absolutePath} 失败: ${error.message}")
        }
    }
}


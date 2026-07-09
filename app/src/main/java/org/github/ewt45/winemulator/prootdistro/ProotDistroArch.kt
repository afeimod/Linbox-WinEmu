package org.github.ewt45.winemulator.prootdistro

import android.os.Build
import java.util.Locale

/**
 * 参考 `proot_distro/arch.py`——把 proot-distro 使用的 arch 名
 * (aarch64 / arm / i686 / x86_64 / riscv64) 和 Docker registry
 * 期望的 (arm64 / arm/v7 / 386 / amd64 / riscv64) 之间做转换。
 *
 * 同时提供 [getDeviceCpuArch] 用 [Build.SUPPORTED_ABIS] 判断当前
 * 安卓设备主架构,失败时回退到 [System.getProperty] "os.arch"。
 */
object ProotDistroArch {
    /** proot-distro arch → (Docker arch, Docker variant) */
    private val ARCH_TO_DOCKER = mapOf(
        "aarch64" to Pair("arm64", ""),
        "arm" to Pair("arm", "v7"),
        "i686" to Pair("386", ""),
        "x86_64" to Pair("amd64", ""),
        "riscv64" to Pair("riscv64", ""),
    )

    /** 反向:把安卓 ABI 转成 proot-distro 的 arch 名 */
    private val ANDROID_ABI_TO_PD = mapOf(
        "arm64-v8a" to "aarch64",
        "armeabi-v7a" to "arm",
        "armeabi" to "arm",
        "x86_64" to "x86_64",
        "x86" to "i686",
        "riscv64" to "riscv64",
    )

    /**
     * 获取当前设备的 proot-distro arch 名(字符串)。
     * 找不到时抛出 [IllegalStateException]。
     */
    fun getDeviceCpuArch(): String {
        // 1. 优先用 SUPPORTED_ABIS (按优先级排好序的 ABI 列表)
        try {
            val abis = Build.SUPPORTED_ABIS
            if (!abis.isNullOrEmpty()) {
                for (abi in abis) {
                    ANDROID_ABI_TO_PD[abi]?.let { return it }
                }
            }
        } catch (_: Throwable) {
            // Build.SUPPORTED_ABIS 在某些定制 ROM 上可能拿不到
        }

        // 2. 回退到 System.getProperty("os.arch")
        val sysArch = System.getProperty("os.arch")?.lowercase(Locale.ROOT)
        when (sysArch) {
            "aarch64" -> return "aarch64"
            "amd64", "x86_64" -> return "x86_64"
            "i386", "i686", "x86" -> return "i686"
            "arm" -> return "arm"
            "riscv64" -> return "riscv64"
        }
        throw IllegalStateException("unsupported CPU architecture: $sysArch")
    }

    /** 校验一个 proot-distro arch 字符串是否合法,返回标准形式。null 表示非法 */
    fun normalize(arch: String?): String? {
        if (arch.isNullOrBlank()) return null
        val low = arch.lowercase(Locale.ROOT)
        return when (low) {
            "aarch64", "arm64" -> "aarch64"
            "arm", "armv7l", "armv7" -> "arm"
            "i686", "i386", "386", "x86" -> "i686"
            "x86_64", "amd64" -> "x86_64"
            "riscv64" -> "riscv64"
            else -> null
        }
    }

    /**
     * proot-distro arch → Docker manifest 期望的 (arch, variant)。
     * @throws IllegalArgumentException arch 未知
     */
    fun toDocker(arch: String): Pair<String, String> {
        return ARCH_TO_DOCKER[arch]
            ?: throw IllegalArgumentException(
                "unknown architecture '$arch'. Valid values: aarch64, arm, " +
                "i686, riscv64, x86_64 (or Docker format: linux/arm64, " +
                "linux/amd64, linux/arm/v7, linux/386, linux/riscv64)."
            )
    }
}
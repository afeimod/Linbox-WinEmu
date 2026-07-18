package org.github.ewt45.winemulator.glibcwine

import android.content.Context
import android.util.Log
import java.io.File
import java.util.regex.Pattern

/**
 * Wine 版本信息, 移植自 winlator-glibc 的 WineInfo.java。
 *
 * 支持两个内置版本:
 * - WINE_X86_64: Wine 9.16, x86_64 架构, 通过 box64 翻译运行
 * - WINE_ARM64EC: Wine 10.14, arm64ec 架构, 原生 ARM64 + FEX 翻译
 *
 * 标识符格式:
 * - 内置: "Wine-9.16-x86_64", "Wine-10.14-arm64ec"
 * - 用户安装: "wine-<version>-<subversion>-<arch>"
 */
data class WineInfo(
    val version: String,
    val subversion: String?,
    var arch: String,
    val path: String?
) {
    companion object {
        private val TAG = "WineInfo"
        private val pattern = Pattern.compile("^wine\\-([0-9\\.]+)\\-?(?:(.+)\\-)?(x86_64|arm64ec)$", Pattern.CASE_INSENSITIVE)

        val WINE_X86_64 = WineInfo("9.16", null, "x86_64", GlibcWineConsts.WINE_X86_64_PATH_REL)
        val WINE_ARM64EC = WineInfo("10.14", null, "arm64ec", GlibcWineConsts.WINE_ARM64EC_PATH_REL)
        val MAIN_WINE_VERSION = WINE_X86_64

        fun fromIdentifier(context: Context, identifier: String?): WineInfo {
            if (identifier == null) return MAIN_WINE_VERSION

            if (identifier.equals(WINE_X86_64.identifier(), ignoreCase = true)) return WINE_X86_64
            if (identifier.equals(WINE_ARM64EC.identifier(), ignoreCase = true)) return WINE_ARM64EC

            // 检查用户安装的 wine (通过 ContentsManager, 暂时简化处理)
            val matcher = pattern.matcher(identifier)
            if (matcher.find()) {
                val path = "${GlibcWineConsts.CONTENTS_DIR_REL}/wine/$identifier"
                return WineInfo(matcher.group(1), matcher.group(2), matcher.group(3).lowercase(), path)
            }
            return MAIN_WINE_VERSION
        }

        fun isMainWineVersion(wineVersion: String?): Boolean {
            return wineVersion == null ||
                    wineVersion.equals(WINE_X86_64.identifier(), ignoreCase = true) ||
                    wineVersion.equals(WINE_ARM64EC.identifier(), ignoreCase = true)
        }
    }

    fun isWin64(): Boolean = true

    fun isDefaultWine(): Boolean {
        return this === WINE_X86_64 || this === WINE_ARM64EC ||
                (path != null && (path == GlibcWineConsts.WINE_X86_64_PATH_REL || path == GlibcWineConsts.WINE_ARM64EC_PATH_REL))
    }

    fun identifier(): String {
        if (this === WINE_X86_64) return "Wine-9.16-x86_64"
        if (this === WINE_ARM64EC) return "Wine-10.14-arm64ec"
        return "wine-${fullVersion()}-$arch"
    }

    fun fullVersion(): String = version + (subversion?.let { "-$it" } ?: "")

    /**
     * 返回 wine 可执行文件名 (不含路径)。
     * wow64Mode 参数保留接口兼容性, 始终返回 "wine"。
     */
    fun getExecutable(context: Context, wow64Mode: Boolean): String = "wine"

    override fun toString(): String = identifier()
}

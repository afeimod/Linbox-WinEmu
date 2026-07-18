package org.github.ewt45.winemulator.glibcwine

import android.content.Context
import android.util.Log
import org.github.ewt45.winemulator.Utils
import java.io.File

/**
 * glibc wine 的镜像文件系统 (ImageFs), 移植自 winlator-glibc 的 ImageFs.java。
 *
 * ImageFs 是 glibc wine 运行时的根目录, 包含:
 * - wine 二进制和库 (/opt/wine, /opt/x86_64-wine, /opt/arm64ec-wine)
 * - box64 二进制 (/usr/local/bin/box64)
 * - glibc 系统库 (/usr/lib, /usr/lib/x86_64-linux-gnu)
 * - Mesa/Turnip/VirGL 图形驱动
 * - fontconfig 字体配置
 * - wine 容器数据 (/home/xuser-N/.wine)
 *
 * 在 linbox 整合中, ImageFs 位于 <filesDir>/imagefs/, 与 proot 的 rootfs 完全分离。
 * proot 容器通过 --bind 将其挂载到 /opt/glibc-wine, 使 wine 可从容器内访问。
 */
class GlibcImageFs private constructor(val rootDir: File) {
    private val TAG = "GlibcImageFs"

    companion object {
        private const val TAG = "GlibcImageFs"

        fun find(context: Context): GlibcImageFs {
            return GlibcImageFs(File(context.filesDir, GlibcWineConsts.IMAGEFS_DIR_NAME))
        }
    }

    /**
     * 返回二进制兼容的绝对路径 (/data/data/... 形式)。
     *
     * Android 的 context.filesDir 返回 /data/user/0/<pkg>/files/...,
     * 但 imagefs 中的二进制是按 /data/data/<pkg>/files/... 编译的 (RPATH 硬编码)。
     * /data/user/0 和 /data/data 指向同一目录, 但不是符号链接关系,
     * canonicalPath 不会转换, 需要手动替换。
     */
    val rootPath: String
        get() = rootDir.absolutePath.replace("/data/user/0/", "/data/data/")
            .replace("/data/user/de/", "/data/data/")

    /** wine 安装路径 (绝对路径) */
    var winePath: String = "${rootDir.path}${GlibcWineConsts.WINE_PATH_REL}"
        private set

    /** HOME 路径 (绝对路径) */
    val homePath: String = "${rootDir.path}${GlibcWineConsts.HOME_PATH_REL}"

    /** WINEPREFIX 路径 (绝对路径) */
    val wineprefix: String = "${rootDir.path}${GlibcWineConsts.WINEPREFIX_REL}"

    /** 缓存路径 */
    val cachePath: String = "${rootDir.path}${GlibcWineConsts.CACHE_PATH_REL}"

    /** 配置路径 */
    val configPath: String = "${rootDir.path}${GlibcWineConsts.CONFIG_PATH_REL}"

    /** 镜像是否有效 (目录存在且版本文件存在) */
    fun isValid(): Boolean = rootDir.isDirectory && getImgVersionFile().exists()

    /** 获取镜像版本号 */
    fun getVersion(): Int {
        val file = getImgVersionFile()
        if (!file.exists()) return 0
        return try {
            file.readText().trim().toInt()
        } catch (e: Exception) {
            0
        }
    }

    /** 创建版本文件 */
    fun createImgVersionFile(version: Int) {
        getConfigDir().mkdirs()
        val file = getImgVersionFile()
        try {
            file.writeText(version.toString())
        } catch (e: Exception) {
            Log.e(TAG, "创建版本文件失败", e)
        }
    }

    /** winlator 配置目录 (相对 rootDir) */
    fun getConfigDir(): File = File(rootDir, GlibcWineConsts.WINLATOR_CONFIG_DIR_REL)

    /** 版本文件 */
    fun getImgVersionFile(): File = File(getConfigDir(), ".img_version")

    /** 已安装 wine 版本目录 */
    fun getInstalledWineDir(): File = File(rootDir, GlibcWineConsts.INSTALLED_WINE_DIR_REL)

    /** 临时目录 */
    fun getTmpDir(): File = File(rootDir, GlibcWineConsts.TMP_DIR_REL)

    /** 64 位 glibc 库目录 */
    fun getGlibc64Dir(): File = File(rootDir, GlibcWineConsts.GLIBC64_DIR_REL)

    /** 32 位 glibc 库目录 */
    fun getGlibc32Dir(): File = File(rootDir, GlibcWineConsts.GLIBC32_DIR_REL)

    /** x86_64 glibc 库目录 (供 box64 使用) */
    fun getX8664GlibcDir(): File = File(rootDir, GlibcWineConsts.X86_64_GLIBC_DIR_REL)

    /** fontconfig 配置目录 */
    fun getFontconfigDir(): File = File(rootDir, GlibcWineConsts.FONTCONFIG_DIR_REL)

    /** box64 二进制文件 */
    fun getBox64Bin(): File = File(rootDir, GlibcWineConsts.BOX64_BIN_REL)

    /** home 目录 (包含所有 wine 容器) */
    fun getHomeDir(): File = File(rootDir, "home")

    override fun toString(): String = rootDir.path
}

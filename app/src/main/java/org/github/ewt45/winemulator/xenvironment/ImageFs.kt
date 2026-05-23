package org.github.ewt45.winemulator.xenvironment

import android.content.Context
import org.apache.commons.io.FileUtils
import org.github.ewt45.winemulator.Utils
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * ImageFs - 用于管理Wine运行环境的文件系统镜像
 * 对应Winlator的ImageFs类，管理/opt/wine等关键目录
 */
class ImageFs private constructor(private val rootDir: File) {
    
    companion object {
        const val USER = "xuser"
        const val HOME_PATH = "/home/$USER"
        const val CACHE_PATH = HOME_PATH + "/.cache"
        const val CONFIG_PATH = HOME_PATH + "/.config"
        const val WINEPREFIX = HOME_PATH + "/.wine"
        const val IMG_VERSION_FILE = ".img_version"
        
        /**
         * 获取ImageFs实例，路径为 [context.filesDir]/linbox
         */
        fun find(context: Context): ImageFs {
            return ImageFs(File(context.filesDir, "linbox"))
        }
    }
    
    // Wine相关路径 - 使用内部字段避免与getter/setter冲突
    private var _winePath: String = rootDir.absolutePath + "/opt/wine"
    
    var winePath: String
        get() = _winePath
        set(value) {
            _winePath = toRelativePath(rootDir.absolutePath, value)
        }
    
    var homePath: String = rootDir.absolutePath + HOME_PATH
    var cachePath: String = rootDir.absolutePath + CACHE_PATH
    var configPath: String = rootDir.absolutePath + CONFIG_PATH
    var wineprefix: String = rootDir.absolutePath + WINEPREFIX
    
    fun getRootDir(): File = rootDir
    
    /**
     * 获取Wine路径（兼容方法调用语法）
     */
    fun getWinePath(): String = winePath
    
    /**
     * 设置Wine路径（兼容方法调用语法）
     */
    fun setWinePath(winePath: String) {
        this.winePath = winePath
    }
    
    /**
     * 检查ImageFs是否有效（目录存在且包含版本文件）
     */
    fun isValid(): Boolean {
        return rootDir.isDirectory && getImgVersionFile().exists()
    }
    
    /**
     * 获取ImageFs版本号
     */
    fun getVersion(): Int {
        val imgVersionFile = getImgVersionFile()
        return if (imgVersionFile.exists()) {
            try {
                imgVersionFile.readText().trim().toInt()
            } catch (e: Exception) {
                0
            }
        } else {
            0
        }
    }
    
    /**
     * 获取格式化的版本字符串
     */
    fun getFormattedVersion(): String {
        return String.format(Locale.ENGLISH, "%.1f", getVersion().toFloat() / 10f)
    }
    
    /**
     * 创建版本文件
     */
    fun createImgVersionFile(version: Int) {
        getConfigDir().mkdirs()
        val file = getImgVersionFile()
        try {
            if (file.createNewFile()) {
                file.writeText(version.toString() + "\n")
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
    
    /**
     * 将绝对路径转换为相对于根目录的相对路径
     */
    private fun toRelativePath(rootPath: String, absolutePath: String): String {
        return if (absolutePath.startsWith(rootPath)) {
            absolutePath.substring(rootPath.length)
        } else {
            absolutePath
        }
    }
    
    fun getConfigDir(): File = File(rootDir, ".winlator")
    
    fun getImgVersionFile(): File = File(getConfigDir(), IMG_VERSION_FILE)
    
    fun getInstalledWineDir(): File = File(rootDir, "/opt/installed-wine")
    
    fun getTmpDir(): File = File(rootDir, "/tmp")
    
    fun getGlibc32Dir(): File = File(rootDir, "/usr/lib/arm-linux-gnueabihf")
    
    fun getGlibc64Dir(): File = File(rootDir, "/usr/lib")
    
    fun getLib32Dir(): File = File(rootDir, "/usr/lib/arm-linux-gnueabihf")
    
    fun getLib64Dir(): File = File(rootDir, "/usr/lib")
    
    override fun toString(): String = rootDir.absolutePath
}
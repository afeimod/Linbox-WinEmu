package org.github.ewt45.winemulator.xenvironment

import android.content.Context
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
    
    // Wine相关路径
    var winePath: String = rootDir.absolutePath + "/opt/wine"
    var homePath: String = rootDir.absolutePath + HOME_PATH
    var cachePath: String = rootDir.absolutePath + CACHE_PATH
    var configPath: String = rootDir.absolutePath + CONFIG_PATH
    var wineprefix: String = rootDir.absolutePath + WINEPREFIX
    
    fun getRootDir(): File = rootDir
    
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
                Utils.Files.readStringFromFile(imgVersionFile).trim().toInt()
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
                Utils.Files.writeStringToFileWithLF(file, version.toString())
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
    
    fun getWinePath(): String = winePath
    
    fun setWinePath(winePath: String) {
        this.winePath = Utils.Files.toRelativePath(rootDir.absolutePath, winePath)
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
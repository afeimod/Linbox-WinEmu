package org.github.ewt45.winemulator.xenvironment.components

import android.content.Context
import org.apache.commons.io.FileUtils
import org.github.ewt45.winemulator.Utils
import java.io.File
import java.io.InputStream

/**
 * TarCompressorUtils - Tar压缩/解压工具类
 * 对应Winlator的TarCompressorUtils，用于提取imagefs和box86/64等资源
 */
object TarCompressorUtils {
    
    enum class Type {
        ZSTD,
        XZ,
        GZIP
    }
    
    /**
     * 提取tar.xz/tar.zst等压缩文件到目标目录
     * @param type 压缩类型
     * @param context Context
     * @param assetName assets中的文件名
     * @param destDir 目标目录
     * @param progressCallback 进度回调（可选）
     */
    fun extract(
        type: Type,
        context: Context,
        assetName: String,
        destDir: File,
        progressCallback: ((String, Long) -> Long)? = null
    ): Boolean {
        try {
            // 解压前清空目标目录
            if (destDir.exists()) {
                try {
                    FileUtils.deleteDirectory(destDir)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            destDir.mkdirs()
            
            // 根据类型选择解压方法
            val compType = when (type) {
                Type.ZSTD -> Utils.CompressedType.TZST
                Type.XZ -> Utils.CompressedType.XZ
                Type.GZIP -> Utils.CompressedType.GZ
            }
            
            // 使用项目自带的解压方法
            val compressedTarInput = Utils.Archive.getCompressedInput(compType, context.assets.open(assetName))
            Utils.Archive.decompressCompressedTarStream(compressedTarInput, destDir)
            
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
    
    /**
     * 压缩为tar.zst
     */
    fun compress(
        type: Type,
        sourceDir: File,
        outputFile: File,
        compressionLevel: Int = 22
    ): Boolean {
        // 压缩功能暂未实现
        return false
    }
}
package org.github.ewt45.winemulator.xenvironment

import android.app.Activity
import android.app.ProgressDialog
import android.content.Context
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.io.FileUtils
import org.github.ewt45.winemulator.Consts
import org.github.ewt45.winemulator.Utils
import java.io.File
import java.io.FileInputStream

/**
 * ImageFsInstaller - ImageFs安装和初始化管理
 * 对应Winlator的ImageFsInstaller，用于解压和初始化Wine环境
 */
object ImageFsInstaller {
    
    private const val TAG = "ImageFsInstaller"
    
    // 当前最新版本
    const val LATEST_VERSION = 11
    
    /**
     * 检查是否需要安装或更新ImageFs
     */
    fun installIfNeeded(activity: Activity, callback: (Boolean) -> Unit) {
        val imageFs = ImageFs.find(activity)
        if (!imageFs.isValid() || imageFs.getVersion() < LATEST_VERSION) {
            installFromAssets(activity, callback)
        } else {
            callback(true)
        }
    }
    
    /**
     * 从assets安装ImageFs（同步版本，返回Boolean）
     */
    suspend fun installFromAssetsAsync(activity: Activity): Boolean = withContext(Dispatchers.IO) {
        val imageFs = ImageFs.find(activity)
        val rootDir = imageFs.getRootDir()
        
        try {
            // 清空目标目录
            if (rootDir.exists()) {
                rootDir.listFiles()?.forEach { file ->
                    if (file.isDirectory) {
                        if (file.name != "home") {
                            try {
                                FileUtils.deleteDirectory(file)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    } else {
                        file.delete()
                    }
                }
            } else {
                rootDir.mkdirs()
            }
            
            // 查找assets中的imagefs压缩包
            val imagefsFileNames = listOf(
                "imagefs.tar.xz", "imagefs.tar.gz", "imagefs.tar.zst",
                "imagefs.tzst"
            )
            var foundFileName: String? = null
            
            for (fileName in imagefsFileNames) {
                try {
                    val inputStream = activity.assets.open(fileName)
                    inputStream.close()
                    foundFileName = fileName
                    break
                } catch (e: Exception) {
                    // 文件不存在，继续尝试下一个
                }
            }
            
            if (foundFileName == null) {
                Log.e(TAG, "未在assets中找到imagefs压缩包")
                return@withContext false
            }
            
            Log.d(TAG, "找到assets中的imagefs: $foundFileName")
            
            // 根据文件名确定压缩类型
            val compType = when {
                foundFileName.endsWith(".xz") -> Utils.CompressedType.XZ
                foundFileName.endsWith(".gz") -> Utils.CompressedType.GZ
                foundFileName.endsWith(".zst") || foundFileName.endsWith(".tzst") -> Utils.CompressedType.TZST
                else -> {
                    Log.e(TAG, "不支持的压缩格式: $foundFileName")
                    return@withContext false
                }
            }
            
            // 使用项目自带的解压方法
            val compressedTarInput = Utils.Archive.getCompressedInput(compType, activity.assets.open(foundFileName))
            Utils.Archive.decompressCompressedTarStream(compressedTarInput, rootDir)
            
            // 创建版本文件
            imageFs.createImgVersionFile(LATEST_VERSION)
            
            Log.d(TAG, "ImageFs 安装成功")
            true
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "Failed to install ImageFs: ${e.message}")
            false
        }
    }
    
    /**
     * 从assets安装ImageFs（旧版本，使用回调）
     */
    fun installFromAssets(activity: Activity, callback: (Boolean) -> Unit) {
        // 保持屏幕常亮
        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        val imageFs = ImageFs.find(activity)
        val rootDir = imageFs.getRootDir()
        
        // 显示进度对话框
        val dialog = ProgressDialog(activity)
        dialog.setMessage("正在安装系统文件...")
        dialog.setProgressStyle(ProgressDialog.STYLE_SPINNER)
        dialog.setCancelable(false)
        dialog.show()
        
        // 在后台线程执行解压
        Thread {
            var success = false
            try {
                // 清空目标目录
                if (rootDir.exists()) {
                    rootDir.listFiles()?.forEach { file ->
                        if (file.isDirectory) {
                            if (file.name != "home") {
                                try {
                                    FileUtils.deleteDirectory(file)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        } else {
                            file.delete()
                        }
                    }
                } else {
                    rootDir.mkdirs()
                }
                
                // 查找assets中的imagefs压缩包
                val imagefsFileNames = listOf(
                    "imagefs.tar.xz", "imagefs.tar.gz", "imagefs.tar.zst",
                    "imagefs.tzst"
                )
                var foundFileName: String? = null
                
                for (fileName in imagefsFileNames) {
                    try {
                        val inputStream = activity.assets.open(fileName)
                        inputStream.close()
                        foundFileName = fileName
                        break
                    } catch (e: Exception) {
                        // 文件不存在，继续尝试下一个
                    }
                }
                
                if (foundFileName == null) {
                    throw Exception("未在assets中找到imagefs压缩包")
                }
                
                Log.d(TAG, "找到assets中的imagefs: $foundFileName")
                
                // 根据文件名确定压缩类型
                val compType = when {
                    foundFileName.endsWith(".xz") -> Utils.CompressedType.XZ
                    foundFileName.endsWith(".gz") -> Utils.CompressedType.GZ
                    foundFileName.endsWith(".zst") || foundFileName.endsWith(".tzst") -> Utils.CompressedType.TZST
                    else -> throw RuntimeException("不支持的压缩格式: $foundFileName")
                }
                
                // 使用项目自带的解压方法
                val compressedTarInput = Utils.Archive.getCompressedInput(compType, activity.assets.open(foundFileName))
                Utils.Archive.decompressCompressedTarStream(compressedTarInput, rootDir)
                
                // 创建版本文件
                imageFs.createImgVersionFile(LATEST_VERSION)
                
                success = true
                Log.d(TAG, "ImageFs 安装成功")
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e(TAG, "Failed to install ImageFs: ${e.message}")
            }
            
            activity.runOnUiThread {
                dialog.dismiss()
                activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (success) {
                    Toast.makeText(activity, "ImageFS 安装成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "无法安装系统文件", Toast.LENGTH_SHORT).show()
                }
                callback(success)
            }
        }.start()
    }
    
    /**
     * 获取ImageFs的版本信息
     */
    fun getImageFsVersion(context: Context): Int {
        val imageFs = ImageFs.find(context)
        return if (imageFs.isValid()) imageFs.getVersion() else 0
    }
    
    /**
     * 检查ImageFs是否有效
     */
    fun isImageFsValid(context: Context): Boolean {
        return ImageFs.find(context).isValid()
    }
}
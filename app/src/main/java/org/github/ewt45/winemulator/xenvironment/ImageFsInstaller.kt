package org.github.ewt45.winemulator.xenvironment

import android.app.Activity
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
     * 从assets安装ImageFs
     */
    fun installFromAssets(activity: Activity, callback: (Boolean) -> Unit) {
        // 保持屏幕常亮
        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        val imageFs = ImageFs.find(activity)
        val rootDir = imageFs.getRootDir()
        
        // 显示进度对话框
        val dialog = android.app.ProgressDialog(activity)
        dialog.setMessage(activity.getString(org.github.ewt45.winemulator.R.string.installing_system_files))
        dialog.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
        dialog.max = 100
        dialog.setCancelable(false)
        dialog.show()
        
        // 在后台线程执行解压
        Thread {
            try {
                // 清空目标目录
                clearRootDir(rootDir)
                
                // 解压imagefs.tzst
                val compressionRatio = 18
                val imagefsFile = File(activity.cacheDir, "imagefs.tzst")
                val totalSize = (activity.assets.open("imagefs.tzst").available() * (100.0f / compressionRatio)).toLong()
                var extractedSize = 0L
                
                activity.assets.open("imagefs.tzst").use { inputStream ->
                    imagefsFile.outputStream().use { outputStream ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            extractedSize += bytesRead
                            val progress = ((extractedSize.toFloat() / totalSize) * 100).toInt()
                            activity.runOnUiThread {
                                dialog.progress = progress.coerceIn(0, 100)
                            }
                        }
                    }
                }
                
                // 执行解压
                FileInputStream(imagefsFile).use { inputStream ->
                    Utils.Archive.decompressTarXz(inputStream, rootDir)
                }
                
                // 创建版本文件
                imageFs.createImgVersionFile(LATEST_VERSION)
                
                // 删除临时文件
                imagefsFile.delete()
                
                activity.runOnUiThread {
                    dialog.dismiss()
                    activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    callback(true)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e(TAG, "Failed to install ImageFs: ${e.message}")
                
                activity.runOnUiThread {
                    dialog.dismiss()
                    activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    Toast.makeText(activity, "无法安装系统文件", Toast.LENGTH_SHORT).show()
                    callback(false)
                }
            }
        }.start()
    }
    
    /**
     * 清空root目录（保留home目录）
     */
    private fun clearRootDir(rootDir: File) {
        if (rootDir.isDirectory) {
            rootDir.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    // 保留home目录
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
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
            // 即使已安装，也执行初始化（确保目录存在）
            initializeImageFsInternal(activity, callback)
        }
    }
    
    /**
     * 初始化ImageFs环境（确保必要的目录和软链接存在）
     * 公开方法，供外部调用
     */
    fun initializeImageFs(context: Context, callback: (Boolean) -> Unit) {
        doInitializeImageFs(context, callback)
    }
    
    /**
     * 内部初始化方法
     */
    private fun initializeImageFsInternal(context: Context, callback: (Boolean) -> Unit) {
        doInitializeImageFs(context, callback)
    }
    
    /**
     * 实际执行初始化的私有方法
     */
    private fun doInitializeImageFs(context: Context, callback: (Boolean) -> Unit) {
        Thread {
            try {
                val imageFs = ImageFs.find(context)
                val rootDir = imageFs.getRootDir()
                
                Log.d(TAG, "Initializing ImageFs at: $rootDir")
                
                // 1. 创建 xuser 目录及其子目录
                createXuserDirectories(rootDir)
                
                // 2. 创建 tmp 目录并设置权限
                createTmpDirectory(rootDir)
                
                // 3. 创建必要的软链接
                createSymlinks(rootDir)
                
                // 4. 设置正确的权限
                setPermissions(rootDir)
                
                Log.d(TAG, "ImageFs initialization completed")
                callback(true)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize ImageFs: ${e.message}")
                e.printStackTrace()
                callback(false)
            }
        }.start()
    }
    
    /**
     * 创建 xuser 目录及其子目录
     */
    private fun createXuserDirectories(rootDir: File) {
        // xuser 主目录
        val xuserDir = File(rootDir, "home/${ImageFs.USER}")
        if (!xuserDir.exists()) {
            xuserDir.mkdirs()
            Utils.chmod(xuserDir, "755")
            Log.d(TAG, "Created xuser directory: ${xuserDir.absolutePath}")
        }
        
        // .cache 目录
        val cacheDir = File(xuserDir, ".cache")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
            Utils.chmod(cacheDir, "755")
        }
        
        // .config 目录
        val configDir = File(xuserDir, ".config")
        if (!configDir.exists()) {
            configDir.mkdirs()
            Utils.chmod(configDir, "755")
        }
        
        // .wine 目录（Wine前缀）- 由Wine首次运行时自动创建
        val winePrefixDir = File(xuserDir, ".wine")
        if (!winePrefixDir.exists()) {
            winePrefixDir.mkdirs()
            Utils.chmod(winePrefixDir, "755")
            Log.d(TAG, "Created .wine directory: ${winePrefixDir.absolutePath}")
        }
        
        // .local 目录
        val localDir = File(xuserDir, ".local")
        if (!localDir.exists()) {
            localDir.mkdirs()
            Utils.chmod(localDir, "755")
        }
        
        // .local/share 目录
        val shareDir = File(localDir, "share")
        if (!shareDir.exists()) {
            shareDir.mkdirs()
            Utils.chmod(shareDir, "755")
        }
    }
    
    /**
     * 创建 tmp 目录并设置权限
     */
    private fun createTmpDirectory(rootDir: File) {
        val tmpDir = File(rootDir, "tmp")
        if (!tmpDir.exists()) {
            tmpDir.mkdirs()
            Utils.chmod(tmpDir, "1777")  // 777 + sticky bit
            Log.d(TAG, "Created tmp directory with 1777 permissions")
        }
        
        // 创建 shm 目录（共享内存）
        val shmDir = File(tmpDir, "shm")
        if (!shmDir.exists()) {
            shmDir.mkdirs()
            Utils.chmod(shmDir, "1777")
        }
        
        // 创建 .sysvshm 目录（用于Android共享内存）
        val sysvshmDir = File(tmpDir, ".sysvshm")
        if (!sysvshmDir.exists()) {
            sysvshmDir.mkdirs()
            Utils.chmod(sysvshmDir, "755")
        }
    }
    
    /**
     * 创建必要的软链接
     */
    private fun createSymlinks(rootDir: File) {
        val xuserDir = File(rootDir, "home/${ImageFs.USER}")
        
        // 1. 创建 /usr/local/lib -> /usr/lib 的软链接（如果需要）
        try {
            val localLib = File(rootDir, "usr/local/lib")
            val usrLib = File(rootDir, "usr/lib")
            if (!localLib.exists() && usrLib.exists()) {
                localLib.parentFile?.mkdirs()
                Utils.Files.symlink(usrLib, localLib)
                Log.d(TAG, "Created symlink: usr/local/lib -> usr/lib")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create usr/local/lib symlink: ${e.message}")
        }
        
        // 2. 创建 /home/xuser 目录的软链接指向根目录的对应位置
        // 如果 /home/xuser 不存在但需要其存在
        if (!xuserDir.exists()) {
            xuserDir.mkdirs()
        }
        
        // 3. 确保 /etc/fonts 目录存在
        val fontsDir = File(rootDir, "etc/fonts")
        if (!fontsDir.exists()) {
            fontsDir.mkdirs()
            Utils.chmod(fontsDir, "755")
        }
        
        // 4. 创建 fonts.conf（如果不存在）
        val fontsConf = File(fontsDir, "fonts.conf")
        if (!fontsConf.exists()) {
            createDefaultFontsConf(fontsConf)
        }
    }
    
    /**
     * 创建默认的 fonts.conf
     */
    private fun createDefaultFontsConf(fontsConf: File) {
        try {
            val content = """<?xml version="1.0"?>
<!DOCTYPE fontconfig SYSTEM "fonts.dtd">
<fontconfig>
    <dir>/usr/share/fonts</dir>
    <dir>/home/${ImageFs.USER}/.fonts</dir>
    <cachedir>/home/${ImageFs.USER}/.cache/fontconfig</cachedir>
</fontconfig>
"""
            FileUtils.writeStringToFile(fontsConf, content, Charsets.UTF_8)
            Log.d(TAG, "Created default fonts.conf")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create fonts.conf: ${e.message}")
        }
    }
    
    /**
     * 设置目录权限
     */
    private fun setPermissions(rootDir: File) {
        // 设置 opt 目录权限
        val optDir = File(rootDir, "opt")
        if (optDir.exists()) {
            Utils.chmod(optDir, "755")
        }
        
        // 设置 usr 目录权限
        val usrDir = File(rootDir, "usr")
        if (usrDir.exists()) {
            Utils.chmod(usrDir, "755")
        }
        
        // 递归设置子目录权限
        listOf("bin", "lib", "lib64").forEach { subDir ->
            val dir = File(usrDir, subDir)
            if (dir.exists()) {
                try {
                    Runtime.getRuntime().exec("chmod -R 755 ${dir.absolutePath}")
                    Log.d(TAG, "Set permissions for usr/$subDir")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to chmod usr/$subDir: ${e.message}")
                }
            }
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
        val dialog = ProgressDialog(activity)
        dialog.setMessage("正在安装系统文件...")
        dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
        dialog.max = 100
        dialog.setCancelable(false)
        dialog.show()
        
        // 在后台线程执行解压
        Thread {
            try {
                // 清空目标目录
                clearRootDir(rootDir)
                
                // 解压 imagefs.tzst
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
                
                // 初始化 ImageFs 环境
                initializeImageFsAfterExtraction(activity, rootDir)
                
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
     * 解压后初始化ImageFs
     */
    private fun initializeImageFsAfterExtraction(activity: Activity, rootDir: File) {
        try {
            val imageFs = ImageFs.find(activity)
            
            Log.d(TAG, "Initializing ImageFs after extraction...")
            
            // 1. 创建 xuser 目录及其子目录
            createXuserDirectories(rootDir)
            
            // 2. 创建 tmp 目录并设置权限
            createTmpDirectory(rootDir)
            
            // 3. 创建必要的软链接
            createSymlinks(rootDir)
            
            // 4. 设置正确的权限
            setPermissions(rootDir)
            
            Log.d(TAG, "ImageFs post-extraction initialization completed")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ImageFs after extraction: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 清空root目录（保留home目录）
     */
    private fun clearRootDir(rootDir: File) {
        if (rootDir.isDirectory) {
            rootDir.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    // 保留home目录（包含用户数据）
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
    
    /**
     * 获取ImageFs实例
     */
    fun getImageFs(context: Context): ImageFs {
        return ImageFs.find(context)
    }
}
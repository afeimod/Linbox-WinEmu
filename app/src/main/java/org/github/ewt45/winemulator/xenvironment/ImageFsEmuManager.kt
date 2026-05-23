package org.github.ewt45.winemulator.xenvironment

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.github.ewt45.winemulator.emu.manager.ManagerComponent

/**
 * ImageFsEmuManager - 使用ImageFs方式运行Wine的模拟器管理器
 * 对应Winlator的Container管理方式，通过ImageFs运行Wine程序
 * 
 * 使用方式：
 * 1. 继承或组合使用ImageFsEmuManager替代原有的proot方式
 * 2. 配置wineVersion和启动命令
 * 3. 调用startWine()启动Wine
 */
class ImageFsEmuManager(
    private val scope: CoroutineScope,
    private val context: Context
) : DefaultLifecycleObserver {
    
    private val TAG = "ImageFsEmuManager"
    
    // XEnvironment实例
    private var environment: XEnvironment? = null
    
    // Wine程序启动器组件
    private var programLauncher: org.github.ewt45.winemulator.xenvironment.components.GlibcProgramLauncherComponent? = null
    
    // 组件管理器 - 使用简化版本，不依赖EmuManager
    private var soundPid: Int = -1
    private var soundStarted: Boolean = false
    
    // Wine配置
    var wineVersion: String = "wine-8.22"
    var startCommand: String = "wine64 explorer /desktop=winlator,1280x720"
    var wow64Mode: Boolean = true
    
    // 绑定路径
    var bindingPaths: Array<String> = emptyArray()
    
    // 自定义环境变量
    var customEnvVars: Map<String, String> = emptyMap()
    
    // 回调
    var onWineStarted: (() -> Unit)? = null
    var onWineStopped: ((Int) -> Unit)? = null
    
    // 是否已启动
    private var isStarted: Boolean = false
    
    /**
     * 初始化ImageFs环境
     */
    private fun initEnvironment() {
        if (environment != null) return
        
        // 获取或创建ImageFs
        val imageFs = ImageFs.find(context)
        
        // 创建XEnvironment
        environment = XEnvironment(context, imageFs)
        
        // 创建程序启动器组件
        programLauncher = org.github.ewt45.winemulator.xenvironment.components.GlibcProgramLauncherComponent(wineVersion)
        programLauncher?.apply {
            setGuestExecutable(startCommand)
            setWoW64Mode(wow64Mode)
            setBindingPaths(bindingPaths)
            setEnvVars(customEnvVars)
            setTerminationCallback { exitCode ->
                Log.d(TAG, "Wine process terminated with exit code: $exitCode")
                isStarted = false
                onWineStopped?.invoke(exitCode)
            }
        }
        
        // 添加组件到环境
        environment?.addComponent(programLauncher!!)
    }
    
    /**
     * 启动Wine环境
     */
    fun startWine() {
        if (isStarted) {
            Log.w(TAG, "Wine is already started")
            return
        }
        
        scope.launch(Dispatchers.IO) {
            try {
                // 确保ImageFs已安装
                if (!ImageFsInstaller.isImageFsValid(context)) {
                    Log.e(TAG, "ImageFs is not installed")
                    return@launch
                }
                
                // 初始化环境
                initEnvironment()
                
                // 启动环境组件
                environment?.startEnvironmentComponents()
                
                isStarted = true
                onWineStarted?.invoke()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Wine: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    
    /**
     * 停止Wine环境
     */
    fun stopWine() {
        if (!isStarted) {
            Log.w(TAG, "Wine is not running")
            return
        }
        
        scope.launch(Dispatchers.IO) {
            try {
                // 停止环境组件
                environment?.stopEnvironmentComponents()
                
                isStarted = false
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop Wine: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    
    /**
     * 暂停Wine进程
     */
    fun pauseWine() {
        environment?.onPause()
    }
    
    /**
     * 恢复Wine进程
     */
    fun resumeWine() {
        environment?.onResume()
    }
    
    /**
     * 获取Wine前缀目录
     */
    fun getWinePrefix(): String {
        return environment?.getImageFs()?.wineprefix ?: ""
    }
    
    /**
     * 获取Wine主目录
     */
    fun getHomePath(): String {
        return environment?.getImageFs()?.homePath ?: ""
    }
    
    /**
     * 获取Wine可执行文件路径
     */
    fun getWineBinPath(): String {
        return environment?.getImageFs()?.winePath ?: ""
    }
    
    /**
     * 检查Wine是否正在运行
     */
    fun isWineRunning(): Boolean = isStarted
    
    // 生命周期管理
    override fun onCreate(owner: LifecycleOwner) {
        // 启动声音服务
        scope.launch {
            soundPid = org.github.ewt45.winemulator.emu.Pulseaudio.start()
            soundStarted = true
            Log.d(TAG, "Sound started with pid: $soundPid")
        }
    }
    
    override fun onDestroy(owner: LifecycleOwner) {
        stopWine()
        // 停止声音服务
        if (soundStarted) {
            org.github.ewt45.winemulator.emu.Pulseaudio.stop()
            soundStarted = false
        }
    }
    
    override fun onResume(owner: LifecycleOwner) {
        if (soundStarted) {
            org.github.ewt45.winemulator.emu.Pulseaudio.resume()
        }
        resumeWine()
    }
    
    override fun onPause(owner: LifecycleOwner) {
        if (soundStarted) {
            org.github.ewt45.winemulator.emu.Pulseaudio.pause()
        }
        pauseWine()
    }
    
    /**
     * Builder模式，用于链式配置
     */
    class Builder(private val scope: CoroutineScope, private val context: Context) {
        private val manager = ImageFsEmuManager(scope, context)
        
        fun setWineVersion(version: String): Builder {
            manager.wineVersion = version
            return this
        }
        
        fun setStartCommand(command: String): Builder {
            manager.startCommand = command
            return this
        }
        
        fun setWow64Mode(enabled: Boolean): Builder {
            manager.wow64Mode = enabled
            return this
        }
        
        fun setBindingPaths(paths: Array<String>): Builder {
            manager.bindingPaths = paths
            return this
        }
        
        fun setEnvVars(envVars: Map<String, String>): Builder {
            manager.customEnvVars = envVars
            return this
        }
        
        fun setOnWineStarted(callback: () -> Unit): Builder {
            manager.onWineStarted = callback
            return this
        }
        
        fun setOnWineStopped(callback: (Int) -> Unit): Builder {
            manager.onWineStopped = callback
            return this
        }
        
        fun build(): ImageFsEmuManager = manager
    }
}
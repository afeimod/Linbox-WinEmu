package org.github.ewt45.winemulator.xenvironment

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.github.ewt45.winemulator.emu.Pulseaudio
import org.github.ewt45.winemulator.xenvironment.components.GlibcProgramLauncherComponent

/**
 * ImageFsEmuManager - 使用ImageFs方式运行Wine的模拟器管理器
 * 对应Winlator的Container管理方式，通过ImageFs运行Wine程序
 */
class ImageFsEmuManager(
    private val scope: CoroutineScope,
    private val context: Context
) : DefaultLifecycleObserver {
    
    private val TAG = "ImageFsEmuManager"
    
    // XEnvironment实例
    private var environment: XEnvironment? = null
    
    // Wine程序启动器组件
    private var programLauncher: GlibcProgramLauncherComponent? = null
    
    // 声音服务PID
    private var soundPid: Int = -1
    private var soundStarted: Boolean = false
    
    // Wine配置 - 使用直接属性赋值
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
        
        Log.d(TAG, "initEnvironment called")
        
        // 获取或创建ImageFs
        val imageFs = ImageFs.find(context)
        Log.d(TAG, "ImageFs root: ${imageFs.getRootDir()}")
        
        // 创建XEnvironment
        environment = XEnvironment(context, imageFs)
        
        // 创建程序启动器组件
        programLauncher = GlibcProgramLauncherComponent(wineVersion)
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
        Log.d(TAG, "Environment initialized, component added")
    }
    
    /**
     * 启动Wine环境
     */
    fun startWine() {
        if (isStarted) {
            Log.w(TAG, "Wine is already started")
            return
        }
        
        Log.d(TAG, "startWine called, checking ImageFs validity")
        
        scope.launch(Dispatchers.IO) {
            try {
                // 确保ImageFs已安装
                if (!ImageFsInstaller.isImageFsValid(context)) {
                    Log.e(TAG, "ImageFs is not installed")
                    return@launch
                }
                
                Log.d(TAG, "ImageFs is valid, initializing environment")
                // 初始化环境
                initEnvironment()
                
                Log.d(TAG, "Starting environment components")
                // 启动环境组件
                environment?.startEnvironmentComponents()
                
                isStarted = true
                Log.d(TAG, "Wine started successfully")
                onWineStarted?.invoke()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Wine: ${e.message}", e)
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
                Log.d(TAG, "Wine stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop Wine: ${e.message}", e)
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
     * 检查Wine是否正在运行
     */
    fun isWineRunning(): Boolean = isStarted
    
    // 生命周期管理
    override fun onCreate(owner: LifecycleOwner) {
        Log.d(TAG, "onCreate called, starting sound service")
        // 启动声音服务
        scope.launch {
            soundPid = Pulseaudio.start()
            soundStarted = true
            Log.d(TAG, "Sound started with pid: $soundPid")
        }
    }
    
    override fun onDestroy(owner: LifecycleOwner) {
        Log.d(TAG, "onDestroy called, stopping Wine and sound")
        stopWine()
        // 停止声音服务
        if (soundStarted) {
            Pulseaudio.stop()
            soundStarted = false
        }
    }
    
    override fun onResume(owner: LifecycleOwner) {
        Log.d(TAG, "onResume called")
        if (soundStarted) {
            Pulseaudio.resume()
        }
        resumeWine()
    }
    
    override fun onPause(owner: LifecycleOwner) {
        Log.d(TAG, "onPause called")
        if (soundStarted) {
            Pulseaudio.pause()
        }
        pauseWine()
    }
}
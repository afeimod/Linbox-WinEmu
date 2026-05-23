package org.github.ewt45.winemulator.xenvironment.components

import android.content.Context
import android.os.Process
import androidx.preference.PreferenceManager
import org.github.ewt45.winemulator.Consts
import org.github.ewt45.winemulator.Utils
import org.github.ewt45.winemulator.xenvironment.EnvironmentComponent
import org.github.ewt45.winemulator.xenvironment.ImageFs
import org.github.ewt45.winemulator.xenvironment.XEnvironment
import java.io.File

/**
 * GuestProgramLauncherComponent - 客户程序启动器基类
 * 对应Winlator的GuestProgramLauncherComponent
 * 通过proot运行Wine程序
 */
abstract class GuestProgramLauncherComponent : EnvironmentComponent() {
    
    // 要执行的命令（Wine启动命令）
    @JvmField
    var guestExecutable: String? = null
    
    // 绑定路径列表
    @JvmField
    var bindingPaths: Array<String>? = null
    
    // 自定义环境变量
    @JvmField
    var envVars: Map<String, String>? = null
    
    // Box预设
    @JvmField
    var box86Preset: String = "compatibility"
    
    @JvmField
    var box64Preset: String = "compatibility"
    
    // 终止回调
    @JvmField
    var terminationCallback: ((Int) -> Unit)? = null
    
    // 是否启用WoW64模式（32位Wine on 64位系统）
    @JvmField
    var wow64Mode: Boolean = true
    
    // 日志文件路径
    @JvmField
    var logFilePath: String? = null
    
    @JvmField
    protected var pid: Int = -1
    
    protected val lock = Object()
    
    override fun start() {
        synchronized(lock) {
            stop()
            extractBox86_64Files()
            pid = execGuestProgram()
        }
    }
    
    override fun stop() {
        synchronized(lock) {
            if (pid != -1) {
                Process.killProcess(pid)
                pid = -1
            }
        }
    }
    
    /**
     * 获取要执行的命令
     */
    fun getGuestExecutable(): String? = guestExecutable
    
    /**
     * 设置要执行的命令
     */
    fun setGuestExecutable(guestExecutable: String?) {
        this.guestExecutable = guestExecutable
    }
    
    /**
     * 获取绑定路径
     */
    fun getBindingPaths(): Array<String>? = bindingPaths
    
    /**
     * 设置绑定路径
     */
    fun setBindingPaths(bindingPaths: Array<String>?) {
        this.bindingPaths = bindingPaths
    }
    
    /**
     * 获取环境变量
     */
    fun getEnvVars(): Map<String, String>? = envVars
    
    /**
     * 设置环境变量
     */
    fun setEnvVars(envVars: Map<String, String>?) {
        this.envVars = envVars
    }
    
    /**
     * 是否为WoW64模式
     */
    fun isWoW64Mode(): Boolean = wow64Mode
    
    /**
     * 设置WoW64模式
     */
    fun setWoW64Mode(wow64Mode: Boolean) {
        this.wow64Mode = wow64Mode
    }
    
    /**
     * 设置终止回调
     */
    fun setTerminationCallback(callback: ((Int) -> Unit)?) {
        this.terminationCallback = callback
    }
    
    /**
     * 执行客户程序（通过proot启动）
     */
    protected open fun execGuestProgram(): Int {
        val context: Context = environment!!.getContext()
        val imageFs: ImageFs = environment!!.getImageFs()
        val rootDir: File = imageFs.getRootDir()
        val tmpDir: File = environment!!.getTmpDir()
        
        // 获取原生库目录
        val nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir)
        
        // 获取配置
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val enableBox86_64Logs = prefs.getBoolean("enable_box86_64_logs", false)
        
        // 构建环境变量
        val envVars = EnvVars()
        
        // 设置Box86/64环境变量
        if (!wow64Mode) addBox86EnvVars(envVars, enableBox86_64Logs)
        addBox64EnvVars(envVars, enableBox86_64Logs)
        
        // 设置基本环境变量
        envVars["HOME"] = ImageFs.HOME_PATH
        envVars["USER"] = ImageFs.USER
        envVars["TMPDIR"] = "/tmp"
        envVars["LC_ALL"] = "en_US.utf8"
        envVars["DISPLAY"] = ":0"
        envVars["PATH"] = imageFs.getWinePath() + "/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        envVars["LD_LIBRARY_PATH"] = "/usr/lib/aarch64-linux-gnu:/usr/lib/arm-linux-gnueabihf"
        envVars["ANDROID_SYSVSHM_SERVER"] = "/data/data/" + context.packageName + "/files/sysvshm_socket"
        
        // 检查是否需要预加载sysvshm库
        val lib64Sysvshm = File(imageFs.getLib64Dir(), "libandroid-sysvshm.so")
        val lib32Sysvshm = File(imageFs.getLib32Dir(), "libandroid-sysvshm.so")
        if (lib64Sysvshm.exists() || lib32Sysvshm.exists()) {
            envVars["LD_PRELOAD"] = "libandroid-sysvshm.so"
        }
        
        // 合并自定义环境变量
        if (this.envVars != null) {
            envVars.putAll(this.envVars!!)
        }
        
        // 检查是否启用WINEESYNC
        val bindSHM = envVars.get("WINEESYNC") == "1"
        
        // 构建proot命令
        val command = StringBuilder()
        command.append(nativeLibraryDir.absolutePath).append("/libproot.so")
        command.append(" --kill-on-exit")
        command.append(" --rootfs=").append(rootDir.absolutePath)
        command.append(" --cwd=").append(ImageFs.HOME_PATH)
        command.append(" --bind=/dev")
        
        if (bindSHM) {
            val shmDir = File(rootDir, "/tmp/shm")
            shmDir.mkdirs()
            command.append(" --bind=").append(shmDir.absolutePath).append(":/dev/shm")
        }
        
        command.append(" --bind=/proc")
        command.append(" --bind=/sys")
        
        // 添加自定义绑定路径
        if (bindingPaths != null) {
            for (path in bindingPaths!!) {
                command.append(" --bind=").append(File(path).absolutePath)
            }
        }
        
        // 添加要执行的程序
        command.append(" /usr/bin/env ")
        command.append(envVars.toMap().entries.joinToString(" ") { "${it.key}=${escapeEnvValue(it.value)}" })
        command.append(" box64 ")
        command.append(guestExecutable)
        
        // 清空envVars并设置proot相关环境变量
        envVars.clear()
        envVars["PROOT_TMP_DIR"] = tmpDir.absolutePath
        envVars["PROOT_LOADER"] = nativeLibraryDir.absolutePath + "/libproot-loader.so"
        if (!wow64Mode) {
            envVars["PROOT_LOADER_32"] = nativeLibraryDir.absolutePath + "/libproot-loader32.so"
        }
        
        return ProcessHelper.exec(
            command = command.toString(),
            envp = envVars.toStringArray(),
            workingDir = rootDir,
            callback = { status ->
                synchronized(lock) {
                    pid = -1
                }
                if (terminationCallback != null) {
                    terminationCallback!!(status)
                }
            },
            logFilePath = logFilePath
        )
    }
    
    /**
     * 从assets提取box86/64文件
     */
    protected abstract fun extractBox86_64Files()
    
    /**
     * 添加Box86环境变量
     */
    protected abstract fun addBox86EnvVars(envVars: EnvVars, enableLogs: Boolean)
    
    /**
     * 添加Box64环境变量
     */
    protected abstract fun addBox64EnvVars(envVars: EnvVars, enableLogs: Boolean)
    
    /**
     * 暂停进程
     */
    fun suspendProcess() {
        synchronized(lock) {
            if (pid != -1) ProcessHelper.suspendProcess(pid)
        }
    }
    
    /**
     * 恢复进程
     */
    fun resumeProcess() {
        synchronized(lock) {
            if (pid != -1) ProcessHelper.resumeProcess(pid)
        }
    }
    
    /**
     * 转义环境变量值
     */
    private fun escapeEnvValue(value: String): String {
        val escaped = StringBuilder()
        var escapedSpace = false
        
        for (char in value) {
            when (char) {
                ' ' -> {
                    escaped.append("\\ ")
                    escapedSpace = true
                }
                '"' -> escaped.append("\\\"")
                '\\' -> escaped.append("\\\\")
                '\n' -> escaped.append("\\n")
                '\t' -> escaped.append("\\t")
                else -> escaped.append(char)
            }
        }
        
        // 如果值包含空格或特殊字符，用引号包裹
        return if (escapedSpace || escaped.contains("\"") || escaped.contains("$")) {
            "\"$escaped\""
        } else {
            escaped.toString()
        }
    }
}
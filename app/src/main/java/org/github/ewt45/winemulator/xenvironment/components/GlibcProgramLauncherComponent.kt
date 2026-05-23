package org.github.ewt45.winemulator.xenvironment.components

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.preference.PreferenceManager
import org.github.ewt45.winemulator.Consts
import org.github.ewt45.winemulator.Utils
import org.github.ewt45.winemulator.xenvironment.EnvironmentComponent
import org.github.ewt45.winemulator.xenvironment.ImageFs
import org.github.ewt45.winemulator.xenvironment.XEnvironment
import java.io.File

/**
 * GlibcProgramLauncherComponent - 使用Glibc启动Wine程序
 * 对应Winlator的GlibcProgramLauncherComponent
 * 通过box64/box86运行x86/x64程序
 */
open class GlibcProgramLauncherComponent : GuestProgramLauncherComponent {
    
    private val TAG = "GlibcProgramLauncher"
    
    // Wine版本标识符
    var wineVersion: String = ""
    
    // Box86/64相关配置
    var enableBox86_64Logs: Boolean = false
    
    // Wine相关路径
    var wineDir: File? = null
    var wineBinDir: File? = null
    var wineLibDir: File? = null
    
    constructor(wineVersion: String) {
        this.wineVersion = wineVersion
    }
    
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
     * 执行Wine程序
     */
    override fun execGuestProgram(): Int {
        val context: Context = environment!!.getContext()
        val imageFs: ImageFs = environment!!.getImageFs()
        val rootDir: File = imageFs.getRootDir()
        
        Log.d(TAG, "=== execGuestProgram START ===")
        Log.d(TAG, "rootDir: ${rootDir.absolutePath}")
        Log.d(TAG, "wineVersion: $wineVersion")
        
        // 获取配置
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        enableBox86_64Logs = prefs.getBoolean("enable_box86_64_logs", false)
        
        // 1. 确保 xuser 目录存在
        val xuserDir = File(rootDir, "home/${ImageFs.USER}")
        if (!xuserDir.exists()) {
            xuserDir.mkdirs()
            Utils.chmod(xuserDir, "755")
            Log.d(TAG, "Created xuser directory")
        }
        
        // 2. 确保 .wine 目录存在
        val winePrefixDir = File(xuserDir, ".wine")
        if (!winePrefixDir.exists()) {
            winePrefixDir.mkdirs()
            Utils.chmod(winePrefixDir, "755")
            Log.d(TAG, "Created .wine directory: ${winePrefixDir.absolutePath}")
        }
        
        // 3. 确保 tmp 目录存在
        val tmpDir = File(rootDir, "tmp")
        if (!tmpDir.exists()) {
            tmpDir.mkdirs()
            Utils.chmod(tmpDir, "1777")
            Log.d(TAG, "Created tmp directory")
        }
        
        // 4. 确保 /tmp/shm 目录存在
        val shmDir = File(tmpDir, "shm")
        if (!shmDir.exists()) {
            shmDir.mkdirs()
            Utils.chmod(shmDir, "1777")
            Log.d(TAG, "Created shm directory")
        }
        
        // 5. 确保 /tmp/.sysvshm 目录存在
        val sysvshmDir = File(tmpDir, ".sysvshm")
        if (!sysvshmDir.exists()) {
            sysvshmDir.mkdirs()
            Utils.chmod(sysvshmDir, "755")
            Log.d(TAG, "Created .sysvshm directory")
        }
        
        // 6. 确保 etc/fonts 目录存在
        val fontsDir = File(rootDir, "etc/fonts")
        if (!fontsDir.exists()) {
            fontsDir.mkdirs()
        }
        
        // 构建环境变量
        val envVars = EnvVars()
        
        // 设置Box64环境变量（非WoW64模式）
        if (!wow64Mode) {
            addBox86EnvVars(envVars, enableBox86_64Logs)
        }
        addBox64EnvVars(envVars, enableBox86_64Logs)
        
        // 设置基本环境变量
        envVars["HOME"] = "/home/${ImageFs.USER}"
        envVars["USER"] = ImageFs.USER
        envVars["TMPDIR"] = "/tmp"
        envVars["DISPLAY"] = ":0"
        
        // 设置 Wine 前缀目录 - 关键！
        val winePrefixPath = "/home/${ImageFs.USER}/.wine"
        envVars["WINEPREFIX"] = winePrefixPath
        Log.d(TAG, "WINEPREFIX set to: $winePrefixPath")
        
        // 设置Wine路径
        val wineBinDirPath = File(rootDir, "opt/wine/bin").absolutePath
        val wineLibDirPath = File(rootDir, "opt/wine/lib").absolutePath
        val wineLib64DirPath = File(rootDir, "opt/wine/lib64").absolutePath
        
        envVars["PATH"] = wineBinDirPath + ":" +
                File(rootDir, "usr/bin").absolutePath + ":" +
                File(rootDir, "usr/local/bin").absolutePath
        
        // 构建LD_LIBRARY_PATH
        var ldLibraryPath = File(rootDir, "usr/lib").absolutePath
        
        // 添加Wine库路径到LD_LIBRARY_PATH前端
        ldLibraryPath = wineLib64DirPath + ":" + wineLibDirPath + ":" + ldLibraryPath
        
        // 添加wine目录下的unix库
        val wineDllDir = File(wineLibDirPath, "wine")
        if (!wineDllDir.exists()) {
            val altWineDllDir = File(wineLib64DirPath, "wine")
            if (altWineDllDir.exists()) {
                envVars["WINEDLLPATH"] = altWineDllDir.absolutePath
                val unix64 = File(altWineDllDir, "x86_64-unix")
                if (unix64.exists()) ldLibraryPath = unix64.absolutePath + ":" + ldLibraryPath
                val unix32 = File(altWineDllDir, "i386-unix")
                if (unix32.exists()) ldLibraryPath = unix32.absolutePath + ":" + ldLibraryPath
            }
        } else {
            envVars["WINEDLLPATH"] = wineDllDir.absolutePath
            val unix64 = File(wineDllDir, "x86_64-unix")
            if (unix64.exists()) ldLibraryPath = unix64.absolutePath + ":" + ldLibraryPath
            val unix32 = File(wineDllDir, "i386-unix")
            if (unix32.exists()) ldLibraryPath = unix32.absolutePath + ":" + ldLibraryPath
        }
        
        envVars["LD_LIBRARY_PATH"] = ldLibraryPath
        envVars["BOX64_LD_LIBRARY_PATH"] = File(rootDir, "usr/lib/x86_64-linux-gnu").absolutePath + ":" + ldLibraryPath
        
        // 设置 ANDROID_SYSVSHM_SERVER 路径（相对于 imagefs 根目录）
        envVars["ANDROID_SYSVSHM_SERVER"] = "/tmp/.sysvshm/SM0"
        Log.d(TAG, "ANDROID_SYSVSHM_SERVER: ${envVars["ANDROID_SYSVSHM_SERVER"]}")
        
        // 设置 FONTCONFIG_PATH
        envVars["FONTCONFIG_PATH"] = File(rootDir, "etc/fonts").absolutePath
        
        // 检查是否需要预加载sysvshm库
        val glibc64Sysvshm = File(imageFs.getGlibc64Dir(), "libandroid-sysvshm.so")
        val glibc32Sysvshm = File(imageFs.getGlibc32Dir(), "libandroid-sysvshm.so")
        if (glibc64Sysvshm.exists() || glibc32Sysvshm.exists()) {
            // 使用绝对路径
            val preloadPath = if (glibc64Sysvshm.exists()) {
                glibc64Sysvshm.absolutePath
            } else {
                glibc32Sysvshm.absolutePath
            }
            envVars["LD_PRELOAD"] = preloadPath
            Log.d(TAG, "LD_PRELOAD set to: $preloadPath")
        }
        
        // 合并用户自定义环境变量
        if (this.envVars != null) {
            envVars.putAll(this.envVars!!)
        }
        
        // 处理启动命令参数
        var finalArgs = guestExecutable ?: ""
        finalArgs = finalArgs.trim()
        
        // 移除wine64/wine前缀
        if (finalArgs.startsWith("wine64 ")) {
            finalArgs = finalArgs.substring(7).trim()
        } else if (finalArgs.startsWith("wine ")) {
            finalArgs = finalArgs.substring(5).trim()
        }
        
        // 构建启动命令
        val wineExecutableName = getWineExecutableName()
        
        // 使用box64启动wine64
        val box64Path = File(rootDir, "usr/local/bin/box64").absolutePath
        val wineAbsPath = File(wineBinDirPath, wineExecutableName).absolutePath
        
        // 构建完整的命令
        val command = "$box64Path $wineAbsPath $finalArgs"
        
        Log.d(TAG, "Final command: $command")
        Log.d(TAG, "Working directory: $rootDir")
        Log.d(TAG, "Environment variables:")
        for ((key, value) in envVars.toMap()) {
            Log.d(TAG, "  $key=$value")
        }
        
        // 执行命令
        return ProcessHelper.exec(
            command = command,
            envp = envVars.toStringArray(),
            workingDir = rootDir,
            callback = { status ->
                Log.d(TAG, "Process terminated with status: $status")
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
     * 获取Wine可执行文件名称
     */
    protected open fun getWineExecutableName(): String {
        return if (wow64Mode) "wine64" else "wine64"
    }
    
    /**
     * 从assets提取box86/64文件
     */
    override fun extractBox86_64Files() {
        val imageFs: ImageFs = environment!!.getImageFs()
        val context: Context = environment!!.getContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val rootDir: File = imageFs.getRootDir()
        
        // 获取当前版本
        val currentBox86Version = prefs.getString("current_box86_version", "") ?: ""
        val currentBox64Version = prefs.getString("current_box64_version", "") ?: ""
        
        // WoW64模式下删除box86
        if (wow64Mode) {
            val box86File = File(rootDir, "usr/local/bin/box86")
            if (box86File.isFile) {
                box86File.delete()
                prefs.edit().putString("current_box86_version", "").apply()
            }
        } else if (!currentBox86Version.isEmpty()) {
            // 提取box86
            TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD,
                context,
                "box86_64/box86-${prefs.getString("box86_version", "0.3.3")}.tzst",
                rootDir
            )
        }
        
        // 提取box64
        if (!currentBox64Version.isEmpty() || !currentBox64Version.isEmpty()) {
            TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD,
                context,
                "box86_64/box64-${prefs.getString("box64_version", "0.3.3")}.tzst",
                rootDir
            )
        }
    }
    
    /**
     * 添加Box86环境变量
     */
    override fun addBox86EnvVars(envVars: EnvVars, enableLogs: Boolean) {
        envVars["BOX86_NOBANNER"] = if (enableLogs) "0" else "1"
        envVars["BOX86_DYNAREC"] = "1"
        
        if (enableLogs) {
            envVars["BOX86_LOG"] = "1"
            envVars["BOX86_DYNAREC_MISSING"] = "1"
        }
        
        // 添加预设环境变量
        addBox86_64PresetEnvVars("box86", envVars)
        envVars["BOX86_X11GLX"] = "1"
    }
    
    /**
     * 添加Box64环境变量
     */
    override fun addBox64EnvVars(envVars: EnvVars, enableLogs: Boolean) {
        envVars["BOX64_NOBANNER"] = if (enableLogs) "0" else "1"
        envVars["BOX64_DYNAREC"] = "1"
        if (wow64Mode) envVars["BOX64_MMAP32"] = "1"
        
        if (enableLogs) {
            envVars["BOX64_LOG"] = "1"
            envVars["BOX64_DYNAREC_MISSING"] = "1"
        }
        
        // 添加预设环境变量
        addBox86_64PresetEnvVars("box64", envVars)
        envVars["BOX64_X11GLX"] = "1"
    }
    
    /**
     * 添加Box86/64预设环境变量
     */
    private fun addBox86_64PresetEnvVars(prefix: String, envVars: EnvVars) {
        // 预设配置，可根据需要扩展
        when (if (prefix == "box86") box86Preset else box64Preset) {
            COMPATIBILITY -> {
                // 兼容性预设
                envVars["${prefix.uppercase()}_EMULATED_LIB"] = "libasound.so.2"
            }
            PERFORMANCE -> {
                // 性能预设
            }
            BALANCED -> {
                // 平衡预设
            }
        }
    }
    
    companion object {
        const val COMPATIBILITY = "compatibility"
        const val BALANCED = "balanced"
        const val PERFORMANCE = "performance"
    }
}
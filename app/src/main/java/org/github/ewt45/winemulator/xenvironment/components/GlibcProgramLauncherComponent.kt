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
        
        Log.d(TAG, "=== ImageFs Wine Starting ===")
        Log.d(TAG, "RootDir: ${rootDir.absolutePath}")
        Log.d(TAG, "Wine Path: ${imageFs.getWinePath()}")
        
        // 获取配置
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        enableBox86_64Logs = prefs.getBoolean("enable_box86_64_logs", false)
        
        // 构建环境变量
        val envVars = EnvVars()
        
        // 设置Box64环境变量（非WoW64模式）
        if (!wow64Mode) {
            addBox86EnvVars(envVars, enableBox86_64Logs)
        }
        addBox64EnvVars(envVars, enableBox86_64Logs)
        
        // 设置基本环境变量 - 使用ImageFs中的路径
        envVars["HOME"] = imageFs.homePath
        envVars["USER"] = ImageFs.USER
        envVars["TMPDIR"] = rootDir.absolutePath + "/tmp"
        envVars["DISPLAY"] = ":0"
        
        // Wine路径 - 从ImageFs获取
        val wineDirAbs = File(imageFs.getWinePath())
        val wineBinDirAbs = File(wineDirAbs, "bin")
        val wineLibDirAbs = File(wineDirAbs, "lib")
        
        Log.d(TAG, "WineDir: ${wineDirAbs.absolutePath}")
        Log.d(TAG, "WineBinDir: ${wineBinDirAbs.absolutePath}")
        Log.d(TAG, "WineLibDir: ${wineLibDirAbs.absolutePath}")
        
        // 设置PATH
        envVars["PATH"] = wineBinDirAbs.absolutePath + ":" +
                File(rootDir, "usr/bin").absolutePath + ":" +
                File(rootDir, "usr/local/bin").absolutePath
        
        // 构建LD_LIBRARY_PATH
        var ldLibraryPath = File(rootDir, "usr/lib").absolutePath
        val wineLib64Dir = File(wineDirAbs, "lib64")
        
        // 添加Wine库路径到LD_LIBRARY_PATH前端
        ldLibraryPath = wineLib64Dir.absolutePath + ":" + wineLibDirAbs.absolutePath + ":" + ldLibraryPath
        
        // 添加wine目录下的unix库
        val wineDllDir = File(wineLibDirAbs, "wine")
        if (!wineDllDir.exists()) {
            val altWineDllDir = File(wineLib64Dir, "wine")
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
        envVars["ANDROID_SYSVSHM_SERVER"] = "/data/data/" + context.packageName + "/files/sysvshm_socket"
        envVars["FONTCONFIG_PATH"] = File(rootDir, "usr/etc/fonts").absolutePath
        
        // 检查是否需要预加载sysvshm库
        val glibc64Sysvshm = File(imageFs.getGlibc64Dir(), "libandroid-sysvshm.so")
        val glibc32Sysvshm = File(imageFs.getGlibc32Dir(), "libandroid-sysvshm.so")
        if (glibc64Sysvshm.exists() || glibc32Sysvshm.exists()) {
            envVars["LD_PRELOAD"] = "libandroid-sysvshm.so"
        }
        
        // 合并用户自定义环境变量
        if (this.envVars != null) {
            envVars.putAll(this.envVars!!)
        }
        
        // 处理启动命令参数
        var finalArgs = guestExecutable ?: ""
        finalArgs = finalArgs.trim()
        
        // 移除wine64/wine前缀，但保留后面的参数
        var wineExecutableName = "wine64"
        if (finalArgs.startsWith("wine64 ")) {
            finalArgs = finalArgs.substring(7).trim()
        } else if (finalArgs.startsWith("wine ")) {
            finalArgs = finalArgs.substring(5).trim()
        }
        
        // 构建启动命令: box64 /path/to/wine64 [args]
        val wineBinPath = File(wineBinDirAbs, wineExecutableName)
        
        // 兜底检查：如果 wine64 不存在，尝试 wine
        if (!wineBinPath.exists() && wineExecutableName == "wine64") {
            val altWinePath = File(wineBinDirAbs, "wine")
            if (altWinePath.exists()) {
                Log.d(TAG, "wine64 not found, using wine instead")
            }
        }
        
        // box64 路径
        val box64Path = File(rootDir, "usr/local/bin/box64")
        
        // 构建命令: /data/data/.../files/linbox/usr/local/bin/box64 /data/data/.../files/linbox/opt/wine/bin/wine64 explorer /desktop=winlator,1280x720
        val command = box64Path.absolutePath + " " + wineBinPath.absolutePath + " " + finalArgs
        
        Log.d(TAG, "Executing command: $command")
        Log.d(TAG, "LD_LIBRARY_PATH: $ldLibraryPath")
        Log.d(TAG, "WINEDLLPATH: ${envVars["WINEDLLPATH"]}")
        Log.d(TAG, "PATH: ${envVars["PATH"]}")
        
        // 检查关键文件是否存在
        Log.d(TAG, "box64 exists: ${box64Path.exists()}")
        Log.d(TAG, "wine64 exists: ${wineBinPath.exists()}")
        
        // 执行命令
        return ProcessHelper.exec(
            command = command,
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
    override fun extractBox86_64Files() {
        val imageFs: ImageFs = environment!!.getImageFs()
        val context: Context = environment!!.getContext()
        val rootDir: File = imageFs.getRootDir()
        
        // box64 文件应该在 imagefs.tzst 中已有，或者需要单独提取
        val box64File = File(rootDir, "usr/local/bin/box64")
        Log.d(TAG, "Checking box64 at: ${box64File.absolutePath}")
        Log.d(TAG, "box64 exists: ${box64File.exists()}")
        
        if (!box64File.exists()) {
            // 尝试从assets提取
            Log.d(TAG, "box64 not found, attempting to extract from assets")
            TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD,
                context,
                "box86_64/box64-0.3.4.tzst",
                rootDir
            )
            Log.d(TAG, "box64 extraction attempted")
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
        
        envVars["BOX64_X11GLX"] = "1"
    }
    
    companion object {
        const val COMPATIBILITY = "compatibility"
        const val BALANCED = "balanced"
        const val PERFORMANCE = "performance"
    }
}
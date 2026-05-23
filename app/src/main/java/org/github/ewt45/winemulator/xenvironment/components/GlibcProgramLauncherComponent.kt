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
        
        Log.e(TAG, "==============================================")
        Log.e(TAG, "=== ImageFs Wine Starting ===")
        Log.e(TAG, "RootDir: ${rootDir.absolutePath}")
        Log.e(TAG, "RootDir exists: ${rootDir.exists()}")
        Log.e(TAG, "Wine Path: ${imageFs.getWinePath()}")
        
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
        
        Log.e(TAG, "WineDir: ${wineDirAbs.absolutePath}, exists: ${wineDirAbs.exists()}")
        Log.e(TAG, "WineBinDir: ${wineBinDirAbs.absolutePath}, exists: ${wineBinDirAbs.exists()}")
        Log.e(TAG, "WineLibDir: ${wineLibDirAbs.absolutePath}, exists: ${wineLibDirAbs.exists()}")
        
        // 列出 wine/bin 目录内容
        if (wineBinDirAbs.exists()) {
            val binFiles = wineBinDirAbs.listFiles()?.map { it.name } ?: emptyList()
            Log.e(TAG, "Wine bin files: $binFiles")
        } else {
            Log.e(TAG, "WARNING: Wine bin directory does not exist!")
        }
        
        // 列出 usr/local/bin 目录内容
        val usrLocalBin = File(rootDir, "usr/local/bin")
        if (usrLocalBin.exists()) {
            val localBinFiles = usrLocalBin.listFiles()?.map { it.name } ?: emptyList()
            Log.e(TAG, "usr/local/bin files: $localBinFiles")
        } else {
            Log.e(TAG, "WARNING: usr/local/bin directory does not exist!")
        }
        
        // 列出 usr/bin 目录内容
        val usrBin = File(rootDir, "usr/bin")
        if (usrBin.exists()) {
            val usrBinFiles = usrBin.listFiles()?.map { it.name } ?: emptyList()
            Log.e(TAG, "usr/bin files (first 30): ${usrBinFiles.take(30)}")
        } else {
            Log.e(TAG, "WARNING: usr/bin directory does not exist!")
        }
        
        // 列出 usr/lib 目录内容
        val usrLib = File(rootDir, "usr/lib")
        if (usrLib.exists()) {
            val usrLibFiles = usrLib.listFiles()?.map { it.name } ?: emptyList()
            Log.e(TAG, "usr/lib files (first 30): ${usrLibFiles.take(30)}")
        } else {
            Log.e(TAG, "WARNING: usr/lib directory does not exist!")
        }
        
        // 设置PATH
        envVars["PATH"] = wineBinDirAbs.absolutePath + ":" +
                usrBin.absolutePath + ":" +
                usrLocalBin.absolutePath
        
        Log.e(TAG, "PATH set to: ${envVars["PATH"]}")
        
        // 构建LD_LIBRARY_PATH
        var ldLibraryPath = File(rootDir, "usr/lib").absolutePath
        val wineLib64Dir = File(wineDirAbs, "lib64")
        
        // 添加Wine库路径到LD_LIBRARY_PATH前端
        ldLibraryPath = wineLib64Dir.absolutePath + ":" + wineLibDirAbs.absolutePath + ":" + ldLibraryPath
        
        Log.e(TAG, "WineLib64Dir: ${wineLib64Dir.absolutePath}, exists: ${wineLib64Dir.exists()}")
        
        // 列出 lib64 目录内容
        if (wineLib64Dir.exists()) {
            val lib64Files = wineLib64Dir.listFiles()?.map { it.name } ?: emptyList()
            Log.e(TAG, "Wine lib64 files: $lib64Files")
        }
        
        // 添加wine目录下的unix库
        val wineDllDir = File(wineLibDirAbs, "wine")
        Log.e(TAG, "WineDllDir (lib/wine): ${wineDllDir.absolutePath}, exists: ${wineDllDir.exists()}")
        
        if (!wineDllDir.exists()) {
            val altWineDllDir = File(wineLib64Dir, "wine")
            Log.e(TAG, "AltWineDllDir (lib64/wine): ${altWineDllDir.absolutePath}, exists: ${altWineDllDir.exists()}")
            if (altWineDllDir.exists()) {
                envVars["WINEDLLPATH"] = altWineDllDir.absolutePath
                val unix64 = File(altWineDllDir, "x86_64-unix")
                val unix32 = File(altWineDllDir, "i386-unix")
                Log.e(TAG, "unix64 exists: ${unix64.exists()}, path: ${unix64.absolutePath}")
                Log.e(TAG, "unix32 exists: ${unix32.exists()}, path: ${unix32.absolutePath}")
                if (unix64.exists()) ldLibraryPath = unix64.absolutePath + ":" + ldLibraryPath
                if (unix32.exists()) ldLibraryPath = unix32.absolutePath + ":" + ldLibraryPath
            }
        } else {
            envVars["WINEDLLPATH"] = wineDllDir.absolutePath
            val unix64 = File(wineDllDir, "x86_64-unix")
            val unix32 = File(wineDllDir, "i386-unix")
            Log.e(TAG, "unix64 exists: ${unix64.exists()}, path: ${unix64.absolutePath}")
            Log.e(TAG, "unix32 exists: ${unix32.exists()}, path: ${unix32.absolutePath}")
            if (unix64.exists()) ldLibraryPath = unix64.absolutePath + ":" + ldLibraryPath
            if (unix32.exists()) ldLibraryPath = unix32.absolutePath + ":" + ldLibraryPath
        }
        
        // 列出 wine dll 目录内容
        if (wineDllDir.exists()) {
            val dllFiles = wineDllDir.listFiles()?.map { it.name } ?: emptyList()
            Log.e(TAG, "Wine dll directory files (first 20): ${dllFiles.take(20)}")
        }
        
        envVars["LD_LIBRARY_PATH"] = ldLibraryPath
        envVars["BOX64_LD_LIBRARY_PATH"] = File(rootDir, "usr/lib/x86_64-linux-gnu").absolutePath + ":" + ldLibraryPath
        
        // 使用正确的 sysvshm 路径（与 Winlator 一致: /tmp/.sysvshm/SM0）
        val sysvshmServerPath = "/tmp/.sysvshm/SM0"
        envVars["ANDROID_SYSVSHM_SERVER"] = File(rootDir, sysvshmServerPath).absolutePath
        envVars["FONTCONFIG_PATH"] = File(rootDir, "usr/etc/fonts").absolutePath
        
        Log.e(TAG, "LD_LIBRARY_PATH: $ldLibraryPath")
        Log.e(TAG, "ANDROID_SYSVSHM_SERVER: ${envVars["ANDROID_SYSVSHM_SERVER"]}")
        
        // 检查是否需要预加载sysvshm库
        val glibc64Dir = imageFs.getGlibc64Dir()
        val glibc32Dir = imageFs.getGlibc32Dir()
        Log.e(TAG, "Glibc64Dir: ${glibc64Dir.absolutePath}, exists: ${glibc64Dir.exists()}")
        Log.e(TAG, "Glibc32Dir: ${glibc32Dir.absolutePath}, exists: ${glibc32Dir.exists()}")
        
        val glibc64Sysvshm = File(glibc64Dir, "libandroid-sysvshm.so")
        val glibc32Sysvshm = File(glibc32Dir, "libandroid-sysvshm.so")
        Log.e(TAG, "glibc64Sysvshm exists: ${glibc64Sysvshm.exists()}, path: ${glibc64Sysvshm.absolutePath}")
        Log.e(TAG, "glibc32Sysvshm exists: ${glibc32Sysvshm.exists()}, path: ${glibc32Sysvshm.absolutePath}")
        
        if (glibc64Sysvshm.exists() || glibc32Sysvshm.exists()) {
            envVars["LD_PRELOAD"] = "libandroid-sysvshm.so"
            Log.e(TAG, "LD_PRELOAD set to: libandroid-sysvshm.so")
        }
        
        // 合并用户自定义环境变量
        if (this.envVars != null) {
            envVars.putAll(this.envVars!!)
        }
        
        // 打印所有环境变量
        Log.e(TAG, "=== All Environment Variables ===")
        for ((key, value) in envVars) {
            Log.e(TAG, "  $key = $value")
        }
        
        // 处理启动命令参数
        var finalArgs = guestExecutable ?: ""
        finalArgs = finalArgs.trim()
        
        Log.e(TAG, "Original guestExecutable: $guestExecutable")
        Log.e(TAG, "Final args after trim: $finalArgs")
        
        // 移除wine64/wine前缀，但保留后面的参数
        var wineExecutableName = "wine64"
        if (finalArgs.startsWith("wine64 ")) {
            wineExecutableName = "wine64"
            finalArgs = finalArgs.substring(7).trim()
            Log.e(TAG, "Removed wine64 prefix, remaining: $finalArgs")
        } else if (finalArgs.startsWith("wine ")) {
            wineExecutableName = "wine64" // 保持使用 wine64
            finalArgs = finalArgs.substring(5).trim()
            Log.e(TAG, "Removed wine prefix, remaining: $finalArgs, will use wine64")
        }
        
        // 构建启动命令: box64 /path/to/wine64 [args]
        val wineBinPath = File(wineBinDirAbs, wineExecutableName)
        
        Log.e(TAG, "wineExecutableName: $wineExecutableName")
        Log.e(TAG, "wineBinPath: ${wineBinPath.absolutePath}")
        Log.e(TAG, "wineBinPath exists: ${wineBinPath.exists()}")
        
        // 兜底检查：如果 wine64 不存在，尝试 wine
        if (!wineBinPath.exists() && wineExecutableName == "wine64") {
            val altWinePath = File(wineBinDirAbs, "wine")
            Log.e(TAG, "wine64 not found, checking wine: ${altWinePath.exists()}")
            if (altWinePath.exists()) {
                Log.d(TAG, "wine64 not found, using wine instead")
            } else {
                Log.e(TAG, "ERROR: Neither wine64 nor wine found in ${wineBinDirAbs.absolutePath}")
            }
        }
        
        // box64 路径
        val box64Path = File(rootDir, "usr/local/bin/box64")
        Log.e(TAG, "box64Path: ${box64Path.absolutePath}")
        Log.e(TAG, "box64Path exists: ${box64Path.exists()}")
        
        // 构建命令: /data/data/.../files/linbox/usr/local/bin/box64 /data/data/.../files/linbox/opt/wine/bin/wine64 explorer /desktop=winlator,1280x720
        val command = box64Path.absolutePath + " " + wineBinPath.absolutePath + " " + finalArgs
        
        Log.e(TAG, "==============================================")
        Log.e(TAG, "FINAL COMMAND TO EXECUTE:")
        Log.e(TAG, command)
        Log.e(TAG, "==============================================")
        Log.e(TAG, "WORKING DIR: ${rootDir.absolutePath}")
        Log.e(TAG, "==============================================")
        
        // 检查关键文件是否存在
        if (!box64Path.exists()) {
            Log.e(TAG, "ERROR: box64 does not exist at ${box64Path.absolutePath}")
        }
        if (!wineBinPath.exists()) {
            Log.e(TAG, "ERROR: wine executable does not exist at ${wineBinPath.absolutePath}")
        }
        
        // 执行命令
        return ProcessHelper.exec(
            command = command,
            envp = envVars.toStringArray(),
            workingDir = rootDir,
            callback = { status ->
                Log.e(TAG, "Wine process terminated with status: $status")
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
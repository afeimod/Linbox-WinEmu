package org.github.ewt45.winemulator.xenvironment.components

import android.content.Context
import android.system.OsConstants
import android.util.Log
import androidx.preference.PreferenceManager
import org.github.ewt45.winemulator.Consts
import java.io.File

/**
 * ProcessHelper - 进程执行辅助类
 * 用于启动和管理Wine进程
 */
object ProcessHelper {
    
    private const val TAG = "ProcessHelper"
    const val PRINT_DEBUG = true  // 是否打印调试信息
    
    /**
     * 执行命令
     * 使用 sh -c 执行完整命令字符串，支持包含空格和特殊字符的路径
     * @param command 要执行的命令
     * @param envp 环境变量数组
     * @param workingDir 工作目录
     * @param callback 执行完成回调
     * @param logFilePath 日志文件路径
     * @return 进程ID
     */
    fun exec(
        command: String,
        envp: Array<String>,
        workingDir: File,
        callback: ((Int) -> Unit)? = null,
        logFilePath: String? = null
    ): Int {
        Log.d(TAG, "=== ProcessHelper.exec START ===")
        Log.d(TAG, "Full command: $command")
        Log.d(TAG, "Working directory: ${workingDir.absolutePath}")
        
        try {
            // 使用 sh -c 执行完整命令，避免 split(" ") 导致的路径分割问题
            val processBuilder = ProcessBuilder("/system/bin/sh", "-c", command)
            processBuilder.directory(workingDir)
            
            // 设置环境变量
            val environment = processBuilder.environment()
            environment.clear()  // 清空默认环境变量
            
            // 设置 PATH（最小配置）
            environment["PATH"] = "/system/bin:/vendor/bin:/product/bin"
            
            // 设置用户指定的环境变量
            for (env in envp) {
                val parts = env.split("=", limit = 2)
                if (parts.size == 2) {
                    environment[parts[0]] = parts[1]
                    if (PRINT_DEBUG) {
                        Log.d(TAG, "  ENV: ${parts[0]}=${parts[1]}")
                    }
                }
            }
            
            // 重定向错误流到日志文件
            if (logFilePath != null) {
                processBuilder.redirectErrorStream(true)
            }
            
            Log.d(TAG, "Starting process...")
            val process = processBuilder.start()
            // Android Process 类没有 pid 属性，使用反射获取
            val pid = getProcessId(process)
            Log.d(TAG, "Process started with PID: $pid")
            
            // 处理输出
            if (logFilePath != null) {
                Thread {
                    try {
                        val logFile = File(logFilePath)
                        logFile.parentFile?.mkdirs()
                        
                        val reader = process.inputStream.bufferedReader()
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            Log.d(TAG, "[stdout] $line")
                            logFile.appendText("$line\n")
                        }
                        reader.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading process output: ${e.message}")
                    }
                }.start()
            } else {
                // 即使没有日志文件，也读取输出避免管道阻塞
                Thread {
                    try {
                        val reader = process.inputStream.bufferedReader()
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            Log.d(TAG, "[stdout] $line")
                        }
                        reader.close()
                    } catch (e: Exception) {
                        // 忽略读取错误
                    }
                }.start()
            }
            
            // 等待进程结束
            Thread {
                try {
                    val exitCode = process.waitFor()
                    Log.d(TAG, "Process exited with code: $exitCode")
                    callback?.invoke(exitCode)
                } catch (e: Exception) {
                    Log.e(TAG, "Error waiting for process: ${e.message}")
                    callback?.invoke(-1)
                }
            }.start()
            
            return pid
        } catch (e: Exception) {
            Log.e(TAG, "Error executing command: ${e.message}")
            e.printStackTrace()
            callback?.invoke(-1)
            return -1
        }
    }
    
    /**
     * 获取进程ID
     * Android的Process类没有直接的pid属性，需要通过反射获取
     */
    private fun getProcessId(process: Process): Int {
        return try {
            val pidField = process.javaClass.getDeclaredMethod("getPid")
            pidField.isAccessible = true
            pidField.invoke(process) as Int
        } catch (e: Exception) {
            // 如果反射失败，返回一个随机值
            Log.w(TAG, "Could not get PID via reflection, returning -1")
            -1
        }
    }
    
    /**
     * 暂停进程
     */
    fun suspendProcess(pid: Int) {
        try {
            android.os.Process.sendSignal(pid, OsConstants.SIGSTOP)
        } catch (e: Exception) {
            Log.e(TAG, "Error suspending process: ${e.message}")
        }
    }
    
    /**
     * 恢复进程
     */
    fun resumeProcess(pid: Int) {
        try {
            android.os.Process.sendSignal(pid, OsConstants.SIGCONT)
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming process: ${e.message}")
        }
    }
    
    /**
     * 终止进程
     */
    fun killProcess(pid: Int) {
        try {
            android.os.Process.killProcess(pid)
        } catch (e: Exception) {
            Log.e(TAG, "Error killing process: ${e.message}")
        }
    }
}
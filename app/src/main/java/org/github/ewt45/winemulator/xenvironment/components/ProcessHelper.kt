package org.github.ewt45.winemulator.xenvironment.components

import android.content.Context
import android.system.OsConstants
import android.util.Log
import androidx.preference.PreferenceManager
import org.github.ewt45.winemulator.Consts
import org.github.ewt45.winemulator.Utils.getPid
import java.io.File

/**
 * ProcessHelper - 进程执行辅助类
 * 用于启动和管理Wine进程
 */
object ProcessHelper {
    
    private const val TAG = "ProcessHelper"
    
    /**
     * 执行命令
     * @param command 要执行的完整命令字符串
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
        Log.d(TAG, "Executing command: $command")
        Log.d(TAG, "Working directory: ${workingDir.absolutePath}")
        
        try {
            // 使用 shell 执行完整命令，这样可以正确处理包含空格的参数
            val shellCommand = "cd ${workingDir.absolutePath} && $command"
            
            // 创建进程
            val processBuilder = ProcessBuilder("/bin/sh", "-c", shellCommand)
            processBuilder.directory(workingDir)
            
            // 设置环境变量
            val environment = processBuilder.environment()
            for (env in envp) {
                val parts = env.split("=", limit = 2)
                if (parts.size == 2) {
                    environment[parts[0]] = parts[1]
                    Log.v(TAG, "  ENV: ${parts[0]} = ${parts[1]}")
                }
            }
            
            val process = processBuilder.start()
            val pid = process.getPid()
            
            Log.d(TAG, "Process started with PID: $pid")
            
            // 处理输出
            if (logFilePath != null || Log.isLoggable(TAG, Log.DEBUG)) {
                Thread {
                    try {
                        val reader = process.inputStream.bufferedReader()
                        while (true) {
                            val line = reader.readLine() ?: break
                            Log.d(TAG, "[stdout] $line")
                            if (logFilePath != null) {
                                File(logFilePath).appendText("$line\n")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading stdout: ${e.message}")
                    }
                }.start()
                
                Thread {
                    try {
                        val reader = process.errorStream.bufferedReader()
                        while (true) {
                            val line = reader.readLine() ?: break
                            Log.e(TAG, "[stderr] $line")
                            if (logFilePath != null) {
                                File(logFilePath).appendText("$line\n")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading stderr: ${e.message}")
                    }
                }.start()
            }
            
            // 等待进程结束
            Thread {
                val exitCode = process.waitFor()
                Log.d(TAG, "Process $pid exited with code: $exitCode")
                callback?.invoke(exitCode)
            }.start()
            
            return pid
        } catch (e: Exception) {
            Log.e(TAG, "Error executing command: ${e.message}", e)
            callback?.invoke(-1)
            return -1
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
    
    /**
     * 获取进程亲和性掩码
     */
    fun getAffinityMask(cpuList: String?): Int {
        if (cpuList == null || cpuList.isEmpty()) return 0
        var mask = 0
        try {
            for (cpu in cpuList.split(",")) {
                val cpuIndex = cpu.trim().toInt()
                mask = mask or (1 shl cpuIndex)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing CPU list: $cpuList")
        }
        return mask
    }
}
package org.github.ewt45.winemulator.xenvironment.components

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import org.github.ewt45.winemulator.Consts
import org.github.ewt45.winemulator.Utils
import java.io.File
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.isAccessible

/**
 * ProcessHelper - 进程执行辅助类
 * 用于启动和管理Wine进程
 */
object ProcessHelper {
    
    private const val TAG = "ProcessHelper"
    const val PRINT_DEBUG = true  // 是否打印调试信息
    
    /**
     * 执行命令
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
        Log.d(TAG, "Executing: $command")
        Log.d(TAG, "Working directory: ${workingDir.absolutePath}")
        
        try {
            // 创建进程
            val processBuilder = ProcessBuilder(*command.split(" ").toTypedArray())
            processBuilder.directory(workingDir)
            
            // 设置环境变量
            val environment = processBuilder.environment()
            for (env in envp) {
                val parts = env.split("=", limit = 2)
                if (parts.size == 2) {
                    environment[parts[0]] = parts[1]
                }
            }
            
            // 重定向错误流到日志文件
            if (logFilePath != null) {
                processBuilder.redirectErrorStream(true)
            }
            
            val process = processBuilder.start()
            val pid = Utils.getPid(process)
            
            // 处理输出
            if (logFilePath != null) {
                Thread {
                    val logFile = File(logFilePath)
                    logFile.parentFile?.mkdirs()
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            Log.d(TAG, line)
                            logFile.appendText("$line\n")
                        }
                    }
                }.start()
            }
            
            // 等待进程结束
            Thread {
                val exitCode = process.waitFor()
                Log.d(TAG, "Process exited with code: $exitCode")
                callback?.invoke(exitCode)
            }.start()
            
            return pid
        } catch (e: Exception) {
            Log.e(TAG, "Error executing command: ${e.message}")
            callback?.invoke(-1)
            return -1
        }
    }
    
    /**
     * 暂停进程
     */
    fun suspendProcess(pid: Int) {
        try {
            android.os.Process.sendSignal(pid, android.os.Signal.STOP.signal)
        } catch (e: Exception) {
            Log.e(TAG, "Error suspending process: ${e.message}")
        }
    }
    
    /**
     * 恢复进程
     */
    fun resumeProcess(pid: Int) {
        try {
            android.os.Process.sendSignal(pid, android.os.Signal.CONTINUE.signal)
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
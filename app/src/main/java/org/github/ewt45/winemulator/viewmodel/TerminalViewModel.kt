package org.github.ewt45.winemulator.viewmodel

import android.system.OsConstants.SIGCONT
import android.system.OsConstants.SIGSTOP
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.github.ewt45.winemulator.Utils.getPid
import org.github.ewt45.winemulator.emu.Proot
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class TerminalViewModel : ViewModel() {
    private val TAG = "TerminalViewModel"
    private val terminal: Proot = Proot()
    private var process: Process? = null

    /** 输入 */
    private var processWriter: OutputStreamWriter? = null

    /** 输出行。每个字符串代表一行，换行字符包括在字符串结尾，拼接时不应再添加 */
    private val _output = mutableStateOf<List<String>>(emptyList())
    val output get() = _output

    private val outputMutex = Mutex() //锁，修改output相关内容时应该使用

    /** 当前用户名 */
    var currentUser by mutableStateOf("root")
        private set

    /** 当前主机名 */
    var currentHost by mutableStateOf("localhost")
        private set

    /** 当前路径 */
    var currentPath by mutableStateOf("~")
        private set

    /** 连接状态 */
    var isConnected by mutableStateOf(false)
        private set

    /** root用户的颜色（白色） */
    private val rootUserColor = Color(0xFFE0E0E0)
    
    /** 普通用户的颜色（蓝色） */
    private val normalUserColor = Color(0xFF64B5F6)
    
    /** 主机名颜色（青色） */
    private val hostColor = Color(0xFF4DD0E1)
    
    /** 路径颜色（绿色） */
    private val pathColor = Color(0xFF81C784)
    
    /** 符号颜色（黄色） */
    private val symbolColor = Color(0xFFFFD54F)

    /** 美化的命令提示符格式: 用户名@主机:路径$ */
    val promptPrefix: String
        get() = "$currentUser@$currentHost:$currentPath$ "
    
    /**
     * 获取带颜色的命令提示符 (AnnotatedString)
     * root用户显示为白色，其他用户显示为蓝色
     */
    fun getColoredPrompt(): AnnotatedString {
        val userColor = if (currentUser == "root") rootUserColor else normalUserColor
        
        return buildAnnotatedString {
            // 用户名
            withStyle(SpanStyle(color = userColor, fontWeight = FontWeight.Bold)) {
                append(currentUser)
            }
            // @ 符号
            withStyle(SpanStyle(color = symbolColor)) {
                append("@")
            }
            // 主机名
            withStyle(SpanStyle(color = hostColor, fontWeight = FontWeight.Bold)) {
                append(currentHost)
            }
            // : 符号
            withStyle(SpanStyle(color = symbolColor)) {
                append(":")
            }
            // 路径
            withStyle(SpanStyle(color = pathColor, fontWeight = FontWeight.Bold)) {
                append(currentPath)
            }
            // $ 或 # 符号
            withStyle(SpanStyle(color = symbolColor)) {
                append(if (currentUser == "root") "# " else "$ ")
            }
        }
    }

    /**
     * 启动终端
     */
    suspend fun startTerminal() {
        if (process != null) return
        isConnected = true

        process = withContext(Dispatchers.IO) {
            terminal.attach().start()
        }

        //绑定输入输出
        processWriter = OutputStreamWriter(process!!.outputStream)

        //另起协程获取输出以及等待关闭
        viewModelScope.launch(Dispatchers.IO) {
            // 简洁的启动提示
            updateOutput("终端已连接")
            updateOutput("---")
            try {
                BufferedReader(InputStreamReader(process!!.inputStream)).use { reader ->
                    val builder = StringBuilder()
                    var readInt: Int
                    var charRead: Char
                    var lastReadCharTime = 0L //上次读取到新输出字符的时间。即使不完成整行 也会更新
                    //builder lastUpdateTime output 应该在锁下进行

                    // FIXME adduser 最后一条确认没显示出来？
                    val updateInlineOutputJob = launch {
                        var lastReadCharTimeCopy = 0L
                        while (process?.isAlive == true) {
                            delay(500)
                            outputMutex.withLock {
                                // 如果500ms内字符输出没有更新过，则将当前缓存的无换行字符串显示出来。
                                if (lastReadCharTime == lastReadCharTimeCopy && lastReadCharTimeCopy != 0L && builder.isNotEmpty()) {
                                    val currentList = _output.value
                                    val lastLine = if (currentList.isNotEmpty()) currentList[currentList.lastIndex] else null
                                    if (lastLine?.endsWith('\n') != false) updateOutput(builder.toString())
                                    else updateOutputAtLast(builder.toString())
                                    builder.clear()
                                }
                                lastReadCharTimeCopy = lastReadCharTime
                            }
                        }
                    }

                    while (reader.read().also { readInt = it } != -1) {
                        var charRead = readInt.toChar()
                        var skipChar = false
                        
                        // 处理回车符：将 \r\n 或 \r 视为换行
                        if (charRead == '\r') {
                            // 看看下一个字符是否是 \n
                            val nextInt = reader.read()
                            if (nextInt != -1) {
                                val nextChar = nextInt.toChar()
                                if (nextChar == '\n') {
                                    // 如果是 \r\n，跳过这个 \r，\n 会在下一次循环中处理
                                    charRead = nextChar
                                } else {
                                    // 如果下一个字符不是 \n，把 \r 当作换行处理
                                    val line = builder.toString()
                                    // 检测用户名变化
                                    detectUserChange(line)
                                    // 添加当前行
                                    val currentList = _output.value.toMutableList()
                                    if (currentList.size > 800) {
                                        currentList.removeAt(0)
                                    }
                                    currentList.add(line)
                                    _output.value = currentList
                                    builder.clear()
                                    // 把下一个非 \n 字符加入新的行
                                    builder.append(nextChar)
                                    skipChar = true
                                }
                            }
                        }
                        
                        if (!skipChar) {
                            outputMutex.withLock {
                                lastReadCharTime = System.currentTimeMillis()
                                builder.append(charRead)

                                // 尝试解析路径（简单的启发式方法）
                                if (charRead == ':' && builder.length > 2) {
                                    val potentialPath = builder.toString().takeLast(50)
                                    if (potentialPath.matches(Regex(""".*:[/~][/\w.-]*\$?"""))) {
                                        currentPath = extractPath(potentialPath)
                                    }
                                }

                                if (charRead == '\n') {
                                    val line = builder.toString()
                                    // 检测用户名变化
                                    detectUserChange(line)

                                    // 限制输出数量
                                    val currentList = _output.value.toMutableList()
                                    if (currentList.size > 800) {
                                        currentList.removeAt(0)
                                    }
                                    currentList.add(line)
                                    _output.value = currentList
                                    builder.clear()
                                }
                            }
                        }
                    }
                    updateInlineOutputJob.cancel()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                updateOutput("错误: ${e.message}")
            }
            process?.waitFor()
            isConnected = false
            closeResources()
        }

        if (Proot.lastTimeCmd.isNotBlank()) {
            updateOutput("使用以下参数启动proot：")
            updateOutput(Proot.lastTimeCmd)
            updateOutput("")
        }
        return
    }

    /**
     * 从提示符行中提取路径
     */
    private fun extractPath(text: String): String {
        // 尝试匹配 ~ 或 / 开头的路径
        val pathMatch = Regex(""":([/~][/\w.-]*)[\$#]""").find(text)
        return pathMatch?.groupValues?.get(1) ?: "~"
    }

    /**
     * 检测用户名是否变化
     */
    private fun detectUserChange(line: String) {
        // 检测 su - username 或 sudo -i 等命令后的用户变化
        if (line.contains("su -") || line.contains("sudo -i")) {
            val userMatch = Regex("""su\s+-\s*(\w+)""").find(line)
                ?: Regex("""sudo\s+-i""").find(line)
            // 简化处理：假设切换到root
            if (userMatch != null) {
                currentUser = "root"
            }
        }
        // 检测 whoami 输出
        if (line.contains("root") && _output.value.size > 5) {
            // 检查前几行是否有 whoami 命令
            val recentLines = _output.value.takeLast(5)
            if (recentLines.any { it.contains("whoami") }) {
                currentUser = "root"
            }
        }
    }

    /**
     * 更新输出
     */
    private fun updateOutput(line: String) {
        val currentList = _output.value.toMutableList()
        currentList.add(line)
        _output.value = currentList
    }

    /**
     * 在最后一行追加内容
     */
    private fun updateOutputAtLast(additional: String) {
        val currentList = _output.value.toMutableList()
        if (currentList.isNotEmpty()) {
            val lastIndex = currentList.lastIndex
            currentList[lastIndex] = currentList[lastIndex] + additional
            _output.value = currentList
        } else {
            currentList.add(additional)
            _output.value = currentList
        }
    }

    /**
     * 执行某个命令
     * @param display 为false时不显示在屏幕上
     */
    fun runCommand(command: String, display: Boolean = true) = viewModelScope.launch(Dispatchers.IO) {

        if (processWriter == null || process?.isAlive != true) {
            updateOutput("进程已关闭。无法执行命令 $command")
            stopTerminal()
            return@launch
        }

        if (display) {
            // 使用美化的提示符
            updateOutput(promptPrefix + command)
        }

        try {
            // 添加回车，否则不会执行
            processWriter?.write(command + "\n")
            // 确保命令立刻发送
            processWriter?.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 更新提示符信息（从设置读取）
     */
    fun updatePromptFromSettings(userName: String) {
        currentUser = userName.ifBlank { "root" }
    }

    /**
     * 结束终端
     */
    fun stopTerminal() {
        isConnected = false
        closeResources()
    }

    /**
     * 清理资源
     */
    private fun closeResources() {
        try {
            processWriter?.close()
            process?.outputStream?.close()
            process?.inputStream?.close()
            process?.errorStream?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            process?.destroy()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        process = null
        processWriter = null
        isConnected = false
    }


    /**
     * viewModel销毁时结束终端
     */
    override fun onCleared() {
        super.onCleared()
        stopTerminal()
    }

    fun pauseTerminal() {
        val pid = process?.getPid() ?: -1
        android.os.Process.sendSignal(pid, SIGSTOP)
    }

    fun resumeTerminal() {
        val pid = process?.getPid() ?: -1
        android.os.Process.sendSignal(pid, SIGCONT)
    }
}

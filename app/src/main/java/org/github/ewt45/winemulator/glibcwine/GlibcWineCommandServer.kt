package org.github.ewt45.winemulator.glibcwine

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import kotlin.concurrent.thread

/**
 * glibc wine 命令服务器 - 通过 fifo 接收 proot 容器内的启动命令。
 *
 * ## 架构说明
 *
 * glibc wine 不在 proot 容器内运行,而是直接在 Android 上运行(使用 imagefs 的 glibc 库)。
 * proot 容器桌面通过 fifo 向 Android 端发送启动命令。
 *
 * ```
 * ┌─────────────────────────────────────────────────────┐
 * │  Android 进程空间                                     │
 * │                                                       │
 * │  ┌──────────────┐    ┌──────────────────────┐       │
 * │  │  proot 容器   │    │  glibc wine (独立)    │       │
 * │  │  (桌面环境)   │    │  直接在 Android 运行   │       │
 * │  │              │    │  使用 imagefs 的 glibc │       │
 * │  │  linbox-wine │───►│  CommandServer 读取    │       │
 * │  │  (写 fifo)   │    │  fifo 并启动 wine      │       │
 * │  └──────────────┘    └──────────────────────┘       │
 * │         │                       │                     │
 * │         ▼                       ▼                     │
 * │  ┌─────────────────────────────────────────────┐    │
 * │  │  Termux-X11 (:13) + PulseAudio (4713)       │    │
 * │  └─────────────────────────────────────────────┘    │
 * └─────────────────────────────────────────────────────┘
 * ```
 *
 * ## fifo 通信协议
 *
 * fifo 路径: <filesDir>/imagefs/tmp/wine-cmd
 * (通过 proot --bind 暴露给容器内 /opt/glibc-wine/tmp/wine-cmd)
 *
 * 命令格式 (一行文本):
 * - 空行或 "winefile" → 启动 winefile
 * - "exe:<路径>" → 启动指定 exe
 * - "kill" → 停止 wine
 */
object GlibcWineCommandServer {
    private const val TAG = "GlibcWineCmdServer"

    /** fifo 文件名 */
    private const val FIFO_NAME = "wine-cmd"

    /** 是否正在运行 */
    @Volatile
    private var running = false

    /** 当前运行的 wine 进程 */
    @Volatile
    private var wineProcess: Process? = null

    /**
     * 获取 fifo 文件路径。
     * 位于 imagefs/tmp/ 下,通过 proot --bind 暴露给容器。
     */
    fun getFifoPath(context: Context): File {
        val imageFs = GlibcImageFs.find(context)
        val tmpDir = File(imageFs.rootDir, GlibcWineConsts.TMP_DIR_REL)
        tmpDir.mkdirs()
        return File(tmpDir, FIFO_NAME)
    }

    /**
     * 启动命令服务器。
     * 在 Application.onCreate 或 proot 启动前调用。
     */
    fun start(context: Context) {
        if (running) {
            Log.i(TAG, "命令服务器已在运行")
            return
        }
        running = true

        // 创建 fifo
        val fifo = getFifoPath(context)
        try {
            if (!fifo.exists()) {
                Os.mkfifo(fifo.absolutePath, 420) // 0644
                Log.i(TAG, "fifo 已创建: ${fifo.path}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建 fifo 失败, 尝试使用普通文件", e)
            // fallback: 使用普通文件
            if (!fifo.exists()) fifo.createNewFile()
        }

        // 后台线程读取 fifo
        // 使用持久打开方式: 一直保持 fifo 打开, 逐行读取
        // 这样 proot 容器每次 echo > fifo 都能被读到
        thread(name = "GlibcWineCmdServer", isDaemon = true) {
            Log.i(TAG, "命令服务器开始监听: ${fifo.path}")
            while (running) {
                var fis: FileInputStream? = null
                try {
                    fis = FileInputStream(fifo)
                    val reader = BufferedReader(InputStreamReader(fis))
                    while (running) {
                        val line = reader.readLine() ?: break // EOF, 写入端关闭
                        if (line.isNotEmpty()) {
                            Log.i(TAG, "收到命令: $line")
                            handleCommand(context, line.trim())
                        }
                    }
                } catch (e: Exception) {
                    if (running) {
                        Log.e(TAG, "读取 fifo 失败, 1秒后重试", e)
                        Thread.sleep(1000)
                    }
                } finally {
                    try { fis?.close() } catch (_: Exception) {}
                }
            }
            Log.i(TAG, "命令服务器已停止")
        }
    }

    /**
     * 停止命令服务器。
     */
    fun stop() {
        running = false
        wineProcess?.destroy()
        wineProcess = null
        Log.i(TAG, "命令服务器停止中")
    }

    /**
     * 处理收到的命令。
     */
    private fun handleCommand(context: Context, command: String) {
        try {
            val launcher = GlibcWineLauncher(context)
            when {
                command.isEmpty() || command == "winefile" -> {
                    launcher.launchDirect(null, "")
                }
                command == "kill" -> {
                    launcher.killWine()
                }
                command.startsWith("exe:") -> {
                    val exePath = command.substring(4)
                    launcher.launchDirect(exePath, "")
                }
                else -> {
                    // 直接当作 exe 路径
                    launcher.launchDirect(command, "")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理命令失败: $command", e)
        }
    }

    /**
     * 检查服务器是否正在运行。
     */
    fun isRunning(): Boolean = running
}

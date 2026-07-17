package org.github.ewt45.winemulator.glibc

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.github.ewt45.winemulator.Consts
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Android 端 FIFO exec server (纯 Java 实现, 不依赖 sh 脚本)。
 *
 * 角色 (用户原话 v5.4):
 *   "安卓 app 创建 FIFO 管道和锁文件并启动 fifo 服务"
 *   "使 proot 能够正确管理 /data/data/.../files/imagefs 的 glibc 环境"
 *   "然后在 proot 里使用 startexec 定义 FIFO 管道路径和将命令参数拼接后写入 FIFO"
 *   "echo "$*" > "$FIFO""
 *
 * 实现: linboxapp (Android 进程) 用纯 Java 启 FIFO server
 *   - 创建 <tmpDir>/.exec.fifo + <tmpDir>/.exec-lock
 *   - 后台线程读 FIFO, 收到一行命令后, 直接 fork /system/bin/sh 跑
 *     <imagefs>/usr/local/bin/glibc-run.sh <args>
 *   - 不用 sh 写 server 脚本 (避免 Android 14+ 对 sh 派发的限制)
 *   - 不再依赖 assets/glibc/fifo_exec_server.sh
 *
 * proot 这边:
 *   - proot 启时 --bind=tmpDir:/tmp, 所以 proot 内 /tmp 就是 host tmpDir,
 *     跟 server 创建的 FIFO 同目录
 *   - xfce4 terminal 跑 startexec, 把 "glibc-run <args>" 写到
 *     /tmp/.exec.fifo
 *   - server 收到后, Android 进程 fork sh 跑 glibc-run
 *
 * 生命周期:
 *   - [start] 在 MainEmuActivity.startEmu() 里、proot 启之前调用
 *   - [stop] 在 onDestroy 里调用: 删 lock 文件让 server 主循环退出
 */
class FifoExecServer {
    companion object {
        private const val TAG = "FifoExecServer"

        /** glibc-run 在 imagefs 内的路径 (Android 进程能直接 exec) */
        const val GLIBC_RUN = "/data/data/a.io.github.ewt45.winemulator/files/imagefs/usr/local/bin/glibc-run.sh"

        /** Android 端 FIFO 路径 (server 创建 + startexec 写) */
        const val FIFO_PATH = "/data/data/a.io.github.ewt45.winemulator/cache/tmp/.exec.fifo"

        /** Android 端 lock 文件路径 (server 创建, 删了 server 退出) */
        const val LOCK_PATH = "/data/data/a.io.github.ewt45.winemulator/cache/tmp/.exec-lock"

        /** exec.log (调试日志) */
        const val LOG_PATH = "/data/data/a.io.github.ewt45.winemulator/cache/tmp/.exec.log"
    }

    @Volatile private var running = false
    private var serverThread: Thread? = null

    /**
     * 启 fifo server (纯 Java 后台线程)。
     *
     * @return true = 启成功 (或已经在跑)
     */
    fun start(): Boolean {
        if (running) {
            Log.i(TAG, "start: server 已经在跑")
            return true
        }

        // 启动前清掉残留 lock + fifo
        try {
            File(FIFO_PATH).delete()
            File(LOCK_PATH).delete()
        } catch (_: Exception) {
        }

        // 创建 lock 文件 (server 在跑时存在, 删除就退出)
        try {
            File(LOCK_PATH).writeText(System.currentTimeMillis().toString())
        } catch (e: Exception) {
            Log.e(TAG, "start: 无法创建 lock 文件 $LOCK_PATH", e)
            return false
        }

        // 创建 FIFO (命名管道)
        try {
            // 用 Runtime.exec 调 mkfifo (Android sh 自带 mkfifo)
            // 走 sh 但只创建一次, 不会卡
            val mkfifo = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", "mkfifo '$FIFO_PATH' && chmod 0666 '$FIFO_PATH'"))
            mkfifo.waitFor()
            val rc = mkfifo.exitValue()
            if (rc != 0) {
                Log.e(TAG, "start: mkfifo 失败 rc=$rc")
                // 检查是否已经存在
                if (!File(FIFO_PATH).exists()) {
                    File(LOCK_PATH).delete()
                    return false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "start: mkfifo 异常", e)
            File(LOCK_PATH).delete()
            return false
        }

        // 写 log 启动信息
        try {
            File(LOG_PATH).writeText("[${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}] fifo_exec_server (java) started pid=${android.os.Process.myPid()}\n")
        } catch (_: Exception) {
        }

        running = true
        serverThread = Thread {
            try {
                runServerLoop()
            } catch (e: Throwable) {
                Log.e(TAG, "server thread crashed", e)
            } finally {
                running = false
            }
        }.apply {
            name = "FifoExecServer"
            isDaemon = false
            start()
        }
        Log.i(TAG, "start: fifo server thread started fifo=$FIFO_PATH")
        return true
    }

    /**
     * 主循环:
     *   1) 检查 lock 文件, 不存在就退出
     *   2) 打开 FIFO 读一行 (阻塞, startexec 写就返回)
     *   3) fork /system/bin/sh 跑 <imagefs>/glibc-run.sh <args>
     *   4) 关 FIFO fd, 回到 1)
     */
    private fun runServerLoop() {
        while (running && File(LOCK_PATH).exists()) {
            // 临时打开 FIFO 读一行
            val cmd = try {
                FileInputStream(FIFO_PATH).use { fis ->
                    val buf = ByteArray(8192)
                    val sb = StringBuilder()
                    while (true) {
                        val n = fis.read(buf)
                        if (n <= 0) break  // EOF
                        for (i in 0 until n) {
                            val c = buf[i].toInt().toChar()
                            if (c == '\n') return@use sb.toString()
                            sb.append(c)
                        }
                    }
                    sb.toString()  // EOF 但有数据
                }
            } catch (e: Exception) {
                Log.w(TAG, "read FIFO 异常: ${e.message}")
                Thread.sleep(100)
                continue
            }

            val trimmed = cmd.trim()
            if (trimmed.isEmpty()) continue

            Log.i(TAG, "[server] cmd=[$trimmed]")
            try {
                File(LOG_PATH).appendText("[${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}] exec: $trimmed\n")
            } catch (_: Exception) {
            }

            // 派发: Android 进程 fork /system/bin/sh 跑 glibc-run
            //
            // 替换 cmd 开头的 "glibc-run" 为绝对路径
            val resolvedCmd = if (trimmed.startsWith("glibc-run ")) {
                "/system/bin/sh '$GLIBC_RUN' " + trimmed.removePrefix("glibc-run ")
            } else if (trimmed == "glibc-run") {
                "/system/bin/sh '$GLIBC_RUN'"
            } else {
                // 不是以 glibc-run 开头, 透传 (用户可能想跑其他)
                "/system/bin/sh -c '$trimmed'"
            }

            Log.i(TAG, "[server] resolved_cmd=[$resolvedCmd]")
            try {
                val proc = ProcessBuilder("sh", "-c", resolvedCmd)
                    .directory(File("/data/data/a.io.github.ewt45.winemulator/cache/tmp"))
                    .redirectErrorStream(true)
                proc.environment().clear()
                // box64+wine 需要 DISPLAY=:13
                proc.environment()["DISPLAY"] = ":13"
                proc.environment()["TMPDIR"] = "/data/data/a.io.github.ewt45.winemulator/cache/tmp"
                proc.environment()["XDG_RUNTIME_DIR"] = "/data/data/a.io.github.ewt45.winemulator/cache/tmp"
                val p = proc.start()
                // 后台线程 pump 输出到 logcat
                Thread {
                    try {
                        p.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { Log.i(TAG, "[glibc] $it") }
                        }
                    } catch (_: Exception) {
                    }
                }.start()
            } catch (e: Exception) {
                Log.e(TAG, "[server] 派发失败: ${e.message}", e)
            }
        }
        Log.i(TAG, "runServerLoop: 退出 (lock 不存在或 running=false)")
    }

    /**
     * 停 server:
     *   1) 删 lock 文件 → 主循环退出
     *   2) 清 fifo + lock 残留
     */
    fun stop() {
        Log.i(TAG, "stop: 停 fifo server")
        running = false
        try { File(LOCK_PATH).delete() } catch (_: Exception) {}
        // 等线程退出 (最多 2 秒)
        try { serverThread?.join(2000) } catch (_: Exception) {}
        serverThread = null
        try { File(FIFO_PATH).delete() } catch (_: Exception) {}
        try { File(LOCK_PATH).delete() } catch (_: Exception) {}
    }

    fun isAlive(): Boolean = running
}
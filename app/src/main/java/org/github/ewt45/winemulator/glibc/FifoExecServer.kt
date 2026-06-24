package org.github.ewt45.winemulator.glibc

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.github.ewt45.winemulator.Consts
import java.io.File

/**
 * Android 端跑的 FIFO exec server。
 *
 * 角色 (用户原话 v5.4):
 *   "安卓 app 创建 FIFO 管道和锁文件并启动 fifo 服务"
 *   "或者运行 proot 的时候加入 fifo_exec_server 的启动"
 *   "使 proot 能够正确管理 /data/data/.../files/imagefs 的 glibc 环境"
 *   "然后在 proot 里使用 startexec 定义 FIFO 管道路径
 *    和将命令参数拼接后写入 FIFO"
 *   "echo "$*" > "$FIFO""
 *
 * 实现: linboxapp (Android 进程) fork /system/bin/sh 跑 fifo_exec_server.sh。
 *   - server 创建 <tmpDir>/.exec.fifo + <tmpDir>/.exec-lock
 *   - server 监听 FIFO, 收到一行命令后 fork /system/bin/sh 跑
 *     <imagefs>/usr/local/bin/glibc-run.sh <args>
 *   - 因为 server 跑在 Android 进程, 能直接 exec imagefs 里的
 *     glibc 二进制 (编译路径是 /data/data/.../files/imagefs)
 *
 * proot 这边:
 *   - proot 启时 --bind=tmpDir:/tmp, 所以 proot 内 /tmp 就是 host tmpDir,
 *     跟 server 创建的 FIFO 同目录
 *   - xfce4 terminal 跑 startexec, 把 "glibc-run <args>" 写到
 *     /tmp/.exec.fifo (startexec 也知道 server 的绝对路径)
 *   - server 收到后, Android 进程直接 fork glibc-run.sh
 *
 * 生命周期:
 *   - [start] 在 MainEmuActivity.startEmu() 里、proot 启之前调用
 *   - [stop] 在 onDestroy 里调用: 删 lock 文件让 server 主循环退出
 */
class FifoExecServer {
    companion object {
        private const val TAG = "FifoExecServer"

        /** Android 端 server 脚本路径 (Proot.installAsset 写到这) */
        const val SERVER_SCRIPT = "/data/data/a.io.github.ewt45.winemulator/cache/tmp/fifo_exec_server.sh"

        /** Android 端 FIFO 路径 (server 创建 + startexec 写) */
        const val FIFO_PATH = "/data/data/a.io.github.ewt45.winemulator/cache/tmp/.exec.fifo"

        /** Android 端 lock 文件路径 (server 创建, 删了 server 退出) */
        const val LOCK_PATH = "/data/data/a.io.github.ewt45.winemulator/cache/tmp/.exec-lock"

        /** 派发的 glibc-run 路径 (Android 进程能直接 exec) */
        const val GLIBC_RUN = "/data/data/a.io.github.ewt45.winemulator/files/imagefs/usr/local/bin/glibc-run.sh"
    }

    private var process: Process? = null
    private var pumpJob: Job? = null

    /**
     * 启 fifo server。
     *
     * @param scope 用来跑 stdout/stderr 泵的协程 scope
     * @return true = 启成功 (或已经在跑), false = 失败
     */
    fun start(scope: CoroutineScope): Boolean {
        if (process?.isAlive == true) {
            Log.i(TAG, "start: server 已经在跑, 不重复启")
            return true
        }
        val script = File(SERVER_SCRIPT)
        if (!script.exists()) {
            Log.e(TAG, "start: 找不到 server 脚本 $SERVER_SCRIPT (Proot.attachInternal 还没装?)")
            return false
        }
        // 启动前清掉残留 lock + fifo, 防止上次崩溃留下脏数据
        try {
            File(FIFO_PATH).delete()
            File(LOCK_PATH).delete()
        } catch (_: Exception) {
        }
        try {
            // chdir 到 tmpDir, server 脚本里所有相对路径都在这
            val pb = ProcessBuilder("/system/bin/sh", SERVER_SCRIPT)
                .directory(Consts.tmpDir)
                .redirectErrorStream(true)
            // 清空环境, server 脚本自己会 set 必要的环境变量
            pb.environment().clear()
            process = pb.start()
        } catch (e: Exception) {
            Log.e(TAG, "start: fork server 失败", e)
            return false
        }

        val pid = try {
            val f = process!!.javaClass.getDeclaredField("pid")
            f.isAccessible = true
            f.getInt(process!!)
        } catch (_: Exception) {
            -1
        }
        Log.i(TAG, "start: fifo server pid=$pid script=$SERVER_SCRIPT fifo=$FIFO_PATH")

        // 泵 stdout/stderr 到 logcat
        pumpJob = scope.launch(Dispatchers.IO) {
            try {
                process!!.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { Log.i(TAG, "[server] $it") }
                }
            } catch (e: Exception) {
                Log.w(TAG, "pump done: ${e.message}")
            }
        }
        return true
    }

    /**
     * 停 server:
     *   1) 删 lock 文件 → server 主循环 while [ -e LOCK ] 退出
     *   2) destroy 子进程兜底
     *   3) 清 fifo + lock 残留
     */
    fun stop() {
        Log.i(TAG, "stop: 停 fifo server")
        try {
            File(LOCK_PATH).delete()
        } catch (_: Exception) {
        }
        try {
            process?.destroy()
        } catch (_: Exception) {
        }
        pumpJob?.cancel()
        pumpJob = null
        process = null
        try {
            File(FIFO_PATH).delete()
            File(LOCK_PATH).delete()
        } catch (_: Exception) {
        }
    }

    /** server 是否在跑 (调试用) */
    fun isAlive(): Boolean = process?.isAlive == true
}
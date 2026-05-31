package org.github.ewt45.winemulator.emu

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.github.ewt45.winemulator.Consts
import org.github.ewt45.winemulator.Utils.getPid
import java.io.BufferedReader
import java.io.InputStreamReader

//linux内添加环境变量 PULSE_SERVER=tcp:127.0.0.1:4713
object Pulseaudio {
    private val TAG = "Pulseaudio"

    private fun buildProcess(): ProcessBuilder {
        return ProcessBuilder().apply {
            environment()["HOME"] = Consts.pulseHomeDir.absolutePath
            environment()["TMPDIR"] = Consts.tmpDir.absolutePath
            environment()["LD_LIBRARY_PATH"] = Consts.pulseDir.absolutePath
            //模块从sles改成aaudio就没这问题了
//                environment()["LD_PRELOAD"] = "/system/lib64/liblzma.so" //这也是三星的问题？
//                environment()["LD_PRELOAD"] = "/system/lib64/libskcodec.so"

        }
            .directory(Consts.pulseDir)
            .redirectErrorStream(true)
    }

    fun stop() {
        buildProcess().command("./pulseaudio", "--kill").start().waitFor()
        //TODO  删除残留.config文件夹和pulse-xxxx文件夹，防止pa_pid_file_create() failed.?

    }

    suspend fun start():Int = withContext(Dispatchers.IO) {
        stop()

        val process = buildProcess()
            .command("./pulseaudio --start --exit-idle-time=-1 -n -F ./pulseaudio.conf --daemonize=true".split(" "))
            .start()
        launch {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    Log.d(TAG, "pulseaudio输出 $line")
                }
            }
        }
        return@withContext process.getPid()
    }

    /**
     * pulseaudio直接发送信号不生效。pacmd suspend 1倒是可以
     */
    fun pause() {
        buildProcess()
            .command("./pacmd", "suspend", "1")
            .start()
    }

    fun resume() {
        buildProcess()
            .command("./pacmd", "suspend", "0")
            .start()
    }
}
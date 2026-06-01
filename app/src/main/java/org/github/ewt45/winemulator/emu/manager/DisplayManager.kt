package org.github.ewt45.winemulator.emu.manager

import android.system.Os
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.github.ewt45.winemulator.Consts
import org.github.ewt45.winemulator.MainEmuActivity
import org.github.ewt45.winemulator.MainEmuApplication
import org.github.ewt45.winemulator.emu.X11Loader
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.reflect.jvm.jvmName

class DisplayManager(scope: CoroutineScope, parent: EmuManager) : ManagerComponent(scope, parent)  {
    private val TAG = "DisplayManager"
    private val pid: Int = -1
    override suspend fun onCreate() {

    }


    private suspend fun unused() {
        Log.d(TAG, "onCreate: 启动xserver apkfilePath=${Consts.apkFilePath}")

        val cmdLine = "/system/bin/app_process -Xnoimage-dex2oat / --nice-name=\"xserver-termux-x11\" ${X11Loader::class.jvmName} :13"

        val process = withContext(Dispatchers.IO) {
            return@withContext ProcessBuilder()
                .command(cmdLine.split(" "))
                .apply {
                    environment()["CLASSPATH"] = Consts.apkFilePath +":" + environment()["CLASSPATH"]
                    environment()["XSERVER_APK_PATH"] = Consts.apkFilePath
                    environment()["TERMUX_X11_OVERRIDE_PACKAGE"] = MainEmuApplication.i.packageName
                    environment()["TMPDIR"] = Consts.tmpDir.absolutePath
                    environment()["XKB_CONFIG_ROOT"] = Consts.rootfsCurrDir.absolutePath + "/usr/share/X11/xkb"
                }
                .redirectErrorStream(true)
                .start()
        }

        MainEmuActivity.instance.waitForXStartedWithDialog()

        scope.launch {
            try {
                BufferedReader(InputStreamReader(process!!.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        Log.d(TAG, "tx11输出 $line")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
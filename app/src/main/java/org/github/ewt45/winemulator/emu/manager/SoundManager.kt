package org.github.ewt45.winemulator.emu.manager

import android.system.Os
import android.system.OsConstants.SIGSTOP
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.github.ewt45.winemulator.Utils
import org.github.ewt45.winemulator.emu.Pulseaudio
import kotlin.reflect.full.declaredMemberProperties

class SoundManager(scope: CoroutineScope, parent: EmuManager) : ManagerComponent(scope, parent) {
    private val TAG = "SoundManager"
    var pid: Int = -1
    override suspend fun onCreate() {
        pid = Pulseaudio.start()
        Log.d(TAG, "onCreate: 声音pid为$pid")
    }

    override fun onDestroy() {
        Pulseaudio.stop()
    }

    override fun onResume() {
//        process.pid()
        Pulseaudio.resume()
    }

    override fun onPause() {
        val a = 1

//        android.os.Process.sendSignal(pid, SIGSTOP)
        Pulseaudio.pause()
    }

}
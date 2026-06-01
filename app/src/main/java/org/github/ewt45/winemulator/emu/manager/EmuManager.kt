package org.github.ewt45.winemulator.emu.manager

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.github.ewt45.winemulator.MainEmuActivity
import org.github.ewt45.winemulator.emu.Pulseaudio
// TODO 要考虑到 用户会切换某个内容的实现
/**
 * 启动子组件时，在协程中同步依次执行，子组件应该保证函数返回时必要项已经启动，但长久运行的应该另起一个协程，否则永远返回不了了
 */
class EmuManager(private val scope: CoroutineScope) : DefaultLifecycleObserver {
    private val TAG = "EmuManager"
    val sound: SoundManager = SoundManager(scope, this)
    val display: DisplayManager = DisplayManager(scope, this)

    override fun onCreate(owner: LifecycleOwner) {
        scope.launch {
            display.onCreate()
            sound.onCreate()
            // TODO 应该先启动终端，然后启动显示和声音，最后运行 初始执行命令
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        display.onDestroy()
        sound.onDestroy()
    }

    override fun onResume(owner: LifecycleOwner) {
        display.onResume()
        sound.onResume()

    }

    override fun onPause(owner: LifecycleOwner) {
        display.onPause()
        sound.onPause()
    }




}

package org.github.ewt45.winemulator.emu.manager

import androidx.lifecycle.DefaultLifecycleObserver
import kotlinx.coroutines.CoroutineScope

abstract class ManagerComponent(
    val scope: CoroutineScope,
    val parent: EmuManager,
) {
    /**
     * 因为启动时可能一个依赖另一个， 所以函数返回时 必要项应该已经启动完成
     */
    abstract suspend fun onCreate()

    /**
     * destroy别用协程了吧，不然可能来不及执行完就退出了？
     */
    open fun onDestroy() {}

    open fun onResume() {}
    open fun onPause() {}
}




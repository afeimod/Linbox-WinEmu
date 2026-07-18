package org.github.ewt45.winemulator

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import org.github.ewt45.winemulator.glibcwine.GlibcImageFsInstaller
import org.github.ewt45.winemulator.glibcwine.GlibcWineInitializer


class MainEmuApplication:Application() {
    companion object {
        lateinit var  i:MainEmuApplication
        private const val TAG = "MainEmuApplication"
    }
    override fun onCreate() {
        super.onCreate()

        i = this
        Consts.init(this)

        // 后台初始化 glibc wine 运行时 (不阻塞主线程)
        // imagefs 安装可能耗时 (解压大文件), 在后台线程执行
        Thread {
            try {
                Log.i(TAG, "后台初始化 glibc wine 运行时...")
                GlibcWineInitializer.initialize(this) { ready ->
                    if (ready) {
                        Log.i(TAG, "glibc wine 运行时就绪")
                    } else {
                        Log.w(TAG, "glibc wine 运行时未就绪 (imagefs 可能需要手动安装)")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "glibc wine 初始化失败 (非致命): ${e.message}")
            }
        }.start()
    }
}

private val MainEmuApplication.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
/** 持久化数据。获取某个key的最新值可以通过Consts.Pref.xxx.get() */
val dataStore = MainEmuApplication.i.dataStore


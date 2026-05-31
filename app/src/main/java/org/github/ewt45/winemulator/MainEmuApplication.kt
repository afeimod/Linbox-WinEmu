package org.github.ewt45.winemulator

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore


class MainEmuApplication : Application() {
    companion object {
        lateinit var i: MainEmuApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        i = this
        // 初始化 Consts
        Consts.init(this)
    }
}

// 使用顶层懒加载的 dataStore，基于 Application 实例
// preferencesDataStore 是一个 Context 扩展属性
private val Context._dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 获取 dataStore 实例，用于持久化数据存储
 */
val dataStore: DataStore<Preferences>
    get() = MainEmuApplication.i._dataStore
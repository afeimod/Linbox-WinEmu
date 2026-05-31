package org.github.ewt45.winemulator

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore


class MainEmuApplication:Application() {
    companion object {
        lateinit var  i:MainEmuApplication
    }
    override fun onCreate() {
        super.onCreate()

        i = this
        Consts.init(this)
    }
}

private val MainEmuApplication.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
/** 持久化数据。获取某个key的最新值可以通过Consts.Pref.xxx.get()
 *  使用lazy确保在Application初始化完成后才访问dataStore */
val dataStore by lazy { MainEmuApplication.i.dataStore }


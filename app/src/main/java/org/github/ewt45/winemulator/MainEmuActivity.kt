package org.github.ewt45.winemulator

import a.io.github.ewt45.winemulator.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import com.termux.x11.MainActivity
import com.termux.x11.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.github.ewt45.winemulator.Consts.Pref.general_rootfs_lang
import org.github.ewt45.winemulator.Consts.Pref.proot_startup_cmd
import org.github.ewt45.winemulator.Utils.activityRecreate
import org.github.ewt45.winemulator.Utils.getX11ServicePid
import org.github.ewt45.winemulator.emu.X11Service
import org.github.ewt45.winemulator.emu.manager.EmuManager
import org.github.ewt45.winemulator.xenvironment.ImageFsEmuManager
import org.github.ewt45.winemulator.xenvironment.ImageFsInstaller
import org.github.ewt45.winemulator.terminal.SessionClientAImpl
import org.github.ewt45.winemulator.terminal.ViewClientImpl
import org.github.ewt45.winemulator.ui.Destination
import org.github.ewt45.winemulator.ui.MainScreen
import org.github.ewt45.winemulator.ui.theme.MainTheme
import org.github.ewt45.winemulator.viewmodel.MainViewModel
import org.github.ewt45.winemulator.viewmodel.PrepareViewModel
import org.github.ewt45.winemulator.viewmodel.SettingViewModel
import org.github.ewt45.winemulator.viewmodel.TerminalViewModel


class MainEmuActivity : MainActivity() {
    private val TAG = "MainEmuActivity"
    val mainViewModel: MainViewModel by viewModels()
    val terminalViewModel: TerminalViewModel by viewModels()
    val settingViewModel: SettingViewModel by viewModels()
    val prepareViewModel: PrepareViewModel by viewModels()
    private lateinit var startX11Intent: Intent
    private var emuStarted: Boolean = false
    val sessionClient: SessionClientAImpl = SessionClientAImpl(this)
    val viewClient: ViewClientImpl = ViewClientImpl(this, sessionClient)
    
    // ImageFs Wine管理器
    private var imageFsEmuManager: ImageFsEmuManager? = null
    private var imageFsWineStarted: Boolean = false

    companion object {
        val instance get() = getInstance() as MainEmuActivity
    }

    fun getPref(): Prefs = prefs

    init {
        Utils.Permissions.registerForActivityResult(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.activityRecreate = true
        Log.d(TAG, "进入onSaveInstanceState1, 保存数据")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (savedInstanceState?.activityRecreate == true) {
            Log.e(TAG, "进入onCreate 本次为重启。或许应该特殊处理。")
        }

        MainActivity.HOST_PKG_NAME = packageName
        startX11Intent = createStartX11Intent()
        super.onCreate(savedInstanceState)

        settingViewModel.initSharedPreferences(this)
        settingViewModel.syncX11SettingsToSharedPrefs()
        
        // 启动时检查并安装ImageFs（如果需要）
        ImageFsInstaller.installIfNeeded(this)

        prefs.displayResolutionMode.put("custom")
        runBlocking { prefs.displayResolutionCustom.put(Consts.Pref.general_resolution.get()) }
        prefs.showAdditionalKbd.put(false)
        runBlocking { prefs.fullscreen.put(Consts.Pref.x11_fullscreen.get()) }
        prefs.hideCutout.put(false)

        setContent {
            val themeMode by settingViewModel.themeState.collectAsState()
            val isDarkTheme = themeMode != 0

            MainTheme(darkTheme = isDarkTheme) {
                MainScreen(
                    tx11Content = { frm.also { (frm.parent as? ViewGroup)?.removeView(frm) } },
                    Destination.X11, mainViewModel, terminalViewModel, settingViewModel, prepareViewModel
                )
            }
        }

        lifecycleScope.launch {
            prepareViewModel.uiState.collect { state ->
                if (state.isPrepareFinished && !emuStarted) {
                    lifecycleScope.launch {
                        startEmu()
                    }
                }
            }
        }

        enableEdgeToEdge()
    }

    suspend fun startEmu() = withContext(Dispatchers.Default) {
        if (emuStarted) {
            Log.w(TAG, "prepareAndStart: emuStarted为true, 模拟器已经启动。不再执行逻辑")
            return@withContext
        }

        val selectedRootfs = Utils.Rootfs.getSelectedRootfs()!!
        Utils.Rootfs.makeCurrent(selectedRootfs)

        emuStarted = true

        runBlocking {
            val userName = settingViewModel.getCurrentLoginUser()
            terminalViewModel.updatePromptFromSettings(userName)
            Log.d(TAG, "startEmu: 已从设置获取用户名: $userName")
        }

        if (Consts.rootfsCurrXkbDir.exists()) {
            startService(startX11Intent)
            waitForXStartedWithDialog()
            terminalViewModel.runCommand("xset -display ${X11Service.DISPLAY_NUM} r rate 200 30 2>/dev/null || true")
        } else {
            mainViewModel.showConfirmDialog("rootfs下缺少xkb文件夹，x11不会启动。可以安装类似 ' libxkbcommon-x11 ' 的软件包来补全。")
        }

        terminalViewModel.startTerminal()
        withContext(Dispatchers.Main) {
            lifecycle.addObserver(EmuManager(lifecycleScope))
        }
        val LANG = general_rootfs_lang.get()
        val langBase = LANG.substringBefore('.')
        terminalViewModel.runCommand("""if ! locale -a | grep -qi "$langBase"; then locale-gen $LANG; fi; export LANG=$LANG""")
        proot_startup_cmd.get().takeIf { it.isNotBlank() }?.let {
            terminalViewModel.runCommand("$it &")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        terminalViewModel.stopTerminal()
        stopService(startX11Intent)
        android.os.Process.killProcess(getX11ServicePid())

        val notificationManager = getSystemService(NotificationManager::class.java)
        val mNotificationId = 7892
        for (notification in notificationManager.activeNotifications)
            if (notification.id == mNotificationId)
                notificationManager.cancel(mNotificationId)
    }

    suspend fun waitForXStarted() {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < 5000) {
            if (isConnected()) break
            else delay(200)
        }
    }

    suspend fun waitForXStartedWithDialog() {
        mainViewModel.showBlockDialog("xserver启动中") {
            waitForXStarted()
        }
    }

    override fun buildNotification(): Notification {
        val channelName = this.resources.getString(R.string.app_name)
        val channel = NotificationChannel(channelName, channelName, NotificationManager.IMPORTANCE_HIGH)
        channel.lockscreenVisibility = Notification.VISIBILITY_SECRET
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) channel.setAllowBubbles(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val builder: NotificationCompat.Builder =
            (NotificationCompat.Builder(this, channelName)).setContentTitle(channelName)
                .setSmallIcon(R.mipmap.ic_launcher).setContentText("模拟器正在运行")
                .setOngoing(true).setPriority(NotificationCompat.PRIORITY_MAX)
                .setSilent(true).setShowWhen(false)
        return builder.build()
    }

    private fun createStartX11Intent(): Intent {
        return Intent(this, X11Service::class.java).apply {
            putExtra("timestamp", System.currentTimeMillis())
        }
    }
    
    /**
     * 启动ImageFS Wine环境
     * 参考Winlator的XServerDisplayActivity启动逻辑
     */
    fun startImageFsWine() {
        Log.d(TAG, "startImageFsWine called")
        
        if (imageFsWineStarted) {
            Toast.makeText(this, "ImageFS Wine 已经在运行", Toast.LENGTH_SHORT).show()
            return
        }
        
        lifecycleScope.launch {
            try {
                // 检查ImageFs是否已安装
                if (!ImageFsInstaller.isImageFsValid(this@MainEmuActivity)) {
                    Log.d(TAG, "ImageFs not valid, installing...")
                    val result = mainViewModel.showBlockDialog("正在安装ImageFS系统文件...") {
                        ImageFsInstaller.installFromAssetsAsync(this@MainEmuActivity)
                    }
                    
                    if (!result.isSuccess || result.getOrNull() != true) {
                        Log.e(TAG, "Installation failed")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainEmuActivity, "ImageFS 安装失败", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }
                }
                
                Log.d(TAG, "Starting ImageFs Wine...")
                
                // 停止当前的proot模拟器（如果正在运行）
                if (emuStarted) {
                    terminalViewModel.stopTerminal()
                    stopService(startX11Intent)
                    android.os.Process.killProcess(getX11ServicePid())
                    emuStarted = false
                }
                
                // 创建并配置 ImageFsEmuManager - 使用 this@MainEmuActivity 作为 context
                imageFsEmuManager = ImageFsEmuManager(lifecycleScope, this@MainEmuActivity).apply {
                    wineVersion = "wine-8.22"
                    startCommand = "wine64 explorer /desktop=winlator,1280x720"
                    wow64Mode = true
                    onWineStarted = {
                        imageFsWineStarted = true
                        lifecycleScope.launch(Dispatchers.Main) {
                            Toast.makeText(this@MainEmuActivity, "ImageFS Wine 已启动", Toast.LENGTH_SHORT).show()
                        }
                    }
                    onWineStopped = { exitCode ->
                        imageFsWineStarted = false
                        lifecycleScope.launch(Dispatchers.Main) {
                            Toast.makeText(this@MainEmuActivity, "ImageFS Wine 已退出，退出码: $exitCode", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                
                Log.d(TAG, "ImageFsEmuManager created, adding lifecycle observer")
                // 在主线程添加 lifecycle observer
                lifecycle.addObserver(imageFsEmuManager!!)
                
                // 启动X11服务
                startService(startX11Intent)
                waitForXStartedWithDialog()
                
                Log.d(TAG, "X11 started, starting Wine")
                imageFsEmuManager?.startWine()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error starting ImageFs Wine: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainEmuActivity, "启动ImageFS Wine失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * 停止ImageFS Wine环境
     */
    fun stopImageFsWine() {
        imageFsEmuManager?.stopWine()
        if (imageFsEmuManager != null) {
            lifecycle.removeObserver(imageFsEmuManager!!)
        }
        imageFsEmuManager = null
        imageFsWineStarted = false
    }
}
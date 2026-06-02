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
import androidx.core.view.WindowCompat
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

    companion object {
        val instance get() = getInstance() as MainEmuActivity // val instance: MainEmuActivity by lazy { getInstance() as MainEmuActivity }
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

        //设置包名
        MainActivity.HOST_PKG_NAME = packageName
        startX11Intent = createStartX11Intent()

        // ★关键：必须在 super.onCreate 之前就写入 lorie_prefs，
        // 因为 AAR 的 MainActivity.onCreate 会立刻 new Prefs(this) 并读取这些值，
        // 之后 LorieView.onMeasure 会基于 displayResolutionMode 决定是否 setFixedSize。
        // 旧代码在 super.onCreate 之后才写 prefs，此时 LorieView 已经 fixed size 成 1280x720 居中显示了。
        val sharedPrefsEarly = getSharedPreferences("lorie_prefs", Context.MODE_PRIVATE)
        sharedPrefsEarly.edit().apply {
            // 用 scaled 模式 + adjustResolution=false，让 LorieView 直接按父布局尺寸铺满，
            // 不要再用 setFixedSize(1280,720) 把 X11 钉在屏幕中间一块
            putString("displayResolutionMode", "scaled")
            putString("displayResolutionCustom", "1280x720")
            putBoolean("adjustResolution", false)
            putBoolean("displayStretch", true)
            putInt("displayScale", 100)
            // 全屏显示，不要状态栏/导航栏遮挡
            putBoolean("fullscreen", true)
            putBoolean("hideCutout", true)
            // 不显示 termux 的额外按键栏（我们用自己实现的虚拟按键系统）
            putBoolean("showAdditionalKbd", false)
        }.apply()

        super.onCreate(savedInstanceState)

        // 初始化X11设置SharedPreferences同步
        settingViewModel.initSharedPreferences(this)

        // 启动时同步所有X11设置到SharedPreferences
        settingViewModel.syncX11SettingsToSharedPrefs()

        // ★全屏修复（重做版）：
        // 1. 反射拿到 AAR 里的 lorieView，把它从 frm 这个 FrameLayout 里找出来；
        // 2. 将 lorieView 从 frm 里 remove 出来并 saveInstanceState 保留；
        // 3. frm 本身从它的父 ViewGroup 里 removeView 出来交给 Compose；
        // 4. Compose 里不要在 frm 里查 LorieView，而是直接拿 lorieView 包装到 AndroidView 里，
        //    Modifier.fillMaxSize() 让 lorieView 拿到 1920x1080 真实屏幕尺寸，
        //    AAR 的 LorieView.onMeasure 看到 displayResolutionMode=scaled + displayScale=100
        //    会直接 setMeasuredDimension(全屏) 拉伸 X11 画面填满屏幕。
        var extractedLorieView: android.view.View? = null
        try {
            val lorieViewField = com.termux.x11.MainActivity::class.java.getDeclaredField("lorieView")
            lorieViewField.isAccessible = true
            val lv = lorieViewField.get(this) as? android.view.View
            if (lv != null) {
                // 重要：保持 ID 留下来，lorieView 内部 native 侧可能用 getId 查找自己
                // 1) 强制 lorieView 自己的 layoutParams 为 MATCH_PARENT
                val lvLp = lv.layoutParams
                if (lvLp != null) {
                    lvLp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    lvLp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    lv.layoutParams = lvLp
                }
                // 2) 它的父 View（按 AAR 布局就是 frm）也要 MATCH_PARENT（虽然下面会 remove 它）
                val parent = lv.parent as? android.view.ViewGroup
                if (parent != null) {
                    val pLp = parent.layoutParams
                    if (pLp != null) {
                        pLp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        pLp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        parent.layoutParams = pLp
                    }
                }
                // 3) 从父 View 拿出来，单独保留，等下交给 Compose
                (lv.parent as? android.view.ViewGroup)?.removeView(lv)
                extractedLorieView = lv

                // 4) 主动调 AAR 的 LorieView.reloadPreferences(prefs)，让 LorieView 重新读刚才改的 prefs
                //    （displayResolutionMode=scaled, displayScale=100, displayStretch=true），
                //    然后调 requestLayout 触发重测。AAR 的 onMeasure 会按 scaled 模式 measure
                //    而不是 fixed size。
                try {
                    val prefsField = com.termux.x11.MainActivity::class.java.getDeclaredField("prefs")
                    prefsField.isAccessible = true
                    val prefs = prefsField.get(this)
                    val reloadMethod = lv.javaClass.getDeclaredMethod("reloadPreferences", prefs.javaClass)
                    reloadMethod.isAccessible = true
                    reloadMethod.invoke(lv, prefs)
                    Log.d(TAG, "已调 LorieView.reloadPreferences(prefs)")
                } catch (e: Throwable) {
                    Log.w(TAG, "reloadPreferences 反射调失败: ${e.message}")
                }
                lv.requestLayout()
                lv.invalidate()
                Log.d(TAG, "lorieView 已从 frm 剥离,准备交给 Compose")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "提取 lorieView 失败: ${e.message}")
        }


//        //将composeView添加到原视图布局中
//        val composeView = ComposeView(this).apply {
//            id = R.id.compose_view
//            setContent {
//                MainTheme {
//                    MainScreen()
//                }
//            }
//        }
//        val frame = findViewById<FrameLayout>(com.termux.x11.R.id.frame)
//        frame.addView(composeView, FrameLayout.LayoutParams(-2, -2))

        // 将原视图放到compose中
        setContent {
            // 获取主题设置并应用
            val themeMode by settingViewModel.themeState.collectAsState()
            val isDarkTheme = themeMode != 0 // 0 = 跟随系统

            // edge-to-edge：让 LorieView 可以延伸到状态栏 / 导航栏后面，
            // 避免出现“中间一块，中间两边黑色边”的现象。
            // SideEffect 保证不会每次重组都重复调。
            androidx.compose.runtime.SideEffect {
                WindowCompat.setDecorFitsSystemWindows(window, false)
            }

            MainTheme(darkTheme = isDarkTheme) {
                MainScreen(
                    tx11Content = { ctx ->
                        // 优先级：1) 剥出来的 lorieView；2) 反射拿 frm 从中找 lorieView
                        extractedLorieView ?: (findFrmViaReflection()?.let { findLorieView(it) } ?: findFrmViaReflection()!!)
                    },
                    Destination.X11, mainViewModel, terminalViewModel, settingViewModel, prepareViewModel
                )
            }
        }

        // 在准备完成时自动启动模拟器
        enableEdgeToEdge()

        lifecycleScope.launch {
            // 监听准备状态变化
            prepareViewModel.uiState.collect { state ->
                if (state.isPrepareFinished && !emuStarted) {
                    // 准备完成且模拟器未启动，自动启动
                    lifecycleScope.launch {
                        startEmu()
                    }
                }
            }
        }

//            startEmu()
//
//            //尝试termux终端
    }

    suspend fun startEmu() = withContext(Dispatchers.Default) {
        if (emuStarted) {
            Log.w(TAG, "prepareAndStart: emuStarted为true, 模拟器已经启动。不再执行逻辑")
            return@withContext
        }
        // TODO 这里launch切换到IO协程会不会好一点？
//        lifecycleScope.launch {
//            Log.d(TAG, "prepareAndStart: 测试process输出？${Utils.readLinesProcessOutput(Runtime.getRuntime().exec(arrayOf("sh",
//                "-c",
//                "umask 0022 ; ls /storage/emulated/0",//sh -c 之后应该用一个字符串 不应再分割了
//                )))}")

        val selectedRootfs = Utils.Rootfs.getSelectedRootfs()!!
        //rootfs处理（目前绑定外部存储路径在Proot里执行）
        Utils.Rootfs.makeCurrent(selectedRootfs)

        emuStarted = true

        // 启动终端前，从设置中获取用户名并更新到TerminalViewModel
        // 使用runBlocking确保在startTerminal之前获取用户名
        runBlocking {
            val userName = settingViewModel.getCurrentLoginUser()
            terminalViewModel.updatePromptFromSettings(userName)
            Log.d(TAG, "startEmu: 已从设置获取用户名: $userName")
        }

        //启动xserver
        if (Consts.rootfsCurrXkbDir.exists()) {
            startService(startX11Intent)
            waitForXStartedWithDialog() // 等待x11启动完成
        } else {
            mainViewModel.showConfirmDialog("rootfs下缺少xkb文件夹，x11不会启动。可以安装类似 ' libxkbcommon-x11 ' 的软件包来补全。")
        }

        terminalViewModel.startTerminal()
        // TODO 全部移到emuManager后，改为在init添加观察者，但是onCreate不启动，而是在startEmu中手动启动
        //添加observer时会立刻发送一遍从头到现在的状态，所以onCreate会触发
        withContext(Dispatchers.Main) {
            lifecycle.addObserver(EmuManager(lifecycleScope))
        }
        val LANG = general_rootfs_lang.get()
        // 检查目标 locale 是否已生成，未生成则执行 locale-gen
        val langBase = LANG.substringBefore('.')  // "zh_CN.utf8" -> "zh_CN"
        terminalViewModel.runCommand("""if ! locale -a | grep -qi "$langBase"; then locale-gen $LANG; fi; export LANG=$LANG""")
        //这里还不能用state因为state第一次获取的是默认值而非datastore来的值
        proot_startup_cmd.get().takeIf { it.isNotBlank() }?.let {
            terminalViewModel.runCommand("$it &")
        }


//        }
    }

    override fun onDestroy() {
        super.onDestroy()
        terminalViewModel.stopTerminal()
        stopService(startX11Intent)
        // FIXME 目前release构建 finish 无法结束 service 进程 导致下次启动 xserver启动失败。需要手动强制结束进程
        android.os.Process.killProcess(getX11ServicePid())

        // 删除通知 从onPause改到onDestroy
        val notificationManager = getSystemService(NotificationManager::class.java)
        val mNotificationId = 7892
        for (notification in notificationManager.activeNotifications)
            if (notification.id == mNotificationId)
                notificationManager.cancel(mNotificationId)
    }

    /**
     * 等待xserver启动完成。最多等待5秒
     */
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
//                .setContentIntent(PendingIntent.getActivity(this, 0, Intent.makeMainActivity(componentName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        //.setColor(-10453621)
        return builder.build()
    }

    /**
     * 创建一个intent用于启动X11Service. 在intent放入数据：
     * timestamp：时间戳
     *
     */
    private fun createStartX11Intent(): Intent {
        return Intent(this, X11Service::class.java).apply {
            putExtra("timestamp", System.currentTimeMillis())
        }
    }

    /**
     * 反射拿到 AAR 里的 frm（FrameLayout 字段，package-private / private）。
     * 用于不直接依赖 AAR 源代码，避免编译期不兼容。
     */
    private fun findFrmViaReflection(): android.view.View? {
        return try {
            val f = com.termux.x11.MainActivity::class.java.getDeclaredField("frm")
            f.isAccessible = true
            f.get(this) as? android.view.View
        } catch (e: Throwable) {
            Log.w(TAG, "反射拿 frm 失败: ${e.message}")
            null
        }
    }

    /**
     * 在 root 这棵 ViewTree 里找出 lorieView（AAR 布局里 R.id.lorieView 的实例）。
     * 优先用 id 匹配，找不到时按类名 fallback。
     */
    private fun findLorieView(root: android.view.View): android.view.View? {
        if (root.javaClass.name == "com.termux.x11.LorieView") return root
        val id = try {
            resources.getIdentifier("lorieView", "id", com.termux.x11.MainActivity.HOST_PKG_NAME)
        } catch (_: Throwable) { android.view.View.NO_ID }
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                if (id != android.view.View.NO_ID && child.id == id) return child
                val found = findLorieView(child)
                if (found != null) return found
            }
        }
        return null
    }
}

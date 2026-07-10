package org.github.ewt45.winemulator.ui

import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.preference.PreferenceManager
import com.termux.x11.input.InputStub
import com.termux.x11.input.RenderData
import org.github.ewt45.winemulator.MainEmuActivity
import org.github.ewt45.winemulator.Utils.Ui.snapToNearestEdgeHalfway
import org.github.ewt45.winemulator.inputcontrols.InputControlsManager
import org.github.ewt45.winemulator.inputcontrols.InputControlsView
import org.github.ewt45.winemulator.inputcontrols.X11InputSender
import org.github.ewt45.winemulator.inputcontrols.InputEventHandler
import org.github.ewt45.winemulator.Consts
import org.github.ewt45.winemulator.viewmodel.SettingViewModel
import kotlinx.coroutines.delay

/**
 * X11 Screen composable that displays X11 content with virtual controls overlay
 *
 * This screen includes:
 * - X11 rendering content from LorieView
 * - Touch event forwarding for mouse/touchpad control
 * - Virtual controls overlay
 * - Expandable floating menu with independent popup windows for:
 *   - General settings (container language, shared folders, rootfs management)
 *   - Virtual keys settings (enable/disable, profile selection, layout editing)
 *   - X11 display settings (resolution, touch mode, orientation, scale)
 *
 * Input events from virtual controls are routed through the X11InputSender
 * to the X11 session via the LorieView JNI bridge.
 * 
 * Touch events on the screen are also converted to mouse events for X11 control.
 */
@Composable
fun X11Screen(
    x11Content: (Context) -> View,
    onNavigateToOthers: (Destination) -> Unit,
    onLorieViewReady: ((InputStub) -> Unit)? = null,
    settingVm: SettingViewModel? = null
) {
    val context = LocalContext.current
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    val x11InputSender = remember {
        X11InputSender().apply {
            // 从 SharedPreferences 同步读 hardwareKbdScancodesWorkaround 开关 (默认 true).
            // SettingViewModel.onChangeHardwareKbdScancodesWorkaround 会同步写 SharedPreferences,
            // syncX11SettingsToSharedPrefs 也会在启动时同步 DataStore → SharedPreferences,
            // 所以这里同步读 SharedPreferences 拿到的是最新值. 万一没写, Consts.Pref 的 default
            // 就是 true, 不会拿到错误的 false.
            try {
                hardwareKbdScancodesWorkaround = prefs.getBoolean(
                    Consts.Pref.inputcontrols_hardware_kbd_scancodes_workaround.key.name,
                    Consts.Pref.inputcontrols_hardware_kbd_scancodes_workaround.default
                )
            } catch (t: Throwable) {
                Log.e("X11Screen", "读取 hardwareKbdScancodesWorkaround pref 失败, 用默认值 true", t)
            }
        }
    }
    val renderData = remember { RenderData() }
    val manager = remember { InputControlsManager(context) }

    // 状态变量
    var currentProfileId by remember { mutableStateOf(prefs.getInt(InputControlsFragment.SELECTED_PROFILE_ID, 0)) }
    var showTouchscreenControls by remember { mutableStateOf(prefs.getBoolean("show_touchscreen_controls", false)) }

    // 轮询监听 SharedPreferences 变化（后备同步机制）
    LaunchedEffect(Unit) {
        while (true) {
            val newShowControls = prefs.getBoolean("show_touchscreen_controls", false)
            val newProfileId = prefs.getInt(InputControlsFragment.SELECTED_PROFILE_ID, 0)
            // 同步 hardwareKbdScancodesWorkaround pref (设置界面里改了之后能立即生效)
            val newHwKbdWorkaround = prefs.getBoolean(
                Consts.Pref.inputcontrols_hardware_kbd_scancodes_workaround.key.name,
                Consts.Pref.inputcontrols_hardware_kbd_scancodes_workaround.default
            )

            if (newShowControls != showTouchscreenControls) {
                Log.d("X11Screen", "showTouchscreenControls changed: $showTouchscreenControls -> $newShowControls")
                showTouchscreenControls = newShowControls
            }
            if (newProfileId != currentProfileId) {
                Log.d("X11Screen", "currentProfileId changed: $currentProfileId -> $newProfileId")
                currentProfileId = newProfileId
            }
            if (newHwKbdWorkaround != x11InputSender.hardwareKbdScancodesWorkaround) {
                Log.d("X11Screen", "hardwareKbdScancodesWorkaround changed: ${x11InputSender.hardwareKbdScancodesWorkaround} -> $newHwKbdWorkaround")
                x11InputSender.hardwareKbdScancodesWorkaround = newHwKbdWorkaround
            }
            delay(300)
        }
    }

    // InputEventHandler 使用 X11InputSender
    val inputEventHandler = remember {
        object : InputEventHandler {
            override fun onKeyEvent(keycode: Int, isDown: Boolean) {
                x11InputSender.sendEvdevKeyEvent(keycode, isDown)
            }
            override fun onPointerMove(dx: Int, dy: Int) {
                x11InputSender.sendMouseMotionEvent(dx, dy)
            }
            override fun onPointerButton(button: Int, isDown: Boolean) {
                x11InputSender.sendMouseButtonEvent(button, isDown)
            }
        }
    }

    // 只创建一次 InputControlsView，避免重建
    val inputControlsView = remember {
        InputControlsView(context).apply {
            this.setEditMode(false)
            this.inputEventHandler = inputEventHandler
            // 初始化时同步虚拟按键开关, 避免在 Compose 还没跑 LaunchedEffect 前
            // InputControlsView 默认 isClickable=true 而误吃触摸事件
            // 这里 readBack 用 prefs 里的最新值, 跟 LaunchedEffect 用同一个数据源
            val initialShow = prefs.getBoolean("show_touchscreen_controls", false)
            this.setShowTouchscreenControlsValue(initialShow)
        }
    }

    // 监听显示开关变化并实时更新视图
    LaunchedEffect(showTouchscreenControls) {
        // 用 setter 方法, 走到 InputControlsView 里完整的释放按键 + 调可点击状态逻辑
        inputControlsView.setShowTouchscreenControlsValue(showTouchscreenControls)
        Log.d("X11Screen", "Updated showTouchscreenControls: $showTouchscreenControls")
    }

    // 监听配置 ID 变化并实时更新视图
    LaunchedEffect(currentProfileId) {
        val newProfile = if (currentProfileId != 0) manager.getProfile(currentProfileId) else manager.getProfiles().firstOrNull()
        inputControlsView.setProfile(newProfile)
        Log.d("X11Screen", "Updated profile to: ${newProfile?.name}")
    }

    // 即时刷新函数（供悬浮弹窗回调使用）
    val refreshControlsImmediately: () -> Unit = {
        // 强制重新加载配置列表，确保能获取到新建的配置
        manager.forceReloadProfiles()

        val newShowControls = prefs.getBoolean("show_touchscreen_controls", false)
        val newProfileId = prefs.getInt(InputControlsFragment.SELECTED_PROFILE_ID, 0)

        var needRefresh = false
        if (newShowControls != showTouchscreenControls) {
            showTouchscreenControls = newShowControls
            needRefresh = true
        }
        if (newProfileId != currentProfileId) {
            currentProfileId = newProfileId
            needRefresh = true
        }

        // 无论状态是否变化，都立即刷新 InputControlsView
        // 这样可以确保新建配置后立即生效
        inputControlsView.setShowTouchscreenControlsValue(newShowControls)
        val newProfile = if (newProfileId != 0) manager.getProfile(newProfileId) else manager.getProfiles().firstOrNull()
        inputControlsView.setProfile(newProfile)
        Log.d("X11Screen", "refreshControlsImmediately: show=$newShowControls, profile=${newProfile?.name}")
    }

    Box(Modifier.fillMaxSize()) {
        // X11 渲染视图
        AndroidView(
            factory = { ctx ->
                val view = x11Content(ctx)
                try {
                    val lorieView = if (view is com.termux.x11.LorieView) {
                        view
                    } else {
                        findLorieView(view)
                    }
                    lorieView?.let {
                        x11InputSender.initialize(it)
                        // 在初始化后强制重置鼠标按钮状态，防止首次启动时鼠标卡在按下状态
                        x11InputSender.forceResetMouseButtons()
                        renderData.scale = android.graphics.PointF(1f, 1f)
                        x11InputSender.renderData = renderData
                        onLorieViewReady?.invoke(it)
                        Log.d("X11Screen", "X11InputSender initialized with LorieView")
                        
                        // 不再设置触摸监听器，因为 InputControlsView 会处理触摸事件
                        // 触摸监听器会导致重复发送鼠标按钮事件
                    } ?: run {
                        Log.e("X11Screen", "Could not find LorieView in X11 content")
                    }
                } catch (e: Exception) {
                    Log.e("X11Screen", "Failed to initialize X11InputSender: ${e.message}", e)
                }
                view
            },
            modifier = Modifier.fillMaxSize()
        )

        // 虚拟按键覆盖层
        AndroidView(
            factory = { inputControlsView },
            modifier = Modifier.fillMaxSize()
        )

        val floatingPopupState = remember { FloatingPopupState() }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            ExpandableFloatingMenu(
                parentWidth = constraints.maxWidth.toFloat(),
                parentHeight = constraints.maxHeight.toFloat(),
                onMainMenuClick = { onNavigateToOthers(Destination.Home) },
                onGeneralSettingsClick = { floatingPopupState.showPopup(FloatingPopupType.GENERAL_SETTINGS) },
                onVirtualKeysClick = { floatingPopupState.showPopup(FloatingPopupType.VIRTUAL_KEYS_SETTINGS) },
                onX11SettingsClick = { floatingPopupState.showPopup(FloatingPopupType.X11_SETTINGS) },
                onMinimizeClick = {
                    // 把 compose_view 缩成屏幕边缘的小图标，等同于 Home 顶栏右上角最小化按钮
                    val activity = LocalContext.current as? MainEmuActivity
                    val view = activity?.findViewById<View>(R.id.compose_view) ?: return@ExpandableFloatingMenu
                    val miniIconPx = (Consts.Ui.minimizedIconSize * LocalDensity.current.density).toInt()
                    view.apply {
                        val lp = layoutParams as android.view.ViewGroup.MarginLayoutParams
                        lp.height = miniIconPx
                        lp.width = miniIconPx
                        lp.leftMargin = 0
                        lp.topMargin = 100
                        lp.rightMargin = 0
                        lp.bottomMargin = 0
                        requestLayout()
                        view.post { view.snapToNearestEdgeHalfway() }
                    }
                },
            )
        }

        settingVm?.let { vm ->
            FloatingSettingsPopups(
                popupState = floatingPopupState,
                settingVm = vm,
                onVirtualKeysSettingsChanged = refreshControlsImmediately
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            x11InputSender.release()
        }
    }
}

/**
 * Handle touch events and convert them to mouse events for X11 control
 * 
 * This implements touchpad-style control:
 * - Touch down: Send left mouse button press
 * - Touch move: Send relative mouse movement
 * - Touch up: Send left mouse button release
 * 
 * @param event The touch motion event
 * @param inputSender The X11 input sender to send events to
 * @param lastTouchX Last recorded touch X position
 * @param lastTouchY Last recorded touch Y position
 * @param isFirstTouch Whether this is the first touch (for initialization)
 * @param leftButtonDown Whether the left button is currently pressed
 * @param updateLastTouch Lambda to update last touch position
 * @param updateFirstTouch Lambda to update first touch flag
 * @param updateLeftButton Lambda to update left button state
 */
private fun handleX11TouchEvent(
    event: MotionEvent,
    inputSender: X11InputSender,
    getLastTouch: () -> Pair<Float, Float>,
    updateLastTouch: (Float, Float) -> Unit,
    getIsFirstTouch: () -> Boolean,
    updateFirstTouch: (Boolean) -> Unit,
    getLeftButtonDown: () -> Boolean,
    updateLeftButton: (Boolean) -> Unit
) {
    // 检查输入是否已初始化
    if (!inputSender.isInitialized) {
        Log.w("X11Touch", "InputSender not initialized, skipping touch event")
        return
    }

    val lastTouch = getLastTouch()
    val isFirstTouch = getIsFirstTouch()
    val leftButtonDown = getLeftButtonDown()

    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
            val actionIndex = event.actionIndex
            val x = event.getX(actionIndex)
            val y = event.getY(actionIndex)
            
            Log.d("X11Touch", "Touch down at ($x, $y)")
            
            // 记录初始触摸位置
            updateLastTouch(x, y)
            updateFirstTouch(false)
            
            // 如果左键未按下，发送左键按下事件
            if (!leftButtonDown) {
                inputSender.sendMouseButtonEvent(1, true)
                updateLeftButton(true)
                Log.d("X11Touch", "Left button pressed")
            }
        }
        
        MotionEvent.ACTION_MOVE -> {
            // 处理所有指针的移动
            for (i in 0 until event.pointerCount) {
                val pointerId = event.getPointerId(i)
                val x = event.getX(i)
                val y = event.getY(i)
                
                // 只处理主指针（actionIndex 对应的指针）
                if (pointerId == event.getPointerId(event.actionIndex)) {
                    // 计算相对移动
                    val dx = x - lastTouch.first
                    val dy = y - lastTouch.second
                    
                    // 只有当移动超过阈值时才发送移动事件（防止抖动）
                    if (kotlin.math.abs(dx) > 2 || kotlin.math.abs(dy) > 2) {
                        // 发送鼠标移动事件
                        val intDx = kotlin.math.round(dx).toInt()
                        val intDy = kotlin.math.round(dy).toInt()
                        inputSender.sendMouseMotionEvent(intDx, intDy)
                        
                        // 更新位置
                        updateLastTouch(x, y)
                        Log.v("X11Touch", "Move: dx=$intDx, dy=$intDy")
                    }
                    break
                }
            }
        }
        
        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
            Log.d("X11Touch", "Touch up")
            
            // 如果左键处于按下状态，发送左键释放事件
            if (leftButtonDown) {
                inputSender.sendMouseButtonEvent(1, false)
                updateLeftButton(false)
                Log.d("X11Touch", "Left button released")
            }
            
            updateFirstTouch(true)
        }
    }
}

/**
 * Recursively find LorieView in the view hierarchy
 */
private fun findLorieView(view: View): com.termux.x11.LorieView? {
    if (view is com.termux.x11.LorieView) {
        return view
    }
    if (view is android.view.ViewGroup) {
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            val result = findLorieView(child)
            if (result != null) {
                return result
            }
        }
    }
    return null
}

@Preview(widthDp = 300, heightDp = 500)
@Composable
fun X11ScreenPreview() {
    X11Screen(
        x11Content = { ctx -> FrameLayout(ctx).apply { setBackgroundColor(android.graphics.Color.GRAY) } },
        {},
        settingVm = null
    )
}
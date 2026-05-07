package org.github.ewt45.winemulator.ui

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.preference.PreferenceManager
import com.termux.x11.input.InputStub
import com.termux.x11.input.RenderData
import org.github.ewt45.winemulator.inputcontrols.InputControlsManager
import org.github.ewt45.winemulator.inputcontrols.InputControlsView
import org.github.ewt45.winemulator.inputcontrols.X11InputSender
import org.github.ewt45.winemulator.inputcontrols.InputEventHandler
import org.github.ewt45.winemulator.viewmodel.SettingViewModel
import kotlinx.coroutines.delay

/**
 * X11 Screen composable that displays X11 content with virtual controls overlay
 *
 * This screen includes:
 * - X11 rendering content from LorieView
 * - Virtual controls overlay
 * - Expandable floating menu with independent popup windows for:
 *   - General settings (container language, shared folders, rootfs management)
 *   - Virtual keys settings (enable/disable, profile selection, layout editing)
 *   - X11 display settings (resolution, touch mode, orientation, scale)
 *
 * Input events from virtual controls are routed through the X11InputSender
 * to the X11 session via the LorieView JNI bridge.
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

    val x11InputSender = remember { X11InputSender() }
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

            if (newShowControls != showTouchscreenControls) {
                Log.d("X11Screen", "showTouchscreenControls changed: $showTouchscreenControls -> $newShowControls")
                showTouchscreenControls = newShowControls
            }
            if (newProfileId != currentProfileId) {
                Log.d("X11Screen", "currentProfileId changed: $currentProfileId -> $newProfileId")
                currentProfileId = newProfileId
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
        }
    }

    // 监听显示开关变化并实时更新视图
    LaunchedEffect(showTouchscreenControls) {
        inputControlsView.showTouchscreenControls = showTouchscreenControls
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
        inputControlsView.showTouchscreenControls = newShowControls
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
                        renderData.scale = android.graphics.PointF(1f, 1f)
                        x11InputSender.renderData = renderData
                        onLorieViewReady?.invoke(it)
                        Log.d("X11Screen", "X11InputSender initialized with LorieView")
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
                onMainMenuClick = { onNavigateToOthers(Destination.Terminal) },
                onGeneralSettingsClick = { floatingPopupState.showPopup(FloatingPopupType.GENERAL_SETTINGS) },
                onVirtualKeysClick = { floatingPopupState.showPopup(FloatingPopupType.VIRTUAL_KEYS_SETTINGS) },
                onX11SettingsClick = { floatingPopupState.showPopup(FloatingPopupType.X11_SETTINGS) }
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
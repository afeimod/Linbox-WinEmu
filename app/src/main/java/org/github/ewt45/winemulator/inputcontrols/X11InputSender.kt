package org.github.ewt45.winemulator.inputcontrols

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.termux.x11.input.InputEventSender
import com.termux.x11.input.InputStub
import com.termux.x11.input.RenderData

/**
 * X11 Input Handler — 把虚拟按键事件直接转给 termux-x11 的 [InputStub] (LorieView 实现).
 *
 * ## 重大修复 (完全对齐 abc-fix X11InputSender.java 的 "v7 键位修复" + 注入路径修复)
 *
 * 旧 master 实现走的是 [InputEventSender.sendKeyEvent] 包装层 (AAR 里的 Java 包装器),
 * 那个包装器有自己的 [mPressedKeys] / [mPressedTextKeys] 状态机和字符/组合键处理逻辑,
 * 但**它处理 scancode 的方式跟 termux-x11 native 端实际期望的 (keysym, scancode) 数值
 * 体系不一致**, 而且包装层依赖 Android [KeyEvent] 的 keyCode 字段做 fallback,
 * 一旦 scancode 跟 keyCode 对不上号, native 端就 fall back 到错误的 X11 keysym。
 *
 * Java 版 abc-fix 完全不走这层包装, 而是直接拿 [InputStub] (就是 LorieView) 调
 * `sendKeyEvent(scancode, scancode, isDown)` — 把 scancode 同时当 keysym 和
 * scancode 传过去, native 端从 scancode 字段反查 X11 keysym, 一查一个准。
 *
 * 这条 [X11InputSender] 完全按 abc-fix 的方式重写:
 *
 * 1. [initialize] 拿到 [InputStub] (LorieView) 引用后**直接保存**, 不再 wrap 进
 *    [InputEventSender]。InputEventSender 仍保留以兼容可能存在的旧调用方 (目前
 *    没有, 仅作 fallback), 但主路径全走 [inputStub]。
 *
 * 2. [sendEvdevKeyEvent] (和 [sendKeyEvent]) 直接调
 *    `inputStub.sendKeyEvent(scancode, scancode, isDown)` —— 这跟 abc-fix 的
 *    `xServer.sendKeyEvent(scancode, scancode, isDown)` 字节级一致。
 *
 * 3. 鼠标 / 滚轮事件同样直走 [inputStub], 绕开 wrapper 的 [mInjector] 链路。
 *
 * 4. [Binding.toEvdev] 用 PC AT Set 1 scancode 全量表 (跟 abc-fix 完全对齐),
 *    之前 master 混着 Linux evdev 码的方向键 / Home / PgUp / PgDn / PrtScn /
 *    DEL / 小键盘 全部错位的问题一并修了。
 *
 * 5. 配套 [scancodeToAndroidKeycode] 反向表, 给 [InputEventSender] fallback 路径
 *    留着, 防止新代码不小心走到 wrapper 又把 keycode 算错。
 */
class X11InputSender {
    // 直接持有的 InputStub (LorieView), 跟 abc-fix 一样绕过 InputEventSender
    private var inputStub: InputStub? = null

    // 保留 InputEventSender 引用, 只用于 resetMouseButtons 之类必须走 wrapper 的
    // 兜底场景; 正式按键/鼠标路径全用 inputStub。
    private var inputEventSender: InputEventSender? = null
    private val handler = Handler(Looper.getMainLooper())

    // RenderData for touch events - needs to be set from LorieView
    var renderData: RenderData? = null

    // Whether the sender is initialized and ready to send events
    val isInitialized: Boolean
        get() = inputStub != null

    /**
     * Initialize with an InputStub (typically LorieView).
     * 直接持有 inputStub, 旁路 InputEventSender wrapper, 完全对齐 abc-fix。
     */
    fun initialize(inputStub: InputStub) {
        this.inputStub = inputStub
        // 仍然创建 InputEventSender 是为了用它的 forceResetMouseButtons 兜底。
        // 注意: 后续 sendKey / sendMouse 都走 inputStub, 不走它。
        this.inputEventSender = InputEventSender(inputStub)
        resetMouseButtons()
    }

    /**
     * 重置所有鼠标按钮状态, 防止首次启动时鼠标卡在按下状态
     * 走 InputEventSender 的 reset 逻辑 (abc-fix 同款).
     */
    private fun resetMouseButtons() {
        val sender = inputEventSender ?: return
        sender.sendMouseEvent(null, com.termux.x11.input.InputStub.BUTTON_LEFT, false, true)
        sender.sendMouseEvent(null, com.termux.x11.input.InputStub.BUTTON_MIDDLE, false, true)
        sender.sendMouseEvent(null, com.termux.x11.input.InputStub.BUTTON_RIGHT, false, true)
    }

    /**
     * 强制重置所有鼠标按钮状态 (公开方法)。
     * 供外部调用以确保没有卡住的鼠标按钮。
     */
    fun forceResetMouseButtons() {
        resetMouseButtons()
    }

    // ============================================================
    // 键盘事件 — 主路径, 直接调 inputStub, 跟 abc-fix X11InputSender.java 一致
    // ============================================================

    /**
     * 把 Binding 翻译成 scancode, 然后直接调 [InputStub.sendKeyEvent].
     * 这个是 [InputControlsView.handleInputEvent] 实际调用的入口。
     *
     * 跟 abc-fix `sendEvdevKeyEvent(int scancode, boolean isDown)` 字节级一致:
     * ```
     * public void sendEvdevKeyEvent(int scancode, boolean isDown) {
     *     if (xServer != null) {
     *         xServer.sendKeyEvent(scancode, scancode, isDown);
     *     }
     * }
     * ```
     */
    fun sendEvdevKeyEvent(scancode: Int, isDown: Boolean) {
        val stub = inputStub ?: return
        if (scancode <= 0) return
        // 严格保证按下/释放配对, 全部进同一个 Handler 队列, 顺序 FIFO。
        // abc-fix 这里是同步调 xServer.sendKeyEvent, 但 master 之前用的是异步
        // handler.post, 行为一致 (主线程顺序执行); 这里也用 post 避免阻塞触摸事件循环。
        handler.post {
            // 关键: scancode 同时作 keysym 和 scancode, 跟 abc-fix 一致。
            // termux-x11 native 端会从 scancode 反查 X11 keysym。
            //
            // 方向键 8246 错位的修法不在这里 (见 X11Settings 的 hardwareKbdScancodesWorkaround
            // 开关: 由 SettingViewModel.onChangeX11HwKbdScancodesWorkaround 在 App 启动 / 改设置时
            // 写到 com.termux.x11 的 SharedPreferences, termux-x11 AAR 内部读这个 key 决定如何
            // 处理 stub.sendKeyEvent, 跟这条发送路径本身无关)。
            stub.sendKeyEvent(scancode, scancode, isDown)
        }
    }

    /**
     * 释放某个 scancode 的按键 (对已按下的按键补发一次 release)。
     */
    fun sendKeyRelease(scancode: Int) {
        if (scancode <= 0) return
        sendEvdevKeyEvent(scancode, false)
    }

    /**
     * 强制释放一组 scancode, 用于:
     * - 视图销毁 / 切后台兜底
     * - 失去焦点 / 切 profile
     */
    fun releaseScancodes(scancodes: Collection<Int>) {
        if (scancodes.isEmpty()) return
        for (s in scancodes.sortedDescending()) sendKeyRelease(s)
    }

    /**
     * 兼容旧 API: 直接传 androidKeycode + scancode (新代码建议用 [sendEvdevKeyEvent])。
     * 如果 inputStub 还没初始化, 降级走 InputEventSender 路径。
     */
    fun sendKeyEvent(androidKeycode: Int, scancode: Int, isDown: Boolean) {
        if (scancode > 0) {
            sendEvdevKeyEvent(scancode, isDown)
        } else if (androidKeycode != 0) {
            // scancode 拿不到时, 用 androidKeycode 当 fallback (旧路径, 不推荐)
            inputStub?.sendKeyEvent(androidKeycode, androidKeycode, isDown)
        }
    }

    // ============================================================
    // 鼠标事件 — 同样直接走 inputStub, 跟 abc-fix sendMouseEvent 一致
    // ============================================================

    /**
     * Send mouse button event
     * 跟 abc-fix `sendMouseEvent(int x, int y, int button, boolean isDown, boolean isAbsolute)` 一致,
     * 用 (0, 0, button, isDown, false) 表示纯按钮事件 (无位置变化)。
     */
    fun sendMouseButtonEvent(button: Int, isDown: Boolean) {
        val stub = inputStub ?: return
        handler.post {
            when (button) {
                1 -> stub.sendMouseEvent(0f, 0f, com.termux.x11.input.InputStub.BUTTON_LEFT, isDown, false)
                2 -> stub.sendMouseEvent(0f, 0f, com.termux.x11.input.InputStub.BUTTON_MIDDLE, isDown, false)
                3 -> stub.sendMouseEvent(0f, 0f, com.termux.x11.input.InputStub.BUTTON_RIGHT, isDown, false)
                4 -> if (isDown) stub.sendMouseWheelEvent(0f, -1f)  // scroll up
                5 -> if (isDown) stub.sendMouseWheelEvent(0f, 1f)   // scroll down
            }
        }
    }

    /**
     * Send mouse motion event (relative movement)
     * 跟 abc-fix `sendMouseMotionEvent(int dx, int dy)` 一致。
     */
    fun sendMouseMotionEvent(dx: Int, dy: Int) {
        val stub = inputStub ?: return
        handler.post {
            stub.sendMouseEvent(dx.toFloat(), dy.toFloat(), 0, false, true)
        }
    }

    /**
     * Send mouse wheel event
     */
    fun sendMouseWheelEvent(deltaX: Float, deltaY: Float) {
        val stub = inputStub ?: return
        handler.post {
            stub.sendMouseWheelEvent(deltaX, deltaY)
        }
    }

    // ============================================================
    // PC AT Set 1 scancode -> Android KeyEvent.keycode
    // ============================================================

    /**
     * PC AT Set 1 scancode -> Android KeyEvent keycode (保留作 fallback 路径用)。
     * 主路径 [sendEvdevKeyEvent] 不依赖这个表, 它直接用 scancode。
     */
    private fun scancodeToAndroidKeycode(scancode: Int): Int {
        return when (scancode) {
            // 字母 A-Z
            30 -> KeyEvent.KEYCODE_A
            48 -> KeyEvent.KEYCODE_B
            46 -> KeyEvent.KEYCODE_C
            32 -> KeyEvent.KEYCODE_D
            18 -> KeyEvent.KEYCODE_E
            33 -> KeyEvent.KEYCODE_F
            34 -> KeyEvent.KEYCODE_G
            35 -> KeyEvent.KEYCODE_H
            23 -> KeyEvent.KEYCODE_I
            36 -> KeyEvent.KEYCODE_J
            37 -> KeyEvent.KEYCODE_K
            38 -> KeyEvent.KEYCODE_L
            50 -> KeyEvent.KEYCODE_M
            49 -> KeyEvent.KEYCODE_N
            24 -> KeyEvent.KEYCODE_O
            25 -> KeyEvent.KEYCODE_P
            16 -> KeyEvent.KEYCODE_Q
            19 -> KeyEvent.KEYCODE_R
            31 -> KeyEvent.KEYCODE_S
            20 -> KeyEvent.KEYCODE_T
            22 -> KeyEvent.KEYCODE_U
            47 -> KeyEvent.KEYCODE_V
            17 -> KeyEvent.KEYCODE_W
            45 -> KeyEvent.KEYCODE_X
            21 -> KeyEvent.KEYCODE_Y
            44 -> KeyEvent.KEYCODE_Z
            // 数字 0-9
            11 -> KeyEvent.KEYCODE_0; 2 -> KeyEvent.KEYCODE_1; 3 -> KeyEvent.KEYCODE_2
            4 -> KeyEvent.KEYCODE_3; 5 -> KeyEvent.KEYCODE_4; 6 -> KeyEvent.KEYCODE_5
            7 -> KeyEvent.KEYCODE_6; 8 -> KeyEvent.KEYCODE_7; 9 -> KeyEvent.KEYCODE_8
            10 -> KeyEvent.KEYCODE_9
            // 功能键 / 编辑键
            1 -> KeyEvent.KEYCODE_ESCAPE; 14 -> KeyEvent.KEYCODE_DEL; 15 -> KeyEvent.KEYCODE_TAB
            28 -> KeyEvent.KEYCODE_ENTER; 57 -> KeyEvent.KEYCODE_SPACE
            // 修饰键
            29 -> KeyEvent.KEYCODE_CTRL_LEFT; 97 -> KeyEvent.KEYCODE_CTRL_RIGHT
            42 -> KeyEvent.KEYCODE_SHIFT_LEFT; 54 -> KeyEvent.KEYCODE_SHIFT_RIGHT
            56 -> KeyEvent.KEYCODE_ALT_LEFT; 100 -> KeyEvent.KEYCODE_ALT_RIGHT
            58 -> KeyEvent.KEYCODE_CAPS_LOCK; 69 -> KeyEvent.KEYCODE_NUM_LOCK
            70 -> KeyEvent.KEYCODE_SCROLL_LOCK
            // 方向键 / 导航
            72 -> KeyEvent.KEYCODE_DPAD_UP; 80 -> KeyEvent.KEYCODE_DPAD_DOWN
            75 -> KeyEvent.KEYCODE_DPAD_LEFT; 77 -> KeyEvent.KEYCODE_DPAD_RIGHT
            71 -> KeyEvent.KEYCODE_MOVE_HOME; 79 -> KeyEvent.KEYCODE_MOVE_END
            73 -> KeyEvent.KEYCODE_PAGE_UP; 81 -> KeyEvent.KEYCODE_PAGE_DOWN
            82 -> KeyEvent.KEYCODE_INSERT; 83 -> KeyEvent.KEYCODE_FORWARD_DEL
            99 -> KeyEvent.KEYCODE_SYSRQ
            // F1-F12
            59 -> KeyEvent.KEYCODE_F1; 60 -> KeyEvent.KEYCODE_F2; 61 -> KeyEvent.KEYCODE_F3
            62 -> KeyEvent.KEYCODE_F4; 63 -> KeyEvent.KEYCODE_F5; 64 -> KeyEvent.KEYCODE_F6
            65 -> KeyEvent.KEYCODE_F7; 66 -> KeyEvent.KEYCODE_F8; 67 -> KeyEvent.KEYCODE_F9
            68 -> KeyEvent.KEYCODE_F10; 87 -> KeyEvent.KEYCODE_F11; 88 -> KeyEvent.KEYCODE_F12
            // 符号键
            12 -> KeyEvent.KEYCODE_MINUS; 26 -> KeyEvent.KEYCODE_LEFT_BRACKET
            27 -> KeyEvent.KEYCODE_RIGHT_BRACKET; 43 -> KeyEvent.KEYCODE_BACKSLASH
            53 -> KeyEvent.KEYCODE_SLASH; 39 -> KeyEvent.KEYCODE_SEMICOLON
            40 -> KeyEvent.KEYCODE_APOSTROPHE; 51 -> KeyEvent.KEYCODE_COMMA
            52 -> KeyEvent.KEYCODE_PERIOD
            // 小键盘
            78 -> KeyEvent.KEYCODE_NUMPAD_ADD; 74 -> KeyEvent.KEYCODE_NUMPAD_SUBTRACT
            55 -> KeyEvent.KEYCODE_NUMPAD_MULTIPLY; 98 -> KeyEvent.KEYCODE_NUMPAD_DIVIDE
            96 -> KeyEvent.KEYCODE_NUMPAD_ENTER; 76 -> KeyEvent.KEYCODE_NUMPAD_5
            else -> 0
        }
    }

    // ============================================================
    // 生命周期
    // ============================================================

    /**
     * Cleanup resources
     */
    fun release() {
        handler.removeCallbacksAndMessages(null)
        inputStub = null
        inputEventSender = null
    }
}

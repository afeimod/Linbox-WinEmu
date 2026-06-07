package org.github.ewt45.winemulator.inputcontrols

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.termux.x11.input.InputEventSender
import com.termux.x11.input.InputStub
import com.termux.x11.input.InputStub.BUTTON_LEFT
import com.termux.x11.input.InputStub.BUTTON_MIDDLE
import com.termux.x11.input.InputStub.BUTTON_RIGHT
import com.termux.x11.input.RenderData

/**
 * X11 Input Handler using InputEventSender
 * 把虚拟按键事件转成 termux-x11 的 native InputStub 事件注入到 X server.
 *
 * 修复要点 (对齐 abc-fix X11InputSender.java 的 "v7 键位修复" 方案):
 * 1. 旧实现 [sendKeyEvent] 调的是 [KeyEvent] 的两参数构造器, KeyEvent.scanCode 永远是 0。
 *    AAR 里的 InputEventSender.sendKeyEvent(KeyEvent) 实际上会调 native 的
 *    sendKeyEvent(int keyCode, int scancode, boolean pressed), 它依赖
 *    event.getScanCode() 拿到 scancode。scancode=0 直接导致 termux-x11 native 端
 *    无法用 scancode 找 X11 keysym, 表现出来就是 "所有键位都乱了, 包括方向键"。
 *
 * 2. 修法: 改用 [KeyEvent] 的完整构造器, 把 PC AT Set 1 scancode 显式传进去;
 *    配合 [Binding.toEvdev] 修复后的统一 scancode 表, 整条链路就通了。
 *
 * 3. 顺手把 [Binding.toEvdev] 里方向键/Home/PgUp/PgDn/PrtScn/DEL/小键盘
 *    那些混着 Linux evdev 的错值都换成 PC AT Set 1 标准值, 跟 abc-fix 完全对齐。
 */
class X11InputSender {
    private var inputEventSender: InputEventSender? = null
    private val handler = Handler(Looper.getMainLooper())

    // RenderData for touch events - needs to be set from LorieView
    var renderData: RenderData? = null

    // Whether InputEventSender is initialized
    val isInitialized: Boolean
        get() = inputEventSender != null

    /**
     * Initialize with an InputStub (typically LorieView)
     * Also resets all mouse button states to prevent stuck buttons on startup
     */
    fun initialize(inputStub: InputStub) {
        inputEventSender = InputEventSender(inputStub)
        // 初始化后重置鼠标按钮状态，确保没有按钮处于按下状态
        resetMouseButtons()
    }

    /**
     * 重置所有鼠标按钮状态，确保没有按钮处于按下状态
     * 这可以解决首次启动时鼠标按钮卡住的问题
     */
    private fun resetMouseButtons() {
        val sender = inputEventSender ?: return
        // 发送所有鼠标按钮的释放事件
        sender.sendMouseEvent(null, BUTTON_LEFT, false, true)
        sender.sendMouseEvent(null, BUTTON_MIDDLE, false, true)
        sender.sendMouseEvent(null, BUTTON_RIGHT, false, true)
    }

    /**
     * 强制重置所有鼠标按钮状态（公开方法）
     * 可供外部调用以确保没有卡住的鼠标按钮
     */
    fun forceResetMouseButtons() {
        resetMouseButtons()
    }

    // ============================================================
    // 键盘事件 (修复后的核心路径)
    // ============================================================

    /**
     * 发送带 scancode 的键盘事件。
     *
     * @param androidKeycode Android KeyEvent 用的 keycode (给 [KeyEvent.getKeyCode] 用)
     * @param scancode       PC AT Set 1 scancode (给 [KeyEvent.getScanCode] 用,
     *                       实际决定 X server 还原成哪个 X11 keysym 的关键参数)
     * @param isDown         true = 按下, false = 释放
     */
    fun sendKeyEvent(androidKeycode: Int, scancode: Int, isDown: Boolean) {
        val sender = inputEventSender ?: return
        if (androidKeycode == 0) return  // 未知按键, 不要污染 native 端

        // 严格保证按下/释放配对, 按下 / 释放都进同一个 Handler 队列, 顺序 FIFO
        handler.post {
            val now = SystemClock.uptimeMillis()
            // KeyEvent FLAG 说明:
            //  - FLAG_KEEP_TOUCH_MODE (0x4) = API 5+, 告诉系统保持触摸模式, 防止弹起软键盘
            //  - FLAG_FROM_SOURCE (0x1000000) = API 23+, 但 termux-x11 用的 android.jar
            //    影子版本里解析不到, 这里就不用它, 避免 unresolved reference。
            val event = KeyEvent(
                now,                                  // downTime
                now,                                  // eventTime
                if (isDown) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
                androidKeycode,                       // code (Android 侧)
                0,                                    // repeat
                0,                                    // metaState
                0,                                    // deviceId
                scancode and 0xFF,                    // scanCode (PC AT Set 1) - 关键
                KeyEvent.FLAG_KEEP_TOUCH_MODE         // flags
            )
            sender.sendKeyEvent(event)
        }
    }

    /**
     * 把 Binding 翻译成 (keycode, scancode) 然后注入。
     * 这个是 [InputControlsView.handleInputEvent] 实际调用的入口。
     */
    fun sendEvdevKeyEvent(scancode: Int, isDown: Boolean) {
        val androidKeycode = scancodeToAndroidKeycode(scancode)
        sendKeyEvent(androidKeycode, scancode, isDown)
    }

    /**
     * 释放某个 scancode 的按键 (对已按下的按键补发一次 release)。
     * 防止"卡键"问题。
     */
    fun sendKeyRelease(scancode: Int) {
        if (scancode <= 0) return
        sendKeyEvent(scancodeToAndroidKeycode(scancode), scancode, false)
    }

    /**
     * 强制释放一组 scancode (按从大到小顺序), 用于:
     * - onDetachedFromWindow 时兜底
     * - 失去焦点 / 切后台 / 切 profile 时
     * 修: 不发送就不会出现 "卡键 / 走路不停" 这种 bug。
     */
    fun releaseScancodes(scancodes: Collection<Int>) {
        if (scancodes.isEmpty()) return
        // 排序保证释放顺序稳定, 避免和按 Ctrl+X 这类组合键的状态错乱
        for (s in scancodes.sortedDescending()) sendKeyRelease(s)
    }

    // ============================================================
    // 鼠标事件
    // ============================================================

    /**
     * Send mouse button event
     * @param button Button index (1=left, 2=middle, 3=right, 4=scroll up, 5=scroll down)
     * @param isDown True if pressed, false if released
     */
    fun sendMouseButtonEvent(button: Int, isDown: Boolean) {
        val sender = inputEventSender ?: return

        handler.post {
            when (button) {
                1 -> {
                    // Left button - send as button press/release
                    sender.sendMouseEvent(null, BUTTON_LEFT, isDown, true)
                }
                2 -> {
                    // Middle button
                    sender.sendMouseEvent(null, BUTTON_MIDDLE, isDown, true)
                }
                3 -> {
                    // Right button
                    sender.sendMouseEvent(null, BUTTON_RIGHT, isDown, true)
                }
                4 -> {
                    // Scroll up - use wheel event
                    if (isDown) {
                        sender.sendMouseWheelEvent(0f, -1f)
                    }
                }
                5 -> {
                    // Scroll down - use wheel event
                    if (isDown) {
                        sender.sendMouseWheelEvent(0f, 1f)
                    }
                }
            }
        }
    }

    /**
     * Send mouse motion event (relative movement)
     * @param dx Change in X coordinate
     * @param dy Change in Y coordinate
     */
    fun sendMouseMotionEvent(dx: Int, dy: Int) {
        val sender = inputEventSender ?: return

        handler.post {
            // Send cursor move with relative coordinates
            // The last parameter 'true' means relative movement
            sender.sendCursorMove(dx.toFloat(), dy.toFloat(), true)
        }
    }

    /**
     * Send mouse wheel event
     * @param deltaX Horizontal scroll amount
     * @param deltaY Vertical scroll amount
     */
    fun sendMouseWheelEvent(deltaX: Float, deltaY: Float) {
        val sender = inputEventSender ?: return

        handler.post {
            sender.sendMouseWheelEvent(deltaX, deltaY)
        }
    }

    // ============================================================
    // PC AT Set 1 scancode -> Android KeyEvent.keycode
    // ============================================================

    /**
     * PC AT Set 1 scancode -> Android KeyEvent keycode。
     *
     * 这个表是 [Binding.toEvdev] 输出值的反向映射。两者必须保持一致,
     * 否则 KeyEvent.getKeyCode() 拿到的就是错的 Android keycode, Android
     * 自身的 keyguard / accessibility 路径会乱。
     *
     * 注意: scancode 相同的情况下 (例如 scancode 80 同时是 Down 和 KP_2),
     * Android keycode 取最常见的那一个, 最终 X server 用 scancode 还原 X11 keysym,
     * 不会影响游戏内行为。
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
            11 -> KeyEvent.KEYCODE_0
            2 -> KeyEvent.KEYCODE_1
            3 -> KeyEvent.KEYCODE_2
            4 -> KeyEvent.KEYCODE_3
            5 -> KeyEvent.KEYCODE_4
            6 -> KeyEvent.KEYCODE_5
            7 -> KeyEvent.KEYCODE_6
            8 -> KeyEvent.KEYCODE_7
            9 -> KeyEvent.KEYCODE_8
            10 -> KeyEvent.KEYCODE_9

            // 功能键 / 编辑键
            1 -> KeyEvent.KEYCODE_ESCAPE
            14 -> KeyEvent.KEYCODE_DEL            // Backspace
            15 -> KeyEvent.KEYCODE_TAB
            28 -> KeyEvent.KEYCODE_ENTER
            57 -> KeyEvent.KEYCODE_SPACE

            // 修饰键
            29 -> KeyEvent.KEYCODE_CTRL_LEFT
            97 -> KeyEvent.KEYCODE_CTRL_RIGHT
            42 -> KeyEvent.KEYCODE_SHIFT_LEFT
            54 -> KeyEvent.KEYCODE_SHIFT_RIGHT
            56 -> KeyEvent.KEYCODE_ALT_LEFT
            100 -> KeyEvent.KEYCODE_ALT_RIGHT
            58 -> KeyEvent.KEYCODE_CAPS_LOCK
            69 -> KeyEvent.KEYCODE_NUM_LOCK
            70 -> KeyEvent.KEYCODE_SCROLL_LOCK

            // 方向键 / 导航 (PC AT Set 1)
            72 -> KeyEvent.KEYCODE_DPAD_UP
            80 -> KeyEvent.KEYCODE_DPAD_DOWN
            75 -> KeyEvent.KEYCODE_DPAD_LEFT
            77 -> KeyEvent.KEYCODE_DPAD_RIGHT
            71 -> KeyEvent.KEYCODE_MOVE_HOME
            79 -> KeyEvent.KEYCODE_MOVE_END
            73 -> KeyEvent.KEYCODE_PAGE_UP
            81 -> KeyEvent.KEYCODE_PAGE_DOWN
            82 -> KeyEvent.KEYCODE_INSERT
            83 -> KeyEvent.KEYCODE_FORWARD_DEL    // 修正: 旧实现 111 -> NUMPAD_DECIMAL 错位
            99 -> KeyEvent.KEYCODE_SYSRQ          // Print Screen

            // F1-F12
            59 -> KeyEvent.KEYCODE_F1
            60 -> KeyEvent.KEYCODE_F2
            61 -> KeyEvent.KEYCODE_F3
            62 -> KeyEvent.KEYCODE_F4
            63 -> KeyEvent.KEYCODE_F5
            64 -> KeyEvent.KEYCODE_F6
            65 -> KeyEvent.KEYCODE_F7
            66 -> KeyEvent.KEYCODE_F8
            67 -> KeyEvent.KEYCODE_F9
            68 -> KeyEvent.KEYCODE_F10
            87 -> KeyEvent.KEYCODE_F11
            88 -> KeyEvent.KEYCODE_F12

            // 符号键
            12 -> KeyEvent.KEYCODE_MINUS
            26 -> KeyEvent.KEYCODE_LEFT_BRACKET
            27 -> KeyEvent.KEYCODE_RIGHT_BRACKET
            43 -> KeyEvent.KEYCODE_BACKSLASH
            53 -> KeyEvent.KEYCODE_SLASH
            39 -> KeyEvent.KEYCODE_SEMICOLON
            40 -> KeyEvent.KEYCODE_APOSTROPHE
            51 -> KeyEvent.KEYCODE_COMMA
            52 -> KeyEvent.KEYCODE_PERIOD

            // 小键盘 (PC AT Set 1)
            78 -> KeyEvent.KEYCODE_NUMPAD_ADD
            74 -> KeyEvent.KEYCODE_NUMPAD_SUBTRACT
            55 -> KeyEvent.KEYCODE_NUMPAD_MULTIPLY
            98 -> KeyEvent.KEYCODE_NUMPAD_DIVIDE
            96 -> KeyEvent.KEYCODE_NUMPAD_ENTER
            // 71/72/73/75/77/79/80/81/82/83 上面已经映射成方向键/导航键,
            // 它们和 KP_7/8/9/4/6/1/2/3/0/. 共用 scancode, 取最常用的那一个。
            // X server 拿到 scancode 后会根据 xkb keymap 还原成正确的 X11 keysym。
            76 -> KeyEvent.KEYCODE_NUMPAD_5

            // 未知 / 未实现
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
        inputEventSender = null
    }
}

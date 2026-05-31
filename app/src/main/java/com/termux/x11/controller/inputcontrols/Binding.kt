package com.termux.x11.controller.inputcontrols

/**
 * Binding types for control elements
 * 参考 winlator 实现，保持简洁
 */
enum class Binding(
    val keycode: Int = 0,
    val isKeyboard: Boolean = false,
    val isMouse: Boolean = false,
    val isGamepad: Boolean = false
) {
    // Special bindings
    NONE,

    // Mouse bindings - 鼠标绑定 (放在前面，与 winlator 一致)
    MOUSE_LEFT_BUTTON(0, false, true),
    MOUSE_MIDDLE_BUTTON(0, false, true),
    MOUSE_RIGHT_BUTTON(0, false, true),
    MOUSE_MOVE_UP(0, false, true),
    MOUSE_MOVE_DOWN(0, false, true),
    MOUSE_MOVE_LEFT(0, false, true),
    MOUSE_MOVE_RIGHT(0, false, true),
    MOUSE_SCROLL_UP(0, false, true),
    MOUSE_SCROLL_DOWN(0, false, true),
    MOUSE_LEFT_RIGHT(0, false, true),
    MOUSE_TOUCHMODE_SWITCH(0, false, true),

    // Navigation keys (参考 winlator 的顺序)
    KEY_UP(72, true),
    KEY_DOWN(80, true),
    KEY_LEFT(105, true),
    KEY_RIGHT(106, true),
    KEY_ENTER(28, true),
    KEY_ESC(1, true),
    KEY_ESCAPE(1, true),
    KEY_BKSP(14, true),
    KEY_BACKSPACE(14, true),
    KEY_INSERT(110, true),
    KEY_TAB(15, true),
    KEY_SPACE(57, true),
    KEY_CTRL_L(29, true),
    KEY_CTRL_R(157, true),
    KEY_LCTRL(29, true),
    KEY_LCONTROL(29, true),
    KEY_RCTRL(157, true),
    KEY_RCONTROL(157, true),
    KEY_SHIFT_L(42, true),
    KEY_SHIFT_R(54, true),
    KEY_LSHIFT(42, true),
    KEY_RSHIFT(54, true),
    KEY_ALT_L(56, true),
    KEY_ALT_R(126, true),
    KEY_LALT(56, true),
    KEY_LMENU(56, true),
    KEY_RALT(126, true),
    KEY_RMENU(126, true),
    KEY_HOME(102, true),
    KEY_PRTSCN(127, true),
    KEY_PRINT(127, true),
    KEY_PGUP(104, true),
    KEY_PAGEUP(104, true),
    KEY_PGDN(109, true),
    KEY_PAGEDOWN(109, true),
    KEY_END(107, true),
    KEY_CAPS_LOCK(58, true),
    KEY_CAPITAL(58, true),
    KEY_NUM_LOCK(69, true),
    KEY_NUMLOCK(69, true),
    KEY_SCROLL_LOCK(70, true),
    KEY_SCROLL(151, true),
    KEY_PAUSE(197, true),
    KEY_DELETE(111, true),
    KEY_DEL(111, true),
    KEY_LWIN(125, true),
    KEY_RWIN(127, true),

    // Alphabet keys A-Z (按字母顺序排列)
    KEY_A(30, true),
    KEY_B(48, true),
    KEY_C(46, true),
    KEY_D(32, true),
    KEY_E(18, true),
    KEY_F(33, true),
    KEY_G(34, true),
    KEY_H(35, true),
    KEY_I(23, true),
    KEY_J(36, true),
    KEY_K(37, true),
    KEY_L(38, true),
    KEY_M(50, true),
    KEY_N(49, true),
    KEY_O(24, true),
    KEY_P(25, true),
    KEY_Q(16, true),
    KEY_R(19, true),
    KEY_S(31, true),
    KEY_T(20, true),
    KEY_U(22, true),
    KEY_V(47, true),
    KEY_W(17, true),
    KEY_X(45, true),
    KEY_Y(21, true),
    KEY_Z(44, true),

    // Number keys 0-9
    KEY_0(11, true),
    KEY_1(2, true),
    KEY_2(3, true),
    KEY_3(4, true),
    KEY_4(5, true),
    KEY_5(6, true),
    KEY_6(7, true),
    KEY_7(8, true),
    KEY_8(9, true),
    KEY_9(10, true),

    // Symbol keys (数字行)
    KEY_GRAVE(41, true),
    KEY_MINUS(12, true),
    KEY_EQUALS(13, true),
    KEY_BRACKET_LEFT(26, true),
    KEY_BRACKET_RIGHT(27, true),
    KEY_BACKSLASH(43, true),
    KEY_SEMICOLON(39, true),
    KEY_APOSTROPHE(40, true),
    KEY_COMMA(51, true),
    KEY_PERIOD(52, true),
    KEY_SLASH(53, true),

    // Function keys F1-F12
    KEY_F1(59, true),
    KEY_F2(60, true),
    KEY_F3(61, true),
    KEY_F4(62, true),
    KEY_F5(63, true),
    KEY_F6(64, true),
    KEY_F7(65, true),
    KEY_F8(66, true),
    KEY_F9(67, true),
    KEY_F10(68, true),
    KEY_F11(69, true),
    KEY_F12(70, true),

    // Numpad (数字小键盘)
    NUMPAD_0(82, true),
    KEY_KP_0(82, true),
    NUMPAD_1(79, true),
    KEY_KP_1(79, true),
    NUMPAD_2(80, true),
    KEY_KP_2(80, true),
    NUMPAD_3(81, true),
    KEY_KP_3(81, true),
    NUMPAD_4(75, true),
    KEY_KP_4(75, true),
    NUMPAD_5(76, true),
    KEY_KP_5(76, true),
    NUMPAD_6(77, true),
    KEY_KP_6(77, true),
    NUMPAD_7(71, true),
    KEY_KP_7(71, true),
    NUMPAD_8(72, true),
    KEY_KP_8(72, true),
    NUMPAD_9(73, true),
    KEY_KP_9(73, true),
    NUMPAD_DECIMAL(83, true),
    KEY_KP_DECIMAL(83, true),
    NUMPAD_DIVIDE(84, true),
    KEY_KP_DIVIDE(84, true),
    NUMPAD_MULTIPLY(85, true),
    KEY_KP_MULTIPLY(85, true),
    NUMPAD_MINUS(86, true),
    KEY_KP_SUBTRACT(86, true),
    NUMPAD_PLUS(87, true),
    KEY_KP_ADD(87, true),
    NUMPAD_ENTER(156, true),
    KEY_KP_ENTER(156, true),

    // Gamepad bindings - 手柄绑定 (放在最后)
    GAMEPAD_BUTTON_A(0, false, false, true),
    GAMEPAD_BUTTON_B(0, false, false, true),
    GAMEPAD_BUTTON_X(0, false, false, true),
    GAMEPAD_BUTTON_Y(0, false, false, true),
    GAMEPAD_BUTTON_L1(0, false, false, true),
    GAMEPAD_BUTTON_R1(0, false, false, true),
    GAMEPAD_BUTTON_L2(0, false, false, true),
    GAMEPAD_BUTTON_R2(0, false, false, true),
    GAMEPAD_BUTTON_THUMBL(0, false, false, true),
    GAMEPAD_BUTTON_THUMBR(0, false, false, true),
    GAMEPAD_BUTTON_START(0, false, false, true),
    GAMEPAD_BUTTON_SELECT(0, false, false, true),
    GAMEPAD_BUTTON_HOME(0, false, false, true),
    GAMEPAD_DPAD_UP(0, false, false, true),
    GAMEPAD_DPAD_DOWN(0, false, false, true),
    GAMEPAD_DPAD_LEFT(0, false, false, true),
    GAMEPAD_DPAD_RIGHT(0, false, false, true),
    GAMEPAD_LEFT_THUMB_UP(0, false, false, true),
    GAMEPAD_LEFT_THUMB_DOWN(0, false, false, true),
    GAMEPAD_LEFT_THUMB_LEFT(0, false, false, true),
    GAMEPAD_LEFT_THUMB_RIGHT(0, false, false, true),
    GAMEPAD_RIGHT_THUMB_UP(0, false, false, true),
    GAMEPAD_RIGHT_THUMB_DOWN(0, false, false, true),
    GAMEPAD_RIGHT_THUMB_LEFT(0, false, false, true),
    GAMEPAD_RIGHT_THUMB_RIGHT(0, false, false, true);

    /**
     * 返回用于显示的字符串，参考 winlator 的实现
     */
    override fun toString(): String {
        return when (this) {
            NONE -> "NONE"
            KEY_SHIFT_L, KEY_LSHIFT -> "L SHIFT"
            KEY_SHIFT_R, KEY_RSHIFT -> "R SHIFT"
            KEY_CTRL_L, KEY_LCTRL, KEY_LCONTROL -> "L CTRL"
            KEY_CTRL_R, KEY_RCTRL, KEY_RCONTROL -> "R CTRL"
            KEY_ALT_L, KEY_LALT, KEY_LMENU -> "L ALT"
            KEY_ALT_R, KEY_RALT, KEY_RMENU -> "R ALT"
            KEY_BRACKET_LEFT -> "["
            KEY_BRACKET_RIGHT -> "]"
            KEY_BACKSLASH -> "\\"
            KEY_SLASH -> "/"
            KEY_SEMICOLON -> ";"
            KEY_COMMA -> ","
            KEY_PERIOD -> "."
            KEY_APOSTROPHE -> "'"
            KEY_MINUS -> "-"
            KEY_EQUALS -> "="
            KEY_GRAVE -> "`"
            KEY_KP_0, NUMPAD_0 -> "NP 0"
            KEY_KP_1, NUMPAD_1 -> "NP 1"
            KEY_KP_2, NUMPAD_2 -> "NP 2"
            KEY_KP_3, NUMPAD_3 -> "NP 3"
            KEY_KP_4, NUMPAD_4 -> "NP 4"
            KEY_KP_5, NUMPAD_5 -> "NP 5"
            KEY_KP_6, NUMPAD_6 -> "NP 6"
            KEY_KP_7, NUMPAD_7 -> "NP 7"
            KEY_KP_8, NUMPAD_8 -> "NP 8"
            KEY_KP_9, NUMPAD_9 -> "NP 9"
            KEY_KP_DIVIDE, NUMPAD_DIVIDE -> "NP /"
            KEY_KP_MULTIPLY, NUMPAD_MULTIPLY -> "NP *"
            KEY_KP_SUBTRACT, NUMPAD_MINUS -> "NP -"
            KEY_KP_ADD, NUMPAD_PLUS -> "NP +"
            KEY_KP_DECIMAL, NUMPAD_DECIMAL -> "NP ."
            KEY_KP_ENTER, NUMPAD_ENTER -> "NP ENTER"
            KEY_CAPS_LOCK, KEY_CAPITAL -> "CAPS LOCK"
            KEY_NUM_LOCK, KEY_NUMLOCK -> "NUM LOCK"
            KEY_SCROLL_LOCK, KEY_SCROLL -> "SCROLL LOCK"
            KEY_ESC, KEY_ESCAPE -> "ESC"
            KEY_ENTER -> "ENTER"
            KEY_TAB -> "TAB"
            KEY_SPACE -> "SPACE"
            KEY_BKSP, KEY_BACKSPACE -> "BKSP"
            KEY_DELETE, KEY_DEL -> "DELETE"
            KEY_INSERT -> "INSERT"
            KEY_HOME -> "HOME"
            KEY_END -> "END"
            KEY_PGUP, KEY_PAGEUP -> "PG UP"
            KEY_PGDN, KEY_PAGEDOWN -> "PG DN"
            KEY_UP -> "UP"
            KEY_DOWN -> "DOWN"
            KEY_LEFT -> "LEFT"
            KEY_RIGHT -> "RIGHT"
            KEY_PRTSCN, KEY_PRINT -> "PRINT"
            KEY_PAUSE -> "PAUSE"
            MOUSE_LEFT_BUTTON -> "L MB"
            MOUSE_RIGHT_BUTTON -> "R MB"
            MOUSE_MIDDLE_BUTTON -> "M MB"
            MOUSE_MOVE_UP -> "M UP"
            MOUSE_MOVE_DOWN -> "M DOWN"
            MOUSE_MOVE_LEFT -> "M LEFT"
            MOUSE_MOVE_RIGHT -> "M RIGHT"
            MOUSE_SCROLL_UP -> "M SC UP"
            MOUSE_SCROLL_DOWN -> "M SC DOWN"
            GAMEPAD_BUTTON_A -> "G A"
            GAMEPAD_BUTTON_B -> "G B"
            GAMEPAD_BUTTON_X -> "G X"
            GAMEPAD_BUTTON_Y -> "G Y"
            GAMEPAD_BUTTON_L1 -> "G L1"
            GAMEPAD_BUTTON_R1 -> "G R1"
            GAMEPAD_BUTTON_L2 -> "G L2"
            GAMEPAD_BUTTON_R2 -> "G R2"
            GAMEPAD_BUTTON_THUMBL -> "G TL"
            GAMEPAD_BUTTON_THUMBR -> "G TR"
            GAMEPAD_BUTTON_START -> "G START"
            GAMEPAD_BUTTON_SELECT -> "G SELECT"
            GAMEPAD_BUTTON_HOME -> "G HOME"
            GAMEPAD_DPAD_UP -> "G UP"
            GAMEPAD_DPAD_DOWN -> "G DOWN"
            GAMEPAD_DPAD_LEFT -> "G LEFT"
            GAMEPAD_DPAD_RIGHT -> "G RIGHT"
            else -> name.removePrefix("KEY_").removePrefix("NUMPAD_").removePrefix("GAMEPAD_")
        }
    }

    companion object {
        /**
         * 从字符串解析Binding，支持多种命名变体
         */
        fun fromString(name: String): Binding {
            return try {
                // 直接匹配
                valueOf(name)
            } catch (e: IllegalArgumentException) {
                // 尝试常见的别名映射
                when (name) {
                    "KEY_ESC" -> KEY_ESC
                    "KEY_BKSP", "KEY_BACKSPACE" -> KEY_BKSP
                    "KEY_SHIFT", "KEY_LSHIFT" -> KEY_SHIFT_L
                    "KEY_RSHIFT" -> KEY_SHIFT_R
                    "KEY_CTRL", "KEY_LCTRL", "KEY_LCONTROL" -> KEY_CTRL_L
                    "KEY_RCTRL", "KEY_RCONTROL" -> KEY_CTRL_R
                    "KEY_ALT", "KEY_LALT" -> KEY_ALT_L
                    "KEY_RALT" -> KEY_ALT_R
                    "KEY_DELETE" -> KEY_DEL
                    "KEY_PAGEUP", "KEY_PG_UP" -> KEY_PGUP
                    "KEY_PAGEDOWN", "KEY_PG_DOWN" -> KEY_PGDN
                    "KEY_PRINT" -> KEY_PRTSCN
                    "KEY_CAPS" -> KEY_CAPITAL
                    "KEY_NUMLOCK" -> KEY_NUM_LOCK
                    "KEY_SCROLL" -> KEY_SCROLL_LOCK
                    "KEY_KP0", "NUMPAD_0" -> NUMPAD_0
                    "KEY_KP1", "NUMPAD_1" -> NUMPAD_1
                    "KEY_KP2", "NUMPAD_2" -> NUMPAD_2
                    "KEY_KP3", "NUMPAD_3" -> NUMPAD_3
                    "KEY_KP4", "NUMPAD_4" -> NUMPAD_4
                    "KEY_KP5", "NUMPAD_5" -> NUMPAD_5
                    "KEY_KP6", "NUMPAD_6" -> NUMPAD_6
                    "KEY_KP7", "NUMPAD_7" -> NUMPAD_7
                    "KEY_KP8", "NUMPAD_8" -> NUMPAD_8
                    "KEY_KP9", "NUMPAD_9" -> NUMPAD_9
                    "KEY_KP_DIVIDE" -> NUMPAD_DIVIDE
                    "KEY_KP_MULTIPLY" -> NUMPAD_MULTIPLY
                    "KEY_KP_SUBTRACT" -> NUMPAD_MINUS
                    "KEY_KP_ADD" -> NUMPAD_PLUS
                    "KEY_KP_DECIMAL" -> NUMPAD_DECIMAL
                    "KEY_KP_ENTER" -> NUMPAD_ENTER
                    else -> NONE
                }
            }
        }

        /**
         * 获取所有键盘按键绑定
         * 参考 winlator 的实现，直接按枚举顺序返回
         */
        fun keyboardBindings(): List<Binding> = entries.filter { it.isKeyboard }

        fun mouseBindings(): List<Binding> = entries.filter { it.isMouse }

        fun gamepadBindings(): List<Binding> = entries.filter { it.isGamepad }

        /**
         * 获取键盘按键标签数组
         * 参考 winlator 的 keyboardBindingLabels 实现
         */
        fun keyboardBindingLabels(): Array<String> {
            val labels = ArrayList<String>()
            for (binding in entries) {
                if (binding.isKeyboard) {
                    labels.add(binding.toString())
                }
            }
            return labels.toArray(arrayOf<String>())
        }

        /**
         * 获取键盘按键值数组
         * 参考 winlator 的 keyboardBindingValues 实现
         */
        fun keyboardBindingValues(): Array<Binding> {
            val values = ArrayList<Binding>()
            for (binding in entries) {
                if (binding.isKeyboard) {
                    values.add(binding)
                }
            }
            return values.toArray(arrayOf<Binding>())
        }

        fun mouseBindingLabels(): Array<String> = mouseBindings().map { it.toString() }.toTypedArray()

        fun mouseBindingValues(): Array<Binding> = mouseBindings().toTypedArray()

        fun gamepadBindingLabels(): Array<String> = gamepadBindings().map { it.toString() }.toTypedArray()

        fun gamepadBindingValues(): Array<Binding> = gamepadBindings().toTypedArray()
    }

    fun isMouseMove(): Boolean = this in listOf(MOUSE_MOVE_UP, MOUSE_MOVE_DOWN, MOUSE_MOVE_LEFT, MOUSE_MOVE_RIGHT)

    /**
     * 获取鼠标按钮对应的指针按钮值
     * 用于将鼠标绑定转换为实际的鼠标事件
     * @return X11按钮值 (1=左键, 2=中键, 3=右键, 4=滚轮上, 5=滚轮下)，如果不是鼠标按钮则返回 null
     */
    fun getPointerButton(): Int? {
        return when (this) {
            MOUSE_LEFT_BUTTON -> 1   // X11 Button1
            MOUSE_MIDDLE_BUTTON -> 2 // X11 Button2
            MOUSE_RIGHT_BUTTON -> 3  // X11 Button3
            MOUSE_SCROLL_UP -> 4     // X11 Button4
            MOUSE_SCROLL_DOWN -> 5    // X11 Button5
            else -> null
        }
    }
}

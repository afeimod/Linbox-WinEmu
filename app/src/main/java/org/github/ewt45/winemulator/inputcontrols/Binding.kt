package org.github.ewt45.winemulator.inputcontrols

import androidx.annotation.NonNull
import com.termux.x11.controller.xserver.Pointer
import com.termux.x11.controller.xserver.XKeycode

enum class Binding {
    NONE,
    MOUSE_LEFT_BUTTON,
    MOUSE_MIDDLE_BUTTON,
    MOUSE_RIGHT_BUTTON,
    MOUSE_MOVE_LEFT,
    MOUSE_MOVE_RIGHT,
    MOUSE_MOVE_UP,
    MOUSE_MOVE_DOWN,
    MOUSE_SCROLL_UP,
    MOUSE_SCROLL_DOWN,
    KEY_UP,
    KEY_RIGHT,
    KEY_DOWN,
    KEY_LEFT,
    KEY_ENTER,
    KEY_ESC,
    KEY_BKSP,
    KEY_DEL,
    KEY_TAB,
    KEY_SPACE,
    KEY_CTRL_L,
    KEY_CTRL_R,
    KEY_SHIFT_L,
    KEY_SHIFT_R,
    KEY_ALT_L,
    KEY_ALT_R,
    KEY_HOME,
    KEY_PRTSCN,
    KEY_PG_UP,
    KEY_PG_DOWN,
    KEY_CAPS_LOCK,
    KEY_NUM_LOCK,
    KEY_0,
    KEY_1,
    KEY_2,
    KEY_3,
    KEY_4,
    KEY_5,
    KEY_6,
    KEY_7,
    KEY_8,
    KEY_9,
    KEY_A,
    KEY_B,
    KEY_C,
    KEY_D,
    KEY_E,
    KEY_F,
    KEY_G,
    KEY_H,
    KEY_I,
    KEY_J,
    KEY_K,
    KEY_L,
    KEY_M,
    KEY_N,
    KEY_O,
    KEY_P,
    KEY_Q,
    KEY_R,
    KEY_S,
    KEY_T,
    KEY_U,
    KEY_V,
    KEY_W,
    KEY_X,
    KEY_Y,
    KEY_Z,
    KEY_BRACKET_LEFT,
    KEY_BRACKET_RIGHT,
    KEY_BACKSLASH,
    KEY_SLASH,
    KEY_SEMICOLON,
    KEY_COMMA,
    KEY_PERIOD,
    KEY_APOSTROPHE,
    KEY_KP_ADD,
    KEY_MINUS,
    KEY_F1,
    KEY_F2,
    KEY_F3,
    KEY_F4,
    KEY_F5,
    KEY_F6,
    KEY_F7,
    KEY_F8,
    KEY_F9,
    KEY_F10,
    KEY_F11,
    KEY_F12,
    KEY_KP_0,
    KEY_KP_1,
    KEY_KP_2,
    KEY_KP_3,
    KEY_KP_4,
    KEY_KP_5,
    KEY_KP_6,
    KEY_KP_7,
    KEY_KP_8,
    KEY_KP_9,
    GAMEPAD_BUTTON_A,
    GAMEPAD_BUTTON_B,
    GAMEPAD_BUTTON_X,
    GAMEPAD_BUTTON_Y,
    GAMEPAD_BUTTON_L1,
    GAMEPAD_BUTTON_R1,
    GAMEPAD_BUTTON_SELECT,
    GAMEPAD_BUTTON_START,
    GAMEPAD_BUTTON_L3,
    GAMEPAD_BUTTON_R3,
    GAMEPAD_BUTTON_L2,
    GAMEPAD_BUTTON_R2,
    GAMEPAD_LEFT_THUMB_UP,
    GAMEPAD_LEFT_THUMB_RIGHT,
    GAMEPAD_LEFT_THUMB_DOWN,
    GAMEPAD_LEFT_THUMB_LEFT,
    GAMEPAD_RIGHT_THUMB_UP,
    GAMEPAD_RIGHT_THUMB_RIGHT,
    GAMEPAD_RIGHT_THUMB_DOWN,
    GAMEPAD_RIGHT_THUMB_LEFT,
    GAMEPAD_DPAD_UP,
    GAMEPAD_DPAD_RIGHT,
    GAMEPAD_DPAD_DOWN,
    GAMEPAD_DPAD_LEFT;

    val keycode: XKeycode by lazy {
        val nameToFind = when (this) {
            KEY_PG_UP -> "KEY_PRIOR"
            KEY_PG_DOWN -> "KEY_NEXT"
            else -> name
        }
        try {
            XKeycode.valueOf(nameToFind)
        } catch (e: IllegalArgumentException) {
            XKeycode.KEY_NONE
        }
    }

    /**
     * Convert XKeycode to Linux evdev keycode
     * This is needed because the X11InputSender expects evdev keycodes,
     * but the XKeycode enum uses X11 keycode values.
     */
    fun toEvdev(): Int {
        // First get the Android keycode equivalent
        val androidKeycode = when (this) {
            // Navigation cluster (evdev 72-77, 79-84)
            KEY_UP -> android.view.KeyEvent.KEYCODE_DPAD_UP
            KEY_DOWN -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
            KEY_LEFT -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
            KEY_RIGHT -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            KEY_HOME -> android.view.KeyEvent.KEYCODE_MOVE_HOME
            KEY_END -> android.view.KeyEvent.KEYCODE_MOVE_END
            KEY_PG_UP, KEY_PAGEUP -> android.view.KeyEvent.KEYCODE_PAGE_UP
            KEY_PG_DOWN, KEY_PAGEDOWN -> android.view.KeyEvent.KEYCODE_PAGE_DOWN
            KEY_INSERT -> android.view.KeyEvent.KEYCODE_INSERT
            KEY_DELETE, KEY_DEL -> android.view.KeyEvent.KEYCODE_FORWARD_DEL
            
            // Letters A-Z (evdev 30-50)
            KEY_A -> android.view.KeyEvent.KEYCODE_A
            KEY_B -> android.view.KeyEvent.KEYCODE_B
            KEY_C -> android.view.KeyEvent.KEYCODE_C
            KEY_D -> android.view.KeyEvent.KEYCODE_D
            KEY_E -> android.view.KeyEvent.KEYCODE_E
            KEY_F -> android.view.KeyEvent.KEYCODE_F
            KEY_G -> android.view.KeyEvent.KEYCODE_G
            KEY_H -> android.view.KeyEvent.KEYCODE_H
            KEY_I -> android.view.KeyEvent.KEYCODE_I
            KEY_J -> android.view.KeyEvent.KEYCODE_J
            KEY_K -> android.view.KeyEvent.KEYCODE_K
            KEY_L -> android.view.KeyEvent.KEYCODE_L
            KEY_M -> android.view.KeyEvent.KEYCODE_M
            KEY_N -> android.view.KeyEvent.KEYCODE_N
            KEY_O -> android.view.KeyEvent.KEYCODE_O
            KEY_P -> android.view.KeyEvent.KEYCODE_P
            KEY_Q -> android.view.KeyEvent.KEYCODE_Q
            KEY_R -> android.view.KeyEvent.KEYCODE_R
            KEY_S -> android.view.KeyEvent.KEYCODE_S
            KEY_T -> android.view.KeyEvent.KEYCODE_T
            KEY_U -> android.view.KeyEvent.KEYCODE_U
            KEY_V -> android.view.KeyEvent.KEYCODE_V
            KEY_W -> android.view.KeyEvent.KEYCODE_W
            KEY_X -> android.view.KeyEvent.KEYCODE_X
            KEY_Y -> android.view.KeyEvent.KEYCODE_Y
            KEY_Z -> android.view.KeyEvent.KEYCODE_Z
            
            // Numbers 0-9 (evdev 2-11)
            KEY_0 -> android.view.KeyEvent.KEYCODE_0
            KEY_1 -> android.view.KeyEvent.KEYCODE_1
            KEY_2 -> android.view.KeyEvent.KEYCODE_2
            KEY_3 -> android.view.KeyEvent.KEYCODE_3
            KEY_4 -> android.view.KeyEvent.KEYCODE_4
            KEY_5 -> android.view.KeyEvent.KEYCODE_5
            KEY_6 -> android.view.KeyEvent.KEYCODE_6
            KEY_7 -> android.view.KeyEvent.KEYCODE_7
            KEY_8 -> android.view.KeyEvent.KEYCODE_8
            KEY_9 -> android.view.KeyEvent.KEYCODE_9
            
            // Special keys
            KEY_ENTER -> android.view.KeyEvent.KEYCODE_ENTER
            KEY_ESC, KEY_ESCAPE -> android.view.KeyEvent.KEYCODE_ESCAPE
            KEY_BKSP, KEY_BACKSPACE -> android.view.KeyEvent.KEYCODE_DEL
            KEY_TAB -> android.view.KeyEvent.KEYCODE_TAB
            KEY_SPACE -> android.view.KeyEvent.KEYCODE_SPACE
            KEY_CTRL_L, KEY_LCTRL, KEY_LCONTROL -> android.view.KeyEvent.KEYCODE_CTRL_LEFT
            KEY_CTRL_R, KEY_RCTRL, KEY_RCONTROL -> android.view.KeyEvent.KEYCODE_CTRL_RIGHT
            KEY_SHIFT_L, KEY_LSHIFT -> android.view.KeyEvent.KEYCODE_SHIFT_LEFT
            KEY_SHIFT_R, KEY_RSHIFT -> android.view.KeyEvent.KEYCODE_SHIFT_RIGHT
            KEY_ALT_L, KEY_LALT, KEY_LMENU -> android.view.KeyEvent.KEYCODE_ALT_LEFT
            KEY_ALT_R, KEY_RALT, KEY_RMENU -> android.view.KeyEvent.KEYCODE_ALT_RIGHT
            KEY_CAPS_LOCK, KEY_CAPITAL -> android.view.KeyEvent.KEYCODE_CAPS_LOCK
            KEY_NUM_LOCK, KEY_NUMLOCK -> android.view.KeyEvent.KEYCODE_NUM_LOCK
            KEY_SCROLL_LOCK, KEY_SCROLL -> android.view.KeyEvent.KEYCODE_SCROLL_LOCK
            KEY_PRTSCN, KEY_PRINT -> android.view.KeyEvent.KEYCODE_SYSRQ
            
            // Symbols
            KEY_MINUS -> android.view.KeyEvent.KEYCODE_MINUS
            KEY_EQUALS -> android.view.KeyEvent.KEYCODE_EQUALS
            KEY_BRACKET_LEFT -> android.view.KeyEvent.KEYCODE_LEFT_BRACKET
            KEY_BRACKET_RIGHT -> android.view.KeyEvent.KEYCODE_RIGHT_BRACKET
            KEY_BACKSLASH -> android.view.KeyEvent.KEYCODE_BACKSLASH
            KEY_SLASH -> android.view.KeyEvent.KEYCODE_SLASH
            KEY_SEMICOLON -> android.view.KeyEvent.KEYCODE_SEMICOLON
            KEY_APOSTROPHE -> android.view.KeyEvent.KEYCODE_APOSTROPHE
            KEY_COMMA -> android.view.KeyEvent.KEYCODE_COMMA
            KEY_PERIOD -> android.view.KeyEvent.KEYCODE_PERIOD
            KEY_GRAVE -> android.view.KeyEvent.KEYCODE_GRAVE
            
            // Function keys F1-F12 (evdev 59-68, 87-88)
            KEY_F1 -> android.view.KeyEvent.KEYCODE_F1
            KEY_F2 -> android.view.KeyEvent.KEYCODE_F2
            KEY_F3 -> android.view.KeyEvent.KEYCODE_F3
            KEY_F4 -> android.view.KeyEvent.KEYCODE_F4
            KEY_F5 -> android.view.KeyEvent.KEYCODE_F5
            KEY_F6 -> android.view.KeyEvent.KEYCODE_F6
            KEY_F7 -> android.view.KeyEvent.KEYCODE_F7
            KEY_F8 -> android.view.KeyEvent.KEYCODE_F8
            KEY_F9 -> android.view.KeyEvent.KEYCODE_F9
            KEY_F10 -> android.view.KeyEvent.KEYCODE_F10
            KEY_F11 -> android.view.KeyEvent.KEYCODE_F11
            KEY_F12 -> android.view.KeyEvent.KEYCODE_F12
            
            // Numpad (evdev 79-90 for 0-9, 84 for 5, 86 for +, 82 for -, etc.)
            KEY_KP_0, KEY_KP_1, KEY_KP_2, KEY_KP_3, KEY_KP_4,
            KEY_KP_5, KEY_KP_6, KEY_KP_7, KEY_KP_8, KEY_KP_9 -> android.view.KeyEvent.KEYCODE_NUMPAD_0 + (this.ordinal - Binding.KEY_KP_0.ordinal)
            KEY_KP_ADD -> android.view.KeyEvent.KEYCODE_NUMPAD_ADD
            KEY_KP_DECIMAL, KEY_KP_DOT -> android.view.KeyEvent.KEYCODE_NUMPAD_DOT
            KEY_KP_ENTER -> android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
            KEY_KP_DIVIDE -> android.view.KeyEvent.KEYCODE_NUMPAD_DIVIDE
            KEY_KP_MULTIPLY -> android.view.KeyEvent.KEYCODE_NUMPAD_MULTIPLY
            KEY_KP_SUBTRACT -> android.view.KeyEvent.KEYCODE_NUMPAD_SUBTRACT
            
            else -> android.view.KeyEvent.KEYCODE_UNKNOWN
        }
        
        // Now convert Android keycode to evdev keycode
        return androidKeycodeToEvdev(androidKeycode)
    }

    /**
     * Convert Android keycode to Linux evdev keycode
     */
    private fun androidKeycodeToEvdev(keycode: Int): Int {
        return when (keycode) {
            // Special keys
            android.view.KeyEvent.KEYCODE_ESCAPE -> 1
            android.view.KeyEvent.KEYCODE_1 -> 2
            android.view.KeyEvent.KEYCODE_2 -> 3
            android.view.KeyEvent.KEYCODE_3 -> 4
            android.view.KeyEvent.KEYCODE_4 -> 5
            android.view.KeyEvent.KEYCODE_5 -> 6
            android.view.KeyEvent.KEYCODE_6 -> 7
            android.view.KeyEvent.KEYCODE_7 -> 8
            android.view.KeyEvent.KEYCODE_8 -> 9
            android.view.KeyEvent.KEYCODE_9 -> 10
            android.view.KeyEvent.KEYCODE_0 -> 11
            android.view.KeyEvent.KEYCODE_MINUS -> 12
            android.view.KeyEvent.KEYCODE_EQUALS -> 13
            android.view.KeyEvent.KEYCODE_DEL -> 14
            android.view.KeyEvent.KEYCODE_TAB -> 15
            android.view.KeyEvent.KEYCODE_Q -> 16
            android.view.KeyEvent.KEYCODE_W -> 17
            android.view.KeyEvent.KEYCODE_E -> 18
            android.view.KeyEvent.KEYCODE_R -> 19
            android.view.KeyEvent.KEYCODE_T -> 20
            android.view.KeyEvent.KEYCODE_Y -> 21
            android.view.KeyEvent.KEYCODE_U -> 22
            android.view.KeyEvent.KEYCODE_I -> 23
            android.view.KeyEvent.KEYCODE_O -> 24
            android.view.KeyEvent.KEYCODE_P -> 25
            android.view.KeyEvent.KEYCODE_LEFT_BRACKET -> 26
            android.view.KeyEvent.KEYCODE_RIGHT_BRACKET -> 27
            android.view.KeyEvent.KEYCODE_ENTER -> 28
            android.view.KeyEvent.KEYCODE_CTRL_LEFT -> 29
            android.view.KeyEvent.KEYCODE_A -> 30
            android.view.KeyEvent.KEYCODE_S -> 31
            android.view.KeyEvent.KEYCODE_D -> 32
            android.view.KeyEvent.KEYCODE_F -> 33
            android.view.KeyEvent.KEYCODE_G -> 34
            android.view.KeyEvent.KEYCODE_H -> 35
            android.view.KeyEvent.KEYCODE_J -> 36
            android.view.KeyEvent.KEYCODE_K -> 37
            android.view.KeyEvent.KEYCODE_L -> 38
            android.view.KeyEvent.KEYCODE_SEMICOLON -> 39
            android.view.KeyEvent.KEYCODE_APOSTROPHE -> 40
            android.view.KeyEvent.KEYCODE_GRAVE -> 41
            android.view.KeyEvent.KEYCODE_SHIFT_LEFT -> 42
            android.view.KeyEvent.KEYCODE_BACKSLASH -> 43
            android.view.KeyEvent.KEYCODE_Z -> 44
            android.view.KeyEvent.KEYCODE_X -> 45
            android.view.KeyEvent.KEYCODE_C -> 46
            android.view.KeyEvent.KEYCODE_V -> 47
            android.view.KeyEvent.KEYCODE_B -> 48
            android.view.KeyEvent.KEYCODE_N -> 49
            android.view.KeyEvent.KEYCODE_M -> 50
            android.view.KeyEvent.KEYCODE_COMMA -> 51
            android.view.KeyEvent.KEYCODE_PERIOD -> 52
            android.view.KeyEvent.KEYCODE_SLASH -> 53
            android.view.KeyEvent.KEYCODE_SHIFT_RIGHT -> 54
            android.view.KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> 55
            android.view.KeyEvent.KEYCODE_ALT_LEFT -> 56
            android.view.KeyEvent.KEYCODE_SPACE -> 57
            android.view.KeyEvent.KEYCODE_CAPS_LOCK -> 58
            android.view.KeyEvent.KEYCODE_F1 -> 59
            android.view.KeyEvent.KEYCODE_F2 -> 60
            android.view.KeyEvent.KEYCODE_F3 -> 61
            android.view.KeyEvent.KEYCODE_F4 -> 62
            android.view.KeyEvent.KEYCODE_F5 -> 63
            android.view.KeyEvent.KEYCODE_F6 -> 64
            android.view.KeyEvent.KEYCODE_F7 -> 65
            android.view.KeyEvent.KEYCODE_F8 -> 66
            android.view.KeyEvent.KEYCODE_F9 -> 67
            android.view.KeyEvent.KEYCODE_F10 -> 68
            android.view.KeyEvent.KEYCODE_NUM_LOCK -> 69
            android.view.KeyEvent.KEYCODE_SCROLL_LOCK -> 70
            
            // Navigation cluster
            android.view.KeyEvent.KEYCODE_DPAD_UP -> 72
            android.view.KeyEvent.KEYCODE_PAGE_UP -> 73
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> 80
            android.view.KeyEvent.KEYCODE_PAGE_DOWN -> 74
            
            // Keypad
            android.view.KeyEvent.KEYCODE_NUMPAD_7 -> 79
            android.view.KeyEvent.KEYCODE_NUMPAD_8 -> 80
            android.view.KeyEvent.KEYCODE_NUMPAD_9 -> 81
            android.view.KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> 82
            android.view.KeyEvent.KEYCODE_NUMPAD_4 -> 83
            android.view.KeyEvent.KEYCODE_NUMPAD_5 -> 84
            android.view.KeyEvent.KEYCODE_NUMPAD_6 -> 85
            android.view.KeyEvent.KEYCODE_NUMPAD_ADD -> 86
            android.view.KeyEvent.KEYCODE_NUMPAD_1 -> 87
            android.view.KeyEvent.KEYCODE_NUMPAD_2 -> 88
            android.view.KeyEvent.KEYCODE_NUMPAD_3 -> 89
            android.view.KeyEvent.KEYCODE_NUMPAD_0 -> 90
            android.view.KeyEvent.KEYCODE_NUMPAD_DOT -> 91
            
            // More navigation
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> 105
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> 106
            android.view.KeyEvent.KEYCODE_MOVE_END -> 107
            android.view.KeyEvent.KEYCODE_PAGE_DOWN -> 109
            android.view.KeyEvent.KEYCODE_INSERT -> 110
            android.view.KeyEvent.KEYCODE_FORWARD_DEL -> 111
            android.view.KeyEvent.KEYCODE_MOVE_HOME -> 102
            
            // Numpad enter and other
            android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> 96
            android.view.KeyEvent.KEYCODE_CTRL_RIGHT -> 97
            android.view.KeyEvent.KEYCODE_SYSRQ -> 99
            android.view.KeyEvent.KEYCODE_ALT_RIGHT -> 100
            
            // Function keys F11-F12
            android.view.KeyEvent.KEYCODE_F11 -> 87
            android.view.KeyEvent.KEYCODE_F12 -> 88
            
            // Keypad divide
            android.view.KeyEvent.KEYCODE_NUMPAD_DIVIDE -> 84
            
            else -> 0
        }
    }

    @NonNull
    override fun toString(): String {
        return when (this) {
            KEY_SHIFT_L -> "L SHIFT"
            KEY_SHIFT_R -> "R SHIFT"
            KEY_CTRL_L -> "L CTRL"
            KEY_CTRL_R -> "R CTRL"
            KEY_ALT_L -> "L ALT"
            KEY_ALT_R -> "R ALT"
            KEY_BRACKET_LEFT -> "["
            KEY_BRACKET_RIGHT -> "]"
            KEY_BACKSLASH -> "\\"
            KEY_SLASH -> "/"
            KEY_SEMICOLON -> ";"
            KEY_COMMA -> ","
            KEY_PERIOD -> "."
            KEY_APOSTROPHE -> "'"
            KEY_MINUS -> "-"
            KEY_KP_ADD -> "+"
            else -> super.toString().replace(Regex("^(MOUSE_)|(KEY_)|(GAMEPAD_)"), "").replace("KP_", "NUMPAD_").replace("_", " ")
        }
    }

    fun getPointerButton(): Pointer.Button? {
        return when (this) {
            MOUSE_LEFT_BUTTON -> Pointer.Button.BUTTON_LEFT
            MOUSE_MIDDLE_BUTTON -> Pointer.Button.BUTTON_MIDDLE
            MOUSE_RIGHT_BUTTON -> Pointer.Button.BUTTON_RIGHT
            MOUSE_SCROLL_UP -> Pointer.Button.BUTTON_SCROLL_UP
            MOUSE_SCROLL_DOWN -> Pointer.Button.BUTTON_SCROLL_DOWN
            else -> null
        }
    }

    fun isMouse(): Boolean {
        return name.startsWith("MOUSE_")
    }

    fun isKeyboard(): Boolean {
        return name.startsWith("KEY_") || this == NONE
    }

    fun isGamepad(): Boolean {
        return name.startsWith("GAMEPAD_")
    }

    fun isMouseMove(): Boolean {
        return this == MOUSE_MOVE_UP || this == MOUSE_MOVE_RIGHT || this == MOUSE_MOVE_DOWN || this == MOUSE_MOVE_LEFT
    }

    companion object {
        fun fromString(name: String): Binding {
            return when (name) {
                "KEY_CTRL" -> KEY_CTRL_L
                "KEY_SHIFT" -> KEY_SHIFT_L
                "KEY_ALT" -> KEY_ALT_L
                else -> valueOf(name)
            }
        }

        fun mouseBindingLabels(): Array<String> {
            return entries.filter { it.isMouse() }.map { it.toString() }.toTypedArray()
        }

        fun keyboardBindingLabels(): Array<String> {
            return entries.filter { it.isKeyboard() }.map { it.toString() }.toTypedArray()
        }

        fun gamepadBindingLabels(): Array<String> {
            return entries.filter { it.isGamepad() }.map { it.toString() }.toTypedArray()
        }

        fun mouseBindingValues(): Array<Binding> {
            return entries.filter { it.isMouse() }.toTypedArray()
        }

        fun keyboardBindingValues(): Array<Binding> {
            return entries.filter { it.isKeyboard() }.toTypedArray()
        }

        fun gamepadBindingValues(): Array<Binding> {
            return entries.filter { it.isGamepad() }.toTypedArray()
        }
    }
}
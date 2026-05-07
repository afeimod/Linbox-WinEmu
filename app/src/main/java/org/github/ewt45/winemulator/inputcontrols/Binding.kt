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
     * Convert Binding to Linux evdev keycode
     * This is needed because the X11InputSender expects evdev keycodes,
     * but the XKeycode enum uses X11 keycode values.
     */
    fun toEvdev(): Int {
        return when (this) {
            // Navigation cluster
            KEY_UP -> 72
            KEY_DOWN -> 80
            KEY_LEFT -> 105
            KEY_RIGHT -> 106
            KEY_HOME -> 102
            KEY_PRTSCN -> 127
            KEY_PG_UP -> 112
            KEY_PG_DOWN -> 117
            
            // Modifiers
            KEY_CTRL_L -> 29
            KEY_CTRL_R -> 97
            KEY_SHIFT_L -> 42
            KEY_SHIFT_R -> 54
            KEY_ALT_L -> 56
            KEY_ALT_R -> 100
            
            // Lock keys
            KEY_CAPS_LOCK -> 58
            KEY_NUM_LOCK -> 69
            
            // Special keys
            KEY_ENTER -> 28
            KEY_ESC -> 1
            KEY_BKSP -> 14
            KEY_DEL -> 111
            KEY_TAB -> 15
            KEY_SPACE -> 57
            
            // Numbers 1-9, 0
            KEY_1 -> 2
            KEY_2 -> 3
            KEY_3 -> 4
            KEY_4 -> 5
            KEY_5 -> 6
            KEY_6 -> 7
            KEY_7 -> 8
            KEY_8 -> 9
            KEY_9 -> 10
            KEY_0 -> 11
            
            // Operators
            KEY_MINUS -> 12
            KEY_KP_ADD -> 86
            
            // Letters A-Z
            KEY_A -> 30
            KEY_B -> 48
            KEY_C -> 46
            KEY_D -> 32
            KEY_E -> 18
            KEY_F -> 33
            KEY_G -> 34
            KEY_H -> 35
            KEY_I -> 23
            KEY_J -> 36
            KEY_K -> 37
            KEY_L -> 38
            KEY_M -> 50
            KEY_N -> 49
            KEY_O -> 24
            KEY_P -> 25
            KEY_Q -> 16
            KEY_R -> 19
            KEY_S -> 31
            KEY_T -> 20
            KEY_U -> 22
            KEY_V -> 47
            KEY_W -> 17
            KEY_X -> 45
            KEY_Y -> 21
            KEY_Z -> 44
            
            // Symbols
            KEY_BRACKET_LEFT -> 26
            KEY_BRACKET_RIGHT -> 27
            KEY_BACKSLASH -> 43
            KEY_SLASH -> 53
            KEY_SEMICOLON -> 39
            KEY_COMMA -> 51
            KEY_PERIOD -> 52
            KEY_APOSTROPHE -> 40
            
            // Function keys F1-F12
            KEY_F1 -> 59
            KEY_F2 -> 60
            KEY_F3 -> 61
            KEY_F4 -> 62
            KEY_F5 -> 63
            KEY_F6 -> 64
            KEY_F7 -> 65
            KEY_F8 -> 66
            KEY_F9 -> 67
            KEY_F10 -> 68
            KEY_F11 -> 87
            KEY_F12 -> 88
            
            // Numpad keys
            KEY_KP_0 -> 90
            KEY_KP_1 -> 87
            KEY_KP_2 -> 88
            KEY_KP_3 -> 89
            KEY_KP_4 -> 83
            KEY_KP_5 -> 84
            KEY_KP_6 -> 85
            KEY_KP_7 -> 79
            KEY_KP_8 -> 80
            KEY_KP_9 -> 81
            
            // None
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
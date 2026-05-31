package com.termux.x11.controller.inputcontrols

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * X11 Input Injector for termux-x11
 * Sends keyboard and mouse events to the X server via Unix socket
 */
class X11InputInjector {
    companion object {
        private const val TAG = "X11InputInjector"
        private const val DISPLAY = ":13"

        // Mouse button constants for X11
        const val BUTTON_LEFT = 1
        const val BUTTON_RIGHT = 3
        const val BUTTON_MIDDLE = 2

        // X11 KeySyms for common keys
        const val XK_Escape = 0xFF1B
        const val XK_Return = 0xFF0D
        const val XK_Tab = 0xFF09
        const val XK_space = 0x0020
        const val XK_BackSpace = 0xFF08

        // X11 KeyCodes for Linux (evdev)
        // These are the evdev keycodes used by X server
        const val KEY_ESC = 1
        const val KEY_1 = 2
        const val KEY_2 = 3
        const val KEY_3 = 4
        const val KEY_4 = 5
        const val KEY_5 = 6
        const val KEY_6 = 7
        const val KEY_7 = 8
        const val KEY_8 = 9
        const val KEY_9 = 10
        const val KEY_0 = 11
        const val KEY_MINUS = 12
        const val KEY_EQUAL = 13
        const val KEY_BKSP = 14
        const val KEY_TAB = 15
        const val KEY_Q = 16
        const val KEY_W = 17
        const val KEY_E = 18
        const val KEY_R = 19
        const val KEY_T = 20
        const val KEY_Y = 21
        const val KEY_U = 22
        const val KEY_I = 23
        const val KEY_O = 24
        const val KEY_P = 25
        const val KEY_BRACKET_LEFT = 26
        const val KEY_BRACKET_RIGHT = 27
        const val KEY_ENTER = 28
        const val KEY_CTRL_L = 29
        const val KEY_A = 30
        const val KEY_S = 31
        const val KEY_D = 32
        const val KEY_F = 33
        const val KEY_G = 34
        const val KEY_H = 35
        const val KEY_J = 36
        const val KEY_K = 37
        const val KEY_L = 38
        const val KEY_SEMICOLON = 39
        const val KEY_APOSTROPHE = 40
        const val KEY_GRAVE = 41
        const val KEY_SHIFT_L = 42
        const val KEY_BACKSLASH = 43
        const val KEY_Z = 44
        const val KEY_X = 45
        const val KEY_C = 46
        const val KEY_V = 47
        const val KEY_B = 48
        const val KEY_N = 49
        const val KEY_M = 50
        const val KEY_COMMA = 51
        const val KEY_PERIOD = 52
        const val KEY_SLASH = 53
        const val KEY_SHIFT_R = 54
        const val KEY_KP_MULTIPLY = 55
        const val KEY_ALT_L = 56
        const val KEY_SPACE = 57
        const val KEY_CAPS_LOCK = 58
        const val KEY_F1 = 59
        const val KEY_F2 = 60
        const val KEY_F3 = 61
        const val KEY_F4 = 62
        const val KEY_F5 = 63
        const val KEY_F6 = 64
        const val KEY_F7 = 65
        const val KEY_F8 = 66
        const val KEY_F9 = 67
        const val KEY_F10 = 68
        const val KEY_NUM_LOCK = 69
        const val KEY_SCROLL_LOCK = 71
        const val KEY_KP_7 = 79
        const val KEY_KP_8 = 80
        const val KEY_KP_9 = 81
        const val KEY_KP_MINUS = 82
        const val KEY_KP_4 = 83
        const val KEY_KP_5 = 84
        const val KEY_KP_6 = 85
        const val KEY_KP_ADD = 86
        const val KEY_KP_1 = 87
        const val KEY_KP_2 = 88
        const val KEY_KP_3 = 89
        const val KEY_KP_0 = 90
        const val KEY_KP_DECIMAL = 91
        const val KEY_UP = 73  // PageUp keycode
        const val KEY_LEFT = 105
        const val KEY_RIGHT = 106
        const val KEY_DOWN = 74   // PageDown keycode
        const val KEY_HOME = 102
        const val KEY_END = 107
        const val KEY_INSERT = 110
        const val KEY_DELETE = 111
        const val KEY_PGUP = 104
        const val KEY_PGDN = 109

        // Mouse buttons
        const val Button1 = 1
        const val Button2 = 2
        const val Button3 = 3
        const val Button4 = 4
        const val Button5 = 5

        // Convert Android keycode to evdev keycode
        fun androidKeycodeToEvdev(keycode: Int): Int {
            return when (keycode) {
                android.view.KeyEvent.KEYCODE_ESCAPE -> KEY_ESC
                android.view.KeyEvent.KEYCODE_ENTER -> KEY_ENTER
                android.view.KeyEvent.KEYCODE_TAB -> KEY_TAB
                android.view.KeyEvent.KEYCODE_SPACE -> KEY_SPACE
                android.view.KeyEvent.KEYCODE_DEL -> KEY_BKSP
                android.view.KeyEvent.KEYCODE_A -> KEY_A
                android.view.KeyEvent.KEYCODE_B -> KEY_B
                android.view.KeyEvent.KEYCODE_C -> KEY_C
                android.view.KeyEvent.KEYCODE_D -> KEY_D
                android.view.KeyEvent.KEYCODE_E -> KEY_E
                android.view.KeyEvent.KEYCODE_F -> KEY_F
                android.view.KeyEvent.KEYCODE_G -> KEY_G
                android.view.KeyEvent.KEYCODE_H -> KEY_H
                android.view.KeyEvent.KEYCODE_I -> KEY_I
                android.view.KeyEvent.KEYCODE_J -> KEY_J
                android.view.KeyEvent.KEYCODE_K -> KEY_K
                android.view.KeyEvent.KEYCODE_L -> KEY_L
                android.view.KeyEvent.KEYCODE_M -> KEY_M
                android.view.KeyEvent.KEYCODE_N -> KEY_N
                android.view.KeyEvent.KEYCODE_O -> KEY_O
                android.view.KeyEvent.KEYCODE_P -> KEY_P
                android.view.KeyEvent.KEYCODE_Q -> KEY_Q
                android.view.KeyEvent.KEYCODE_R -> KEY_R
                android.view.KeyEvent.KEYCODE_S -> KEY_S
                android.view.KeyEvent.KEYCODE_T -> KEY_T
                android.view.KeyEvent.KEYCODE_U -> KEY_U
                android.view.KeyEvent.KEYCODE_V -> KEY_V
                android.view.KeyEvent.KEYCODE_W -> KEY_W
                android.view.KeyEvent.KEYCODE_X -> KEY_X
                android.view.KeyEvent.KEYCODE_Y -> KEY_Y
                android.view.KeyEvent.KEYCODE_Z -> KEY_Z
                android.view.KeyEvent.KEYCODE_0 -> KEY_0
                android.view.KeyEvent.KEYCODE_1 -> KEY_1
                android.view.KeyEvent.KEYCODE_2 -> KEY_2
                android.view.KeyEvent.KEYCODE_3 -> KEY_3
                android.view.KeyEvent.KEYCODE_4 -> KEY_4
                android.view.KeyEvent.KEYCODE_5 -> KEY_5
                android.view.KeyEvent.KEYCODE_6 -> KEY_6
                android.view.KeyEvent.KEYCODE_7 -> KEY_7
                android.view.KeyEvent.KEYCODE_8 -> KEY_8
                android.view.KeyEvent.KEYCODE_9 -> KEY_9
                android.view.KeyEvent.KEYCODE_MINUS -> KEY_MINUS
                android.view.KeyEvent.KEYCODE_EQUALS -> KEY_EQUAL
                android.view.KeyEvent.KEYCODE_LEFT_BRACKET -> KEY_BRACKET_LEFT
                android.view.KeyEvent.KEYCODE_RIGHT_BRACKET -> KEY_BRACKET_RIGHT
                android.view.KeyEvent.KEYCODE_BACKSLASH -> KEY_BACKSLASH
                android.view.KeyEvent.KEYCODE_SEMICOLON -> KEY_SEMICOLON
                android.view.KeyEvent.KEYCODE_APOSTROPHE -> KEY_APOSTROPHE
                android.view.KeyEvent.KEYCODE_GRAVE -> KEY_GRAVE
                android.view.KeyEvent.KEYCODE_COMMA -> KEY_COMMA
                android.view.KeyEvent.KEYCODE_PERIOD -> KEY_PERIOD
                android.view.KeyEvent.KEYCODE_SLASH -> KEY_SLASH
                android.view.KeyEvent.KEYCODE_SHIFT_LEFT -> KEY_SHIFT_L
                android.view.KeyEvent.KEYCODE_SHIFT_RIGHT -> KEY_SHIFT_R
                android.view.KeyEvent.KEYCODE_ALT_LEFT -> KEY_ALT_L
                android.view.KeyEvent.KEYCODE_ALT_RIGHT -> KEY_ALT_L
                android.view.KeyEvent.KEYCODE_CTRL_LEFT -> KEY_CTRL_L
                android.view.KeyEvent.KEYCODE_CTRL_RIGHT -> KEY_CTRL_L
                android.view.KeyEvent.KEYCODE_CAPS_LOCK -> KEY_CAPS_LOCK
                android.view.KeyEvent.KEYCODE_SCROLL_LOCK -> KEY_SCROLL_LOCK
                android.view.KeyEvent.KEYCODE_NUM_LOCK -> KEY_NUM_LOCK
                android.view.KeyEvent.KEYCODE_F1 -> KEY_F1
                android.view.KeyEvent.KEYCODE_F2 -> KEY_F2
                android.view.KeyEvent.KEYCODE_F3 -> KEY_F3
                android.view.KeyEvent.KEYCODE_F4 -> KEY_F4
                android.view.KeyEvent.KEYCODE_F5 -> KEY_F5
                android.view.KeyEvent.KEYCODE_F6 -> KEY_F6
                android.view.KeyEvent.KEYCODE_F7 -> KEY_F7
                android.view.KeyEvent.KEYCODE_F8 -> KEY_F8
                android.view.KeyEvent.KEYCODE_F9 -> KEY_F9
                android.view.KeyEvent.KEYCODE_F10 -> KEY_F10
                android.view.KeyEvent.KEYCODE_F11 -> KEY_F1  // Not directly available
                android.view.KeyEvent.KEYCODE_F12 -> KEY_F2  // Not directly available
                android.view.KeyEvent.KEYCODE_NUMPAD_0 -> KEY_KP_0
                android.view.KeyEvent.KEYCODE_NUMPAD_1 -> KEY_KP_1
                android.view.KeyEvent.KEYCODE_NUMPAD_2 -> KEY_KP_2
                android.view.KeyEvent.KEYCODE_NUMPAD_3 -> KEY_KP_3
                android.view.KeyEvent.KEYCODE_NUMPAD_4 -> KEY_KP_4
                android.view.KeyEvent.KEYCODE_NUMPAD_5 -> KEY_KP_5
                android.view.KeyEvent.KEYCODE_NUMPAD_6 -> KEY_KP_6
                android.view.KeyEvent.KEYCODE_NUMPAD_7 -> KEY_KP_7
                android.view.KeyEvent.KEYCODE_NUMPAD_8 -> KEY_KP_8
                android.view.KeyEvent.KEYCODE_NUMPAD_9 -> KEY_KP_9
                android.view.KeyEvent.KEYCODE_NUMPAD_DIVIDE -> 84
                android.view.KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> KEY_KP_MULTIPLY
                android.view.KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> 82
                android.view.KeyEvent.KEYCODE_NUMPAD_ADD -> KEY_KP_ADD
                android.view.KeyEvent.KEYCODE_NUMPAD_DOT -> KEY_KP_DECIMAL
                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> KEY_ENTER
                android.view.KeyEvent.KEYCODE_DPAD_UP -> KEY_UP
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> KEY_DOWN
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> KEY_LEFT
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> KEY_RIGHT
                android.view.KeyEvent.KEYCODE_MOVE_HOME -> KEY_HOME
                android.view.KeyEvent.KEYCODE_MOVE_END -> KEY_END
                android.view.KeyEvent.KEYCODE_PAGE_UP -> KEY_PGUP
                android.view.KeyEvent.KEYCODE_PAGE_DOWN -> KEY_PGDN
                android.view.KeyEvent.KEYCODE_INSERT -> KEY_INSERT
                android.view.KeyEvent.KEYCODE_FORWARD_DEL -> KEY_DELETE
                else -> keycode
            }
        }
    }

    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private var input: DataInputStream? = null
    private var windowId: Int = 0
    private var isConnected = false

    // X11 protocol constants
    private val X11_PORT = 6013  // 6000 + 13 (display number)

    /**
     * Connect to the X server
     */
    fun connect(): Boolean {
        if (isConnected) return true

        try {
            socket = Socket()
            socket?.connect(InetSocketAddress("127.0.0.1", X11_PORT), 1000)
            output = DataOutputStream(socket?.getOutputStream())
            input = DataInputStream(socket?.getInputStream())

            // Read initial connection response
            readConnectionResponse()
            isConnected = true
            Log.d(TAG, "Connected to X11 server")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to X11 server: ${e.message}")
            disconnect()
            return false
        }
    }

    private fun readConnectionResponse() {
        try {
            // Read authentication name length
            val authNameLen = input?.readInt() ?: 0
            val authDataLen = input?.readInt() ?: 0
            val responseLen = input?.readInt() ?: 0

            // Skip remaining bytes
            input?.skipBytes(responseLen * 4)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading connection response: ${e.message}")
        }
    }

    /**
     * Disconnect from the X server
     */
    fun disconnect() {
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting: ${e.message}")
        }
        socket = null
        output = null
        input = null
        isConnected = false
    }

    /**
     * Send a key press event
     */
    fun sendKeyPress(keycode: Int) {
        if (!isConnected) return
        sendXTestKeyEvent(keycode, true)
    }

    /**
     * Send a key release event
     */
    fun sendKeyRelease(keycode: Int) {
        if (!isConnected) return
        sendXTestKeyEvent(keycode, false)
    }

    /**
     * Send mouse button press
     */
    fun sendMouseButtonPress(button: Int) {
        if (!isConnected) return
        sendXTestButtonEvent(button, true)
    }

    /**
     * Send mouse button release
     */
    fun sendMouseButtonRelease(button: Int) {
        if (!isConnected) return
        sendXTestButtonEvent(button, false)
    }

    /**
     * Send mouse motion event
     */
    fun sendMouseMotion(dx: Int, dy: Int) {
        if (!isConnected) return
        sendXTestMotionEvent(dx, dy)
    }

    private fun sendXTestKeyEvent(keycode: Int, isPress: Boolean) {
        try {
            val opcode = 37  // XTEST extension opcode (may vary)
            val eventType = if (isPress) 2 else 3  // KeyPress or KeyRelease
            val delay = 0

            // XTEST FakeKeyEvent request
            val data = ByteArray(32)
            data[0] = 1  // Major opcode
            data[1] = opcode.toByte()  // XTEST opcode
            data[2] = 2  // FakeKey
            data[3] = 0  // Padding
            writeInt32(data, 4, if (isPress) 1L else 0L)  // isPress
            writeInt32(data, 8, keycode.toLong().and(0xFF))  // keycode
            writeInt32(data, 12, delay.toLong())  // delay

            output?.write(data)
            output?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending key event: ${e.message}")
        }
    }

    private fun sendXTestButtonEvent(button: Int, isPress: Boolean) {
        try {
            val data = ByteArray(32)
            data[0] = 1  // Major opcode
            data[1] = 37.toByte()  // XTEST opcode
            data[2] = 4  // FakeButton
            data[3] = 0  // Padding
            writeInt32(data, 4, if (isPress) 1L else 0L)  // isPress
            writeInt32(data, 8, button.toLong().and(0xFF))  // button
            writeInt32(data, 12, 0L)  // delay

            output?.write(data)
            output?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending button event: ${e.message}")
        }
    }

    private fun sendXTestMotionEvent(dx: Int, dy: Int) {
        try {
            val data = ByteArray(32)
            data[0] = 1  // Major opcode
            data[1] = 37.toByte()  // XTEST opcode
            data[2] = 3  // FakeMotion
            data[3] = 0  // Padding
            writeInt32(data, 4, -1L)  // root window (screen)
            writeInt32(data, 8, dx.toLong())  // x
            writeInt32(data, 12, dy.toLong())  // y

            output?.write(data)
            output?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending motion event: ${e.message}")
        }
    }

    private fun writeInt32(buffer: ByteArray, offset: Int, value: Long) {
        buffer[offset] = (value.and(0xFF)).toByte()
        buffer[offset + 1] = (value.shr(8).and(0xFF)).toByte()
        buffer[offset + 2] = (value.shr(16).and(0xFF)).toByte()
        buffer[offset + 3] = (value.shr(24).and(0xFF)).toByte()
    }
}

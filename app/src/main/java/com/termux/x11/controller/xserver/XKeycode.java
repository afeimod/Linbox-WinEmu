package com.termux.x11.controller.xserver;

import java.util.HashMap;
import java.util.Map;

/** The enum class that defines X11 keycodes. */
public enum XKeycode {
    KEY_UNKNOWN(0, 0),
    KEY_ESC(1, 65307),
    KEY_1(2, 49),
    KEY_2(3, 50),
    KEY_3(4, 51),
    KEY_4(5, 52),
    KEY_5(6, 53),
    KEY_6(7, 54),
    KEY_7(8, 55),
    KEY_8(9, 56),
    KEY_9(10, 57),
    KEY_0(11, 48),
    KEY_MINUS(12, 45),
    KEY_EQUAL(13, 61),
    KEY_BACKSPACE(14, 65288),
    KEY_TAB(15, 65289),
    KEY_Q(16, 113),
    KEY_W(17, 119),
    KEY_E(18, 101),
    KEY_R(19, 114),
    KEY_T(20, 116),
    KEY_U(21, 117),
    KEY_I(22, 105),
    KEY_O(23, 111),
    KEY_P(24, 112),
    KEY_BRACKET_LEFT(26, 91),
    KEY_BRACKET_RIGHT(27, 93),
    KEY_ENTER(28, 65293),
    KEY_LEFTCTRL(29, 65507),
    KEY_RIGHTCTRL(105, 65508),
    KEY_A(30, 97),
    KEY_S(31, 115),
    KEY_D(32, 100),
    KEY_F(33, 102),
    KEY_G(34, 103),
    KEY_H(35, 104),
    KEY_J(36, 106),
    KEY_K(37, 107),
    KEY_L(38, 108),
    KEY_SEMICOLON(39, 59),
    KEY_APOSTROPHE(40, 39),
    KEY_GRAVE(41, 96),
    KEY_LEFTSHIFT(42, 65505),
    KEY_RIGHTSHIFT(54, 65506),
    KEY_BACKSLASH(43, 92),
    KEY_Z(44, 122),
    KEY_X(45, 120),
    KEY_C(46, 99),
    KEY_V(47, 118),
    KEY_B(48, 98),
    KEY_N(49, 110),
    KEY_M(50, 109),
    KEY_COMMA(51, 44),
    KEY_PERIOD(52, 46),
    KEY_SLASH(53, 47),
    KEY_KP_MULTIPLY(55, 65450),
    KEY_LEFTALT(56, 65511),
    KEY_RIGHTALT(108, 65512),
    KEY_SPACE(57, 32),
    KEY_CAPSLOCK(57, 65509),
    KEY_F1(59, 65472),
    KEY_F2(60, 65473),
    KEY_F3(61, 65474),
    KEY_F4(62, 65475),
    KEY_F5(63, 65476),
    KEY_F6(64, 65477),
    KEY_F7(65, 65478),
    KEY_F8(66, 65479),
    KEY_F9(67, 65480),
    KEY_F10(68, 65481),
    KEY_NUMLOCK(87, 65407),
    KEY_SCROLLLOCK(70, 65366),
    KEY_KP_7(71, 65456),
    KEY_KP_8(72, 65457),
    KEY_KP_9(73, 65458),
    KEY_KP_MINUS(74, 65453),
    KEY_KP_4(75, 65452),
    KEY_KP_5(76, 65453),
    KEY_KP_6(77, 65454),
    KEY_KP_ADD(78, 65451),
    KEY_KP_SUBTRACT(74, 65454),
    KEY_KP_DIVIDE(53, 65455),
    KEY_KP_DEL(83, 65454),
    KEY_CAPS_LOCK(57, 65509),
    KEY_NUM_LOCK(87, 65407),
    KEY_Y(25, 121),
    KEY_END_ALT(KEY_END.code, KEY_END.id);

    public final int code;
    public final int id;

    private static final Map<String, XKeycode> BY_NAME = new HashMap<>();

    static {
        for (XKeycode kc : values()) {
            BY_NAME.put(kc.name(), kc);
        }
    }

    XKeycode(int code, int id) {
        this.code = code;
        this.id = id;
    }

    public int getCode() {
        return code;
    }

    public int getId() {
        return id;
    }

    public static XKeycode fromCode(int code) {
        for (XKeycode kc : values()) {
            if (kc.code == code) return kc;
        }
        return KEY_UNKNOWN;
    }

    public static XKeycode fromString(String name) {
        XKeycode result = BY_NAME.get(name);
        if (result == null) {
            throw new IllegalArgumentException("No enum constant " + name);
        }
        return result;
    }

    /**
     * Convert evdev keycode to XKeycode
     */
    public static XKeycode fromEvdev(int evdevKeycode) {
        switch (evdevKeycode) {
            case 1: return KEY_ESC;
            case 2: return KEY_1;
            case 3: return KEY_2;
            case 4: return KEY_3;
            case 5: return KEY_4;
            case 6: return KEY_5;
            case 7: return KEY_6;
            case 8: return KEY_7;
            case 9: return KEY_8;
            case 10: return KEY_9;
            case 11: return KEY_0;
            case 12: return KEY_MINUS;
            case 14: return KEY_BACKSPACE;
            case 15: return KEY_TAB;
            case 16: return KEY_Q;
            case 17: return KEY_W;
            case 18: return KEY_E;
            case 19: return KEY_R;
            case 20: return KEY_T;
            case 21: return KEY_U;
            case 22: return KEY_I;
            case 23: return KEY_O;
            case 24: return KEY_P;
            case 25: return KEY_BRACKET_LEFT;
            case 26: return KEY_BRACKET_RIGHT;
            case 28: return KEY_ENTER;
            case 29: return KEY_LEFTCTRL;
            case 30: return KEY_A;
            case 31: return KEY_S;
            case 32: return KEY_D;
            case 33: return KEY_F;
            case 34: return KEY_G;
            case 35: return KEY_H;
            case 36: return KEY_J;
            case 37: return KEY_K;
            case 38: return KEY_L;
            case 39: return KEY_SEMICOLON;
            case 40: return KEY_APOSTROPHE;
            case 41: return KEY_GRAVE;
            case 42: return KEY_LEFTSHIFT;
            case 43: return KEY_BACKSLASH;
            case 44: return KEY_Z;
            case 45: return KEY_X;
            case 46: return KEY_C;
            case 47: return KEY_V;
            case 48: return KEY_B;
            case 49: return KEY_N;
            case 50: return KEY_M;
            case 51: return KEY_COMMA;
            case 52: return KEY_PERIOD;
            case 53: return KEY_SLASH;
            case 54: return KEY_RIGHTSHIFT;
            case 56: return KEY_LEFTALT;
            case 57: return KEY_SPACE;
            case 58: return KEY_CAPSLOCK;
            case 59: return KEY_F1;
            case 60: return KEY_F2;
            case 61: return KEY_F3;
            case 62: return KEY_F4;
            case 63: return KEY_F5;
            case 64: return KEY_F6;
            case 65: return KEY_F7;
            case 66: return KEY_F8;
            case 67: return KEY_F9;
            case 68: return KEY_F10;
            case 87: return KEY_F11;
            case 88: return KEY_F12;
            case 91: return KEY_HOME;
            case 92: return KEY_UP;
            case 93: return KEY_PAGE_UP;
            case 94: return KEY_LEFT;
            case 95: return KEY_RIGHT;
            case 96: return KEY_END;
            case 97: return KEY_DOWN;
            case 98: return KEY_PAGE_DOWN;
            case 99: return KEY_INSERT;
            case 100: return KEY_DELETE;
            case 102: return KEY_HOME;
            case 105: return KEY_LEFT;
            case 106: return KEY_UP;
            case 107: return KEY_END;
            case 108: return KEY_RIGHTALT;
            case 109: return KEY_DOWN;
            case 110: return KEY_END;
            case 111: return KEY_PRINT;
            case 112: return KEY_DELETE;
            case 113: return KEY_PAUSE;
            case 114: return KEY_PRINT;
            case 127: return KEY_PAUSE;
            default: return KEY_UNKNOWN;
        }
    }
}
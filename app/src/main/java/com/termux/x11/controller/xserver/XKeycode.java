package com.termux.x11.controller.xserver;

/** The {@link Class} that defines X11 keycodes. */
public class XKeycode {
    // Mapping from evdev keycodes to X11 keycodes
    private static final int[] EVDEV_TO_X = {
        0,   // 0 - KEY_RESERVED
        1,   // 1 - KEY_ESC
        2,   // 2 - KEY_1
        3,   // 3 - KEY_2
        4,   // 4 - KEY_3
        5,   // 5 - KEY_4
        6,   // 6 - KEY_5
        7,   // 7 - KEY_6
        8,   // 8 - KEY_7
        9,   // 9 - KEY_8
        10,  // 10 - KEY_9
        11,  // 11 - KEY_0
        12,  // 12 - KEY_MINUS
        13,  // 13 - KEY_EQUAL
        14,  // 14 - KEY_BACKSPACE
        15,  // 15 - KEY_TAB
        16,  // 16 - KEY_Q
        17,  // 17 - KEY_W
        18,  // 18 - KEY_E
        19,  // 19 - KEY_R
        20,  // 20 - KEY_T
        21,  // 21 - KEY_Y
        22,  // 22 - KEY_U
        23,  // 23 - KEY_I
        24,  // 24 - KEY_O
        25,  // 25 - KEY_P
        26,  // 26 - KEY_BRACKET_LEFT
        27,  // 27 - KEY_BRACKET_RIGHT
        28,  // 28 - KEY_ENTER
        29,  // 29 - KEY_LEFTCTRL
        30,  // 30 - KEY_A
        31,  // 31 - KEY_S
        32,  // 32 - KEY_D
        33,  // 33 - KEY_F
        34,  // 34 - KEY_G
        35,  // 35 - KEY_H
        36,  // 36 - KEY_J
        37,  // 37 - KEY_K
        38,  // 38 - KEY_L
        39,  // 39 - KEY_SEMICOLON
        40,  // 40 - KEY_APOSTROPHE
        41,  // 41 - KEY_GRAVE
        42,  // 42 - KEY_LEFTSHIFT
        43,  // 43 - KEY_BACKSLASH
        44,  // 44 - KEY_Z
        45,  // 45 - KEY_X
        46,  // 46 - KEY_C
        47,  // 47 - KEY_V
        48,  // 48 - KEY_B
        49,  // 49 - KEY_N
        50,  // 50 - KEY_M
        51,  // 51 - KEY_COMMA
        52,  // 52 - KEY_PERIOD
        53,  // 53 - KEY_SLASH
        54,  // 54 - KEY_RIGHTSHIFT
        55,  // 55 - KEY_KP_MULTIPLY
        56,  // 56 - KEY_LEFTALT
        57,  // 57 - KEY_SPACE
        58,  // 58 - KEY_CAPSLOCK
        59,  // 59 - KEY_F1
        60,  // 60 - KEY_F2
        61,  // 61 - KEY_F3
        62,  // 62 - KEY_F4
        63,  // 63 - KEY_F5
        64,  // 64 - KEY_F6
        65,  // 65 - KEY_F7
        66,  // 66 - KEY_F8
        67,  // 67 - KEY_F9
        68,  // 68 - KEY_F10
        87,  // 69 - KEY_NUMLOCK -> F11
        70,  // 70 - KEY_SCROLLLOCK -> F12
        // ... more mappings as needed
    };

    /**
     * Convert evdev keycode to XKeycode
     */
    public static XKeycode fromEvdev(int evdevKeycode) {
        // For common keys, do a simple mapping
        if (evdevKeycode >= 1 && evdevKeycode < EVDEV_TO_X.length) {
            return new XKeycode(EVDEV_TO_X[evdevKeycode]);
        }
        return new XKeycode(evdevKeycode);
    }

    public final int code;

    public XKeycode(int code) {
        this.code = code;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        XKeycode xKeycode = (XKeycode) o;
        return code == xKeycode.code;
    }

    @Override
    public int hashCode() {
        return code;
    }
    public static final int KEY_UNKNOWN = 0;
    public static final int KEY_ESCAPE = 1;
    public static final int KEY_1 = 2;
    public static final int KEY_2 = 3;
    public static final int KEY_3 = 4;
    public static final int KEY_4 = 5;
    public static final int KEY_5 = 6;
    public static final int KEY_6 = 7;
    public static final int KEY_7 = 8;
    public static final int KEY_8 = 9;
    public static final int KEY_9 = 10;
    public static final int KEY_0 = 11;
    public static final int KEY_MINUS = 12;
    public static final int KEY_EQUAL = 13;
    public static final int KEY_BACKSPACE = 14;
    public static final int KEY_TAB = 15;
    public static final int KEY_Q = 16;
    public static final int KEY_W = 17;
    public static final int KEY_E = 18;
    public static final int KEY_R = 19;
    public static final int KEY_T = 20;
    public static final int KEY_Y = 21;
    public static final int KEY_U = 22;
    public static final int KEY_I = 23;
    public static final int KEY_O = 24;
    public static final int KEY_P = 25;
    public static final int KEY_BRACKET_LEFT = 26;
    public static final int KEY_BRACKET_RIGHT = 27;
    public static final int KEY_ENTER = 28;
    public static final int KEY_LEFTCTRL = 29;
    public static final int KEY_A = 30;
    public static final int KEY_S = 31;
    public static final int KEY_D = 32;
    public static final int KEY_F = 33;
    public static final int KEY_G = 34;
    public static final int KEY_H = 35;
    public static final int KEY_J = 36;
    public static final int KEY_K = 37;
    public static final int KEY_L = 38;
    public static final int KEY_SEMICOLON = 39;
    public static final int KEY_APOSTROPHE = 40;
    public static final int KEY_GRAVE = 41;
    public static final int KEY_LEFTSHIFT = 42;
    public static final int KEY_BACKSLASH = 43;
    public static final int KEY_Z = 44;
    public static final int KEY_X = 45;
    public static final int KEY_C = 46;
    public static final int KEY_V = 47;
    public static final int KEY_B = 48;
    public static final int KEY_N = 49;
    public static final int KEY_M = 50;
    public static final int KEY_COMMA = 51;
    public static final int KEY_PERIOD = 52;
    public static final int KEY_SLASH = 53;
    public static final int KEY_RIGHTSHIFT = 54;
    public static final int KEY_KP_MULTIPLY = 55;
    public static final int KEY_LEFTALT = 56;
    public static final int KEY_SPACE = 57;
    public static final int KEY_CAPSLOCK = 58;
    public static final int KEY_F1 = 59;
    public static final int KEY_F2 = 60;
    public static final int KEY_F3 = 61;
    public static final int KEY_F4 = 62;
    public static final int KEY_F5 = 63;
    public static final int KEY_F6 = 64;
    public static final int KEY_F7 = 65;
    public static final int KEY_F8 = 66;
    public static final int KEY_F9 = 67;
    public static final int KEY_F10 = 68;
    public static final int KEY_NUMLOCK = 69;
    public static final int KEY_SCROLLLOCK = 70;
    public static final int KEY_KP_7 = 71;
    public static final int KEY_KP_8 = 72;
    public static final int KEY_KP_9 = 73;
    public static final int KEY_KP_MINUS = 74;
    public static final int KEY_KP_4 = 75;
    public static final int KEY_KP_5 = 76;
    public static final int KEY_KP_6 = 77;
    public static final int KEY_KP_PLUS = 78;
    public static final int KEY_KP_1 = 79;
    public static final int KEY_KP_2 = 80;
    public static final int KEY_KP_3 = 81;
    public static final int KEY_KP_0 = 82;
    public static final int KEY_KP_DECIMAL = 83;
    public static final int KEY_84 = 84;
    public static final int KEY_85 = 85;
    public static final int KEY_86 = 86;
    public static final int KEY_F11 = 87;
    public static final int KEY_F12 = 88;
    public static final int KEY_89 = 89;
    public static final int KEY_90 = 90;
    public static final int KEY_HOME = 91;
    public static final int KEY_UP = 92;
    public static final int KEY_PAGE_UP = 93;
    public static final int KEY_LEFT = 94;
    public static final int KEY_RIGHT = 95;
    public static final int KEY_END = 96;
    public static final int KEY_DOWN = 97;
    public static final int KEY_PAGE_DOWN = 98;
    public static final int KEY_INSERT = 99;
    public static final int KEY_DELETE = 100;
    public static final int KEY_101 = 101;
    public static final int KEY_102 = 102;
    public static final int KEY_103 = 103;
    public static final int KEY_104 = 104;
    public static final int KEY_105 = 105;
    public static final int KEY_106 = 106;
    public static final int KEY_107 = 107;
    public static final int KEY_108 = 108;
    public static final int KEY_109 = 109;
    public static final int KEY_LEFTMETA = 110;
    public static final int KEY_RIGHTMETA = 111;
    public static final int KEY_COMPOSE = 112;
    public static final int KEY_PAUSE = 113;
    public static final int KEY_PRINT = 114;

    // Additional key aliases
    public static final int KEY_ESC = 1;
    public static final int KEY_BKSP = 14;
    public static final int KEY_DEL = 100;
    public static final int KEY_PRTSCN = 114;
    public static final int KEY_PRIOR = 93;
    public static final int KEY_NEXT = 98;

    // Static XKeycode constant objects for common keys
    public static final XKeycode KEY_NONE = new XKeycode(0);
    public static final XKeycode KEY_ENTER_OBJ = new XKeycode(KEY_ENTER);
}
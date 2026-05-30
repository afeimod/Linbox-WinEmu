package com.termux.x11.controller.xserver;

/** The {@link Class} that defines X11 pointer buttons. */
public class Pointer {
    public enum Button {
        NONE(-1),
        LEFT(1),
        MIDDLE(2),
        RIGHT(3),
        WHEEL_UP(4),
        WHEEL_DOWN(5),
        WHEEL_LEFT(6),
        WHEEL_RIGHT(7);

        private final int code;

        Button(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }

        public static Button fromCode(int code) {
            for (Button button : values()) {
                if (button.code == code) return button;
            }
            return NONE;
        }
    }

    public static final int MOTION = 0;
    public static final int BUTTON_PRESS = 1;
    public static final int BUTTON_RELEASE = 2;
    public static final int WHEEL = 3;
}
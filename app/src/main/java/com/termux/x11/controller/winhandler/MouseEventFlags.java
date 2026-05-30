package com.termux.x11.controller.winhandler;

abstract class MouseEventFlags {
    public static final int NONE = 0;
    public static final int MOVE = 1;
    public static final int LEFT_DOWN = 2;
    public static final int LEFT_UP = 4;
    public static final int RIGHT_DOWN = 8;
    public static final int RIGHT_UP = 16;
    public static final int MIDDLE_DOWN = 32;
    public static final int MIDDLE_UP = 64;
    public static final int WHEEL = 128;
    public static final int HWHEEL = 256;
    public static final int LEFT_AND_RIGHT = LEFT_DOWN | RIGHT_UP;
}
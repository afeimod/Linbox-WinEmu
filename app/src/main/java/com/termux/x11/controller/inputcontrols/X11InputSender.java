package com.termux.x11.controller.inputcontrols;

import com.termux.x11.ILorieView;
import com.termux.x11.controller.xserver.Keyboard;
import com.termux.x11.controller.xserver.Pointer;
import com.termux.x11.controller.xserver.XKeycode;
import com.termux.x11.input.InputStub;
import com.termux.x11.input.RenderData;

/**
 * Sender class for X11 input events.
 *
 * 修复要点 (v7 键位修复):
 * 旧版 X11InputSender.injectKeyPress(XKeycode) 内部直接 sendKeyEvent(XKeycode.id, XKeycode.id, isDown)。
 * XKeycode.id 实际是 termux-x11 内部的 X11 keysym table 索引, 跟 termux-x11 native 端
 * 实际期望的 (keycode, scancode) 数值体系不一致——这导致用户报告"所有键位都乱了,包括方向键"。
 *
 * 修法: 参考 BF 项目 (InputControlsView.kt + Binding.kt), BF 用的是 PC AT Set 1 scancode
 * (KEY_UP=72, KEY_LEFT=75, KEY_A=30, KEY_ESC=1 等)。
 *
 * 这里在 X11InputSender 内部维护一张 XKeycode -> PC AT scancode 的完整映射表,
 * injectKeyPress(XKeycode) 时按映射表查 scancode, 然后调 sendKeyEvent(scancode, scancode, isDown)。
 * 让 termux-x11 native 端能从 scancode 找到 X11 keysym 还原按键。
 */
public class X11InputSender implements ILorieView {
    private InputStub xServer;
    private RenderData renderData;
    private boolean initialized = false;

    // 懒加载
    private Pointer pointer;
    private Keyboard keyboard;
    private final ScreenInfo screenInfo = new ScreenInfo();

    public X11InputSender() {
    }

    public boolean isInitialized() {
        return initialized && xServer != null;
    }

    public void initialize(InputStub xServer) {
        this.xServer = xServer;
        this.initialized = true;
        if (renderData == null) {
            this.renderData = new RenderData();
        }
    }

    public void setXServer(InputStub xServer) {
        this.xServer = xServer;
        this.initialized = (xServer != null);
    }

    // ============= 旧 API（保持兼容）=============

    public void sendEvdevKeyEvent(int scancode, boolean isDown) {
        if (xServer != null) {
            // scancode 已经是 PC AT scancode, 直接发
            xServer.sendKeyEvent(scancode, scancode, isDown);
        }
    }

    public void sendMouseMotionEvent(int dx, int dy) {
        if (xServer != null) {
            xServer.sendMouseEvent(dx, dy, 0, true, false);
        }
    }

    public void sendMouseButtonEvent(int button, boolean isDown) {
        if (xServer != null) {
            xServer.sendMouseEvent(0, 0, button, isDown, false);
        }
    }

    public void forceResetMouseButtons() {
        if (xServer != null) {
            xServer.sendMouseEvent(0, 0, 1, false, false);
            xServer.sendMouseEvent(0, 0, 2, false, false);
            xServer.sendMouseEvent(0, 0, 3, false, false);
        }
    }

    public RenderData getRenderData() {
        return renderData;
    }

    public void setRenderData(RenderData renderData) {
        this.renderData = renderData;
    }

    public void release() {
        this.xServer = null;
        this.initialized = false;
        this.pointer = null;
        this.keyboard = null;
    }

    // ============= ILorieView 接口实现 =============

    @Override
    public com.termux.x11.controller.xserver.Pointer getPointer() {
        if (pointer == null) pointer = new Pointer(this);
        return pointer;
    }

    @Override
    public Keyboard getKeyboard() {
        if (keyboard == null) keyboard = new Keyboard(this);
        return keyboard;
    }

    @Override
    public com.termux.x11.controller.winhandler.WinHandler getWinHandler() {
        return null;
    }

    @Override
    public ScreenInfo getScreenInfo() {
        return screenInfo;
    }

    @Override
    public ScreenInfo getScreenInfoAccessor() {
        return screenInfo;
    }

    @Override
    public com.termux.x11.ILorieView.CursorLocker getCursorLocker() {
        return new com.termux.x11.ILorieView.CursorLocker() {
            @Override
            public void panBy(float dx, float dy) {
                if (xServer != null) xServer.sendMouseEvent(dx, dy, 0, true, false);
            }
        };
    }

    @Override
    public boolean isFullscreen() {
        return true;
    }

    @Override
    public void sendMouseWheelEvent(int dx, int dy) {
        if (xServer != null) xServer.sendMouseWheelEvent(dx, dy);
    }

    @Override
    public void sendMouseEvent(int x, int y, int button, boolean isDown, boolean isAbsolute) {
        if (xServer != null) xServer.sendMouseEvent(x, y, button, isDown, isAbsolute);
    }

    @Override
    public void sendKeyEvent(int keysym, int keycode, boolean isDown) {
        if (xServer != null) xServer.sendKeyEvent(keysym, keycode, isDown);
    }

    @Override
    public void injectPointerMoveDelta(int dx, int dy) {
        if (xServer != null) xServer.sendMouseEvent(dx, dy, 0, true, false);
    }

    @Override
    public void injectPointerButtonPress(int button) {
        if (xServer != null) xServer.sendMouseEvent(0, 0, button, true, false);
    }

    @Override
    public void injectPointerButtonPress(Pointer.Button button) {
        if (xServer != null && button != null) {
            xServer.sendMouseEvent(0, 0, button.code(), true, false);
        }
    }

    @Override
    public void injectPointerButtonRelease(int button) {
        if (xServer != null) xServer.sendMouseEvent(0, 0, button, false, false);
    }

    @Override
    public void injectPointerButtonRelease(Pointer.Button button) {
        if (xServer != null && button != null) {
            xServer.sendMouseEvent(0, 0, button.code(), false, false);
        }
    }

    @Override
    public void injectKeyPress(int keycode) {
        if (xServer != null) xServer.sendKeyEvent(keycode, keycode, true);
    }

    /**
     * 修：把 XKeycode 转成 PC AT scancode, 然后 sendKeyEvent(scancode, scancode, isDown)。
     * 这是参考 BF 项目 (InputControlsView.kt + Binding.kt) 的方案。
     * XKeycode 跟 PC AT scancode 的数值体系完全不一样, 必须做映射。
     */
    @Override
    public void injectKeyPress(XKeycode keycode) {
        if (xServer == null || keycode == null) return;
        int scancode = xkeycodeToScancode(keycode);
        if (scancode <= 0) {
            // 没找到映射, 退回到 XKeycode.id (可能不准确但不抛异常)
            int k = keycode.id & 0xff;
            xServer.sendKeyEvent(k, k, true);
            return;
        }
        xServer.sendKeyEvent(scancode, scancode, true);
    }

    @Override
    public void injectKeyPress(XKeycode keycode, int unicodeChar) {
        injectKeyPress(keycode);
    }

    @Override
    public void injectKeyRelease(int keycode) {
        if (xServer != null) xServer.sendKeyEvent(keycode, keycode, false);
    }

    @Override
    public void injectKeyRelease(XKeycode keycode) {
        if (xServer == null || keycode == null) return;
        int scancode = xkeycodeToScancode(keycode);
        if (scancode <= 0) {
            int k = keycode.id & 0xff;
            xServer.sendKeyEvent(k, k, false);
            return;
        }
        xServer.sendKeyEvent(scancode, scancode, false);
    }

    @Override
    public void injectText(String text) {
        if (xServer == null || text == null) return;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int code = (int) c;
            xServer.sendKeyEvent(code, code, true);
            xServer.sendKeyEvent(code, code, false);
        }
    }

    @Override
    public void refreshViewport() {
    }

    // ============= XKeycode -> PC AT Set 1 scancode 映射 =============

    /**
     * 把 XKeycode 转成 PC AT Set 1 scancode。
     * XKeycode 的 id 是 termux-x11 内部 X11 keysym table 索引 (1..248), 跟 PC AT scancode 不是同一套。
     * termux-x11 的 InputStub.sendKeyEvent 实际期望 PC AT scancode, 所以必须做映射。
     * 参考 BF Binding.kt 提供的 scancode 数值体系。
     */
    private static int xkeycodeToScancode(XKeycode k) {
        if (k == null) return 0;
        switch (k) {
            // 字母 A-Z (Set 1 scancode: 30..58 范围内, A=30, B=48, C=46, ...)
            case KEY_A: return 30;
            case KEY_B: return 48;
            case KEY_C: return 46;
            case KEY_D: return 32;
            case KEY_E: return 18;
            case KEY_F: return 33;
            case KEY_G: return 34;
            case KEY_H: return 35;
            case KEY_I: return 23;
            case KEY_J: return 36;
            case KEY_K: return 37;
            case KEY_L: return 38;
            case KEY_M: return 50;
            case KEY_N: return 49;
            case KEY_O: return 24;
            case KEY_P: return 25;
            case KEY_Q: return 16;
            case KEY_R: return 19;
            case KEY_S: return 31;
            case KEY_T: return 20;
            case KEY_U: return 22;
            case KEY_V: return 47;
            case KEY_W: return 17;
            case KEY_X: return 45;
            case KEY_Y: return 21;
            case KEY_Z: return 44;

            // 数字 0-9
            case KEY_0: return 11;
            case KEY_1: return 2;
            case KEY_2: return 3;
            case KEY_3: return 4;
            case KEY_4: return 5;
            case KEY_5: return 6;
            case KEY_6: return 7;
            case KEY_7: return 8;
            case KEY_8: return 9;
            case KEY_9: return 10;

            // 功能键
            case KEY_ESC: return 1;
            case KEY_TAB: return 15;
            case KEY_SPACE: return 57;
            case KEY_ENTER: return 28;
            case KEY_BKSP: return 14;
            case KEY_DEL: return 83;   // 注意：DEL 不是 BACKSPACE, scancode 不同
            case KEY_INSERT: return 82;
            case KEY_HOME: return 71;
            case KEY_END: return 79;
            case KEY_PRIOR: return 73;   // Page Up
            case KEY_NEXT: return 81;    // Page Down
            case KEY_UP: return 72;
            case KEY_DOWN: return 80;
            case KEY_LEFT: return 75;
            case KEY_RIGHT: return 77;

            // 修饰键
            case KEY_SHIFT_L: return 42;
            case KEY_SHIFT_R: return 54;
            case KEY_CTRL_L: return 29;
            case KEY_CTRL_R: return 97;
            case KEY_ALT_L: return 56;
            case KEY_ALT_R: return 100;
            case KEY_CAPS_LOCK: return 58;
            case KEY_NUM_LOCK: return 69;
            case KEY_SCROLL_LOCK: return 70;
            case KEY_PRTSCN: return 99;

            // F1-F12
            case KEY_F1: return 59;
            case KEY_F2: return 60;
            case KEY_F3: return 61;
            case KEY_F4: return 62;
            case KEY_F5: return 63;
            case KEY_F6: return 64;
            case KEY_F7: return 65;
            case KEY_F8: return 66;
            case KEY_F9: return 67;
            case KEY_F10: return 68;
            case KEY_F11: return 87;
            case KEY_F12: return 88;

            // 符号键
            case KEY_MINUS: return 12;
            case KEY_EQUAL: return 13;
            case KEY_BRACKET_LEFT: return 26;
            case KEY_BRACKET_RIGHT: return 27;
            case KEY_BACKSLASH: return 43;
            case KEY_SEMICOLON: return 39;
            case KEY_APOSTROPHE: return 40;
            case KEY_GRAVE: return 41;
            case KEY_COMMA: return 51;
            case KEY_PERIOD: return 52;
            case KEY_SLASH: return 53;

            // 小键盘
            case KEY_KP_0: return 82;
            case KEY_KP_1: return 79;
            case KEY_KP_2: return 80;
            case KEY_KP_3: return 81;
            case KEY_KP_4: return 75;
            case KEY_KP_5: return 76;
            case KEY_KP_6: return 77;
            case KEY_KP_7: return 71;
            case KEY_KP_8: return 72;
            case KEY_KP_9: return 73;
            case KEY_KP_DEL: return 83;   // 小键盘小数点
            case KEY_KP_ENTER: return 96;
            case KEY_KP_DIVIDE: return 98;
            case KEY_KP_ADD: return 78;
            case KEY_KP_SUBTRACT: return 74;
            case KEY_MAX: return 0;   // 这个是 alias 不用

            default:
                return 0;   // 找不到, 上层会 fallback 到 XKeycode.id
        }
    }
}

package com.termux.x11.controller.inputcontrols;

import com.termux.x11.ILorieView;
import com.termux.x11.controller.xserver.Keyboard;
import com.termux.x11.controller.xserver.Pointer;
import com.termux.x11.controller.xserver.XKeycode;
import com.termux.x11.input.InputStub;
import com.termux.x11.input.RenderData;

// 注：ILorieView.ScreenInfo 原来在 com.termux.x11 包内是 package-private，
// 跨包实现 ILorieView 时拿不到这个类型。已修复为 public（见 ILorieView.java）。

/**
 * Sender class for X11 input events.
 *
 * 修复要点：旧的 X11InputSender 只持有 InputStub，并通过 sendXxxEvent 包装了少量事件。
 * 但项目里的虚拟按键系统（InputControlsView）要求的不是 InputStub，而是 ILorieView 接口。
 * 旧代码里 X11Screen 创建了 InputControlsView 之后，从未调用 setXServer(...)，
 * 导致 InputControlsView.handleInputEvent 在最开头 if (xServer == null) return; 直接丢弃事件，
 * 这就是用户报告的"虚拟按键没有任何作用，点击没有真正输出到 X11 界面"。
 *
 * 修复：让 X11InputSender implement ILorieView，所有 injectXXX/sendXXX 委托给底层 InputStub，
 * 这样 X11Screen 可以直接把 x11InputSender 注入给 InputControlsView.setXServer(x11InputSender)，
 * 虚拟按键的键鼠事件就能真正送达 X11。
 */
public class X11InputSender implements ILorieView {
    private InputStub xServer;
    private RenderData renderData;
    private boolean initialized = false;

    // 懒加载的辅助对象，仅在 InputControlsView 真正用到时构造。
    // 之所以懒加载，是因为 X11Screen 在 Compose 工厂里就能拿到 x11InputSender，
    // 但不一定立即需要 Pointer/Keyboard/ScreenInfo。
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

    public void sendEvdevKeyEvent(int keycode, boolean isDown) {
        if (xServer != null) {
            xServer.sendKeyEvent(keycode, keycode, isDown);
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
            xServer.sendMouseEvent(0, 0, 1, false, false); // Left button
            xServer.sendMouseEvent(0, 0, 2, false, false); // Middle button
            xServer.sendMouseEvent(0, 0, 3, false, false); // Right button
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
        // 释放懒加载对象
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
        // 此实现不参与 WinHandler 流程，让上层做 null 防御即可
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
        // 注意：ILorieView.CursorLocker（interface） 跟 controller.core.CursorLocker（class） 是不同类型，
        // ILorieView 里的接口签名是 panBy(float, float)。这里返回一个内联实现，
        // 把 panBy 转发为 InputStub 的鼠标移动事件。
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

    @Override
    public void injectKeyPress(XKeycode keycode) {
        if (xServer != null && keycode != null) {
            int k = keycode.id & 0xff;
            xServer.sendKeyEvent(k, k, true);
        }
    }

    @Override
    public void injectKeyPress(XKeycode keycode, int unicodeChar) {
        if (xServer != null && keycode != null) {
            int k = keycode.id & 0xff;
            xServer.sendKeyEvent(k, k, true);
        }
    }

    @Override
    public void injectKeyRelease(int keycode) {
        if (xServer != null) xServer.sendKeyEvent(keycode, keycode, false);
    }

    @Override
    public void injectKeyRelease(XKeycode keycode) {
        if (xServer != null && keycode != null) {
            int k = keycode.id & 0xff;
            xServer.sendKeyEvent(k, k, false);
        }
    }

    @Override
    public void injectText(String text) {
        // InputStub 没有直接的 sendText 接口，termux-x11 内部是通过 injectKeyPress(unicode) 一字一字发。
        // 这里保守起见：按字符逐个 sendKeyEvent，用 keysym 模式。
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
        // 不需要做事
    }
}

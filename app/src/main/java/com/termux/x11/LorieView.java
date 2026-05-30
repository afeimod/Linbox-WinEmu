package com.termux.x11;

import com.termux.x11.controller.xserver.Keyboard;
import com.termux.x11.controller.xserver.Pointer;
import com.termux.x11.controller.xserver.XKeycode;
import com.termux.x11.controller.winhandler.WinHandler;
import com.termux.x11.input.InputStub;
import com.termux.x11.input.RenderData;

/**
 * Runtime API interface for LorieView (XServer) that provides
 * all the methods and properties needed by the input controls system.
 * Also serves as InputStub for compatibility with input handling.
 */
public interface LorieView extends InputStub {
    /**
     * Interface for cursor locking operations
     */
    interface CursorLocker {
        void panBy(float dx, float dy);
    }

    /**
     * Screen information containing display dimensions and scaling
     */
    class ScreenInfo {
        public int screenWidth;
        public int screenHeight;
        public int offsetX;
        public int offsetY;
        public float scaleX = 1.0f;
        public float scaleY = 1.0f;
        public int imageWidth;
        public int imageHeight;

        public boolean setCursorPosition(int x, int y) {
            return true;
        }
    }

    // Accessors for core components
    Pointer getPointer();
    Keyboard getKeyboard();
    ScreenInfo getScreenInfo();

    // Legacy field accessors (for backward compatibility)
    default Pointer getXServerPointer() { return getPointer(); }
    default Keyboard getXServerKeyboard() { return getKeyboard(); }
    default ScreenInfo getXServerScreenInfo() { return getScreenInfo(); }

    // Cursor locker accessor
    CursorLocker getCursorLocker();

    boolean isFullscreen();

    // Pointer injection methods
    void injectPointerMoveDelta(int dx, int dy);
    void injectPointerButtonPress(Pointer.Button button);
    void injectPointerButtonRelease(Pointer.Button button);

    // Mouse event injection (legacy methods used by InputDeviceManager)
    default void sendMouseEvent(int dx, int dy, int button, boolean isDown, boolean isAbsolute) {
        // Default implementation delegates to new methods
        if (button == 0 && dy == 0) {
            injectPointerMoveDelta(dx, dy);
        }
    }

    default void sendMouseWheelEvent(int x, int wheelDelta) {
        // Default implementation
    }

    // Keyboard injection methods
    void injectKeyPress(XKeycode keycode);
    void injectKeyRelease(XKeycode keycode);

    // Legacy keyboard event with unicode (used by Keyboard.onKeyEvent)
    default void injectKeyPress(XKeycode keycode, int unicodeChar) {
        injectKeyPress(keycode);
    }

    // Key event injection (legacy methods used by InputDeviceManager)
    default void sendKeyEvent(int keysym, int keycode, boolean isDown) {
        if (isDown) {
            injectKeyPress(XKeycode.fromId(keycode));
        } else {
            injectKeyRelease(XKeycode.fromId(keycode));
        }
    }

    // Text injection
    void injectText(String text);

    // Window handler
    WinHandler getWinHandler();

    // Default implementations for InputStub methods
    @Override
    default void initialize(LorieView view) {
        // Default implementation - can be overridden
    }

    @Override
    default boolean isReady() {
        return true;
    }

    @Override
    default void setRenderData(RenderData renderData) {
        // Default implementation - can be overridden
    }

    @Override
    default void forceResetMouseButtons() {
        // Default implementation - can be overridden
    }

    @Override
    default void sendEvdevKeyEvent(int keycode, boolean isDown) {
        // Default implementation - can be overridden
        // Convert evdev keycode to XKeycode using fromId
        XKeycode xKeycode = XKeycode.fromId(keycode);
        if (xKeycode != null) {
            if (isDown) {
                injectKeyPress(xKeycode);
            } else {
                injectKeyRelease(xKeycode);
            }
        }
    }

    @Override
    default void sendMouseMotionEvent(int dx, int dy) {
        // Default implementation - can be overridden
        injectPointerMoveDelta(dx, dy);
    }

    @Override
    default void sendMouseButtonEvent(int button, boolean isDown) {
        // Default implementation - can be overridden
        Pointer.Button btn;
        switch (button) {
            case 1: btn = Pointer.Button.BUTTON_LEFT; break;
            case 2: btn = Pointer.Button.BUTTON_MIDDLE; break;
            case 3: btn = Pointer.Button.BUTTON_RIGHT; break;
            case 4: btn = Pointer.Button.BUTTON_SCROLL_UP; break;
            case 5: btn = Pointer.Button.BUTTON_SCROLL_DOWN; break;
            default: btn = Pointer.Button.BUTTON_LEFT;
        }
        if (isDown) {
            injectPointerButtonPress(btn);
        } else {
            injectPointerButtonRelease(btn);
        }
    }
}
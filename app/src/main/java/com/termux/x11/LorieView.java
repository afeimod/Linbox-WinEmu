package com.termux.x11;

import com.termux.x11.controller.xserver.XKeycode;
import com.termux.x11.controller.winhandler.WinHandler;
import com.termux.x11.input.RenderData;

/**
 * Runtime API interface for LorieView (XServer) that provides
 * all the methods and properties needed by the input controls system.
 * Also serves as InputStub for compatibility with input handling.
 */
public interface LorieView extends com.termux.x11.input.InputStub {
    /**
     * Interface for pointer operations
     */
    interface Pointer {
        enum Button {
            BUTTON_LEFT,
            BUTTON_RIGHT,
            BUTTON_MIDDLE,
            BUTTON_SCROLL_UP,
            BUTTON_SCROLL_DOWN
        }

        void moveTo(int x, int y);
        boolean isButtonPressed(Button button);
    }

    /**
     * Interface for cursor locking operations
     */
    interface CursorLocker {
        void panBy(float dx, float dy);
    }

    /**
     * Screen information containing display dimensions
     */
    class ScreenInfo {
        public int screenWidth;
        public int screenHeight;
    }

    // Pointer accessor
    Pointer getPointer();

    // Cursor locker accessor
    CursorLocker getCursorLocker();

    // Screen information
    ScreenInfo getScreenInfo();
    boolean isFullscreen();

    // Pointer injection methods
    void injectPointerMoveDelta(int dx, int dy);
    void injectPointerButtonPress(Pointer.Button button);
    void injectPointerButtonRelease(Pointer.Button button);

    // Keyboard injection methods
    void injectKeyPress(XKeycode keycode);
    void injectKeyRelease(XKeycode keycode);

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
        XKeycode xKeycode = XKeycode.fromEvdev(keycode);
        if (isDown) {
            injectKeyPress(xKeycode);
        } else {
            injectKeyRelease(xKeycode);
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
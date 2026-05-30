package com.termux.x11;

import com.termux.x11.controller.xserver.XKeycode;
import com.termux.x11.controller.winhandler.WinHandler;

/**
 * Runtime API interface for LorieView (XServer) that provides
 * all the methods and properties needed by the input controls system.
 */
public interface LorieView {
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
}
package com.termux.x11;

/**
 * Local interface for virtual controls to interact with LorieView.
 * This interface is used by the virtual controls system (Pointer, Keyboard, etc.)
 * and is distinct from the AAR's LorieView class.
 */
public interface ILorieView {

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
        public int screenWidth = 1920;
        public int screenHeight = 1080;
        public int offsetX = 0;
        public int offsetY = 0;
        public float scaleX = 1.0f;
        public float scaleY = 1.0f;
        public int imageWidth = 1920;
        public int imageHeight = 1080;

        public boolean setCursorPosition(int x, int y) {
            return true;
        }
    }

    // Screen info accessor for direct field access
    ScreenInfo getScreenInfoAccessor();

    // Core component accessors
    com.termux.x11.controller.xserver.Pointer getPointer();
    com.termux.x11.controller.xserver.Keyboard getKeyboard();
    com.termux.x11.controller.winhandler.WinHandler getWinHandler();
    ScreenInfo getScreenInfo();

    // Cursor locker accessor
    CursorLocker getCursorLocker();

    boolean isFullscreen();

    // Pointer injection methods
    void injectPointerMoveDelta(int dx, int dy);
    void injectPointerButtonPress(int button);
    void injectPointerButtonPress(com.termux.x11.controller.xserver.Pointer.Button button);
    void injectPointerButtonRelease(int button);
    void injectPointerButtonRelease(com.termux.x11.controller.xserver.Pointer.Button button);

    // Keyboard injection methods - accept XKeycode enum
    void injectKeyPress(int keycode);
    void injectKeyPress(com.termux.x11.controller.xserver.XKeycode keycode);
    void injectKeyPress(com.termux.x11.controller.xserver.XKeycode keycode, int unicodeChar);
    void injectKeyRelease(int keycode);
    void injectKeyRelease(com.termux.x11.controller.xserver.XKeycode keycode);

    // Text injection
    void injectText(String text);

    // Viewport refresh method
    default void refreshViewport() {
    }
}
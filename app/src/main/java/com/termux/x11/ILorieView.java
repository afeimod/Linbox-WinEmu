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
    Object getPointer();
    Object getKeyboard();
    ScreenInfo getScreenInfo();

    // Cursor locker accessor
    CursorLocker getCursorLocker();

    boolean isFullscreen();

    // Pointer injection methods
    void injectPointerMoveDelta(int dx, int dy);
    void injectPointerButtonPress(int button);
    void injectPointerButtonRelease(int button);

    // Keyboard injection methods
    void injectKeyPress(int keycode);
    void injectKeyRelease(int keycode);

    // Text injection
    void injectText(String text);

    // Window handler
    Object getWinHandler();

    // Viewport refresh method
    default void refreshViewport() {
    }
}
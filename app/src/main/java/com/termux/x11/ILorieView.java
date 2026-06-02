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
     * Screen information containing display dimensions and scaling.
     * 修正：原来是 package-private，跨包实现 ILorieView 时拿不到类型。改为 public 让 X11InputSender
     * 之类的实现可以直接返回 ScreenInfo 实例。
     */
    public class ScreenInfo {
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

    // Direct mouse event sending methods
    void sendMouseWheelEvent(int dx, int dy);
    void sendMouseEvent(int x, int y, int button, boolean isDown, boolean isAbsolute);

    // Direct keyboard event sending methods
    void sendKeyEvent(int keysym, int keycode, boolean isDown);

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
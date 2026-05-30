package com.termux.x11.input;

import com.termux.x11.LorieView;
import com.termux.x11.controller.xserver.XKeycode;
import com.termux.x11.controller.winhandler.WinHandler;

/**
 * Input stub interface for X11 input handling.
 * This is the input control interface that wraps the LorieView functionality.
 */
public interface InputStub {
    /**
     * Initialize the input stub with a LorieView instance
     */
    void initialize(LorieView view);

    /**
     * Check if the input is ready
     */
    boolean isReady();

    /**
     * Set render data for the input
     */
    void setRenderData(RenderData renderData);

    /**
     * Force reset mouse button states
     */
    void forceResetMouseButtons();

    // Input event sending methods
    void sendEvdevKeyEvent(int keycode, boolean isDown);
    void sendMouseMotionEvent(int dx, int dy);
    void sendMouseButtonEvent(int button, boolean isDown);
}

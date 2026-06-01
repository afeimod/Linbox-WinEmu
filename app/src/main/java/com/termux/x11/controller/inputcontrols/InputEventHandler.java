package com.termux.x11.controller.inputcontrols;

import android.content.Context;
import com.termux.x11.ILorieView;

/**
 * Event handler interface for input events.
 */
public interface InputEventHandler {
    void onKeyEvent(int keycode, boolean isDown);
    void onPointerMove(int dx, int dy);
    void onPointerButton(int button, boolean isDown);
}
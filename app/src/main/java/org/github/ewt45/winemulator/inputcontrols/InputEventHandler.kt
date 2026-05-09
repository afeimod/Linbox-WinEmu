package org.github.ewt45.winemulator.inputcontrols

import android.view.KeyEvent

/**
 * Interface for handling input events from virtual controls
 */
interface InputEventHandler {
    /**
     * Handle a key event with full KeyEvent information
     * This supports ACTION_MULTIPLE events for continuous key presses (like WASD movement)
     * @param event The Android KeyEvent containing keycode, action, and repeat count
     */
    fun onKeyEvent(event: KeyEvent)

    /**
     * Handle pointer movement
     * @param dx Change in X coordinate
     * @param dy Change in Y coordinate
     */
    fun onPointerMove(dx: Int, dy: Int)

    /**
     * Handle pointer button event
     * @param button X11 button number (1=left, 2=middle, 3=right, 4=scroll up, 5=scroll down)
     * @param isDown True if pressed, false if released
     */
    fun onPointerButton(button: Int, isDown: Boolean)
}

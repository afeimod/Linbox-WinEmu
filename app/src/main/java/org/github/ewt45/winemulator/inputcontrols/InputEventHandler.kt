package org.github.ewt45.winemulator.inputcontrols

/**
 * Interface for handling input events from virtual controls
 */
interface InputEventHandler {
    /**
     * Handle a key event
     * @param keycode The Android keycode
     * @param isDown True if key is pressed, false if released
     */
    fun onKeyEvent(keycode: Int, isDown: Boolean)

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

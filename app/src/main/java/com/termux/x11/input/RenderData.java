package com.termux.x11.input;

/**
 * Render data container for X11 input.
 * Contains rendering-related information needed by input handling.
 */
public class RenderData {
    private int screenWidth;
    private int screenHeight;
    private float scale;

    public RenderData() {
        this.screenWidth = 1920;
        this.screenHeight = 1080;
        this.scale = 1.0f;
    }

    public int getScreenWidth() {
        return screenWidth;
    }

    public void setScreenWidth(int screenWidth) {
        this.screenWidth = screenWidth;
    }

    public int getScreenHeight() {
        return screenHeight;
    }

    public void setScreenHeight(int screenHeight) {
        this.screenHeight = screenHeight;
    }

    public float getScale() {
        return scale;
    }

    public void setScale(float scale) {
        this.scale = scale;
    }
}
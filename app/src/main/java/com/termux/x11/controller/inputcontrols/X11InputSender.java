package com.termux.x11.controller.inputcontrols;

import com.termux.x11.ILorieView;
import com.termux.x11.input.RenderData;

/**
 * Sender class for X11 input events.
 * Handles sending keyboard and mouse events to X11 server.
 */
public class X11InputSender {
    private ILorieView xServer;
    private RenderData renderData;
    private boolean initialized = false;

    public X11InputSender() {
    }

    public boolean isInitialized() {
        return initialized && xServer != null;
    }

    public void initialize(ILorieView xServer) {
        this.xServer = xServer;
        this.initialized = true;
        if (renderData == null) {
            this.renderData = new RenderData();
        }
    }

    public void setXServer(ILorieView xServer) {
        this.xServer = xServer;
        this.initialized = (xServer != null);
    }

    public void sendEvdevKeyEvent(int keycode, boolean isDown) {
        if (xServer != null) {
            xServer.sendEvdevKeyEvent(keycode, isDown);
        }
    }

    public void sendMouseMotionEvent(int dx, int dy) {
        if (xServer != null) {
            xServer.sendMouseMotionEvent(dx, dy);
        }
    }

    public void sendMouseButtonEvent(int button, boolean isDown) {
        if (xServer != null) {
            xServer.sendMouseButtonEvent(button, isDown);
        }
    }

    public void forceResetMouseButtons() {
        // Reset all mouse buttons to released state
        if (xServer != null) {
            xServer.sendMouseButtonEvent(1, false); // Left button
            xServer.sendMouseButtonEvent(2, false); // Middle button
            xServer.sendMouseButtonEvent(3, false); // Right button
        }
    }

    public RenderData getRenderData() {
        return renderData;
    }

    public void setRenderData(RenderData renderData) {
        this.renderData = renderData;
    }

    public void release() {
        this.xServer = null;
        this.initialized = false;
    }
}
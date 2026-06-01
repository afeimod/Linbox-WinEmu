package com.termux.x11.controller.inputcontrols;

import com.termux.x11.input.RenderData;

/**
 * Sender class for X11 input events.
 * Handles sending keyboard and mouse events to X11 server.
 * Uses the AAR's InputStub interface methods.
 */
public class X11InputSender {
    private com.termux.x11.input.InputStub xServer;
    private RenderData renderData;
    private boolean initialized = false;

    public X11InputSender() {
    }

    public boolean isInitialized() {
        return initialized && xServer != null;
    }

    public void initialize(com.termux.x11.input.InputStub xServer) {
        this.xServer = xServer;
        this.initialized = true;
        if (renderData == null) {
            this.renderData = new RenderData();
        }
    }

    public void setXServer(com.termux.x11.input.InputStub xServer) {
        this.xServer = xServer;
        this.initialized = (xServer != null);
    }

    public void sendEvdevKeyEvent(int keycode, boolean isDown) {
        if (xServer != null) {
            // AAR's InputStub uses sendKeyEvent(keysym, keycode, isDown)
            xServer.sendKeyEvent(keycode, keycode, isDown);
        }
    }

    public void sendMouseMotionEvent(int dx, int dy) {
        if (xServer != null) {
            // AAR's InputStub uses sendMouseEvent(dx, dy, button, isDown, isAbsolute)
            // button=0, isDown=true, isAbsolute=false for motion
            xServer.sendMouseEvent(dx, dy, 0, true, false);
        }
    }

    public void sendMouseButtonEvent(int button, boolean isDown) {
        if (xServer != null) {
            // AAR's InputStub uses sendMouseEvent(dx, dy, button, isDown, isAbsolute)
            xServer.sendMouseEvent(0, 0, button, isDown, false);
        }
    }

    public void forceResetMouseButtons() {
        // Reset all mouse buttons to released state
        if (xServer != null) {
            xServer.sendMouseEvent(0, 0, 1, false, false); // Left button
            xServer.sendMouseEvent(0, 0, 2, false, false); // Middle button
            xServer.sendMouseEvent(0, 0, 3, false, false); // Right button
        }
    }

    public RenderData getRenderData() {
        return renderData;
    }

    public void setRenderData(RenderData renderData) {
        this.renderData = renderData;
        if (xServer != null) {
            xServer.setRenderData(renderData);
        }
    }

    public void release() {
        this.xServer = null;
        this.initialized = false;
    }
}
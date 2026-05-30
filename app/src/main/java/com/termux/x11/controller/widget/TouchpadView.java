package com.termux.x11.controller.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.termux.x11.controller.core.AppUtils;
import com.termux.x11.controller.math.Mathf;
import com.termux.x11.controller.math.XForm;
import com.termux.x11.controller.xserver.Pointer;
import com.termux.x11.controller.xserver.Viewport;
import com.termux.x11.LorieView;

public class TouchpadView extends View {
    public enum TouchMode {
        TRACK_PAD, TOUCH_SCREEN, LOCKED_CURSOR
    }

    private static final byte MAX_FINGERS = 4;
    private static final short MAX_TWO_FINGERS_SCROLL_DISTANCE = 350;
    public static final byte MAX_TAP_TRAVEL_DISTANCE = 10;
    public static final short MAX_TAP_MILLISECONDS = 200;
    public static final float CURSOR_ACCELERATION = 1.25f;
    public static final byte CURSOR_ACCELERATION_THRESHOLD = 6;
    private final Finger[] fingers = new Finger[MAX_FINGERS];
    private byte numFingers = 0;
    private float sensitivity = 1.0f;
    private boolean pointerButtonLeftEnabled = true;
    private boolean pointerButtonRightEnabled = true;
    private Finger fingerPointerButtonLeft;
    private Finger fingerPointerButtonRight;
    private float scrollAccumY = 0;
    private boolean scrolling = false;
    private LorieView xServer;
    private InputControlsView.InputEventHandler inputEventHandler;
    private Runnable fourFingersTapCallback;
    private final float[] xform = XForm.getInstance();
    private TouchMode touchMode = TouchMode.TOUCH_SCREEN;

    public interface TouchEventCallback {
        void onPointerMove(int dx, int dy);
        void onPointerButton(int button, boolean pressed);
    }

    private TouchEventCallback touchEventCallback;

    public void setTouchEventCallback(TouchEventCallback callback) {
        this.touchEventCallback = callback;
    }

    public void setTouchMode(TouchMode touchMode) {
        this.touchMode = touchMode;
    }

    public TouchMode getTouchMode() {
        return touchMode;
    }

    public boolean handlesPassthroughInput() {
        return touchMode == TouchMode.TOUCH_SCREEN || touchMode == TouchMode.LOCKED_CURSOR;
    }

    public TouchpadView(Context context) {
        super(context);
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(false);
    }

    public void setXServer(LorieView xServer) {
        this.xServer = xServer;
        if (xServer != null) {
            updateXform(AppUtils.getScreenWidth(), AppUtils.getScreenHeight(), xServer.screenInfo.screenWidth, xServer.screenInfo.screenHeight);
        }
    }

    public void setInputEventHandler(InputControlsView.InputEventHandler handler) {
        this.inputEventHandler = handler;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (xServer != null) {
            updateXform(w, h, xServer.screenInfo.screenWidth, xServer.screenInfo.screenHeight);
        }
    }

    private void updateXform(int outerWidth, int outerHeight, int innerWidth, int innerHeight) {
        if (xServer == null) return;
        Viewport viewTransformation = new Viewport();
        viewTransformation.update(outerWidth, outerHeight, innerWidth, innerHeight);

        float invAspect = 1.0f / viewTransformation.aspect;
        if (!xServer.isFullscreen()) {
            XForm.makeTranslation(xform, -viewTransformation.x, -viewTransformation.y);
            XForm.scale(xform, invAspect, invAspect);
        } else XForm.makeScale(xform, invAspect, invAspect);
    }

    private class Finger {
        private int x;
        private int y;
        private final int startX;
        private final int startY;
        private int lastX;
        private int lastY;
        private final long touchTime;

        public Finger(float x, float y) {
            this.x = this.startX = this.lastX = (int) x;
            this.y = this.startY = this.lastY = (int) y;
            touchTime = System.currentTimeMillis();
        }

        public void update(float x, float y) {
            lastX = this.x;
            lastY = this.y;
            this.x = (int) x;
            this.y = (int) y;
        }

        private int deltaX() {
            float dx = (x - lastX) * sensitivity;
            if (Math.abs(dx) > CURSOR_ACCELERATION_THRESHOLD) dx *= CURSOR_ACCELERATION;
            return Mathf.roundPoint(dx);
        }

        private int deltaY() {
            float dy = (y - lastY) * sensitivity;
            if (Math.abs(dy) > CURSOR_ACCELERATION_THRESHOLD) dy *= CURSOR_ACCELERATION;
            return Mathf.roundPoint(dy);
        }

        private boolean isTap() {
            if (touchMode == TouchMode.TOUCH_SCREEN) {
                return (System.currentTimeMillis() - touchTime) < MAX_TAP_MILLISECONDS * 5 && travelDistance() < MAX_TAP_TRAVEL_DISTANCE * 5;
            }
            return (System.currentTimeMillis() - touchTime) < MAX_TAP_MILLISECONDS && travelDistance() < MAX_TAP_TRAVEL_DISTANCE;
        }

        private float travelDistance() {
            return (float) Math.hypot(x - startX, y - startY);
        }
    }

    @Override
    public boolean onHoverEvent(MotionEvent event) {
        return onTouchEvent(event);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (xServer == null && inputEventHandler == null) return false;

        int actionIndex = event.getActionIndex();
        int pointerId = event.getPointerId(actionIndex);
        int actionMasked = event.getActionMasked();
        if (pointerId >= MAX_FINGERS) return true;

        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_HOVER_ENTER:
                scrollAccumY = 0;
                scrolling = false;
                fingers[pointerId] = new Finger(event.getX(actionIndex), event.getY(actionIndex));
                numFingers++;
                if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                    handlerFingerDown(fingers[pointerId]);
                } else {
                    if (xServer != null) {
                        xServer.pointer.moveTo(fingers[pointerId].x, fingers[pointerId].y);
                    }
                }
                break;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_HOVER_MOVE:
                for (byte i = 0; i < MAX_FINGERS; i++) {
                    if (fingers[i] != null) {
                        int pointerIndex = event.findPointerIndex(i);
                        if (pointerIndex >= 0) {
                            fingers[i].update(event.getX(pointerIndex), event.getY(pointerIndex));
                            if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                                if (xServer != null) {
                                    xServer.pointer.moveTo(fingers[pointerId].x, fingers[pointerId].y);
                                }
                            } else {
                                handleFingerMove(fingers[i]);
                            }

                        } else {
                            handleFingerUp(fingers[i]);
                            fingers[i] = null;
                            numFingers--;
                        }
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_HOVER_EXIT:
                if (fingers[pointerId] != null) {
                    fingers[pointerId].update(event.getX(actionIndex), event.getY(actionIndex));
                    handleFingerUp(fingers[pointerId]);
                    fingers[pointerId] = null;
                    numFingers--;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                for (byte i = 0; i < MAX_FINGERS; i++) fingers[i] = null;
                numFingers = 0;
                break;
        }
        return true;
    }

    private void handlerFingerDown(Finger finger1) {
        if (touchMode == TouchMode.TOUCH_SCREEN && numFingers == 1) {
            if (xServer != null) {
                xServer.pointer.moveTo(finger1.x, finger1.y);
            } else if (inputEventHandler != null) {
                inputEventHandler.onPointerMove(finger1.x, finger1.y);
            }
        }
    }

    private void handleFingerUp(Finger finger1) {
        switch (numFingers) {
            case 1:
                if (finger1.isTap()) {
                    pressPointerButtonLeft(finger1);
                }
                break;
            case 2:
                Finger finger2 = findSecondFinger(finger1);
                if (finger2 != null && finger1.isTap()) {
                    pressPointerButtonRight(finger1);
                }
                break;
            case 4:
                if (fourFingersTapCallback != null) {
                    for (byte i = 0; i < 4; i++) {
                        if (fingers[i] != null && !fingers[i].isTap()) return;
                    }
                    fourFingersTapCallback.run();
                }
                break;
        }
        releasePointerButtonLeft(finger1);
        releasePointerButtonRight(finger1);
    }

    private void handleFingerMove(Finger finger1) {
        boolean skipPointerMove = false;

        Finger finger2 = numFingers == 2 ? findSecondFinger(finger1) : null;
        if (finger2 != null) {
            final float resolutionScale = xServer != null ? 1000.0f / Math.min(xServer.screenInfo.screenWidth, xServer.screenInfo.screenHeight) : 1.0f;
            float currDistance = (float) Math.hypot(finger1.x - finger2.x, finger1.y - finger2.y) * resolutionScale;

            if (currDistance < MAX_TWO_FINGERS_SCROLL_DISTANCE) {
                scrollAccumY += ((finger1.y + finger2.y) * 0.5f) - (finger1.lastY + finger2.lastY) * 0.5f;

                if (scrollAccumY < -100) {
                    injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_DOWN);
                    injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_DOWN);
                    scrollAccumY = 0;
                } else if (scrollAccumY > 100) {
                    injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_UP);
                    injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_UP);
                    scrollAccumY = 0;
                }
                scrolling = true;
            } else if (currDistance >= MAX_TWO_FINGERS_SCROLL_DISTANCE) {
                boolean isLeftButtonPressed = xServer != null ? xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT) : false;
                if (!isLeftButtonPressed && finger2.travelDistance() < MAX_TAP_TRAVEL_DISTANCE) {
                    pressPointerButtonLeft(finger1);
                    skipPointerMove = true;
                }
            }
        }

        if (!scrolling && numFingers <= 2 && !skipPointerMove) {
            int dx = finger1.deltaX();
            int dy = finger1.deltaY();

            if (touchMode == TouchMode.TOUCH_SCREEN) {
                if (xServer != null) {
                    xServer.pointer.moveTo(finger1.x, finger1.y);
                } else if (inputEventHandler != null) {
                    inputEventHandler.onPointerMove(finger1.x, finger1.y);
                }
            } else if (touchMode == TouchMode.LOCKED_CURSOR) {
                if (xServer != null && xServer.cursorLocker != null) {
                    xServer.cursorLocker.panBy(dx, dy);
                }
            } else {
                if (xServer != null) {
                    xServer.injectPointerMoveDelta(dx, dy);
                } else if (inputEventHandler != null) {
                    inputEventHandler.onPointerMove(dx, dy);
                }
            }
        }
    }

    private Finger findSecondFinger(Finger finger) {
        for (byte i = 0; i < MAX_FINGERS; i++) {
            if (fingers[i] != null && fingers[i] != finger) return fingers[i];
        }
        return null;
    }

    private void injectPointerButtonPress(Pointer.Button button) {
        if (xServer != null) {
            xServer.injectPointerButtonPress(button);
        } else if (inputEventHandler != null) {
            inputEventHandler.onPointerButton(button.ordinal(), true);
        }
    }

    private void injectPointerButtonRelease(Pointer.Button button) {
        if (xServer != null) {
            xServer.injectPointerButtonRelease(button);
        } else if (inputEventHandler != null) {
            inputEventHandler.onPointerButton(button.ordinal(), false);
        }
    }

    private void pressPointerButtonLeft(Finger finger) {
        if (pointerButtonLeftEnabled) {
            boolean isPressed = xServer != null ? xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT) : false;
            if (!isPressed) {
                injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
                fingerPointerButtonLeft = finger;
            }
        }
    }

    private void pressPointerButtonRight(Finger finger) {
        if (pointerButtonRightEnabled) {
            boolean isPressed = xServer != null ? xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT) : false;
            if (!isPressed) {
                injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
                fingerPointerButtonRight = finger;
            }
        }
    }

    private void releasePointerButtonLeft(final Finger finger) {
        if (pointerButtonLeftEnabled && finger == fingerPointerButtonLeft) {
            boolean isPressed = xServer != null ? xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT) : leftButtonPressed;
            if (isPressed) {
                postDelayed(() -> {
                    injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                    fingerPointerButtonLeft = null;
                }, 30);
            }
        }
    }

    private void releasePointerButtonRight(final Finger finger) {
        if (pointerButtonRightEnabled && finger == fingerPointerButtonRight) {
            boolean isPressed = xServer != null ? xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT) : rightButtonPressed;
            if (isPressed) {
                postDelayed(() -> {
                    injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
                    fingerPointerButtonRight = null;
                }, 30);
            }
        }
    }

    public void setSensitivity(float sensitivity) {
        this.sensitivity = sensitivity;
    }

    public boolean isPointerButtonLeftEnabled() {
        return pointerButtonLeftEnabled;
    }

    public void setPointerButtonLeftEnabled(boolean pointerButtonLeftEnabled) {
        this.pointerButtonLeftEnabled = pointerButtonLeftEnabled;
    }

    public boolean isPointerButtonRightEnabled() {
        return pointerButtonRightEnabled;
    }

    public void setPointerButtonRightEnabled(boolean pointerButtonRightEnabled) {
        this.pointerButtonRightEnabled = pointerButtonRightEnabled;
    }

    public void setFourFingersTapCallback(Runnable fourFingersTapCallback) {
        this.fourFingersTapCallback = fourFingersTapCallback;
    }

    private boolean leftButtonPressed = false;
    private boolean rightButtonPressed = false;

    public boolean onExternalMouseEvent(MotionEvent event) {
        boolean handled = false;
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            int actionButton = event.getActionButton();
            switch (event.getAction()) {
                case MotionEvent.ACTION_BUTTON_PRESS:
                    if (actionButton == MotionEvent.BUTTON_PRIMARY) {
                        injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
                        leftButtonPressed = true;
                    } else if (actionButton == MotionEvent.BUTTON_SECONDARY) {
                        injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
                        rightButtonPressed = true;
                    }
                    handled = true;
                    break;
                case MotionEvent.ACTION_BUTTON_RELEASE:
                    if (actionButton == MotionEvent.BUTTON_PRIMARY) {
                        injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                        leftButtonPressed = false;
                    } else if (actionButton == MotionEvent.BUTTON_SECONDARY) {
                        injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
                        rightButtonPressed = false;
                    }
                    handled = true;
                    break;
                case MotionEvent.ACTION_HOVER_MOVE:
                    float[] transformedPoint = XForm.transformPoint(xform, event.getX(), event.getY());
                    if (xServer != null) {
                        xServer.injectPointerMove((int) transformedPoint[0], (int) transformedPoint[1]);
                    } else if (inputEventHandler != null) {
                        inputEventHandler.onPointerMove((int) transformedPoint[0], (int) transformedPoint[1]);
                    }
                    handled = true;
                    break;
                case MotionEvent.ACTION_SCROLL:
                    float scrollY = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                    if (scrollY <= -1.0f) {
                        injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_DOWN);
                        injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_DOWN);
                    } else if (scrollY >= 1.0f) {
                        injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_UP);
                        injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_UP);
                    }
                    handled = true;
                    break;
            }
        }
        return handled;
    }

    public float[] computeDeltaPoint(float lastX, float lastY, float x, float y) {
        final float[] result = new float[]{0, 0};

        XForm.transformPoint(xform, lastX, lastY, result);
        lastX = result[0];
        lastY = result[1];

        XForm.transformPoint(xform, x, y, result);
        x = result[0];
        y = result[1];

        result[0] = x - lastX;
        result[1] = y - lastY;
        return result;
    }

    // Compatibility methods for Linbox
    public void setPointerButtonLeftEnabled(boolean enabled) {
        this.pointerButtonLeftEnabled = enabled;
    }

    public boolean isPointerButtonLeftEnabled() {
        return this.pointerButtonLeftEnabled;
    }

    public boolean isLeftButtonPressed() {
        return leftButtonPressed;
    }
}
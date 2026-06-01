package com.termux.x11.controller.xserver;

import static androidx.core.math.MathUtils.clamp;

import com.termux.x11.IILorieView;
import com.termux.x11.controller.math.Mathf;

import java.util.ArrayList;

public class Pointer {
    public enum Button {
        BUTTON_LEFT, BUTTON_MIDDLE, BUTTON_RIGHT, BUTTON_SCROLL_UP, BUTTON_SCROLL_DOWN, BUTTON_SCROLL_CLICK_LEFT, BUTTON_SCROLL_CLICK_RIGHT;

        public byte code() {
            return (byte) (ordinal() + 1);
        }

        public int flag() {
            return 1 << (code() + MAX_BUTTONS);
        }
    }

    public static final byte MAX_BUTTONS = 8;
    private final ArrayList<OnPointerMotionListener> onPointerMotionListeners = new ArrayList<>();
    private final Bitmask buttonMask = new Bitmask();
    private final ILorieView xServer;
    private int x;
    private int y;
    private Button pointerButton;

    public Button getPointerButton() {
        return pointerButton;
    }

    public interface OnPointerMotionListener {
        default void onPointerButtonPress(Button button) {
        }

        default void onPointerButtonRelease(Button button) {
        }

        default void onPointerMove(int x, int y) {
        }

        default void onPointMoveDelta(int dx, int dy) {

        }
    }

    public Pointer(ILorieView xServer) {
        this.xServer = xServer;
    }

    public void setX(int x) {
        if (screenPointLiesOutsideImageBoundaryX(x)) {
            return;
        }
        this.x = x;
    }

    public void setY(int y) {
        if (screenPointLiesOutsideImageBoundaryY(y)) {
            return;
        }
        this.y = y;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public short getClampedX() {
        return (short) Mathf.clamp(x, 0, xServer.getScreenInfoAccessor().screenWidth - 1);
    }

    public short getClampedY() {
        return (short) Mathf.clamp(y, 0, xServer.getScreenInfoAccessor().screenHeight - 1);
    }

    public void moveTo(int x, int y) {
        if (screenPointLiesOutsideImageBoundaryX(x) || screenPointLiesOutsideImageBoundaryY(y)) {
            return;
        }
        if (xServer.getScreenInfoAccessor().setCursorPosition(x, y)) {
            this.x = x;
            this.y = y;

            int scaledX = clamp((int) ((x - xServer.getScreenInfoAccessor().offsetX) * xServer.getScreenInfoAccessor().scaleX), 0, xServer.getScreenInfoAccessor().screenWidth - 1);
            int scaledY = clamp((int) ((y - xServer.getScreenInfoAccessor().offsetY) * xServer.getScreenInfoAccessor().scaleY), 0, xServer.getScreenInfoAccessor().screenHeight - 1);
            triggerOnPointerMove(scaledX, scaledY);
        }
    }

    public void moveDelta(int dx, int dy) {
        triggerOnPointerMoveDelta(dx, dy);
    }

    private boolean screenPointLiesOutsideImageBoundaryX(float screenX) {
//        float scaledX = (screenX-xServer.getScreenInfoAccessor().offsetX) * xServer.getScreenInfoAccessor().scale.x;
//        float imageWidth = (float) xServer.getScreenInfoAccessor().imageWidth + EPSILON;
//        Log.d("OutsideBoundaryX", "screenX: " + screenX + ", scaledX:" + scaledX + ", imageWidth: " + imageWidth);
        return screenX < xServer.getScreenInfoAccessor().offsetX || screenX > xServer.getScreenInfoAccessor().imageWidth + xServer.getScreenInfoAccessor().offsetX;
    }

    private boolean screenPointLiesOutsideImageBoundaryY(float screenY) {
//        float scaledY = (screenY-xServer.getScreenInfoAccessor().offsetY) * xServer.getScreenInfoAccessor().scale.y;
//        float imageHeight = (float) xServer.getScreenInfoAccessor().imageHeight + EPSILON;
//        Log.d("OutsideBoundaryX","screenY: "+screenY+", scaledY:"+scaledY+", imageHeight: "+imageHeight);
        return screenY < xServer.getScreenInfoAccessor().offsetY || screenY > xServer.getScreenInfoAccessor().imageHeight + xServer.getScreenInfoAccessor().offsetY;
    }

    public void setButton(Button button, boolean pressed) {
        boolean oldPressed = isButtonPressed(button);
        buttonMask.set(button.flag(), pressed);
        if (oldPressed != pressed) {
            if (pressed) {
                triggerOnPointerButtonPress(button);
                this.pointerButton = button;
            } else {
                triggerOnPointerButtonRelease(button);
                this.pointerButton = null;
            }
        }
    }

    public boolean isButtonPressed(Button button) {
        return buttonMask.isSet(button.flag());
    }

    public void addOnPointerMotionListener(OnPointerMotionListener onPointerMotionListener) {
        onPointerMotionListeners.add(onPointerMotionListener);
    }

    public void removeOnPointerMotionListener(OnPointerMotionListener onPointerMotionListener) {
        onPointerMotionListeners.remove(onPointerMotionListener);
    }

    private void triggerOnPointerButtonPress(Button button) {
        for (int i = onPointerMotionListeners.size() - 1; i >= 0; i--) {
            onPointerMotionListeners.get(i).onPointerButtonPress(button);
        }
    }

    private void triggerOnPointerButtonRelease(Button button) {
        for (int i = onPointerMotionListeners.size() - 1; i >= 0; i--) {
            onPointerMotionListeners.get(i).onPointerButtonRelease(button);
        }
    }

    private void triggerOnPointerMove(int x, int y) {
        for (int i = onPointerMotionListeners.size() - 1; i >= 0; i--) {
            onPointerMotionListeners.get(i).onPointerMove(x, y);
        }
    }

    private void triggerOnPointerMoveDelta(int dx, int dy) {
        for (int i = onPointerMotionListeners.size() - 1; i >= 0; i--) {
            onPointerMotionListeners.get(i).onPointMoveDelta(dx, dy);
        }
    }
}

package com.termux.x11.controller.core;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.AdapterView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.Spinner;
import android.widget.Toast;

public class AppUtils {

    private static int screenWidth = 0;
    private static int screenHeight = 0;

    public static int getVersionCode(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    public static String getVersionName(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    public static int getScreenWidth() {
        return screenWidth;
    }

    public static int getScreenHeight() {
        return screenHeight;
    }

    public static void updateScreenSize(Context context) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
    }

    public static void showToast(Context context, int resId) {
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show();
    }

    public static void showToast(Context context, String text) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }

    public static void hideSystemUI(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = activity.getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Use reflection to avoid compile-time dependency on the constant
                    try {
                        java.lang.reflect.Field field = WindowInsetsController.class.getField("BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE_GESTURE");
                        int behaviorConstant = field.getInt(null);
                        controller.setSystemBarsBehavior(behaviorConstant);
                    } catch (NoSuchFieldException | IllegalAccessException e) {
                        // Fallback: use value 1 for API 31+
                        controller.setSystemBarsBehavior(1);
                    }
                }
            }
        } else {
            activity.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
            );
        }
    }

    public static void setSpinnerSelectionFromValue(Spinner spinner, int value) {
        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) spinner.getTag();
        if (info != null) {
            spinner.setSelection(info.position);
        } else {
            int count = spinner.getAdapter().getCount();
            for (int i = 0; i < count; i++) {
                Object item = spinner.getAdapter().getItem(i);
                if (item instanceof android.content.res.XmlResourceParser) {
                    continue;
                }
                if (item != null && item.toString().contains(String.valueOf(value))) {
                    spinner.setSelection(i);
                    break;
                }
            }
        }
    }

    public static void setSpinnerSelectionFromValue(Spinner spinner, String value) {
        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) spinner.getTag();
        if (info != null) {
            spinner.setSelection(info.position);
        } else {
            int count = spinner.getAdapter().getCount();
            for (int i = 0; i < count; i++) {
                Object item = spinner.getAdapter().getItem(i);
                if (item instanceof android.content.res.XmlResourceParser) {
                    continue;
                }
                if (item != null && item.toString().equals(value)) {
                    spinner.setSelection(i);
                    break;
                }
            }
        }
    }

    /**
     * Show a popup window anchored to a view.
     *
     * 修复：原来 PopupWindow 用 WRAP_CONTENT + 固定 xOffset=340,
     * 竖屏时弹窗在屏幕中部偏右、横屏时会越出屏幕边缘。
     * 改为根据屏幕方向动态计算宽高(竖屏宽 90% 屏宽,横屏宽 60% 屏宽,
     * 高度限制 85% 屏高,内部用 ScrollView 滚动)，并从屏幕顶部 START 开始，
     * 这样 xOffset/yOffset 是相对于屏幕左上角的偏移。
     *
     * @param anchorView the view to anchor the popup to
     * @param contentView the content view of the popup
     * @param xOffset horizontal offset from screen top-start (px)
     * @param yOffset vertical offset from screen top-start (px)
     * @return the PopupWindow
     */
    public static PopupWindow showPopupWindow(View anchorView, View contentView, int xOffset, int yOffset) {
        // 根据屏幕方向计算合理尺寸，保证横竖屏都不超出。
        android.util.DisplayMetrics dm = anchorView.getResources().getDisplayMetrics();
        boolean isLandscape = dm.widthPixels > dm.heightPixels;
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;
        int targetW = isLandscape ? (int) (screenW * 0.6f) : (int) (screenW * 0.9f);
        int targetH = (int) (screenH * 0.85f);

        // 水平居中：忽略调用者传入的 xOffset。仍然尊重 yOffset。
        int centeredX = Math.max(0, (screenW - targetW) / 2);
        int finalY = Math.max(0, yOffset);

        PopupWindow popupWindow = new PopupWindow(contentView, targetW, targetH, true);
        popupWindow.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        popupWindow.setOutsideTouchable(true);
        popupWindow.setTouchable(true);
        // 关键：显示/隐藏动画、点击外部关闭都需要背景 drawable,
        // 这里除了 backgroundDrawable 还要设一个非透明背景让 dismiss 生效。
        // （背景 ColorDrawable.TRANSPARENT 加上 setOutsideTouchable(true) 在大部分机器上可以工作）
        popupWindow.showAtLocation(anchorView, Gravity.TOP | Gravity.START,
                centeredX, finalY);
        return popupWindow;
    }
}
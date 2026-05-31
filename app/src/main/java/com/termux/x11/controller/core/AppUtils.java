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
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE_GESTURE);
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
     * @param anchorView the view to anchor the popup to
     * @param contentView the content view of the popup
     * @param xOffset horizontal offset
     * @param yOffset vertical offset
     * @return the PopupWindow
     */
    public static PopupWindow showPopupWindow(View anchorView, View contentView, int xOffset, int yOffset) {
        PopupWindow popupWindow = new PopupWindow(contentView,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                true);
        popupWindow.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        popupWindow.setOutsideTouchable(true);
        popupWindow.setTouchable(true);
        popupWindow.showAtLocation(anchorView, Gravity.TOP | Gravity.START,
                xOffset, yOffset);
        return popupWindow;
    }
}
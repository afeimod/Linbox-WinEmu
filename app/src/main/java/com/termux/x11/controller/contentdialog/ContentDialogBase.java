package com.termux.x11.controller.contentdialog;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.LayoutRes;
import androidx.annotation.StringRes;

import org.github.ewt45.winemulator.R;
import com.termux.x11.controller.core.Callback;

/**
 * Base dialog class for content dialogs with common dialog functionality.
 * This is an abstract base class that provides common dialog operations.
 */
public abstract class ContentDialogBase {
    protected final Activity activity;
    protected final Context context;
    protected final AlertDialog dialog;
    protected final View dialogView;
    protected Callback<Void> onConfirmCallback;
    protected Runnable onDismissCallback;
    protected LinearLayout bottomBar;
    protected TextView bottomBarText;

    public ContentDialogBase(Activity activity, @LayoutRes int layoutResId) {
        this.activity = activity;
        this.context = activity;
        this.dialogView = LayoutInflater.from(activity).inflate(layoutResId, null);
        this.dialog = new AlertDialog.Builder(activity)
                .setView(dialogView)
                .create();
    }

    public void show() {
        if (!dialog.isShowing()) {
            dialog.show();
        }
    }

    public void dismiss() {
        if (dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    public void setCancelable(boolean cancelable) {
        dialog.setCancelable(cancelable);
    }

    public void setTitle(@StringRes int titleResId) {
        dialog.setTitle(titleResId);
    }

    public void setTitle(String title) {
        dialog.setTitle(title);
    }

    public void setIcon(@DrawableRes int iconResId) {
        dialog.setIcon(iconResId);
    }

    public void setOnDismissListener(Runnable onDismiss) {
        this.onDismissCallback = onDismiss;
        dialog.setOnDismissListener(d -> {
            if (onDismissCallback != null) {
                onDismissCallback.run();
            }
        });
    }

    public void setOnConfirmCallback(Callback<Void> callback) {
        this.onConfirmCallback = callback;
    }

    public void setOnConfirmCallback(Runnable runnable) {
        this.onConfirmCallback = data -> {
            runnable.run();
        };
    }

    public void setBottomBarText(String text) {
        if (bottomBar != null && bottomBarText != null) {
            bottomBar.setVisibility(View.VISIBLE);
            bottomBarText.setText(text);
        }
    }

    @SuppressWarnings("unchecked")
    protected <T extends View> T findViewById(int id) {
        return (T) dialogView.findViewById(id);
    }

    protected View getDialogView() {
        return dialogView;
    }

    protected void initBottomBar() {
        bottomBar = findViewById(R.id.LLBottomBar);
        bottomBarText = findViewById(R.id.TVBottomBarText);
    }
}

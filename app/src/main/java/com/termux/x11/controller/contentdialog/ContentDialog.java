package com.termux.x11.controller.contentdialog;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.LayoutRes;
import androidx.annotation.StringRes;

import a.io.github.ewt45.winemulator.R;
import com.termux.x11.controller.core.AppUtils;
import com.termux.x11.controller.core.Callback;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class ContentDialog {
    protected final Activity activity;
    protected final AlertDialog dialog;
    protected final View dialogView;
    protected Callback<Void> onConfirmCallback;
    protected Runnable onDismissCallback;
    protected LinearLayout bottomBar;
    protected TextView bottomBarText;

    public ContentDialog(Activity activity, @LayoutRes int layoutResId) {
        this.activity = activity;
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

    public void setIcon(int iconResId) {
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

    public interface OnConfirmListener {
        void onConfirm();
    }

    public static void confirm(Context context, @StringRes int messageResId, OnConfirmListener onConfirm) {
        confirm(context, messageResId, null, onConfirm);
    }

    public static void confirm(Context context, @StringRes int messageResId, Runnable onCancel, OnConfirmListener onConfirm) {
        new AlertDialog.Builder(context)
            .setMessage(messageResId)
            .setPositiveButton(R.string.yes, (dialog, which) -> {
                if (onConfirm != null) onConfirm.onConfirm();
            })
            .setNegativeButton(R.string.no, (dialog, which) -> {
                if (onCancel != null) onCancel.run();
            })
            .setOnCancelListener(dialog -> {
                if (onCancel != null) onCancel.run();
            })
            .show();
    }

    public static void prompt(Context context, @StringRes int titleResId, String defaultValue, Callback<String> onConfirm) {
        prompt(context, titleResId, defaultValue, null, onConfirm);
    }

    public static void prompt(Context context, @StringRes int titleResId, String defaultValue, Runnable onCancel, Callback<String> onConfirm) {
        final android.widget.EditText input = new android.widget.EditText(context);
        input.setText(defaultValue);
        input.setSelection(defaultValue != null ? defaultValue.length() : 0);

        new AlertDialog.Builder(context)
            .setTitle(titleResId)
            .setView(input)
            .setPositiveButton(R.string.confirm, (dialog, which) -> {
                String value = input.getText().toString().trim();
                if (!value.isEmpty() && onConfirm != null) {
                    onConfirm.call(value);
                }
            })
            .setNegativeButton(R.string.cancel, (dialog, which) -> {
                if (onCancel != null) onCancel.run();
            })
            .setOnCancelListener(dialog -> {
                if (onCancel != null) onCancel.run();
            })
            .show();
    }

    public interface OnMultipleChoiceListener {
        void onConfirm(ArrayList<Integer> positions);
    }

    public static void showMultipleChoiceList(Context context, @StringRes int titleResId, String[] items, OnMultipleChoiceListener onConfirm) {
        showMultipleChoiceList(context, titleResId, items, new HashSet<>(), onConfirm);
    }

    public static void showMultipleChoiceList(Context context, @StringRes int titleResId, String[] items, Set<Integer> checkedPositions, OnMultipleChoiceListener onConfirm) {
        boolean[] checked = new boolean[items.length];
        for (int i = 0; i < items.length; i++) {
            checked[i] = checkedPositions.contains(i);
        }

        new AlertDialog.Builder(context)
            .setTitle(titleResId)
            .setMultiChoiceItems(items, checked, (dialog, which, isChecked) -> {
                checked[which] = isChecked;
            })
            .setPositiveButton(R.string.confirm, (dialog, which) -> {
                ArrayList<Integer> positions = new ArrayList<>();
                for (int i = 0; i < checked.length; i++) {
                    if (checked[i]) positions.add(i);
                }
                if (onConfirm != null) onConfirm.onConfirm(positions);
            })
            .setNegativeButton(R.string.cancel, (dialog, which) -> {})
            .show();
    }
}
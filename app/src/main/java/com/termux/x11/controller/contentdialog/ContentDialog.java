package com.termux.x11.controller.contentdialog;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.View;

import androidx.annotation.StringRes;

import a.io.github.ewt45.winemulator.R;
import com.termux.x11.controller.core.AppUtils;
import com.termux.x11.controller.core.Callback;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class ContentDialog {
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
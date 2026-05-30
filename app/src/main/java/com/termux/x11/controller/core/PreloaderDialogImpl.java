package com.termux.x11.controller.core;

import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * Implementation of PreloaderDialog using Android ProgressDialog.
 */
public class PreloaderDialogImpl implements PreloaderDialog {
    private final Context context;
    private ProgressDialog dialog;
    private final Handler handler;
    private boolean isShowing = false;

    public PreloaderDialogImpl(Context context) {
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void show(final int messageResId) {
        handler.post(() -> {
            if (dialog == null) {
                dialog = new ProgressDialog(context);
                dialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
                dialog.setCancelable(false);
            }
            dialog.setMessage(context.getText(messageResId));
            dialog.show();
            isShowing = true;
        });
    }

    @Override
    public void dismiss() {
        handler.post(() -> {
            if (dialog != null && dialog.isShowing()) {
                dialog.dismiss();
            }
            isShowing = false;
        });
    }

    @Override
    public boolean isShowing() {
        return isShowing && dialog != null && dialog.isShowing();
    }
}
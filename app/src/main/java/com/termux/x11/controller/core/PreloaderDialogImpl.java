package com.termux.x11.controller.core;

import android.app.ProgressDialog;
import android.content.Context;
import androidx.annotation.StringRes;

/**
 * Default implementation of PreloaderDialog using ProgressDialog.
 */
public class PreloaderDialogImpl implements PreloaderDialog {
    private final ProgressDialog progressDialog;

    public PreloaderDialogImpl(Context context) {
        progressDialog = new ProgressDialog(context);
        progressDialog.setIndeterminate(true);
        progressDialog.setCancelable(false);
    }

    @Override
    public void show(int messageResId) {
        progressDialog.setMessage(progressDialog.getContext().getString(messageResId));
        if (!progressDialog.isShowing()) {
            progressDialog.show();
        }
    }

    @Override
    public void close() {
        if (progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
    }

    @Override
    public void dismiss() {
        close();
    }

    @Override
    public boolean isShowing() {
        return progressDialog.isShowing();
    }
}

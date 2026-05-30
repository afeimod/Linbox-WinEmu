package com.termux.x11.controller.core;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.termux.x11.R;

/**
 * Dialog showing download progress with a circular indicator.
 */
public class DownloadProgressDialog extends Dialog {
    private final String title;
    private final String message;

    public DownloadProgressDialog(Context context, String title, String message) {
        super(context);
        this.title = title;
        this.message = message;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(STYLE_NO_TITLE);
        setContentView(R.layout.dialog_download_progress);

        TextView tvTitle = findViewById(R.id.tv_dialog_title);
        TextView tvMessage = findViewById(R.id.tv_dialog_message);

        if (tvTitle != null) {
            tvTitle.setText(title);
        }
        if (tvMessage != null) {
            tvMessage.setText(message);
        }

        setCancelable(false);
    }
}
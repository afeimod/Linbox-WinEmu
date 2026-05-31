package com.termux.x11.controller.core;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.widget.ProgressBar;
import android.widget.TextView;

import a.io.github.ewt45.winemulator.R;

/**
 * Dialog showing download progress with a progress indicator.
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
        ProgressBar progressBar = findViewById(R.id.progress_indicator);

        if (tvTitle != null) {
            tvTitle.setText(title);
        }
        if (tvMessage != null) {
            tvMessage.setText(message);
        }
        if (progressBar != null) {
            progressBar.setIndeterminate(true);
        }

        setCancelable(false);
    }
}
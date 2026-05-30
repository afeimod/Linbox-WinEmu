package com.termux.x11.controller.core;

/**
 * A simple preloader dialog interface used for showing loading progress.
 */
public interface PreloaderDialog {
    void show(int messageResId);
    void close();
    void dismiss();
    boolean isShowing();
}
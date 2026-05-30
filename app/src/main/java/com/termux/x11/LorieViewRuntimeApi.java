package com.termux.x11;

import android.app.Activity;

import com.termux.x11.controller.core.PreloaderDialog;
import com.termux.x11.controller.widget.InputControlsView;

/**
 * Runtime API interface for the main activity.
 * Contains interfaces for various hosts that need to be implemented by the activity.
 */
public interface LorieViewRuntimeApi {

    /**
     * Interface for activities that host input controls functionality.
     */
    interface InputControlsHost {
        /**
         * Get the activity instance.
         * @return the Activity
         */
        Activity getActivity();

        /**
         * Get the preloader dialog instance.
         * @return a PreloaderDialog instance
         */
        PreloaderDialog getPreloaderDialog();
    }

    /**
     * Interface for activities that host window handler functionality.
     */
    interface WinHandlerHost {
        /**
         * Get the InputControlsView instance.
         * @return the InputControlsView
         */
        InputControlsView getInputControlsView();
    }
}
package com.termux.x11;

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
         * Get the preloader dialog instance.
         * @return a PreloaderDialog instance
         */
        com.termux.x11.controller.core.PreloaderDialog getPreloaderDialog();
    }

    /**
     * Interface for activities that host window handler functionality.
     */
    interface WinHandlerHost {
    }
}
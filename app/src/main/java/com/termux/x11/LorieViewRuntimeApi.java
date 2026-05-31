package com.termux.x11;

import android.app.Activity;
import android.content.Context;

import com.termux.x11.controller.core.PreloaderDialog;
import com.termux.x11.controller.winhandler.ProcessInfo;
import com.termux.x11.controller.winhandler.WinHandler;
import com.termux.x11.controller.widget.InputControlsView;

import java.util.List;

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
         * Get the activity instance.
         * @return the Activity
         */
        Activity getActivity();

        /**
         * Get the InputControlsView instance.
         * @return the InputControlsView
         */
        InputControlsView getInputControlsView();

        /**
         * Get the WinHandler instance.
         * @return the WinHandler
         */
        WinHandler getWinHandler();

        /**
         * Get process information for the task manager.
         * @param filter filter string ("0" for all, "1" for Wine only)
         * @return list of process information
         */
        List<ProcessInfo> getTermuxProcessorInfo(String filter);
    }
}
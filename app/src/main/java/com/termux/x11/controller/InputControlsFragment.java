package com.termux.x11.controller;

import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.termux.x11.R;
import com.termux.x11.controller.core.AppUtils;
import com.termux.x11.controller.core.ContentDialog;
import com.termux.x11.controller.core.PreloaderDialog;
import com.termux.x11.controller.inputcontrols.ControlsProfile;
import com.termux.x11.controller.inputcontrols.ExternalController;
import com.termux.x11.controller.inputcontrols.InputControlsManager;
import com.termux.x11.controller.winhandler.WinHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * Fragment for managing input controls settings.
 */
public class InputControlsFragment extends Fragment {
    public interface InputControlsHost {
        Activity getActivity();
        PreloaderDialog getPreloaderDialog();
    }

    private InputControlsHost host;
    private ControlsProfile currentProfile;
    private Spinner sProfile;
    private List<ControlsProfile> profiles = new ArrayList<>();

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (context instanceof InputControlsHost) {
            host = (InputControlsHost) context;
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.input_controls_fragment, container, false);

        sProfile = view.findViewById(R.id.SProfile);
        TextView tvCursorSpeed = view.findViewById(R.id.TVCursorSpeed);
        SeekBar sbCursorSpeed = view.findViewById(R.id.SBCursorSpeed);
        TextView tvUiOpacity = view.findViewById(R.id.TVUiOpacity);
        SeekBar sbUiOpacity = view.findViewById(R.id.SBOverlayOpacity);

        Button btAddProfile = view.findViewById(R.id.BTAddProfile);
        Button btEditProfile = view.findViewById(R.id.BTEditProfile);
        Button btDuplicateProfile = view.findViewById(R.id.BTDuplicateProfile);
        Button btRemoveProfile = view.findViewById(R.id.BTRemoveProfile);
        Button btImportProfile = view.findViewById(R.id.BTImportProfile);
        Button btExportProfile = view.findViewById(R.id.BTExportProfile);
        Button btControlsEditor = view.findViewById(R.id.BTControlsEditor);
        LinearLayout llExternalControllers = view.findViewById(R.id.LLExternalControllers);
        TextView tvEmptyText = view.findViewById(R.id.TVEmptyText);

        Context context = getContext();

        // Profile spinner
        btAddProfile.setOnClickListener((v) -> ContentDialog.prompt(context, R.string.profile_name, null, (name) -> {
            // Add new profile logic
        }));

        btEditProfile.setOnClickListener((v) -> {
            if (currentProfile != null) {
                ContentDialog.prompt(context, R.string.profile_name, currentProfile.getName(), (name) -> {
                    // Edit profile logic
                });
            } else {
                AppUtils.showToast(context, R.string.no_profile_selected);
            }
        });

        btDuplicateProfile.setOnClickListener((v) -> {
            if (currentProfile != null) {
                ContentDialog.confirm(context, R.string.do_you_want_to_duplicate_this_profile, () -> {
                    // Duplicate profile logic
                });
            } else {
                AppUtils.showToast(context, R.string.no_profile_selected);
            }
        });

        btRemoveProfile.setOnClickListener((v) -> {
            if (currentProfile != null) {
                ContentDialog.confirm(context, R.string.do_you_want_to_remove_this_profile, () -> {
                    // Remove profile logic
                });
            } else {
                AppUtils.showToast(context, R.string.no_profile_selected);
            }
        });

        btImportProfile.setOnClickListener((v) -> {
            if (host == null) return;
            // Import profile logic - show popup menu
        });

        btExportProfile.setOnClickListener((v) -> {
            if (currentProfile != null) {
                // Export profile logic
            } else {
                AppUtils.showToast(context, R.string.no_profile_selected);
            }
        });

        btControlsEditor.setOnClickListener((v) -> {
            if (currentProfile != null) {
                // Open controls editor
            } else {
                AppUtils.showToast(context, R.string.no_profile_selected);
            }
        });

        // Load profiles
        loadProfiles();

        return view;
    }

    private void loadProfiles() {
        if (host == null) return;
        // Load profiles from InputControlsManager
    }

    public void refreshProfiles() {
        loadProfiles();
    }

    public ControlsProfile getCurrentProfile() {
        return currentProfile;
    }

    private void updateExternalControllersList() {
        // Update external controllers list
    }
}
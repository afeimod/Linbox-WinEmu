package com.termux.x11;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Preferences interface that wraps SharedPreferences.
 * Provides type-safe access to preferences with get/put methods.
 * All preference fields are directly accessible on the Prefs instance.
 */
public class Prefs {
    private final SharedPreferences sharedPreferences;

    /**
     * String preference wrapper with get/put methods
     */
    public static class StringPreference {
        private SharedPreferences sharedPreferences;
        private final String key;
        private final String defaultValue;

        public StringPreference(String key, String defaultValue) {
            this.key = key;
            this.defaultValue = defaultValue;
        }

        public void setSharedPreferences(SharedPreferences sp) {
            this.sharedPreferences = sp;
        }

        public String get() {
            return sharedPreferences != null ? sharedPreferences.getString(key, defaultValue) : defaultValue;
        }

        public void put(String value) {
            if (sharedPreferences != null) {
                sharedPreferences.edit().putString(key, value).apply();
            }
        }
    }

    /**
     * Boolean preference wrapper with get/put methods
     */
    public static class BooleanPreference {
        private SharedPreferences sharedPreferences;
        private final String key;
        private final boolean defaultValue;

        public BooleanPreference(String key, boolean defaultValue) {
            this.key = key;
            this.defaultValue = defaultValue;
        }

        public void setSharedPreferences(SharedPreferences sp) {
            this.sharedPreferences = sp;
        }

        public boolean get() {
            return sharedPreferences != null ? sharedPreferences.getBoolean(key, defaultValue) : defaultValue;
        }

        public void put(boolean value) {
            if (sharedPreferences != null) {
                sharedPreferences.edit().putBoolean(key, value).apply();
            }
        }
    }

    /**
     * Integer preference wrapper with get/put methods
     */
    public static class IntPreference {
        private SharedPreferences sharedPreferences;
        private final String key;
        private final int defaultValue;

        public IntPreference(String key, int defaultValue) {
            this.key = key;
            this.defaultValue = defaultValue;
        }

        public void setSharedPreferences(SharedPreferences sp) {
            this.sharedPreferences = sp;
        }

        public int get() {
            return sharedPreferences != null ? sharedPreferences.getInt(key, defaultValue) : defaultValue;
        }

        public void put(int value) {
            if (sharedPreferences != null) {
                sharedPreferences.edit().putInt(key, value).apply();
            }
        }
    }

    /**
     * Float preference wrapper with get/put methods
     */
    public static class FloatPreference {
        private SharedPreferences sharedPreferences;
        private final String key;
        private final float defaultValue;

        public FloatPreference(String key, float defaultValue) {
            this.key = key;
            this.defaultValue = defaultValue;
        }

        public void setSharedPreferences(SharedPreferences sp) {
            this.sharedPreferences = sp;
        }

        public float get() {
            return sharedPreferences != null ? sharedPreferences.getFloat(key, defaultValue) : defaultValue;
        }

        public void put(float value) {
            if (sharedPreferences != null) {
                sharedPreferences.edit().putFloat(key, value).apply();
            }
        }
    }

    /**
     * List preference wrapper with get/put methods
     */
    public static class ListPreference {
        private SharedPreferences sharedPreferences;
        private final String key;
        private final String defaultValue;

        public ListPreference(String key, String defaultValue) {
            this.key = key;
            this.defaultValue = defaultValue;
        }

        public void setSharedPreferences(SharedPreferences sp) {
            this.sharedPreferences = sp;
        }

        public String get() {
            return sharedPreferences != null ? sharedPreferences.getString(key, defaultValue) : defaultValue;
        }

        public void put(String value) {
            if (sharedPreferences != null) {
                sharedPreferences.edit().putString(key, value).apply();
            }
        }
    }

    // Display preferences - directly accessible on Prefs instance
    public final StringPreference displayResolutionMode = new StringPreference("display_resolution_mode", "auto");
    public final StringPreference displayResolutionCustom = new StringPreference("display_resolution_custom", "");

    // UI preferences
    public final BooleanPreference showAdditionalKbd = new BooleanPreference("show_additional_kbd", false);
    public final BooleanPreference fullscreen = new BooleanPreference("fullscreen", false);
    public final BooleanPreference hideCutout = new BooleanPreference("hide_cutout", false);

    // Other preferences
    public final IntPreference clipboardMode = new IntPreference("clipboard_mode", 0);
    public final StringPreference desktopMode = new StringPreference("desktop_mode", "default");

    public Prefs(Context context) {
        this.sharedPreferences = context.getSharedPreferences("lorie_prefs", Context.MODE_PRIVATE);
        initPreferences();
    }

    public Prefs(SharedPreferences sharedPreferences) {
        this.sharedPreferences = sharedPreferences;
        initPreferences();
    }

    private void initPreferences() {
        displayResolutionMode.setSharedPreferences(sharedPreferences);
        displayResolutionCustom.setSharedPreferences(sharedPreferences);
        showAdditionalKbd.setSharedPreferences(sharedPreferences);
        fullscreen.setSharedPreferences(sharedPreferences);
        hideCutout.setSharedPreferences(sharedPreferences);
        clipboardMode.setSharedPreferences(sharedPreferences);
        desktopMode.setSharedPreferences(sharedPreferences);
    }

    public SharedPreferences getSharedPreferences() {
        return sharedPreferences;
    }
}
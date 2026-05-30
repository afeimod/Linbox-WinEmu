package com.termux.x11;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Preferences interface that wraps SharedPreferences.
 * Provides type-safe access to preferences with get/put methods.
 */
public class Prefs {
    private final SharedPreferences sharedPreferences;
    private final PrefsProto prefsProto;

    public Prefs(Context context) {
        this.sharedPreferences = context.getSharedPreferences("lorie_prefs", Context.MODE_PRIVATE);
        this.prefsProto = new PrefsProto(this);
    }

    public Prefs(SharedPreferences sharedPreferences) {
        this.sharedPreferences = sharedPreferences;
        this.prefsProto = new PrefsProto(this);
    }

    public SharedPreferences getSharedPreferences() {
        return sharedPreferences;
    }

    public PrefsProto getPrefsProto() {
        return prefsProto;
    }

    /**
     * Inner class containing preference definitions
     */
    public static class PrefsProto {
        private final Prefs prefs;

        public PrefsProto(Prefs prefs) {
            this.prefs = prefs;
        }

        /**
         * String preference wrapper with get/put methods
         */
        public static class StringPreference {
            private final SharedPreferences sharedPreferences;
            private final String key;
            private final String defaultValue;

            public StringPreference(SharedPreferences sp, String key, String defaultValue) {
                this.sharedPreferences = sp;
                this.key = key;
                this.defaultValue = defaultValue;
            }

            public String get() {
                return sharedPreferences.getString(key, defaultValue);
            }

            public void put(String value) {
                sharedPreferences.edit().putString(key, value).apply();
            }
        }

        /**
         * Boolean preference wrapper with get/put methods
         */
        public static class BooleanPreference {
            private final SharedPreferences sharedPreferences;
            private final String key;
            private final boolean defaultValue;

            public BooleanPreference(SharedPreferences sp, String key, boolean defaultValue) {
                this.sharedPreferences = sp;
                this.key = key;
                this.defaultValue = defaultValue;
            }

            public boolean get() {
                return sharedPreferences.getBoolean(key, defaultValue);
            }

            public void put(boolean value) {
                sharedPreferences.edit().putBoolean(key, value).apply();
            }
        }

        /**
         * Integer preference wrapper with get/put methods
         */
        public static class IntPreference {
            private final SharedPreferences sharedPreferences;
            private final String key;
            private final int defaultValue;

            public IntPreference(SharedPreferences sp, String key, int defaultValue) {
                this.sharedPreferences = sp;
                this.key = key;
                this.defaultValue = defaultValue;
            }

            public int get() {
                return sharedPreferences.getInt(key, defaultValue);
            }

            public void put(int value) {
                sharedPreferences.edit().putInt(key, value).apply();
            }
        }

        /**
         * Float preference wrapper with get/put methods
         */
        public static class FloatPreference {
            private final SharedPreferences sharedPreferences;
            private final String key;
            private final float defaultValue;

            public FloatPreference(SharedPreferences sp, String key, float defaultValue) {
                this.sharedPreferences = sp;
                this.key = key;
                this.defaultValue = defaultValue;
            }

            public float get() {
                return sharedPreferences.getFloat(key, defaultValue);
            }

            public void put(float value) {
                sharedPreferences.edit().putFloat(key, value).apply();
            }
        }

        /**
         * List preference wrapper with get/put methods
         */
        public static class ListPreference {
            private final SharedPreferences sharedPreferences;
            private final String key;
            private final String defaultValue;

            public ListPreference(SharedPreferences sp, String key, String defaultValue) {
                this.sharedPreferences = sp;
                this.key = key;
                this.defaultValue = defaultValue;
            }

            public String get() {
                return sharedPreferences.getString(key, defaultValue);
            }

            public void put(String value) {
                sharedPreferences.edit().putString(key, value).apply();
            }
        }

        // Display preferences
        public StringPreference displayResolutionMode = new StringPreference(prefs.sharedPreferences, "display_resolution_mode", "auto");
        public StringPreference displayResolutionCustom = new StringPreference(prefs.sharedPreferences, "display_resolution_custom", "");

        // UI preferences
        public BooleanPreference showAdditionalKbd = new BooleanPreference(prefs.sharedPreferences, "show_additional_kbd", false);
        public BooleanPreference fullscreen = new BooleanPreference(prefs.sharedPreferences, "fullscreen", false);
        public BooleanPreference hideCutout = new BooleanPreference(prefs.sharedPreferences, "hide_cutout", false);

        // Other preferences
        public IntPreference clipboardMode = new IntPreference(prefs.sharedPreferences, "clipboard_mode", 0);
        public StringPreference desktopMode = new StringPreference(prefs.sharedPreferences, "desktop_mode", "default");
    }
}
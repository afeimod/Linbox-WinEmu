package com.termux.x11;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Preferences interface that wraps SharedPreferences.
 * Provides type-safe access to preferences with get/put methods.
 */
public class Prefs {
    public final PrefsProto prefsProto;

    public Prefs(Context context) {
        SharedPreferences sp = context.getSharedPreferences("lorie_prefs", Context.MODE_PRIVATE);
        this.prefsProto = new PrefsProto(sp);
    }

    public Prefs(SharedPreferences sharedPreferences) {
        this.prefsProto = new PrefsProto(sharedPreferences);
    }

    public SharedPreferences getSharedPreferences() {
        return prefsProto.sharedPreferences;
    }

    /**
     * Inner class containing preference definitions
     */
    public static class PrefsProto {
        private final SharedPreferences sharedPreferences;

        public PrefsProto(SharedPreferences sp) {
            this.sharedPreferences = sp;
        }

        public SharedPreferences getSharedPreferences() {
            return sharedPreferences;
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
        public StringPreference displayResolutionMode = new StringPreference(sharedPreferences, "display_resolution_mode", "auto");
        public StringPreference displayResolutionCustom = new StringPreference(sharedPreferences, "display_resolution_custom", "");

        // UI preferences
        public BooleanPreference showAdditionalKbd = new BooleanPreference(sharedPreferences, "show_additional_kbd", false);
        public BooleanPreference fullscreen = new BooleanPreference(sharedPreferences, "fullscreen", false);
        public BooleanPreference hideCutout = new BooleanPreference(sharedPreferences, "hide_cutout", false);

        // Other preferences
        public IntPreference clipboardMode = new IntPreference(sharedPreferences, "clipboard_mode", 0);
        public StringPreference desktopMode = new StringPreference(sharedPreferences, "desktop_mode", "default");
    }
}
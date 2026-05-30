package com.termux.x11.controller.core;

import android.content.Context;

import java.io.File;

public class TermuxConfigFiles {
    public static File buttonIconsDir(Context context) {
        File dir = new File(context.getFilesDir(), "home/.buttonIcons");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    public static File getConfigFile(Context context, String name) {
        File dir = context.getFilesDir();
        return new File(dir, name);
    }
}
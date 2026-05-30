package com.termux.x11.controller.core;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class FileUtils {
    public static List<String> readLines(File file) {
        try {
            return org.apache.commons.io.FileUtils.readLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    public static String getBasename(String path) {
        int lastSep = path.lastIndexOf(File.separator);
        return lastSep >= 0 ? path.substring(lastSep + 1) : path;
    }

    public static String readString(Context context, Uri uri) {
        try {
            InputStream input = context.getContentResolver().openInputStream(uri);
            if (input == null) return "";
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
            reader.close();
            return content.toString();
        } catch (IOException e) {
            return "";
        }
    }

    public static String readString(File file) {
        try {
            return org.apache.commons.io.FileUtils.readFileToString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    public static void writeString(File file, String content) {
        try {
            org.apache.commons.io.FileUtils.writeStringToFile(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void copy(Context context, String assetPath, File destFile) {
        try {
            InputStream input = context.getAssets().open(assetPath);
            OutputStream output = java.nio.file.Files.newOutputStream(destFile.toPath());
            org.apache.commons.io.IOUtils.copy(input, output);
            input.close();
            output.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void copy(File source, File dest) {
        try {
            if (source.isDirectory()) {
                org.apache.commons.io.FileUtils.copyDirectory(source, dest);
            } else {
                org.apache.commons.io.FileUtils.copyFile(source, dest);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void copyAssetsDir(Context context, String assetDir, File destDir) {
        destDir.mkdirs();
        try {
            String[] files = context.getAssets().list(assetDir);
            if (files != null) {
                for (String file : files) {
                    File destFile = new File(destDir, file);
                    copy(context, assetDir + "/" + file, destFile);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static boolean isEmpty(File dir) {
        if (!dir.isDirectory()) return true;
        String[] files = dir.list();
        return files == null || files.length == 0;
    }
}
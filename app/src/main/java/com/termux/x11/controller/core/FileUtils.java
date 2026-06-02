package com.termux.x11.controller.core;

import android.content.Context;
import android.net.Uri;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
        String name = lastSep >= 0 ? path.substring(lastSep + 1) : path;
        // 修：原版只去路径分隔符，没去扩展名。结果 getBasename("0.png") = "0.png"，
        // 后续 Byte.parseByte("0.png") 抛 NumberFormatException: "For input string: \"0.png\""，
        // 导致 ControlsEditorActivity.loadIcons 崩溃——从虚拟按键菜单"编辑虚拟按键布局"
        // → 点中某个控件的设置图标 → 崩。现在还要去扩展名。
        int lastDot = name.lastIndexOf('.');
        return lastDot >= 0 ? name.substring(0, lastDot) : name;
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

    /**
     * Create a temporary file with the given prefix in the specified directory.
     * @param dir directory to create temp file in
     * @param prefix prefix for the filename
     * @return the temporary File
     */
    public static File createTempFile(File dir, String prefix) {
        try {
            File tempFile = File.createTempFile(prefix, ".tmp", dir);
            return tempFile;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Read entire file as byte array.
     * @param targetFile the file to read
     * @return byte array of file contents
     */
    public static byte[] read(File targetFile) {
        try {
            return Files.readAllBytes(targetFile.toPath());
        } catch (IOException e) {
            e.printStackTrace();
            return new byte[0];
        }
    }

    /**
     * Change file permissions (chmod).
     * @param file the file to chmod
     * @param mode the permission mode (e.g., 0771)
     */
    public static void chmod(File file, int mode) {
        try {
            Runtime.getRuntime().exec(new String[]{"chmod", Integer.toOctalString(mode), file.getAbsolutePath()}).waitFor();
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Read an integer value from a file.
     * @param path path to the file containing an integer
     * @return the integer value, or 0 on error
     */
    public static int readInt(String path) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(path)));
            String line = reader.readLine();
            reader.close();
            return line != null ? Integer.parseInt(line.trim()) : 0;
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Delete a file or directory.
     * @param file the file or directory to delete
     */
    public static void delete(File file) {
        if (file == null) return;
        try {
            if (file.isDirectory()) {
                org.apache.commons.io.FileUtils.deleteDirectory(file);
            } else {
                org.apache.commons.io.FileUtils.forceDelete(file);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
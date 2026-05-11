package com.termux.x11.controller.core

import android.content.Context
import java.io.*
import java.nio.charset.StandardCharsets

/**
 * Utility class for file operations - simplified version for Linbox compatibility.
 */
abstract class FileUtils {
    companion object {
        /**
         * Read entire file content as string
         */
        fun readString(file: File): String? {
            return try {
                BufferedReader(InputStreamReader(FileInputStream(file), StandardCharsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            } catch (e: IOException) {
                null
            }
        }

        /**
         * Write string content to file
         */
        fun writeString(file: File, content: String): Boolean {
            return try {
                BufferedWriter(OutputStreamWriter(FileOutputStream(file), StandardCharsets.UTF_8)).use { writer ->
                    writer.write(content)
                }
                true
            } catch (e: IOException) {
                false
            }
        }

        /**
         * Copy file from assets to target file
         */
        fun copy(context: Context, assetPath: String, targetFile: File): Boolean {
            return try {
                context.assets.open(assetPath).use { inputStream ->
                    FileOutputStream(targetFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                true
            } catch (e: IOException) {
                false
            }
        }

        /**
         * Copy file from source to destination
         */
        fun copy(sourceFile: File, destFile: File): Boolean {
            return try {
                FileInputStream(sourceFile).use { inputStream ->
                    FileOutputStream(destFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                true
            } catch (e: IOException) {
                false
            }
        }

        /**
         * Check if directory is empty
         */
        fun isEmpty(directory: File): Boolean {
            return !directory.exists() || directory.listFiles()?.isEmpty() != false
        }

        /**
         * Copy entire asset directory (including subdirectories) to destination directory.
         * This is needed because Android assets don't support true directories - they use
         * a flat naming convention with "/" as separators. This function recursively copies
         * all assets under the given path.
         * 
         * @param context Application context
         * @param assetPath Path to the asset directory (e.g., "inputcontrols/profiles")
         * @param destDir Destination directory
         * @return true if all files were copied successfully
         */
        fun copyAssetsDir(context: Context, assetPath: String, destDir: File): Boolean {
            return try {
                val assetManager = context.assets
                
                // First, ensure destination directory exists
                if (!destDir.exists()) {
                    destDir.mkdirs()
                }

                // List all files in the asset directory
                val files = assetManager.list(assetPath) ?: return true  // Empty directory is OK
                
                if (files.isEmpty()) return true
                
                for (file in files) {
                    val sourcePath = "$assetPath/$file"
                    
                    // Check if this is a directory or file
                    val subFiles = assetManager.list(sourcePath)
                    
                    if (subFiles != null && subFiles.isNotEmpty()) {
                        // It's a directory - recursively copy
                        val subDestDir = File(destDir, file)
                        copyAssetsDir(context, sourcePath, subDestDir)
                    } else {
                        // It's a file - copy it
                        val destFile = File(destDir, file)
                        copy(context, sourcePath, destFile)
                    }
                }
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

        /**
         * Read JSON array from file
         */
        fun readJSONArray(file: File): org.json.JSONArray? {
            val content = readString(file) ?: return null
            return try {
                org.json.JSONArray(content)
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Read JSON object from file
         */
        fun readJSONObject(file: File): org.json.JSONObject? {
            val content = readString(file) ?: return null
            return try {
                org.json.JSONObject(content)
            } catch (e: Exception) {
                null
            }
        }
    }
}
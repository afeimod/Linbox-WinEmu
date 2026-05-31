package com.termux.x11.controller.inputcontrols

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.content.pm.PackageInfo
import android.media.MediaScannerConnection
import android.os.Environment
import androidx.preference.PreferenceManager
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Manages input controls profiles
 */
class InputControlsManager(private val context: Context) {
    private val profiles = ArrayList<ControlsProfile>()
    private var maxProfileId = 0
    private var profilesLoaded = false

    fun getProfiles(ignoreTemplates: Boolean = false): ArrayList<ControlsProfile> {
        if (!profilesLoaded) loadProfiles(ignoreTemplates)
        return profiles
    }

    /**
     * 强制重新从磁盘加载配置列表
     * 用于确保在创建新配置后，其他 InputControlsManager 实例能够看到新配置
     */
    fun forceReloadProfiles() {
        profilesLoaded = false
        profiles.clear()
        maxProfileId = 0
        loadProfiles(false)
    }

    private fun copyAssetProfilesIfNeeded() {
        val profilesDir = getProfilesDir(context)
        if (!profilesDir.isDirectory) {
            profilesDir.mkdir()
        }

        // Check if profiles directory is empty
        val isEmpty = profilesDir.listFiles()?.isEmpty() != false

        if (isEmpty) {
            // Copy default profiles from assets (inputcontrols/profiles/)
            copyAssetsToDir(context, "inputcontrols/profiles", profilesDir)
            return
        }

        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val newVersion = try {
            val packageInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.longVersionCode.toInt()
        } catch (e: Exception) {
            1
        }
        val oldVersion = preferences.getInt("inputcontrols_app_version", 0)

        if (oldVersion == newVersion) return
        preferences.edit().putInt("inputcontrols_app_version", newVersion).apply()

        val files = profilesDir.listFiles() ?: return

        try {
            val assetManager = context.assets
            val assetFiles = assetManager.list("inputcontrols/profiles") ?: return

            for (assetFile in assetFiles) {
                val assetPath = "inputcontrols/profiles/$assetFile"
                val originProfile = loadProfile(context, assetManager.open(assetPath))

                var targetFile: File? = null
                for (file in files) {
                    val targetProfile = loadProfile(context, file)
                    if (originProfile?.id == targetProfile?.id && originProfile?.name == targetProfile?.name) {
                        targetFile = file
                        break
                    }
                }

                if (targetFile != null) {
                    // Update existing profiles with new versions from assets
                    assetManager.open(assetPath).use { input ->
                        targetFile!!.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun copyAssetsToDir(context: Context, assetPath: String, destDir: File) {
        val assetManager = context.assets
        try {
            val files = assetManager.list(assetPath) ?: return

            for (file in files) {
                val sourceStream = assetManager.open("$assetPath/$file")
                val destFile = File(destDir, file)
                destFile.outputStream().use { output ->
                    sourceStream.copyTo(output)
                }
                sourceStream.close()
            }
        } catch (e: Exception) {
            // Single file copy
            try {
                val sourceStream = assetManager.open(assetPath)
                val fileName = assetPath.substringAfterLast("/")
                val destFile = File(destDir, fileName)
                destFile.outputStream().use { output ->
                    sourceStream.copyTo(output)
                }
                sourceStream.close()
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }
    }

    fun loadProfiles(ignoreTemplates: Boolean = false) {
        val profilesDir = getProfilesDir(context)
        copyAssetProfilesIfNeeded()

        profiles.clear()
        val files = profilesDir.listFiles()
        if (files != null) {
            for (file in files) {
                val profile = loadProfile(context, file)
                if (profile != null) {
                    if (!(ignoreTemplates && profile.isTemplate())) {
                        profiles.add(profile)
                    }
                    maxProfileId = maxOf(maxProfileId, profile.id)
                }
            }
        }

        profiles.sortWith(compareBy { it.id })
        profilesLoaded = true
    }

    fun createProfile(name: String): ControlsProfile {
        val newId = ++maxProfileId
        val profile = ControlsProfile(context, newId)
        profile.name = name
        profile.save()
        profiles.add(profile)
        return profile
    }

    fun duplicateProfile(source: ControlsProfile): ControlsProfile {
        var newName: String
        var i = 1
        while (true) {
            newName = "${source.name} ($i)"
            var found = false
            for (profile in profiles) {
                if (profile.name == newName) {
                    found = true
                    break
                }
            }
            if (!found) break
            i++
        }

        val newId = ++maxProfileId
        val newFile = ControlsProfile.getProfileFile(context, newId)

        try {
            val sourceFile = ControlsProfile.getProfileFile(context, source.id)
            val data = JSONObject(sourceFile.readText())
            data.put("id", newId)
            data.put("name", newName)
            if (data.has("template")) data.remove("template")
            newFile.writeText(data.toString())
        } catch (e: JSONException) {
            e.printStackTrace()
        }

        val profile = loadProfile(context, newFile)
        if (profile != null) {
            profiles.add(profile)
            return profile
        }
        return source
    }

    fun removeProfile(profile: ControlsProfile) {
        val file = ControlsProfile.getProfileFile(context, profile.id)
        if (file.isFile && file.delete()) {
            profiles.remove(profile)
        }
    }

    fun importProfile(data: JSONObject): ControlsProfile? {
        return try {
            if (!data.has("id") || !data.has("name")) return null

            val newId = ++maxProfileId
            val newFile = ControlsProfile.getProfileFile(context, newId)
            data.put("id", newId)
            newFile.writeText(data.toString())

            val newProfile = loadProfile(context, newFile) ?: return null

            var foundIndex = -1
            for (i in profiles.indices) {
                if (profiles[i].name == newProfile.name) {
                    foundIndex = i
                    break
                }
            }

            if (foundIndex != -1) {
                profiles[foundIndex] = newProfile
            } else {
                profiles.add(newProfile)
            }
            newProfile
        } catch (e: JSONException) {
            null
        }
    }

    fun exportProfile(profile: ControlsProfile): File? {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val destination = File(downloadsDir, "WinEmulator/profiles/${profile.name}.icp")
        destination.parentFile?.mkdirs()

        val sourceFile = ControlsProfile.getProfileFile(context, profile.id)
        sourceFile.copyTo(destination, overwrite = true)

        MediaScannerConnection.scanFile(context, arrayOf(destination.absolutePath), null, null)
        return if (destination.isFile) destination else null
    }

    fun getProfile(id: Int): ControlsProfile? {
        for (profile in getProfiles()) {
            if (profile.id == id) return profile
        }
        return null
    }

    companion object {
        fun getProfilesDir(context: Context): File {
            val profilesDir = File(context.filesDir, "profiles")
            if (!profilesDir.isDirectory) profilesDir.mkdir()
            return profilesDir
        }

        fun getProfileFile(context: Context, id: Int): File {
            return File(getProfilesDir(context), "controls-$id.icp")
        }

        fun loadProfile(context: Context, file: File): ControlsProfile? {
            return try {
                loadProfile(context, FileInputStream(file))
            } catch (e: Exception) {
                null
            }
        }

        fun loadProfile(context: Context, inputStream: java.io.InputStream): ControlsProfile? {
            try {
                InputStreamReader(inputStream, StandardCharsets.UTF_8).use { reader ->
                    val json = reader.readText()
                    val jsonObject = JSONObject(json)

                    val profileId = jsonObject.getInt("id")
                    val profileName = jsonObject.optString("name", "Unnamed")
                    val cursorSpeed = if (jsonObject.has("cursorSpeed"))
                        jsonObject.getDouble("cursorSpeed").toFloat() else 1.0f

                    val profile = ControlsProfile(context, profileId)
                    profile.name = profileName
                    profile.cursorSpeed = cursorSpeed
                    return profile
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
        }
    }
}

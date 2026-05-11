package org.github.ewt45.winemulator.inputcontrols

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.JsonReader
import androidx.preference.PreferenceManager
import com.termux.x11.controller.core.AppUtils
import com.termux.x11.controller.core.FileUtils
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Collections

class InputControlsManager(private val context: Context) {
    private val profiles = ArrayList<ControlsProfile>()
    private var maxProfileId = 0
    private var profilesLoaded = false

    fun forceReloadProfiles() {
        profilesLoaded = false
    }

    fun getProfiles(): ArrayList<ControlsProfile> {
        return getProfiles(false)
    }

    fun getProfiles(ignoreTemplates: Boolean): ArrayList<ControlsProfile> {
        if (!profilesLoaded) loadProfiles(ignoreTemplates)
        return profiles
    }

    private fun copyAssetProfilesIfNeeded() {
        val profilesDir = InputControlsManager.getProfilesDir(context)
        if (FileUtils.isEmpty(profilesDir)) {
            // Use copyAssetsDir to copy all files from asset directory
            FileUtils.copyAssetsDir(context, "inputcontrols/profiles", profilesDir)
            return
        }

        val preferences = PreferenceManager.getDefaultSharedPreferences(context)

        val newVersion = AppUtils.getVersionCode(context)
        val oldVersion = preferences.getInt("inputcontrols_app_version", 0)
        if (oldVersion == newVersion) return
        preferences.edit().putInt("inputcontrols_app_version", newVersion).apply()

        val files = profilesDir.listFiles() ?: return

        try {
            val assetManager = context.assets
            val assetFiles = assetManager.list("inputcontrols/profiles")
            for (assetFile in assetFiles!!) {
                val assetPath = "inputcontrols/profiles/" + assetFile
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
                    FileUtils.copy(context, assetPath, targetFile)
                }
            }
        } catch (e: IOException) {
        }
    }

    fun loadProfiles(ignoreTemplates: Boolean) {
        val profilesDir = InputControlsManager.getProfilesDir(context)
        copyAssetProfilesIfNeeded()

        val profilesList = ArrayList<ControlsProfile>()
        val files = profilesDir.listFiles()
        if (files != null) {
            for (file in files) {
                val profile = loadProfile(context, file)
                if (profile != null) {
                    if (!(ignoreTemplates && profile.isTemplate())) profilesList.add(profile)
                    maxProfileId = maxOf(maxProfileId, profile.id)
                }
            }
        }

        Collections.sort(profilesList)
        this.profiles.clear()
        this.profiles.addAll(profilesList)
        profilesLoaded = true
    }

    fun createProfile(name: String): ControlsProfile {
        val profile = ControlsProfile(context, ++maxProfileId)
        profile.name = name
        profile.save()
        profiles.add(profile)
        return profile
    }

    fun duplicateProfile(source: ControlsProfile): ControlsProfile {
        var newName: String
        var i = 1
        while (true) {
            newName = source.name + " (" + i + ")"
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
            val data = JSONObject(FileUtils.readString(ControlsProfile.getProfileFile(context, source.id)))
            data.put("id", newId)
            data.put("name", newName)
            if (data.has("template")) data.remove("template")
            FileUtils.writeString(newFile, data.toString())
        } catch (e: JSONException) {
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
        if (file.isFile && file.delete()) profiles.remove(profile)
    }

    fun importProfile(data: JSONObject): ControlsProfile? {
        return try {
            if (!data.has("id") || !data.has("name")) return null
            val newId = ++maxProfileId
            val newFile = ControlsProfile.getProfileFile(context, newId)
            data.put("id", newId)
            FileUtils.writeString(newFile, data.toString())
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
        val destination = File(downloadsDir, "Winlator/profiles/" + profile.name + ".icp")
        destination.parentFile?.mkdirs()
        FileUtils.copy(ControlsProfile.getProfileFile(context, profile.id), destination)
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

        fun loadProfile(context: Context, file: File): ControlsProfile? {
            return try {
                loadProfile(context, FileInputStream(file))
            } catch (e: FileNotFoundException) {
                null
            }
        }

        fun loadProfile(context: Context, inStream: InputStream): ControlsProfile? {
            try {
                val reader = JsonReader(InputStreamReader(inStream, StandardCharsets.UTF_8))
                var profileId = 0
                var profileName: String? = null
                var cursorSpeed = Float.NaN
                var fieldsRead = 0

                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()

                    when (name) {
                        "id" -> {
                            profileId = reader.nextInt()
                            fieldsRead++
                        }
                        "name" -> {
                            profileName = reader.nextString()
                            fieldsRead++
                        }
                        "cursorSpeed" -> {
                            cursorSpeed = reader.nextDouble().toFloat()
                            fieldsRead++
                        }
                        else -> {
                            if (fieldsRead == 3) break
                            reader.skipValue()
                        }
                    }
                }

                reader.close()

                val profile = ControlsProfile(context, profileId)
                profile.name = profileName ?: ""
                profile.cursorSpeed = cursorSpeed
                return profile
            } catch (e: IOException) {
                return null
            }
        }
    }
}

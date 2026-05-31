package org.github.ewt45.winemulator.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import org.github.ewt45.winemulator.R
import com.termux.x11.controller.inputcontrols.ControlsProfile
import com.termux.x11.controller.inputcontrols.InputControlsManager
import org.json.JSONObject
import java.util.ArrayList

class InputControlsFragment : Fragment() {
    private lateinit var manager: InputControlsManager
    private var currentProfile: ControlsProfile? = null
    private var updateLayout: Runnable? = null
    private var importProfileCallback: ((ControlsProfile) -> Unit)? = null

    companion object {
        const val SELECTED_PROFILE_ID = "selected_profile_id"
    }

    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    val inputStream = requireContext().contentResolver.openInputStream(uri)
                    val json = inputStream?.bufferedReader()?.readText()
                    inputStream?.close()
                    if (json != null) {
                        val importedProfile = manager.importProfile(JSONObject(json))
                        if (importedProfile != null && importProfileCallback != null) {
                            importProfileCallback!!(importedProfile)
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, R.string.unable_to_import_profile, Toast.LENGTH_SHORT).show()
                }
            }
        }
        importProfileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = InputControlsManager(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.input_controls_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)

        // 从 SharedPreferences 加载上次选中的配置 ID
        val savedProfileId = preferences.getInt(SELECTED_PROFILE_ID, 0)
        currentProfile = if (savedProfileId > 0) manager.getProfile(savedProfileId) else null

        val sProfile = view.findViewById<Spinner>(R.id.SProfile)
        loadProfileSpinner(sProfile)

        val tvCursorSpeed = view.findViewById<TextView>(R.id.TVCursorSpeed)
        val sbCursorSpeed = view.findViewById<SeekBar>(R.id.SBCursorSpeed)
        sbCursorSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvCursorSpeed.text = "$progress%"
                if (currentProfile != null && fromUser) {
                    currentProfile!!.cursorSpeed = progress / 100.0f
                    currentProfile!!.save()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        updateLayout = Runnable {
            if (currentProfile != null) {
                sbCursorSpeed.progress = (currentProfile!!.cursorSpeed * 100).toInt()
            } else {
                sbCursorSpeed.progress = 100
            }
        }

        updateLayout!!.run()

        val tvUiOpacity = view.findViewById<TextView>(R.id.TVUiOpacity)
        val sbUiOpacity = view.findViewById<SeekBar>(R.id.SBOverlayOpacity)
        sbUiOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvUiOpacity.text = "$progress%"
                if (fromUser) {
                    preferences.edit().putFloat("overlay_opacity", progress / 100.0f).apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        val overlayOpacity = preferences.getFloat("overlay_opacity", 0.4f)
        sbUiOpacity.progress = (overlayOpacity * 100).toInt()

        // 新建配置
        view.findViewById<Button>(R.id.BTAddProfile).setOnClickListener {
            showInputDialog(R.string.profile_name, null) { name ->
                currentProfile = manager.createProfile(name)
                preferences.edit().putInt(SELECTED_PROFILE_ID, currentProfile!!.id).apply()
                loadProfileSpinner(sProfile)
                updateLayout!!.run()
            }
        }

        // 编辑配置名称
        view.findViewById<Button>(R.id.BTEditProfile).setOnClickListener {
            if (currentProfile != null) {
                showInputDialog(R.string.profile_name, currentProfile!!.name) { name ->
                    currentProfile!!.name = name
                    currentProfile!!.save()
                    loadProfileSpinner(sProfile)
                }
            } else {
                Toast.makeText(context, R.string.no_profile_selected, Toast.LENGTH_SHORT).show()
            }
        }

        // 复制配置
        view.findViewById<Button>(R.id.BTDuplicateProfile).setOnClickListener {
            if (currentProfile != null) {
                currentProfile = manager.duplicateProfile(currentProfile!!)
                preferences.edit().putInt(SELECTED_PROFILE_ID, currentProfile!!.id).apply()
                loadProfileSpinner(sProfile)
                updateLayout!!.run()
            } else {
                Toast.makeText(context, R.string.no_profile_selected, Toast.LENGTH_SHORT).show()
            }
        }

        // 删除配置
        view.findViewById<Button>(R.id.BTRemoveProfile).setOnClickListener {
            if (currentProfile != null) {
                manager.removeProfile(currentProfile!!)
                preferences.edit().remove(SELECTED_PROFILE_ID).apply()
                currentProfile = manager.getProfiles().firstOrNull()
                if (currentProfile != null) {
                    preferences.edit().putInt(SELECTED_PROFILE_ID, currentProfile!!.id).apply()
                }
                loadProfileSpinner(sProfile)
                updateLayout!!.run()
            } else {
                Toast.makeText(context, R.string.no_profile_selected, Toast.LENGTH_SHORT).show()
            }
        }

        // 导入配置
        view.findViewById<Button>(R.id.BTImportProfile).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            importProfileCallback = { profile ->
                currentProfile = profile
                preferences.edit().putInt(SELECTED_PROFILE_ID, currentProfile!!.id).apply()
                loadProfileSpinner(sProfile)
                updateLayout!!.run()
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, 1)
        }

        // 导出配置
        view.findViewById<Button>(R.id.BTExportProfile).setOnClickListener {
            if (currentProfile != null) {
                val exportedFile = manager.exportProfile(currentProfile!!)
                if (exportedFile != null) {
                    Toast.makeText(context, "${getString(R.string.profile_exported_to)} ${exportedFile.path}", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(context, R.string.no_profile_selected, Toast.LENGTH_SHORT).show()
            }
        }

        // 打开编辑器
        view.findViewById<Button>(R.id.BTControlsEditor).setOnClickListener {
            if (currentProfile != null) {
                val intent = Intent(context, ControlsEditorActivity::class.java)
                intent.putExtra(ControlsEditorActivity.EXTRA_PROFILE_ID, currentProfile!!.id)
                startActivity(intent)
            } else {
                Toast.makeText(context, R.string.no_profile_selected, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        updateLayout?.run()
    }

    private fun loadProfileSpinner(spinner: Spinner) {
        val profiles = manager.getProfiles()
        val values = ArrayList<String>()
        values.add("-- ${getString(R.string.select_profile)} --")

        var selectedPosition = 0
        for (i in profiles.indices) {
            val profile = profiles[i]
            if (profile == currentProfile) {
                selectedPosition = i + 1
            }
            values.add(profile.name)
        }

        spinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, values)
        spinner.setSelection(selectedPosition, false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentProfile = if (position > 0) profiles[position - 1] else null
                val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                if (currentProfile != null) {
                    prefs.edit().putInt(SELECTED_PROFILE_ID, currentProfile!!.id).apply()
                } else {
                    prefs.edit().remove(SELECTED_PROFILE_ID).apply()
                }
                updateLayout?.run()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun showInputDialog(titleResId: Int, initialValue: String?, onResult: (String) -> Unit) {
        val editText = android.widget.EditText(requireContext()).apply {
            setText(initialValue)
            hint = getString(titleResId)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(titleResId)
            .setView(editText)
            .setPositiveButton(R.string.ok) { _, _ ->
                val value = editText.text.toString().trim()
                if (value.isNotEmpty()) {
                    onResult(value)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    val inputStream = requireContext().contentResolver.openInputStream(uri)
                    val json = inputStream?.bufferedReader()?.readText()
                    inputStream?.close()
                    if (json != null) {
                        val importedProfile = manager.importProfile(JSONObject(json))
                        if (importedProfile != null && importProfileCallback != null) {
                            importProfileCallback!!(importedProfile)
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, R.string.unable_to_import_profile, Toast.LENGTH_SHORT).show()
                }
            }
        }
        importProfileCallback = null
    }
}
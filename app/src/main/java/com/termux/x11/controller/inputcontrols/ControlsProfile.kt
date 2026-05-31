package com.termux.x11.controller.inputcontrols

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * Represents a controls profile containing multiple control elements
 */
class ControlsProfile(
    private val context: Context,
    val id: Int
) : Comparable<ControlsProfile> {
    var name: String = ""
    var cursorSpeed: Float = 1.0f
    private val elements = ArrayList<ControlElement>()
    private val immutableElements: List<ControlElement> = elements
    private var elementsLoaded = false
    var isVirtualGamepad = false
        private set

    fun getElements(): List<ControlElement> = immutableElements

    fun addElement(element: ControlElement) {
        elements.add(element)
        elementsLoaded = true
    }

    fun removeElement(element: ControlElement) {
        elements.remove(element)
        elementsLoaded = true
    }

    fun isElementsLoaded(): Boolean = elementsLoaded

    fun isTemplate(): Boolean = name.lowercase().contains("template")

    fun save() {
        val file = getProfileFile(context, id)

        try {
            val data = JSONObject()
            data.put("id", id)
            data.put("name", name)
            data.put("cursorSpeed", cursorSpeed.toDouble())

            val elementsArray = JSONArray()
            if (!elementsLoaded && file.isFile) {
                val profileJson = JSONObject(file.readText())
                elementsArray.put(profileJson.getJSONArray("elements"))
            } else {
                for (element in elements) {
                    elementsArray.put(element.toJSONObject())
                }
            }
            data.put("elements", elementsArray)

            file.writeText(data.toString())
        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }

    fun loadElements(inputControlsView: InputControlsView) {
        elements.clear()
        elementsLoaded = false
        isVirtualGamepad = false

        val file = getProfileFile(context, id)
        if (!file.isFile) return

        try {
            val profileJson = JSONObject(file.readText())
            val elementsArray = profileJson.getJSONArray("elements")

            for (i in 0 until elementsArray.length()) {
                val elementJson = elementsArray.getJSONObject(i)
                val element = ControlElement(inputControlsView)

                element.type = ControlElement.Type.valueOf(elementJson.getString("type"))
                element.shape = ControlElement.Shape.valueOf(elementJson.getString("shape"))
                element.isToggleSwitch = elementJson.getBoolean("toggleSwitch")
                element.x = (elementJson.getDouble("x") * inputControlsView.maxWidth).toInt()
                element.y = (elementJson.getDouble("y") * inputControlsView.maxHeight).toInt()
                element.scale = elementJson.getDouble("scale").toFloat()
                element.text = elementJson.optString("text", "")
                element.iconId = elementJson.optInt("iconId", 0).toByte()

                if (elementJson.has("range")) {
                    element.range = ControlElement.Range.fromString(elementJson.getString("range"))
                }
                if (elementJson.has("orientation")) {
                    element.orientation = elementJson.getInt("orientation").toByte()
                }

                var hasGamepadBinding = true
                val bindingsArray = elementJson.getJSONArray("bindings")
                for (j in 0 until bindingsArray.length()) {
                    val binding = Binding.fromString(bindingsArray.getString(j))
                    element.setBindingAt(j, binding)
                    if (!binding.isGamepad) hasGamepadBinding = false
                }

                if (!isVirtualGamepad && hasGamepadBinding) isVirtualGamepad = true

                elements.add(element)
            }
            elementsLoaded = true
        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }

    override fun compareTo(other: ControlsProfile): Int = id.compareTo(other.id)

    override fun toString(): String = name

    companion object {
        fun getProfileFile(context: Context, id: Int): File {
            return File(InputControlsManager.getProfilesDir(context), "controls-$id.icp")
        }

        fun loadProfile(context: Context, file: File): ControlsProfile? {
            return InputControlsManager.loadProfile(context, file)
        }
    }
}

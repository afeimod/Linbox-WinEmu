package org.github.ewt45.winemulator.glibcwine

import android.os.Environment
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * Wine 容器 (前缀), 移植自 winlator-glibc 的 Container.java。
 *
 * 每个 WineContainer 拥有独立的 WINEPREFIX (.wine 目录), 包含:
 * - Windows 注册表 (system.reg, user.reg)
 * - drive_c (C 盘)
 * - dosdevices (盘符符号链接)
 * - .container (容器配置 JSON)
 * - .box64rc (box64 进程级配置)
 *
 * 容器数据存储在 imagefs/home/xuser-<id>/ 下。
 * activateContainer() 创建 xuser -> xuser-<id> 符号链接, 使 wine 通过 HOME=/home/xuser 访问当前容器。
 *
 * 在 proot 整合中, 容器的 rootDir 是 Android 路径,
 * 但 wine 进程在 proot 内看到的是 /opt/glibc-wine/home/xuser-<id>/。
 */
class WineContainer(
    val id: Int,
    var rootDir: File = File("")
) {
    private val TAG = "WineContainer"

    var name: String = "Container-$id"
    var screenSize: String = GlibcWineConsts.DEFAULT_SCREEN_SIZE
    var envVars: String = GlibcWineConsts.DEFAULT_ENV_VARS
    var graphicsDriver: String = GlibcWineConsts.DEFAULT_GRAPHICS_DRIVER
    var dxwrapper: String = GlibcWineConsts.DEFAULT_DXWRAPPER
    var dxwrapperConfig: String = ""
    var wincomponents: String = GlibcWineConsts.DEFAULT_WINCOMPONENTS
    var audioDriver: String = GlibcWineConsts.DEFAULT_AUDIO_DRIVER
    var drives: String = "D:${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}E:/data/data/a.io.github.ewt45.winemulator/storage"
    var wineVersion: String = WineInfo.MAIN_WINE_VERSION.identifier()
    var showFPS: Boolean = false
    var startupSelection: Byte = GlibcWineConsts.STARTUP_SELECTION_ESSENTIAL
    var cpuList: String? = null
    var box64Preset: String = Box64Preset.COMPATIBILITY
    var box64Version: String = GlibcWineConsts.DefaultVersion.BOX64
    var fexPreset: Int = 0
    var fexPresetCustom: String = "COMPATIBILITY"
    var fexVersion: String = "FEX-2603"
    var cursorTheme: String = GlibcWineConsts.DEFAULT_CURSOR_THEME
    var cursorSize: String = GlibcWineConsts.DEFAULT_CURSOR_SIZE
    var rcfileId: Int = 0
    var midiSoundFont: String = ""
    var inputType: Int = 0
    var lcAll: String = ""
    var primaryController: Int = 1
    private var extraData: JSONObject? = null

    /** 配置文件路径 */
    fun getConfigFile(): File = File(rootDir, ".container")

    /** Desktop 快捷方式目录 */
    fun getDesktopDir(): File = File(rootDir, ".wine/drive_c/users/${GlibcWineConsts.USER}/Desktop/")

    /** Start Menu 目录 */
    fun getStartMenuDir(): File = File(rootDir, ".wine/drive_c/ProgramData/Microsoft/Windows/Start Menu/")

    /** 获取额外数据 */
    fun getExtra(name: String, fallback: String = ""): String {
        return try {
            if (extraData != null && extraData!!.has(name)) extraData!!.getString(name) else fallback
        } catch (e: JSONException) {
            fallback
        }
    }

    /** 设置额外数据 */
    fun putExtra(name: String, value: Any?) {
        if (extraData == null) extraData = JSONObject()
        try {
            if (value != null) {
                extraData!!.put(name, value)
            } else {
                extraData!!.remove(name)
            }
        } catch (e: JSONException) {
            Log.e(TAG, "putExtra 失败", e)
        }
    }

    /** 驱动器盘符迭代器, 返回 [盘符, 路径] 对 */
    fun drivesIterator(): Iterable<Array<String>> = drivesIterator(drives)

    companion object {
        private const val TAG = "WineContainer"

        fun drivesIterator(drives: String): Iterable<Array<String>> {
            var index = drives.indexOf(":")
            return object : Iterable<Array<String>> {
                override fun iterator(): Iterator<Array<String>> = object : Iterator<Array<String>> {
                    override fun hasNext(): Boolean = index != -1

                    override fun next(): Array<String> {
                        val driveLetter = drives[index - 1].toString()
                        val nextIndex = drives.indexOf(":", index + 1)
                        val path = drives.substring(index + 1, if (nextIndex != -1) nextIndex - 1 else drives.length)
                        index = nextIndex
                        return arrayOf(driveLetter, path)
                    }
                }
            }
        }

        fun getFallbackCPUList(): String {
            val numProcessors = Runtime.getRuntime().availableProcessors()
            return (0 until numProcessors).joinToString(",")
        }
    }

    /** 保存配置到 .container 文件 */
    fun saveData() {
        try {
            val data = JSONObject()
            data.put("id", id)
            data.put("name", name)
            data.put("screenSize", screenSize)
            data.put("envVars", envVars)
            data.put("cpuList", cpuList ?: JSONObject.NULL)
            data.put("graphicsDriver", graphicsDriver)
            data.put("dxwrapper", dxwrapper)
            if (dxwrapperConfig.isNotEmpty()) data.put("dxwrapperConfig", dxwrapperConfig)
            data.put("audioDriver", audioDriver)
            data.put("wincomponents", wincomponents)
            data.put("drives", drives)
            data.put("showFPS", showFPS)
            data.put("inputType", inputType)
            data.put("startupSelection", startupSelection.toInt())
            data.put("box64Preset", box64Preset)
            data.put("box64Version", box64Version)
            data.put("fexPreset", fexPreset)
            data.put("fexPresetCustom", fexPresetCustom)
            data.put("fexVersion", fexVersion)
            data.put("rcfileId", rcfileId)
            data.put("midiSoundFont", midiSoundFont)
            data.put("lc_all", lcAll)
            data.put("primaryController", primaryController)
            data.put("cursorTheme", cursorTheme)
            data.put("cursorSize", cursorSize)
            data.put("wineVersion", wineVersion)
            if (extraData != null) data.put("extraData", extraData)

            getConfigFile().writeText(data.toString())
        } catch (e: JSONException) {
            Log.e(TAG, "saveData 失败", e)
        }
    }

    /** 从 JSON 加载配置 */
    fun loadData(data: JSONObject) {
        wineVersion = WineInfo.MAIN_WINE_VERSION.identifier()
        dxwrapperConfig = ""

        val keys = data.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            try {
                when (key) {
                    "name" -> name = data.getString(key)
                    "screenSize" -> screenSize = data.getString(key)
                    "envVars" -> envVars = data.getString(key)
                    "cpuList" -> cpuList = if (data.isNull(key)) null else data.getString(key)
                    "graphicsDriver" -> graphicsDriver = data.getString(key)
                    "wincomponents" -> wincomponents = data.getString(key)
                    "dxwrapper" -> dxwrapper = data.getString(key)
                    "dxwrapperConfig" -> dxwrapperConfig = data.getString(key)
                    "audioDriver" -> audioDriver = data.getString(key)
                    "drives" -> drives = data.getString(key)
                    "showFPS" -> showFPS = data.getBoolean(key)
                    "inputType" -> inputType = data.getInt(key)
                    "startupSelection" -> startupSelection = data.getInt(key).toByte()
                    "box64Preset" -> box64Preset = data.getString(key)
                    "box64Version" -> box64Version = data.getString(key)
                    "fexPreset" -> fexPreset = data.getInt(key)
                    "fexPresetCustom" -> fexPresetCustom = data.getString(key)
                    "fexVersion" -> fexVersion = data.getString(key)
                    "rcfileId" -> rcfileId = data.getInt(key)
                    "midiSoundFont" -> midiSoundFont = data.getString(key)
                    "lc_all" -> lcAll = data.getString(key)
                    "primaryController" -> primaryController = data.getInt(key)
                    "cursorTheme" -> cursorTheme = data.getString(key)
                    "cursorSize" -> cursorSize = data.getString(key)
                    "wineVersion" -> wineVersion = data.getString(key)
                    "extraData" -> extraData = data.getJSONObject(key)
                }
            } catch (e: JSONException) {
                Log.w(TAG, "加载字段 $key 失败", e)
            }
        }
    }
}

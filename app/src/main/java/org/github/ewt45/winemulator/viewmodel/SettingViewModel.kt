package org.github.ewt45.winemulator.viewmodel

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.system.Os
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.datastore.preferences.core.edit
import androidx.preference.PreferenceManager
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.apache.commons.io.FileUtils
import org.github.ewt45.winemulator.Consts
import org.github.ewt45.winemulator.Utils.Ui.editDateStore
import org.github.ewt45.winemulator.Utils.Ui.stateInSimple
import org.github.ewt45.winemulator.dataStore
import org.github.ewt45.winemulator.Consts.Pref
import org.github.ewt45.winemulator.Consts.Pref.Local.rootfs_login_user_json
import org.github.ewt45.winemulator.Consts.Pref.general_resolution
import org.github.ewt45.winemulator.Consts.Pref.general_rootfs_lang
import org.github.ewt45.winemulator.Consts.Pref.general_shared_ext_path
import org.github.ewt45.winemulator.Consts.Pref.inputcontrols_enabled
import org.github.ewt45.winemulator.Consts.Pref.inputcontrols_haptics
import org.github.ewt45.winemulator.Consts.Pref.inputcontrols_opacity
import org.github.ewt45.winemulator.Consts.Pref.inputcontrols_profile_id
import org.github.ewt45.winemulator.Consts.Pref.proot_bool_options
import org.github.ewt45.winemulator.Consts.Pref.proot_startup_cmd
import org.github.ewt45.winemulator.Consts.Pref.general_theme_mode
import org.github.ewt45.winemulator.Consts.Pref.x11_touch_mode
import org.github.ewt45.winemulator.Consts.Pref.x11_screen_orientation
import org.github.ewt45.winemulator.Consts.Pref.x11_display_scale
import org.github.ewt45.winemulator.Consts.Pref.x11_keep_screen_on
import org.github.ewt45.winemulator.Consts.Pref.x11_fullscreen
import org.github.ewt45.winemulator.Consts.Pref.x11_hide_cutout
import org.github.ewt45.winemulator.Consts.Pref.x11_pip_mode
import org.github.ewt45.winemulator.Consts.Pref.x11_resolution
import org.github.ewt45.winemulator.Consts.rootfsAllDir
import org.github.ewt45.winemulator.Consts.rootfsCurrDir
import org.github.ewt45.winemulator.FuncOnChangeAction
import org.github.ewt45.winemulator.MainEmuActivity
import org.github.ewt45.winemulator.RateLimiter
import org.github.ewt45.winemulator.Utils
import org.github.ewt45.winemulator.Utils.Ui.editDateStoreAsync
import org.github.ewt45.winemulator.emu.ProotRootfs
import java.io.File

private val TAG = "SettingViewModel"


/**
 * 修改数据逻辑： 用户编辑 -> 调用viewModel函数 -> 修改dataStore(editDateStore) -> 触发 flow 的emit -> 传递到state -> 触发compose重组
 *
 * 添加一个属性时，记得修改对应flow的map中的新建实例的传参
 */
class SettingViewModel : ViewModel() {

    // SharedPreferences，用于同步X11设置到termux-x11可读的格式
    private var sharedPrefs: SharedPreferences? = null

    /** 初始化SharedPreferences，需要在Activity中调用 */
    fun initSharedPreferences(context: Context) {
        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context)
    }

    /** 将当前X11设置同步到SharedPreferences - 使用DataStore直接获取值避免闪退 */
    fun syncX11SettingsToSharedPrefs() {
        val prefs = sharedPrefs ?: return
        viewModelScope.launch {
            try {
                val data = dataStore.data.first()
                prefs.edit().apply {
                    // 触摸模式: 0=虚拟触控板(1), 1=模拟触摸(2), 2=触摸屏(3)
                    val touchMode = data[x11_touch_mode.key] ?: x11_touch_mode.default
                    putString("touchMode", (touchMode + 1).toString())
                    // 屏幕方向: 10=auto, 11=landscape, 12=portrait, 13=reverseLandscape, 14=reversePortrait
                    val orientationMap = mapOf(10 to "auto", 11 to "landscape", 12 to "portrait", 13 to "reverseLandscape", 14 to "reversePortrait")
                    val orientation = data[x11_screen_orientation.key] ?: x11_screen_orientation.default
                    putString("forceOrientation", orientationMap[orientation] ?: "auto")
                    // 显示缩放
                    val scale = data[x11_display_scale.key] ?: x11_display_scale.default
                    putInt("displayScale", scale)
                    // 保持屏幕常亮
                    val keepScreenOn = data[x11_keep_screen_on.key] ?: x11_keep_screen_on.default
                    putBoolean("keepScreenOn", keepScreenOn)
                    // 分辨率: 使用general_resolution保持与原有逻辑一致
                    val resolution = data[general_resolution.key] ?: general_resolution.default
                    if (resolution.contains("x")) {
                        putString("displayResolutionMode", "custom")
                        putString("displayResolutionCustom", resolution)
                    }
                    apply()
                }
            } catch (e: Exception) {
                Log.e(TAG, "syncX11SettingsToSharedPrefs failed", e)
            }
        }
    }

    // 一般设置
    var resolutionText by mutableStateOf("")
        private set

    /** key为[Consts.rootfsAllDir]下的的rootfs列表，value为对应rootfs的全部可用的用户。
     * rootfs不包含 包括[Consts.rootfsCurrDir] 暂时先用这个替代rootfsList。后续可以改成一个包含rootfs信息的列表（不对 不是字符串的话没法存datastore了） */
    val rootfsUsersOptions = mutableStateOf(mapOf<String, List<ProotRootfs.UserInfo>>())

    /** rootfs 别名映射，key 为文件夹名，value 为别名 */
    val rootfsAliasMap = mutableStateOf(mapOf<String, String>())

    val generalFLow = dataStore.data.map { pref ->
        PrefGeneral(
            general_resolution.run { pref[key] ?: default },
            general_shared_ext_path.run { pref[key] ?: default },
            general_rootfs_lang.run { pref[key] ?: default },
            Json.decodeFromString(rootfs_login_user_json.run { pref[key] ?: default }),
        )
    }
    val generalState = stateInSimple(PrefGeneral_DEFAULT, generalFLow)

    // proot设置
    val prootFlow = dataStore.data.map { pref ->
        PrefProot(
            proot_bool_options.run { pref[key] ?: default },
            proot_startup_cmd.run { pref[key] ?: default },
        )
    }
    val prootState = stateInSimple(PrefProot_DEFAULT, prootFlow)

    // InputControls设置
    val inputControlsFlow = dataStore.data.map { pref ->
        PrefInputControls(
            inputcontrols_enabled.run { pref[key] ?: default },
            inputcontrols_profile_id.run { pref[key] ?: default },
            inputcontrols_opacity.run { pref[key] ?: default },
            inputcontrols_haptics.run { pref[key] ?: default },
        )
    }
    val inputControlsState = stateInSimple(PrefInputControls_DEFAULT, inputControlsFlow)
    
    // Theme设置
    val themeFlow = dataStore.data.map { pref ->
        general_theme_mode.run { pref[key] ?: default }
    }
    val themeState = stateInSimple(1, themeFlow)

    // X11设置
    val x11Flow = dataStore.data.map { pref ->
        PrefX11(
            x11_touch_mode.run { pref[key] ?: default },
            x11_screen_orientation.run { pref[key] ?: default },
            x11_display_scale.run { pref[key] ?: default },
            x11_keep_screen_on.run { pref[key] ?: default },
            x11_fullscreen.run { pref[key] ?: default },
            x11_hide_cutout.run { pref[key] ?: default },
            x11_pip_mode.run { pref[key] ?: default },
            x11_resolution.run { pref[key] ?: default },
        )
    }
    val x11State = stateInSimple(PrefX11_DEFAULT, x11Flow)

    init {
        //部分数据不会自动更新，请在 [updateValuesWhenEnterSettings] 中更新，确保进入设置界面时会获取一次最新的值
    }

    /**
     * 当进入设置界面时，某些数据（例如本地文件列表）可能已经发生变化，但内存中的数据不会更改。所以需要在此时更新这些数据
     */
    fun updateValuesWhenEnterSettings() {
        Log.e(TAG, "updateValuesWhenEnterSettings: 测试一下不执行这个函数的时候是不是不更新")
        viewModelScope.launch(IO) {
            resolutionText = general_resolution.get() //resolutionText不随flow更改，初始化先读取一下
            rootfsUsersOptions.value = getRootfsUsersOptions()
            // 更新别名映射
            rootfsAliasMap.value = getRootfsList().associateWith { Utils.Rootfs.getAlias(File(rootfsAllDir, it)) }
            // 目前 localRootfsLoginUsersMap 依赖 rootfsUsersOptions 才能正常工作。所以也要手动刷新
            onChangeRootfsLoginUser("", "")
        }
    }

    /** 点击重置按钮 */
    suspend fun resetSettings() = withContext(IO) {
        dataStore.edit { pref -> Pref.allItems.forEach { item -> pref[item.key] = item.default } }
    }

    /** 点击导入按钮，从本地文件读取json转为用户偏好 */
    suspend fun importSettings(ctx: Context, uri: Uri) = withContext(IO) {
        return@withContext kotlin.runCatching {
            val readResult = Utils.Files.readFromUri(ctx, uri)
            if (readResult.isFailure) throw readResult.exceptionOrNull()!!
            val map = Utils.Pref.deserializeFromJsonToMap(readResult.getOrNull()!!)
            dataStore.edit { preference ->
                for (entry in map) {
                    Pref.allItems.find { it.key.name == entry.key }?.let { item ->
                        preference[item.key] = entry.value
                    }
                }
            }
            return@runCatching
        }
    }

    /** 点击导出按钮. 将当前用户偏好转为json并写入本地文件 */
    suspend fun exportSettings(ctx: Context, uri: Uri) = withContext(Dispatchers.IO) {
        return@withContext kotlin.runCatching {
            val map = dataStore.data.map { preference ->
                val map = mutableMapOf<String, Any>()
                // 导出所有选项的值，如果没有修改过，就返回当前的默认值
                Pref.allItems.forEach { item -> map[item.key.name] = preference[item.key] ?: item.default }
                return@map map
            }.first()
            val json = Utils.Pref.serializeFromMapToJson(map)
            Utils.Files.writeToUri(ctx, uri, json).exceptionOrNull()?.let { throw it }
        }
    }

    suspend fun onChangeProotBoolOptions(option: String, checked: Boolean) = withContext(IO) {
        val newValue = if (checked) prootState.value.boolOptions + option
        else prootState.value.boolOptions - option
        editDateStore(proot_bool_options.key, newValue)
    }

    fun onChangeProotStartupCmd(cmdRaw: String) {
        //换行 -> 空格， 去掉结尾 &, 去掉首尾空格
        editDateStoreAsync(proot_startup_cmd.key, cmdRaw.replace("\n", " ").trim().trimEnd('&').trim())
    }

    private val resolutionRegex = Regex("^(\\d+)(\\D+)(\\d+)$")
    private val resolutionRateLimiter = RateLimiter()

    /** 格式化分辨率。如果格式不对返回null */
    fun formatResolution(text: String): String? = resolutionRegex.matchEntire(text.trim())?.let { matchResult ->
        val (_, w, _, h) = matchResult.groupValues
        if (w.isNotEmpty() && h.isNotEmpty()) "${w}x${h}"
        else null
    }

    /**
     * 分辨率TextField内容变更时的回调。
     * @param forceFormat 如果为 true, 则当传入text不符合格式规范时，将其改为一个符合规范的值并保存
     */
    fun onChangeResolutionText(text: String, forceFormat: Boolean) {
        Log.d(TAG, "onChangeResolutionText: 分辨率更改")
        resolutionText = text

        var formatted = formatResolution(text)
        if (forceFormat && formatted == null) {
            formatted = Pref.general_resolution.default
        }
        //如果符合格式，保存到本地
        if (formatted != null) {
            resolutionText = formatted //这个独立于flow之外所以要手动赋值
            Log.d(TAG, "onChangeResolutionText: 分辨率更改 - 格式正确，保存到本地")
            editDateStoreAsync(general_resolution.key, formatted)
            MainEmuActivity.instance.getPref().displayResolutionCustom.put(formatted)
        }
    }

    /**
     * 添加或删除外部共享目录
     */
    suspend fun onChangeShareExtPath(oldPath: String, newPath: String, action: FuncOnChangeAction) = withContext(Dispatchers.IO) {
        val newList = general_shared_ext_path.get().run {
            when (action) {
                FuncOnChangeAction.EDIT -> minus(oldPath).plus(newPath)
                FuncOnChangeAction.ADD -> plus(newPath)
                FuncOnChangeAction.DEL -> minus(newPath)
            }
        }
        editDateStore(general_shared_ext_path.key, newList)
    }

    /** 获取当前rootfs列表，不包含[rootfsCurrDir] */
    fun getRootfsList(): List<String> = rootfsAllDir.list()?.toMutableList()?.minus(rootfsCurrDir.name) ?: listOf()

    /**
     * 修改某rootfs文件夹名，或删除
     * 确保：重命名时，新名称不为 [Consts.rootfsCurrDir] 或其他已有名称。 删除时：[rootfsCurrDir] 链接不指向该rootfs
     * @throws Exception 失败时抛出异常
     */
    @Throws(Exception::class)
    suspend fun onChangeRootfsName(oldName: String, newName: String, action: FuncOnChangeAction) = withContext(IO) {
        when (action) {
            FuncOnChangeAction.EDIT -> {
                if (newName == rootfsCurrDir.name || File(rootfsAllDir, newName).exists())
                    throw RuntimeException("该文件已存在，无法重命名")
                File(rootfsAllDir, oldName).renameTo(File(rootfsAllDir, newName))
                // rootfs重命名后，登陆用户map中的rootfs键也要重命名
                onChangeRootfsLoginUser(newName, ProotRootfs.getPreferredUser(newName).name)
            }

            FuncOnChangeAction.ADD -> Unit
            FuncOnChangeAction.DEL -> {
                // 检查是否为当前正在运行的rootfs（current符号链接指向的rootfs）
                if (rootfsCurrDir.exists() && newName == rootfsCurrDir.canonicalFile.name)
                    throw RuntimeException("该Rootfs当前正在运行，无法删除")
                //不知为绑定的那些（dev, proc）文件夹用java方法无法删除。用rm -r 倒是可以
                val output = Utils.readLinesProcessOutput(ProcessBuilder(listOf("sh","-c", "rm -r ${File(rootfsAllDir, oldName).absolutePath}"))
                    .redirectErrorStream(true).start())
                if (output.isNotBlank()) throw RuntimeException(output)
                // rootfs删除后，登陆用户map中的rootfs键也要删除 随便给一个不存在userName 会被删掉
                onChangeRootfsLoginUser(newName, "stub")
            }
        }
    }

    /** 使用[ProotRootfs.getUserInfos]从本地读取rootfs全部可用的用户列表. rootfs 不包含 [rootfsCurrDir] */
    private fun getRootfsUsersOptions(): Map<String, List<ProotRootfs.UserInfo>> =
        getRootfsList().associateWith { rootfs -> ProotRootfs.getUserInfos(File(rootfsAllDir, rootfs)) }

    suspend fun onChangeRootfsSelect(rootfsName: String) {
        MainEmuActivity.instance.terminalViewModel.stopTerminal()
        Utils.Rootfs.makeCurrent(File(rootfsAllDir, rootfsName))
        MainEmuActivity.instance.finish()
    }

    /** 改变某rootfs的默认登陆用户。 该函数内部会调用[getRootfsUsersOptions]自动将 [rootfsUsersOptions] 更新到最新 */
    suspend fun onChangeRootfsLoginUser(rootfsName: String, userName: String) {
        rootfsUsersOptions.value = getRootfsUsersOptions()
        val unfilteredMap = generalState.value.localRootfsLoginUsersMap.plus(rootfsName to userName)
        val newMap = mutableMapOf<String, String>()
        //保证newMap的keys包含全部rootfs，且每个rootfs都对应一个有效值
        rootfsUsersOptions.value.forEach { (k, v) ->
            newMap[k] = ProotRootfs.getPreferredUser(unfilteredMap[k], v).name
        }
        editDateStore(rootfs_login_user_json.key, Json.encodeToString(newMap))
    }

    fun onChangeRootfsLang(lang: String) = editDateStoreAsync(general_rootfs_lang.key, lang)

    /** 修改某rootfs的别名 */
    fun onChangeRootfsAlias(rootfsName: String, newAlias: String) {
        Utils.Rootfs.setAlias(File(rootfsAllDir, rootfsName), newAlias)
        // 更新内存中的别名映射
        rootfsAliasMap.value = rootfsAliasMap.value.toMutableMap().apply { this[rootfsName] = newAlias }
    }

    // InputControls设置相关
    fun onChangeInputControlsEnabled(enabled: Boolean) = editDateStoreAsync(inputcontrols_enabled.key, enabled)
    fun onChangeInputControlsProfileId(profileId: Int) = editDateStoreAsync(inputcontrols_profile_id.key, profileId)
    fun onChangeInputControlsOpacity(opacity: Float) = editDateStoreAsync(inputcontrols_opacity.key, opacity)
    fun onChangeInputControlsHaptics(haptics: Boolean) = editDateStoreAsync(inputcontrols_haptics.key, haptics)
    
    // Theme设置相关
    fun onChangeThemeMode(mode: Int) = editDateStoreAsync(general_theme_mode.key, mode)

    // X11设置相关 - 直接写入SharedPreferences确保立即生效
    fun onChangeX11TouchMode(mode: Int) {
        // 直接写入SharedPreferences，确保立即生效
        sharedPrefs?.edit()?.putString("touchMode", (mode + 1).toString())?.apply()
        // 同时保存到DataStore
        editDateStoreAsync(x11_touch_mode.key, mode)
    }
    fun onChangeX11ScreenOrientation(orientation: Int) {
        // 直接写入SharedPreferences，确保立即生效
        val orientationMap = mapOf(10 to "auto", 11 to "landscape", 12 to "portrait", 13 to "reverseLandscape", 14 to "reversePortrait")
        sharedPrefs?.edit()?.putString("forceOrientation", orientationMap[orientation] ?: "auto")?.apply()
        // 同时保存到DataStore
        editDateStoreAsync(x11_screen_orientation.key, orientation)
    }
    fun onChangeX11DisplayScale(scale: Int) {
        // 直接写入SharedPreferences，确保立即生效
        sharedPrefs?.edit()?.putInt("displayScale", scale)?.apply()
        // 同时保存到DataStore
        editDateStoreAsync(x11_display_scale.key, scale)
    }
    fun onChangeX11KeepScreenOn(enabled: Boolean) {
        // 直接写入SharedPreferences，确保立即生效
        sharedPrefs?.edit()?.putBoolean("keepScreenOn", enabled)?.apply()
        // 同时保存到DataStore
        editDateStoreAsync(x11_keep_screen_on.key, enabled)
    }

    /**
     * 获取当前选择的登录用户名
     * 从rootfs_login_user_json中读取当前rootfs对应的用户名
     */
    suspend fun getCurrentLoginUser(): String {
        try {
            val currentRootfsName = Consts.rootfsCurrDir.canonicalFile.name
            // 直接从DataStore读取最新数据，而不是使用可能未更新的缓存状态
            val jsonString = dataStore.data.first()[rootfs_login_user_json.key] ?: "{}"
            val loginUsersMap: Map<String, String> = Json.decodeFromString(jsonString)
            return loginUsersMap[currentRootfsName] ?: "root"
        } catch (e: Exception) {
            Log.e(TAG, "getCurrentLoginUser failed: ${e.message}")
            return "root"
        }
    }

}

data class PrefProot(
    /** 只会出现一次且没有附加参数的选项。有全名就尽量使用全名 */
    val boolOptions: Set<String>,
    val startupCmd: String,
)

private val PrefProot_DEFAULT = PrefProot(
    proot_bool_options.default,
    proot_startup_cmd.default,
)

data class PrefGeneral(
    val resolution: String,
    val sharedExtPath: Set<String>,
    val rootfsLang: String,
    /** 当前全部rootfs对应的登陆用户。 key为rootfs名，value为用户名  */
    val localRootfsLoginUsersMap: Map<String, String>,
)

private val PrefGeneral_DEFAULT = PrefGeneral(
    general_resolution.default,
    general_shared_ext_path.default,
    general_rootfs_lang.default,
    mapOf(),
)

data class PrefInputControls(
    val enabled: Boolean,
    val profileId: Int,
    val opacity: Float,
    val haptics: Boolean,
)

private val PrefInputControls_DEFAULT = PrefInputControls(
    inputcontrols_enabled.default,
    inputcontrols_profile_id.default,
    inputcontrols_opacity.default,
    inputcontrols_haptics.default,
)

data class PrefX11(
    val touchMode: Int,
    val screenOrientation: Int,
    val displayScale: Int,
    val keepScreenOn: Boolean,
    val fullscreen: Boolean,
    val hideCutout: Boolean,
    val pipMode: Boolean,
    val resolution: String,
)

private val PrefX11_DEFAULT = PrefX11(
    x11_touch_mode.default,
    x11_screen_orientation.default,
    x11_display_scale.default,
    x11_keep_screen_on.default,
    x11_fullscreen.default,
    x11_hide_cutout.default,
    x11_pip_mode.default,
    x11_resolution.default,
)

/** 顶部操作按钮类型 */
sealed interface SettingAction {
    data object RESET : SettingAction
    data object IMPORT : SettingAction
    data object EXPORT : SettingAction
}
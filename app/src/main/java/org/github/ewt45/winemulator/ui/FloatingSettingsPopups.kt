package org.github.ewt45.winemulator.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.preference.PreferenceManager
import kotlinx.coroutines.launch
import org.github.ewt45.winemulator.Consts
import org.github.ewt45.winemulator.FuncOnChangeAction
import com.termux.x11.controller.inputcontrols.ControlsProfile
import com.termux.x11.controller.inputcontrols.InputControlsManager
import com.termux.x11.controller.ControlsEditorActivity
import org.github.ewt45.winemulator.ui.components.*
import org.github.ewt45.winemulator.ui.setting.*
import org.github.ewt45.winemulator.viewmodel.SettingViewModel

/**
 * 悬浮设置弹窗类型枚举
 */
enum class FloatingPopupType {
    NONE,
    GENERAL_SETTINGS,
    VIRTUAL_KEYS_SETTINGS,
    X11_SETTINGS
}

/**
 * 悬浮弹窗状态管理
 */
class FloatingPopupState {
    var currentPopup by mutableStateOf(FloatingPopupType.NONE)
        private set

    fun showPopup(type: FloatingPopupType) {
        currentPopup = type
    }

    fun dismissPopup() {
        currentPopup = FloatingPopupType.NONE
    }
}

/**
 * 可组合的悬浮弹窗容器
 * 根据状态显示对应的设置弹窗
 */
@Composable
fun FloatingSettingsPopups(
    popupState: FloatingPopupState,
    settingVm: SettingViewModel,
    onVirtualKeysSettingsChanged: () -> Unit = {},  // 新增：虚拟按键设置变更回调，用于即时刷新
    modifier: Modifier = Modifier
) {
    when (popupState.currentPopup) {
        FloatingPopupType.NONE -> { /* 不显示任何弹窗 */ }
        FloatingPopupType.GENERAL_SETTINGS -> {
            GeneralSettingsPopup(
                settingVm = settingVm,
                onDismiss = { popupState.dismissPopup() }
            )
        }
        FloatingPopupType.VIRTUAL_KEYS_SETTINGS -> {
            VirtualKeysSettingsPopup(
                onDismiss = { popupState.dismissPopup() },
                onSettingsChanged = onVirtualKeysSettingsChanged  // 传递回调
            )
        }
        FloatingPopupType.X11_SETTINGS -> {
            X11SettingsPopup(
                settingVm = settingVm,
                onDismiss = { popupState.dismissPopup() }
            )
        }
    }
}

/**
 * 一般设置悬浮弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralSettingsPopup(
    settingVm: SettingViewModel,
    onDismiss: () -> Unit
) {
    val generalState by settingVm.generalState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.8f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column {
                // 标题栏
                TopAppBar(
                    title = { Text("一般设置") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            scope.launch {
                                // 刷新配置
                            }
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                )

                // 设置内容
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // 容器语言设置
                    GeneralRootfsLang(
                        currLang = generalState.rootfsLang,
                        langOptions = listOf("en_US.utf8", "zh_CN.utf8"),
                        onLangChange = settingVm::onChangeRootfsLang
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 共享文件夹设置
                    GeneralShareDir(
                        bindSet = generalState.sharedExtPath,
                        onPathChange = settingVm::onChangeShareExtPath
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Rootfs切换
                    GeneralRootfsSelect(
                        currRootfs = Consts.rootfsCurrDir.canonicalFile.name,
                        rootfsToLoginUserMap = generalState.localRootfsLoginUsersMap,
                        loginUsersOptions = settingVm.rootfsUsersOptions.value.mapValues { it.value.map { info -> info.name } },
                        rootfsAliasMap = settingVm.rootfsAliasMap.value,
                        onRootfsNameChange = settingVm::onChangeRootfsName,
                        onRootfsSelectChange = settingVm::onChangeRootfsSelect,
                        onUserSelectChange = { rootfs, user -> scope.launch { settingVm.onChangeRootfsLoginUser(rootfs, user) } },
                        onAliasChange = settingVm::onChangeRootfsAlias,
                        navigateToNewRootfs = { /* 导航到新rootfs */ }
                    )

                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}

/**
 * 虚拟按键设置悬浮弹窗
 * @param onSettingsChanged 设置变更回调，用于通知外部立即刷新虚拟按键视图
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VirtualKeysSettingsPopup(
    onDismiss: () -> Unit,
    onSettingsChanged: () -> Unit = {}  // 新增回调参数
) {
    val context = LocalContext.current
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    val dialogState = rememberConfirmDialogState()

    var profiles by remember { mutableStateOf<List<ControlsProfile>>(emptyList()) }
    var selectedProfile by remember { mutableStateOf<ControlsProfile?>(null) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(false) }
    var isControlsEnabled by remember { mutableStateOf(false) }

    val manager = remember { InputControlsManager(context) }

    // 用于监听 ControlsEditorActivity 返回结果的 ActivityResultLauncher
    val controlsEditorLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // 当从 ControlsEditorActivity 返回时，重新加载配置并刷新视图
        manager.forceReloadProfiles()
        profiles = manager.getProfiles()
        val savedId = prefs.getInt(InputControlsFragment.SELECTED_PROFILE_ID, 0)
        selectedProfile = if (savedId != 0) manager.getProfile(savedId) else null
        onSettingsChanged()
    }

    // 加载配置
    LaunchedEffect(Unit) {
        manager.loadProfiles(false)
        profiles = manager.getProfiles()
        val savedId = prefs.getInt(InputControlsFragment.SELECTED_PROFILE_ID, 0)
        selectedProfile = if (savedId != 0) manager.getProfile(savedId) else null
        if (selectedProfile != null && savedId != selectedProfile!!.id) {
            prefs.edit().putInt(InputControlsFragment.SELECTED_PROFILE_ID, selectedProfile!!.id).apply()
        }
        showControls = prefs.getBoolean("show_touchscreen_controls", false)
        isControlsEnabled = savedId != 0 && selectedProfile != null
    }

    ConfirmDialog(dialogState)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.8f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column {
                // 标题栏
                TopAppBar(
                    title = { Text("虚拟按键设置") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    }
                )

                // 设置内容
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // 启用/禁用开关
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = "启用虚拟按键",
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = isControlsEnabled,
                            onCheckedChange = { enabled ->
                                if (!enabled) {
                                    selectedProfile = null
                                    showControls = false
                                    isControlsEnabled = false
                                    prefs.edit().remove(InputControlsFragment.SELECTED_PROFILE_ID).apply()
                                    prefs.edit().putBoolean("show_touchscreen_controls", false).apply()
                                } else if (profiles.isEmpty()) {
                                    val newProfile = manager.createProfile("默认配置")
                                    profiles = manager.getProfiles()
                                    selectedProfile = newProfile
                                    showControls = true
                                    isControlsEnabled = true
                                    prefs.edit().putInt(InputControlsFragment.SELECTED_PROFILE_ID, newProfile.id).apply()
                                    prefs.edit().putBoolean("show_touchscreen_controls", true).apply()
                                } else {
                                    val savedId = prefs.getInt(InputControlsFragment.SELECTED_PROFILE_ID, 0)
                                    val restoredProfile = if (savedId != 0) manager.getProfile(savedId) else null
                                    if (restoredProfile != null) {
                                        selectedProfile = restoredProfile
                                    } else {
                                        selectedProfile = profiles.first()
                                        prefs.edit().putInt(InputControlsFragment.SELECTED_PROFILE_ID, selectedProfile!!.id).apply()
                                    }
                                    showControls = true
                                    isControlsEnabled = true
                                    prefs.edit().putBoolean("show_touchscreen_controls", true).apply()
                                }
                                onSettingsChanged()  // 通知外部刷新
                            }
                        )
                    }

                    // 显示/隐藏虚拟按键开关
                    if (isControlsEnabled) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(
                                text = "显示虚拟按键",
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = showControls,
                                onCheckedChange = { show ->
                                    showControls = show
                                    prefs.edit().putBoolean("show_touchscreen_controls", show).apply()
                                    onSettingsChanged()  // 通知外部刷新
                                }
                            )
                        }
                    }

                    if (isControlsEnabled) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        // 配置选择器
                        Text(
                            text = "当前配置",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )

                        var expanded by remember { mutableStateOf(false) }

                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = !expanded }
                        ) {
                            OutlinedTextField(
                                value = selectedProfile?.name ?: "未选择",
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                            )

                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                profiles.forEach { profile ->
                                    DropdownMenuItem(
                                        text = { Text(profile.getName()) },
                                        onClick = {
                                            selectedProfile = profile
                                            prefs.edit().putInt(InputControlsFragment.SELECTED_PROFILE_ID, profile.id).apply()
                                            expanded = false
                                            onSettingsChanged()  // 通知外部刷新
                                        }
                                    )
                                }
                            }
                        }

                        // 配置操作按钮
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showProfileDialog = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("新建")
                            }

                            if (profiles.size > 1) {
                                OutlinedButton(
                                    onClick = {
                                        selectedProfile?.let { profile ->
                                            manager.removeProfile(profile)
                                            profiles = manager.getProfiles()
                                            selectedProfile = profiles.firstOrNull()
                                            if (selectedProfile != null) {
                                                prefs.edit().putInt(InputControlsFragment.SELECTED_PROFILE_ID, selectedProfile!!.id).apply()
                                            } else {
                                                prefs.edit().remove(InputControlsFragment.SELECTED_PROFILE_ID).apply()
                                            }
                                            onSettingsChanged()  // 通知外部刷新
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("删除")
                                }
                            }
                        }

                        // 编辑布局按钮
                        Button(
                            onClick = {
                                selectedProfile?.let { profile ->
                                    val intent = Intent(context, ControlsEditorActivity::class.java)
                                    intent.putExtra(ControlsEditorActivity.EXTRA_PROFILE_ID, profile.getId())
                                    controlsEditorLauncher.launch(intent)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("编辑虚拟按键布局")
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }

    // 新建配置对话框
    if (showProfileDialog) {
        var newProfileName by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showProfileDialog = false },
            title = { Text("新建配置") },
            text = {
                OutlinedTextField(
                    value = newProfileName,
                    onValueChange = { newProfileName = it },
                    label = { Text("配置名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newProfileName.isNotBlank()) {
                            val newProfile = manager.createProfile(newProfileName)
                            profiles = manager.getProfiles()
                            selectedProfile = newProfile
                            // 保存新建的配置ID并启用
                            prefs.edit().putInt(InputControlsFragment.SELECTED_PROFILE_ID, newProfile.id).apply()
                            prefs.edit().putBoolean("show_touchscreen_controls", true).apply()
                            isControlsEnabled = true
                            showControls = true
                            showProfileDialog = false
                            // 立即通知外部刷新，确保新建的配置立即生效
                            onSettingsChanged()
                        }
                    }
                ) {
                    Text("创建")
                }
            },
            dismissButton = {
                TextButton(onClick = { showProfileDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * X11设置悬浮弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun X11SettingsPopup(
    settingVm: SettingViewModel,
    onDismiss: () -> Unit
) {
    val x11State by settingVm.x11State.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.8f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column {
                // 标题栏
                TopAppBar(
                    title = { Text("X11显示设置") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    }
                )

                // 设置内容
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // 分辨率设置
                    X11Resolution(
                        text = settingVm.resolutionText,
                        onDone = settingVm::onChangeResolutionText
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 触摸方式设置
                    X11TouchMode(
                        currentMode = x11State.touchMode,
                        onModeChange = settingVm::onChangeX11TouchMode
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 屏幕方向设置
                    X11ScreenOrientation(
                        currentOrientation = x11State.screenOrientation,
                        onOrientationChange = settingVm::onChangeX11ScreenOrientation
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 显示缩放
                    X11DisplayScale(
                        scale = x11State.displayScale,
                        onScaleChange = settingVm::onChangeX11DisplayScale
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 保持屏幕常亮
                    X11KeepScreenOn(
                        enabled = x11State.keepScreenOn,
                        onEnabledChange = settingVm::onChangeX11KeepScreenOn
                    )

                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}

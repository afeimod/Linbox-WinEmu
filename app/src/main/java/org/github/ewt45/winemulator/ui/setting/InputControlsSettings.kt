package org.github.ewt45.winemulator.ui.setting

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import com.termux.x11.controller.inputcontrols.ControlsProfile
import com.termux.x11.controller.inputcontrols.InputControlsManager
import com.termux.x11.controller.ControlsEditorActivity
import com.termux.x11.controller.inputcontrols.ControlElement
import org.github.ewt45.winemulator.ui.components.ConfirmDialog
import org.github.ewt45.winemulator.ui.components.rememberConfirmDialogState
import org.github.ewt45.winemulator.ui.InputControlsFragment

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputControlsSettings(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    val dialogState = rememberConfirmDialogState()

    var profiles by remember { mutableStateOf<List<ControlsProfile>>(emptyList()) }
    var selectedProfile by remember { mutableStateOf<ControlsProfile?>(null) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(false) }
    // 添加启用虚拟按键的状态变量，确保Switch状态正确更新
    var isControlsEnabled by remember { mutableStateOf(false) }

    val manager = remember { InputControlsManager(context) }

    // 加载配置列表，并恢复上次选中的配置
    LaunchedEffect(Unit) {
        manager.loadProfiles(false)
        profiles = manager.getProfiles()
        val savedId = prefs.getInt(InputControlsFragment.SELECTED_PROFILE_ID, 0)
        // 只有当savedId不为0时才尝试恢复配置，否则保持null状态
        selectedProfile = if (savedId != 0) manager.getProfile(savedId) else null
        // 确保 SharedPreferences 中保存了正确的 ID
        if (selectedProfile != null && savedId != selectedProfile!!.id) {
            prefs.edit().putInt(InputControlsFragment.SELECTED_PROFILE_ID, selectedProfile!!.id).apply()
        }
        // 恢复虚拟按键显示状态
        showControls = prefs.getBoolean("show_touchscreen_controls", false)
        // 恢复启用状态 - 只有当有保存的配置时才启用
        isControlsEnabled = savedId != 0 && selectedProfile != null
    }

    // 监听SharedPreferences的变化
    LaunchedEffect(prefs) {
        prefs.registerOnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "show_touchscreen_controls" -> {
                    showControls = prefs.getBoolean("show_touchscreen_controls", false)
                }
                InputControlsFragment.SELECTED_PROFILE_ID -> {
                    val savedId = prefs.getInt(InputControlsFragment.SELECTED_PROFILE_ID, 0)
                    isControlsEnabled = savedId != 0
                    selectedProfile = if (savedId != 0) manager.getProfile(savedId) else null
                }
            }
        }
    }

    // 监听profiles列表的变化，确保配置被删除时能更新UI
    LaunchedEffect(profiles) {
        val savedId = prefs.getInt(InputControlsFragment.SELECTED_PROFILE_ID, 0)
        if (savedId != 0 && selectedProfile == null) {
            // 配置被删除了，禁用虚拟按键
            isControlsEnabled = false
        }
    }

    ConfirmDialog(dialogState)

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "虚拟按键设置",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(vertical = 8.dp)
        )

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
                        // 关闭虚拟按键时，同时隐藏虚拟按键
                        selectedProfile = null
                        showControls = false
                        isControlsEnabled = false
                        prefs.edit().remove(InputControlsFragment.SELECTED_PROFILE_ID).apply()
                        prefs.edit().putBoolean("show_touchscreen_controls", false).apply()
                    } else if (profiles.isEmpty()) {
                        // 创建默认配置
                        val newProfile = manager.createProfile("默认配置")
                        profiles = manager.getProfiles()
                        selectedProfile = newProfile
                        showControls = true
                        isControlsEnabled = true
                        prefs.edit().putInt(InputControlsFragment.SELECTED_PROFILE_ID, newProfile.id).apply()
                        prefs.edit().putBoolean("show_touchscreen_controls", true).apply()
                    } else {
                        // 尝试恢复之前保存的配置，而不是强制选择第一个
                        val savedId = prefs.getInt(InputControlsFragment.SELECTED_PROFILE_ID, 0)
                        val restoredProfile = if (savedId != 0) manager.getProfile(savedId) else null
                        if (restoredProfile != null) {
                            selectedProfile = restoredProfile
                        } else {
                            // 只有在无法恢复时才选择第一个配置
                            selectedProfile = profiles.first()
                            prefs.edit().putInt(InputControlsFragment.SELECTED_PROFILE_ID, selectedProfile!!.id).apply()
                        }
                        // 开启虚拟按键时，同时显示虚拟按键
                        showControls = true
                        isControlsEnabled = true
                        prefs.edit().putBoolean("show_touchscreen_controls", true).apply()
                    }
                }
            )
        }

        // 显示/隐藏虚拟按键开关（仅在启用虚拟按键时显示）
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
                    value = selectedProfile!!.getName(),
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
                                prefs.edit().putInt(InputControlsFragment.SELECTED_PROFILE_ID, profile.getId()).apply()
                                expanded = false
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
                                    prefs.edit().putInt(InputControlsFragment.SELECTED_PROFILE_ID, selectedProfile!!.getId()).apply()
                                } else {
                                    prefs.edit().remove(InputControlsFragment.SELECTED_PROFILE_ID).apply()
                                }
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

            OutlinedButton(
                onClick = {
                    selectedProfile?.let { profile ->
                        selectedProfile = manager.duplicateProfile(profile)
                        profiles = manager.getProfiles()
                        prefs.edit().putInt(InputControlsFragment.SELECTED_PROFILE_ID, selectedProfile!!.getId()).apply()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Star, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("复制当前配置")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // 编辑布局按钮
            Button(
                onClick = {
                    selectedProfile?.let { profile ->
                        val intent = Intent(context, ControlsEditorActivity::class.java)
                        intent.putExtra(ControlsEditorActivity.EXTRA_PROFILE_ID, profile.id)
                        context.startActivity(intent)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Edit, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("编辑虚拟按键布局")
            }

            // 快捷预设（演示用）
            Text(
                text = "快捷预设",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            LazyColumn(
                modifier = Modifier.height(200.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val presetNames = listOf(
                    "FPS游戏", "RPG游戏", "RTS游戏", "赛车游戏", "格斗游戏"
                )

                items(presetNames) { presetName ->
                    ListItem(
                        headlineContent = { Text(presetName) },
                        leadingContent = {
                            Icon(Icons.Default.Star, contentDescription = null)
                        },
                        modifier = Modifier.clickable {
                            dialogState.showConfirm("加载预设 '$presetName'？这将替换当前配置的按键布局。") {
                                // 应用预设的逻辑（需根据项目实现）
                            }
                        }
                    )
                }
            }

            // 导出/导入
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        selectedProfile?.let { profile ->
                            val file = manager.exportProfile(profile)
                            if (file != null) {
                                dialogState.showConfirm("配置已导出到: ${file.absolutePath}")
                            } else {
                                dialogState.showConfirm("导出失败")
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("导出")
                }

                OutlinedButton(
                    onClick = {
                        // 导入逻辑需使用文件选择器，此处仅作演示
                        dialogState.showConfirm("请选择要导入的.icp文件")
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Home, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("导入")
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
                            prefs.edit().putInt(InputControlsFragment.SELECTED_PROFILE_ID, newProfile.id).apply()
                            showProfileDialog = false
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
 * Dialog for editing control elements
 */
@Composable
fun ControlsEditorDialog(
    profile: ControlsProfile,
    dialogState: org.github.ewt45.winemulator.ui.components.ConfirmDialogState,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    var selectedElement by remember { mutableStateOf<ControlElement?>(null) }
    var showElementSettings by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑虚拟按键 - ${profile.name}") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "提示：点击下方按钮添加新的控件元素，长按拖动可以移动位置。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Element list
                LazyColumn(
                    modifier = Modifier.height(200.dp)
                ) {
                    items(profile.getElements()) { element ->
                        ListItem(
                            headlineContent = {
                                Text("${element.type.name} - ${element.getBindingAt(0).name}")
                            },
                            supportingContent = {
                                Text("位置: (${element.getX()}, ${element.getY()})")
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = when (element.type) {
                                        ControlElement.Type.BUTTON -> Icons.Default.Star
                                        ControlElement.Type.D_PAD -> Icons.AutoMirrored.Filled.ArrowForward
                                        ControlElement.Type.STICK -> Icons.Default.Info
                                        ControlElement.Type.RANGE_BUTTON -> Icons.AutoMirrored.Filled.List
                                        ControlElement.Type.TRACKPAD -> Icons.Default.Menu
                                        ControlElement.Type.COMBINE_BUTTON -> Icons.Default.Add
                                        ControlElement.Type.CHEAT_CODE_TEXT -> Icons.Default.Edit
                                    },
                                    contentDescription = null
                                )
                            },
                            trailingContent = {
                                IconButton(onClick = {
                                    profile.removeElement(element)
                                    profile.save()
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除")
                                }
                            },
                            modifier = Modifier.clickable {
                                selectedElement = element
                                showElementSettings = true
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Add element buttons
                Text(
                    text = "添加控件",
                    style = MaterialTheme.typography.titleSmall
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = false,
                        onClick = {
                            // 此处应调用实际的添加逻辑，但需根据项目实现
                            dialogState.showConfirm("请在游戏界面中长按屏幕来添加新的虚拟按键")
                        },
                        label = { Text("按钮") },
                        leadingIcon = { Icon(Icons.Default.Star, contentDescription = null) }
                    )

                    FilterChip(
                        selected = false,
                        onClick = {
                            // 添加方向键
                        },
                        label = { Text("方向键") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) }
                    )

                    FilterChip(
                        selected = false,
                        onClick = {
                            // 添加摇杆
                        },
                        label = { Text("摇杆") },
                        leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) {
                Text("完成")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )

    // Element settings dialog
    if (showElementSettings && selectedElement != null) {
        ElementSettingsDialog(
            element = selectedElement!!,
            onDismiss = { showElementSettings = false },
            onSave = {
                profile.save()
                showElementSettings = false
            }
        )
    }
}

/**
 * Dialog for editing a single control element
 */
@Composable
fun ElementSettingsDialog(
    element: ControlElement,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    var selectedType by remember { mutableStateOf(element.type) }
    var selectedShape by remember { mutableStateOf(element.shape) }
    var scale by remember { mutableStateOf(element.scale) }
    var text by remember { mutableStateOf(element.text) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("控件设置") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Type selector
                Text("类型", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ControlElement.Type.entries.forEach { type ->
                        FilterChip(
                            selected = element.type == type,
                            onClick = {
                                element.type = type
                                selectedType = type
                            },
                            label = { Text(type.name.replace("_", "-")) }
                        )
                    }
                }

                // Shape selector (for buttons)
                if (element.type == ControlElement.Type.BUTTON) {
                    Text("形状", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ControlElement.Shape.entries.forEach { shape ->
                            FilterChip(
                                selected = element.shape == shape,
                                onClick = {
                                    element.shape = shape
                                    selectedShape = shape
                                },
                                label = { Text(shape.name.replace("_", " ")) }
                            )
                        }
                    }
                }

                // Scale slider
                Text("大小: ${(scale * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = scale,
                    onValueChange = {
                        scale = it
                        element.scale = it
                    },
                    valueRange = 0.5f..2f
                )

                // Custom text
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        element.text = it
                    },
                    label = { Text("自定义文字（可选）") },
                    singleLine = true
                )

                // Binding section
                Text("按键绑定", style = MaterialTheme.typography.labelMedium)
                Column {
                    for (i in 0 until element.getBindingCount()) {
                        val binding = element.getBindingAt(i)
                        ListItem(
                            headlineContent = { Text("绑定 ${i + 1}") },
                            supportingContent = { Text(binding.name) },
                            modifier = Modifier.clickable {
                                // 弹出绑定选择器，因项目未提供，仅作演示
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
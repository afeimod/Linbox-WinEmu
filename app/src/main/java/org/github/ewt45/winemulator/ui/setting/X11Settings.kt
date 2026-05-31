package org.github.ewt45.winemulator.ui.setting

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.github.ewt45.winemulator.Consts
import org.github.ewt45.winemulator.ui.components.ComposeSpinner
import org.github.ewt45.winemulator.ui.components.TitleAndContent
import org.github.ewt45.winemulator.ui.components.CollapsePanel
import org.github.ewt45.winemulator.ui.components.TextFieldOption
import org.github.ewt45.winemulator.viewmodel.SettingViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * X11分辨率设置
 * 支持预设分辨率和自定义分辨率输入
 * 使用general_resolution保持与原有逻辑一致
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun X11Resolution(
    text: String,
    onDone: (String, Boolean) -> Unit,
) {
    val options = listOf("800x600", "1024x768", "1280x720", "1600x900", "1920x1080")
    val textInOptions = options.contains(text)
    // isCustom初始根据分辨率是否在给定列表中设定。后续可以手动修改用于表示用户点击了该选项
    var isCustom by remember { mutableStateOf(!textInOptions) }
    val realText = if (isCustom) "自定义" else text
    var expanded by remember { mutableStateOf(false) }

    TitleAndContent(
        title = "分辨率",
        subTitle = "格式：宽x高，x为字母。编辑自定义分辨率后点击末尾对号图标或输入法回车保存。"
    ) {
        ExposedDropdownMenuBox(
            modifier = Modifier.fillMaxWidth(),
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            TextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                value = realText,
                readOnly = true,
                onValueChange = {},
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                colors = ExposedDropdownMenuDefaults.textFieldColors(),
            )

            val contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("自定义", style = MaterialTheme.typography.bodyLarge) },
                    onClick = {
                        expanded = false
                        isCustom = true
                    },
                    contentPadding = contentPadding,
                )
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option, style = MaterialTheme.typography.bodyLarge) },
                        onClick = {
                            onDone(option, false)
                            expanded = false
                            isCustom = false
                        },
                        contentPadding = contentPadding,
                    )
                }
            }
        }

        // 自定义时手动输入的文本框
        AnimatedVisibility(visible = isCustom) {
            TextFieldOption(text = text, onDone = { onDone(it, true) })
        }
    }
}

/**
 * X11设置面板
 * 包含分辨率、触摸方式、屏幕方向、显示缩放、保持屏幕常亮等选项
 */
@Composable
fun X11Settings(
    settingVm: SettingViewModel,
) {
    val x11State by settingVm.x11State.collectAsStateWithLifecycle()
    val context = LocalContext.current

    CollapsePanel("X11显示设置", vPadding = 32.dp) {
        // 分辨率设置 - 使用原有general_resolution
        X11Resolution(
            text = settingVm.resolutionText,
            onDone = settingVm::onChangeResolutionText
        )

        // 触摸方式设置
        X11TouchMode(
            currentMode = x11State.touchMode,
            onModeChange = settingVm::onChangeX11TouchMode
        )

        // 屏幕方向设置
        X11ScreenOrientation(
            currentOrientation = x11State.screenOrientation,
            onOrientationChange = settingVm::onChangeX11ScreenOrientation
        )

        // 显示缩放
        X11DisplayScale(
            scale = x11State.displayScale,
            onScaleChange = settingVm::onChangeX11DisplayScale
        )

        // 保持屏幕常亮
        X11KeepScreenOn(
            enabled = x11State.keepScreenOn,
            onEnabledChange = settingVm::onChangeX11KeepScreenOn
        )
    }
}

/**
 * 触摸方式设置
 * 0 = 虚拟触控板(Trackpad)
 * 1 = 模拟触摸(直接点击)
 * 2 = 触摸屏模式
 */
@Composable
fun X11TouchMode(
    currentMode: Int,
    onModeChange: (Int) -> Unit,
) {
    val touchModeOptions = listOf(0, 1, 2)
    val touchModeNames = listOf("虚拟触控板", "模拟触摸", "触摸屏")
    val touchModeDescriptions = listOf(
        "单指移动光标，点击模拟鼠标",
        "单指点击移动光标，长按模拟右键",
        "直接在屏幕上触摸操作"
    )
    val currentModeName = touchModeNames.getOrElse(currentMode) { touchModeNames[0] }
    val currentDescription = touchModeDescriptions.getOrElse(currentMode) { touchModeDescriptions[0] }

    TitleAndContent(
        title = "触摸方式",
        subTitle = "选择触摸屏的操作方式。\n当前: $currentModeName\n$currentDescription"
    ) {
        ComposeSpinner(
            currKey = currentMode,
            keyList = touchModeOptions,
            nameList = touchModeNames,
            modifier = Modifier.fillMaxWidth()
        ) { _, newValue ->
            onModeChange(newValue)
        }
    }
}

/**
 * 屏幕方向设置
 * 使用与系统自带不同的值避免冲突: 10=自动, 11=横屏, 12=竖屏, 13=反向横屏, 14=反向竖屏
 */
@Composable
fun X11ScreenOrientation(
    currentOrientation: Int,
    onOrientationChange: (Int) -> Unit,
) {
    val orientationOptions = listOf(10, 11, 12, 13, 14)
    val orientationNames = listOf("跟随系统", "横屏(固定)", "竖屏(固定)", "反向横屏", "反向竖屏")

    TitleAndContent(
        title = "屏幕方向",
        subTitle = "控制X11显示的屏幕方向。注意: 此设置为X11专用，不影响系统方向设置。"
    ) {
        ComposeSpinner(
            currKey = currentOrientation,
            keyList = orientationOptions,
            nameList = orientationNames,
            modifier = Modifier.fillMaxWidth()
        ) { _, newValue ->
            onOrientationChange(newValue)
        }
    }
}

/**
 * 显示缩放设置
 */
@Composable
fun X11DisplayScale(
    scale: Int,
    onScaleChange: (Int) -> Unit,
) {
    TitleAndContent(
        title = "显示缩放",
        subTitle = "调整X11显示的缩放比例。当前: ${scale}%"
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Slider(
                value = scale.toFloat(),
                onValueChange = { onScaleChange(it.toInt()) },
                valueRange = 30f..300f,
                steps = 26, // (300-30)/10 - 1 = 26
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("30%", style = MaterialTheme.typography.bodySmall)
                Text("${scale}%", style = MaterialTheme.typography.bodyMedium)
                Text("300%", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * 保持屏幕常亮
 */
@Composable
fun X11KeepScreenOn(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    TitleAndContent(
        title = "运行时保持屏幕常亮",
        subTitle = "启用后，X11运行时防止屏幕自动熄灭。"
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("保持屏幕常亮")
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange
            )
        }
    }
}

/**
 * X11设置预览
 */
@Composable
fun X11SettingsPreview() {
    var resolution by remember { mutableStateOf("1280x720") }
    var touchMode by remember { mutableIntStateOf(0) }
    var screenOrientation by remember { mutableIntStateOf(10) }
    var displayScale by remember { mutableIntStateOf(100) }
    var keepScreenOn by remember { mutableStateOf(true) }

    CollapsePanel("X11设置预览") {
        X11Resolution(resolution) { newValue, _ -> resolution = newValue }
        X11TouchMode(touchMode, { touchMode = it })
        X11ScreenOrientation(screenOrientation, { screenOrientation = it })
        X11DisplayScale(displayScale, { displayScale = it })
        X11KeepScreenOn(keepScreenOn, { keepScreenOn = it })
    }
}

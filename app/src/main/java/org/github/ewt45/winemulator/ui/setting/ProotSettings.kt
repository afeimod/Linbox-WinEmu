package org.github.ewt45.winemulator.ui.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.github.ewt45.winemulator.ui.components.ChipOption
import org.github.ewt45.winemulator.ui.components.CollapsePanel
import org.github.ewt45.winemulator.ui.components.TextFieldOption
import org.github.ewt45.winemulator.viewmodel.SettingViewModel

/**
 * proot设置
 */
@Composable
fun ProotSettings(settingVM: SettingViewModel) {
    val proot by settingVM.prootState.collectAsState()

    CollapsePanel("PRoot参数") {
        ProotNoValueOptions(proot.boolOptions, settingVM::onChangeProotBoolOptions)
        ProotStartupCmd(proot.startupCmd, settingVM::onChangeProotStartupCmd)
    }
}


/**
 * 一些无参数的选项
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ProotNoValueOptions(
    checkedOptions: Set<String>,
    onCheck: suspend (String, Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()

    val allOptions = listOf("-L", "--link2symlink", "--kill-on-exit", "--sysvipc", "--ashmem-memfd", "-H", "-p"/*"--root-id",*/)
    FlowRow(modifier = Modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        //--root-id 不可选但是显示，提示用户去rootfs切换那里设置
        val tooltipState = rememberTooltipState()
        TooltipBox(
            positionProvider = TooltipDefaults.rememberRichTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text("用户id 请在 “Rootfs切换” 处设置。") } },
            state = tooltipState,
        ) {
            Box {
                FilterChip(false, onClick = {}, label = { Text("--root-id") }, enabled = false)
                Box(
                    Modifier
                        .clickable { scope.launch { if (!tooltipState.isVisible) tooltipState.show() else tooltipState.dismiss() } }
                        .matchParentSize()) {}
            }
        }

        for (option in allOptions) {
            ChipOption(checkedOptions.contains(option), option) { key, checked ->
                scope.launch { onCheck(key, checked) }
            }
        }
    }
}

@Composable
fun ProotStartupCmd(
    cmd: String,
    onChange: (String) -> Unit
) {
    TextFieldOption(title = "启动后执行命令", text = cmd, onDone = onChange)
}

@Preview(widthDp = 300, heightDp = 600)
@Composable
fun ProotSettingsPreview() {
    CollapsePanel("PRoot参数") {
        var proot_no_value_options by remember { mutableStateOf(setOf("-L", "--link2symlink", "--sysvipc", "--kill-on-exit" /*"--root-id",*/)) }
        var proot_startup_cmd by remember { mutableStateOf("") }
        ProotNoValueOptions(proot_no_value_options, { key, checked ->
            if (checked) proot_no_value_options += key
            else proot_no_value_options -= key
        })
//        Spacer(Modifier.height(16.dp))
        ProotStartupCmd(proot_startup_cmd) { proot_startup_cmd = it }
    }
}
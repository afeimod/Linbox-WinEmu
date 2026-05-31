package org.github.ewt45.winemulator.ui

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.github.ewt45.winemulator.Consts
import org.github.ewt45.winemulator.ui.components.ConfirmDialog
import org.github.ewt45.winemulator.ui.components.rememberConfirmDialogState
import org.github.ewt45.winemulator.ui.setting.DebugSettings
import org.github.ewt45.winemulator.ui.setting.DebugSettingsImpl
import org.github.ewt45.winemulator.ui.setting.GeneralSettings
import org.github.ewt45.winemulator.ui.setting.GeneralSettingsPreview
import org.github.ewt45.winemulator.ui.setting.GeneralThemeMode
import org.github.ewt45.winemulator.ui.setting.InputControlsSettings
import org.github.ewt45.winemulator.ui.setting.MiscSettings
import org.github.ewt45.winemulator.ui.setting.MiscSettingsPreview
import org.github.ewt45.winemulator.ui.setting.ProotSettings
import org.github.ewt45.winemulator.ui.setting.ProotSettingsPreview
import org.github.ewt45.winemulator.ui.setting.X11Settings
import org.github.ewt45.winemulator.viewmodel.PrepareViewModel
import org.github.ewt45.winemulator.viewmodel.SettingAction
import org.github.ewt45.winemulator.viewmodel.SettingViewModel
import org.github.ewt45.winemulator.viewmodel.TerminalViewModel

@Composable
fun SettingScreen(
    settingVm: SettingViewModel,
    terminalVM: TerminalViewModel,
    prepareVm: PrepareViewModel,
    navigateTo: (Destination) -> Unit
) {
    val TAG = "SettingScreen"
    val scope = rememberCoroutineScope()
    //进入设置时手动更新一些可能过期的数据，比如文件列表。
    SideEffect {
        Log.e(TAG, "SettingScreen: 如果在SettingScreen内部刷新的话会重复执行吗")
        settingVm.updateValuesWhenEnterSettings()
    }
    
    // 获取主题模式状态
    val themeState by settingVm.themeState.collectAsState()
    
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TopBarActions(modifier = Modifier.align(Alignment.End), settingVm)
        if (Consts.isDebug) {
            DebugSettings(terminalVM, navigateTo)
            HorizontalDivider()
        }
        GeneralSettings(settingVm, prepareVm, navigateTo)
        HorizontalDivider()
        // 添加主题设置选项
        GeneralThemeMode(
            themeMode = themeState,
            onThemeModeChange = settingVm::onChangeThemeMode
        )
        HorizontalDivider()
        ProotSettings(settingVm)
        HorizontalDivider()
        X11Settings(settingVm)
        HorizontalDivider()
        InputControlsSettings()
        HorizontalDivider()
        MiscSettings(navigateTo)
        Spacer(Modifier.height(16.dp))
    }
}


/**
 * 顶部操作按钮
 */
@Composable
fun TopBarActions(
    modifier: Modifier = Modifier,
    settingVM: SettingViewModel,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dialogState = rememberConfirmDialogState()
    //导出时 保存为文件
    val saveFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val th = settingVM.exportSettings(ctx, uri).exceptionOrNull()
            val resultStr = if (th != null) "导出失败。错误信息：\n\n${th.stackTraceToString()}" else "导出成功！"
            dialogState.showConfirm(resultStr)
        }

    }
    //导入时 选择文件
    val readFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val th = settingVM.importSettings(ctx, uri).exceptionOrNull()
            th?.printStackTrace()
            val resultStr = if (th != null) "导入失败。错误信息：\n\n${th.stackTraceToString()}" else "导入成功！"
            dialogState.showConfirm(resultStr)
        }
    }

    val onClick: (SettingAction) -> Unit = { action ->
        when (action) {
            SettingAction.EXPORT ->
                dialogState.showConfirm("将设置导出为Json文件。请选择文件保存位置。") { saveFileLauncher.launch("preferences.json") }

            SettingAction.IMPORT ->
                dialogState.showConfirm("导入本地Json文件更新设置。请选择文件所在位置。") { readFileLauncher.launch(arrayOf("text/*", "application/json")) }

            SettingAction.RESET ->
                dialogState.showConfirm("导入本地Json文件更新设置。请选择文件所在位置。") { settingVM.resetSettings() }
        }
    }

    ConfirmDialog(dialogState)
    TopBarActions(modifier, onClick = onClick)
}

@Composable
fun TopBarActions(
    modifier: Modifier = Modifier,
    onClick: (SettingAction) -> Unit = {},
) {
    Row(modifier = modifier) {
        TextButton(onClick = { onClick(SettingAction.EXPORT) }) { Text("导出") }
        TextButton(onClick = { onClick(SettingAction.IMPORT) }) { Text("导入") }
        TextButton(onClick = { onClick(SettingAction.RESET) }) { Text("重置") }
    }
}


@Preview(showBackground = true, widthDp = 350, heightDp = 600)
@Composable
fun SettingScreenPreview() {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TopBarActions(
            modifier = Modifier.align(Alignment.End),
            onClick = {}
        )
        if (Consts.isDebug) {
            DebugSettingsImpl()
            HorizontalDivider()
        }
        GeneralSettingsPreview()
        HorizontalDivider()
        ProotSettingsPreview()
        MiscSettingsPreview()
    }
}


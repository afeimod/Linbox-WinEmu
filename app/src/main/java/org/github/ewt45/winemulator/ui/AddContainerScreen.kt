package org.github.ewt45.winemulator.ui

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.github.ewt45.winemulator.Consts
import org.github.ewt45.winemulator.MainEmuActivity
import org.github.ewt45.winemulator.Utils
import org.github.ewt45.winemulator.emu.ProotRootfs
import org.github.ewt45.winemulator.ui.components.ConfirmDialog
import org.github.ewt45.winemulator.ui.components.ProgressDisplay
import org.github.ewt45.winemulator.ui.components.ProgressStage
import org.github.ewt45.winemulator.ui.components.rememberConfirmDialogState
import org.github.ewt45.winemulator.ui.components.rememberTaskReporter
import org.github.ewt45.winemulator.ui.setting.GeneralRootfsSelect_ExportRootfs
import org.github.ewt45.winemulator.ui.setting.GeneralRootfsSelect_LoginUserSelect
import org.github.ewt45.winemulator.ui.setting.GeneralRootfsSelect_RootfsName
import org.github.ewt45.winemulator.viewmodel.SettingViewModel
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val TAG = "AddContainerScreen"

/**
 * 添加容器页面。把原"设置 → Rootfs 切换"里的所有 rootfs 相关操作
 * （添加新 rootfs、选择外部 rootfs 文件夹、重命名/删除/导出 rootfs）整体迁移到这里。
 *
 * 右上角带返回箭头。返回时仅关闭本页面，不影响其它。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContainerScreen(
    settingVm: SettingViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 提取/解压的进度报告器
    val reporter = rememberTaskReporter(msgTitle = "")
    // 刚提取完成的 rootfs（用于弹出"下一步：选用户/完成"）
    var justExtractedRootfs by remember { mutableStateOf<String?>(null) }
    // 选定作为"下一步处理对象"的 rootfs（来自已有列表，用于重命名/删除/导出/选用户）
    var selectedExistingRootfs by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = { ChildScreenTopBar(title = "添加容器", onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "请选择一种方式添加新容器，或在下方管理已存在的容器。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ProgressDisplay(reporter)

            // --------- 三个添加入口 ---------
            AddContainerActions(
                reporter = reporter,
                onExtractAssets = {
                    scope.launch {
                        reporter.msgTitle = "正在提取内置 Rootfs..."
                        reporter.stage = ProgressStage.PROCESSING
                        reporter.progress = 0
                        reporter.msg = "日志："
                        try {
                            val extracted = Utils.Rootfs.installRootfsFromAssets(context, reporter)
                            if (extracted != null) {
                                reporter.msg("提取成功：${extracted.name}", "提取成功！")
                                reporter.stage = ProgressStage.DONE_SUCCESS
                                justExtractedRootfs = extracted.name
                            } else {
                                reporter.msg("未在assets中找到rootfs压缩包", "内置无 rootfs，请使用其他方式")
                                reporter.stage = ProgressStage.DONE_FAILURE
                            }
                        } catch (e: Throwable) {
                            Log.e(TAG, "extract assets rootfs failed", e)
                            reporter.msg("提取失败：${e.stackTraceToString()}", "提取失败")
                            reporter.stage = ProgressStage.DONE_FAILURE
                        }
                        reporter.progress = 100
                    }
                },
                archiveLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch {
                        reporter.msgTitle = "正在解压 Rootfs 压缩包..."
                        reporter.stage = ProgressStage.PROCESSING
                        reporter.progress = 0
                        reporter.msg = "日志："
                        try {
                            val out = Utils.Rootfs.installRootfsArchive(context, uri, reporter)
                            reporter.msg("解压成功：${out.name}", "解压成功！")
                            reporter.stage = ProgressStage.DONE_SUCCESS
                            justExtractedRootfs = out.name
                        } catch (e: Throwable) {
                            Log.e(TAG, "extract archive rootfs failed", e)
                            reporter.msg("解压失败：${e.stackTraceToString()}", "解压失败")
                            reporter.stage = ProgressStage.DONE_FAILURE
                        }
                        reporter.progress = 100
                    }
                },
                folderLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree()
                ) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch {
                        reporter.msgTitle = "正在注册外部 Rootfs..."
                        reporter.stage = ProgressStage.PROCESSING
                        reporter.progress = 0
                        reporter.msg = "日志："
                        try {
                            val newRootfs = linkExternalRootfs(context, uri, reporter)
                            reporter.msg("注册成功：${newRootfs.name}", "注册成功！")
                            reporter.stage = ProgressStage.DONE_SUCCESS
                            justExtractedRootfs = newRootfs.name
                        } catch (e: Throwable) {
                            Log.e(TAG, "link external rootfs failed", e)
                            reporter.msg("注册失败：${e.stackTraceToString()}", "注册失败")
                            reporter.stage = ProgressStage.DONE_FAILURE
                        }
                        reporter.progress = 100
                    }
                },
            )

            // --------- 完成后让用户选用户/完成 ---------
            if (reporter.stage == ProgressStage.DONE_SUCCESS && justExtractedRootfs != null) {
                JustExtractedSetup(
                    rootfsName = justExtractedRootfs!!,
                    settingVm = settingVm,
                    onDone = {
                        justExtractedRootfs = null
                        reporter.stage = ProgressStage.NOT_STARTED
                        reporter.msg = ""
                        reporter.msgTitle = ""
                    },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                text = "已存在的容器",
                style = MaterialTheme.typography.titleMedium,
            )

            // --------- 已有 rootfs 列表（重命名 / 删除 / 导出 / 设为当前） ---------
            ExistingRootfsList(
                settingVm = settingVm,
                selectedName = selectedExistingRootfs,
                onSelect = { selectedExistingRootfs = it },
            )
        }
    }
}

/**
 * 三个添加入口按钮 + 两个 SAF launcher。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddContainerActions(
    reporter: org.github.ewt45.winemulator.ui.components.SimpleTaskReporter,
    onExtractAssets: () -> Unit,
    archiveLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    folderLauncher: androidx.activity.result.ActivityResultLauncher<Uri?>,
) {
    val enabled = reporter.stage == ProgressStage.NOT_STARTED || reporter.stage == ProgressStage.DONE_FAILURE ||
                  reporter.stage == ProgressStage.DONE_SUCCESS
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = "添加新容器", style = MaterialTheme.typography.titleSmall)
        Button(
            onClick = onExtractAssets,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("下载默认 rootfs (内置)") }
        Button(
            onClick = { archiveLauncher.launch(arrayOf("application/x-xz", "application/gzip", "*/*")) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("选择外部 rootfs 压缩包 (.tar.xz / .gz / .zst)") }
        Button(
            onClick = { folderLauncher.launch(null) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("选择外部 rootfs 文件夹") }
    }
}

/**
 * 把 SAF 选中的外部文件夹符号链接到 [Consts.rootfsAllDir] 下，并返回新目录的 [File]。
 * 文件夹命名为 `ext-<basename>-<时间戳>`。
 */
private suspend fun linkExternalRootfs(
    context: android.content.Context,
    treeUri: Uri,
    reporter: org.github.ewt45.winemulator.ui.components.SimpleTaskReporter,
): File = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    reporter.msg("正在解析外部文件夹 URI: $treeUri")
    val path = treeUri.path?.split(":", limit = 2)?.getOrNull(1) ?: ""
    val fullPath = if (path.isNotEmpty()) "/storage/emulated/0/$path" else ""
    val externalDir = if (fullPath.isNotEmpty()) File(fullPath) else null
    if (externalDir == null || !externalDir.exists() || !externalDir.isDirectory) {
        throw RuntimeException("无法获取外部文件夹路径: $treeUri")
    }
    // 简单校验：必须包含 etc + usr
    val hasEtc = File(externalDir, "etc").exists()
    val hasUsr = File(externalDir, "usr").exists()
    if (!hasEtc || !hasUsr) {
        throw RuntimeException("所选文件夹不像一个 rootfs（缺少 etc/usr）：${externalDir.absolutePath}")
    }
    val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val targetName = "ext-${externalDir.name}-$ts"
    val target = File(Consts.rootfsAllDir, targetName)
    java.nio.file.Files.createSymbolicLink(target.toPath(), externalDir.toPath())
    reporter.msg("已创建符号链接: ${target.absolutePath} -> ${externalDir.absolutePath}")
    // 让符号链接在 rootfs 列表里也带个别名
    Utils.Rootfs.setAlias(target, externalDir.name)
    Utils.Rootfs.postExtractRootfs(target)
    target
}

/**
 * 刚解压/提取/导入成功的 rootfs：让用户编辑别名 + 选用户 + 设下次启动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JustExtractedSetup(
    rootfsName: String,
    settingVm: SettingViewModel,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val rootfsFile = remember(rootfsName) { File(Consts.rootfsAllDir, rootfsName) }
    val dialogState = rememberConfirmDialogState()

    var alias by remember(rootfsName) { mutableStateOf(Utils.Rootfs.getAlias(rootfsFile)) }
    val userList = remember(rootfsName) { ProotRootfs.getUserInfos(rootfsFile).map { it.name } }
    var selectedUser by remember(rootfsName) {
        mutableStateOf(userList.find { it != "root" } ?: "root")
    }
    var isSetCurrent by remember(rootfsName) { mutableStateOf(true) }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("已添加：$rootfsName", style = MaterialTheme.typography.titleSmall)
            GeneralRootfsSelect_RootfsName(
                rootfsName = rootfsName,
                rootfsAlias = alias,
                isCurr = false,
                dialogState = dialogState,
                onAliasChange = { _, newAlias ->
                    Utils.Rootfs.setAlias(rootfsFile, newAlias)
                    alias = newAlias
                },
            )
            if (userList.isNotEmpty()) {
                GeneralRootfsSelect_LoginUserSelect(rootfsName, selectedUser, userList) { _, newUser ->
                    selectedUser = newUser
                    scope.launch { settingVm.onChangeRootfsLoginUser(rootfsName, newUser) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("下次启动 app 运行该容器")
                Checkbox(isSetCurrent, { isSetCurrent = it })
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDone) { Text("完成") }
                Button(onClick = {
                    scope.launch {
                        // 1. 设置当前 rootfs（如果勾选）
                        if (isSetCurrent) {
                            Utils.Rootfs.makeCurrent(rootfsFile)
                            MainEmuActivity.instance.terminalViewModel.stopTerminal()
                            settingVm.updateValuesWhenEnterSettings()
                        }
                        onDone()
                    }
                }) { Text("保存并完成") }
            }
        }
    }
    ConfirmDialog(dialogState)
}

/**
 * 已有 rootfs 列表（来自 [SettingViewModel.rootfsAliasMap] / [SettingViewModel.rootfsUsersOptions]）。
 * 每条提供：设为当前、重命名、删除、导出。
 */
@Composable
private fun ExistingRootfsList(
    settingVm: SettingViewModel,
    selectedName: String?,
    onSelect: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val dialogState = rememberConfirmDialogState()
    val currentCanonical = remember { runCatching { Consts.rootfsCurrDir.canonicalFile.name }.getOrDefault("") }
    val rootfsList = remember(selectedName) {
        settingVm.rootfsAliasMap.value.keys.sortedWith(
            compareBy<String> { it != currentCanonical }.thenBy { it }
        )
    }
    if (rootfsList.isEmpty()) {
        Text(text = "（暂无 rootfs）", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rootfsList.forEach { name ->
                val alias = settingVm.rootfsAliasMap.value[name] ?: name
                val isCurr = name == currentCanonical
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    border = CardDefaults.outlinedCardBorder(),
                ) {
                    Column(modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(alias, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            if (isCurr) Text("当前", color = MaterialTheme.colorScheme.primary)
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            TextButton(
                                onClick = {
                                    if (isCurr) return@TextButton
                                    dialogState.showConfirm("将此容器设为 Proot 使用的 rootfs？\n\n$alias") {
                                        scope.launch {
                                            MainEmuActivity.instance.terminalViewModel.stopTerminal()
                                            Utils.Rootfs.makeCurrent(File(Consts.rootfsAllDir, name))
                                            settingVm.updateValuesWhenEnterSettings()
                                        }
                                    }
                                },
                                enabled = !isCurr,
                            ) { Text("设为当前") }
                            TextButton(onClick = { onSelect(name) }) { Text("重命名") }
                            TextButton(
                                onClick = {
                                    if (isCurr) {
                                        dialogState.showConfirm("该 rootfs 当前正在运行，无法删除。")
                                    } else {
                                        dialogState.showConfirm("确定删除该 rootfs 吗？\n其内部所有文件都将被删除！\n\n$alias") {
                                            scope.launch {
                                                val result = runCatching {
                                                    settingVm.onChangeRootfsName(name, name, org.github.ewt45.winemulator.FuncOnChangeAction.DEL)
                                                }
                                                result.onFailure { dialogState.showConfirm("删除失败：${it.message}") }
                                                settingVm.updateValuesWhenEnterSettings()
                                            }
                                        }
                                    }
                                },
                                enabled = !isCurr,
                            ) { Text("删除") }
                        }
                        // 导出
                        GeneralRootfsSelect_ExportRootfs(modifier = Modifier, rootfsName = name)
                    }
                }
            }
        }
    }
    ConfirmDialog(dialogState)
}
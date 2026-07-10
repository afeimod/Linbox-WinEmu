package org.github.ewt45.winemulator.ui

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.github.ewt45.winemulator.Consts
import org.github.ewt45.winemulator.MainEmuActivity
import org.github.ewt45.winemulator.Utils
import org.github.ewt45.winemulator.emu.ProotRootfs
import org.github.ewt45.winemulator.prootdistro.ProotDistroArch
import org.github.ewt45.winemulator.prootdistro.ProotDistroCatalog
import org.github.ewt45.winemulator.prootdistro.ProotDistroEntry
import org.github.ewt45.winemulator.prootdistro.ProotDistroInstaller
import org.github.ewt45.winemulator.ui.components.ConfirmDialog
import org.github.ewt45.winemulator.ui.components.ProgressDisplay
import org.github.ewt45.winemulator.ui.components.ProgressStage
import org.github.ewt45.winemulator.ui.components.SimpleTaskReporter
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
 * 添加容器页面。把"设置 → Rootfs 切换"里所有的 rootfs 相关操作整体迁移到这里。
 *
 * 顶部：proot-distro 在线下载列表（方格展示）。
 * 中部：3 个本地添加入口（下载默认 / 选压缩包 / 选外部文件夹）。
 * 底部：已存在的 rootfs 列表（设为当前 / 重命名 / 删除 / 导出）。
 *
 * 右上角带返回箭头。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContainerScreen(
    settingVm: SettingViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reporter = rememberTaskReporter(msgTitle = "")
    var justExtractedRootfs by remember { mutableStateOf<String?>(null) }
    var selectedExistingRootfs by remember { mutableStateOf<String?>(null) }
    var isInstalling by remember { mutableStateOf(false) }
    var showCustomImageForm by remember { mutableStateOf(false) }

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
                text = "在线下载 (proot-distro) / 本地导入 / 管理已存在的容器。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---- 在线下载：方格列表 ----
            Text(
                text = "在线下载 Rootfs",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            ProotDistroGrid(
                entries = ProotDistroCatalog.entries,
                isInstalling = isInstalling,
                onPickEntry = { entry ->
                    installProotDistroRootfs(
                        entry = entry,
                        customName = null,
                        context = context,
                        reporter = reporter,
                        scope = scope,
                        onStart = { isInstalling = true },
                        onFinish = {
                            isInstalling = false
                            if (reporter.stage == ProgressStage.DONE_SUCCESS) {
                                // 取安装成功的 rootfs 名
                                val m = Regex("(?m)^安装完成:\\s*(\\S+)").find(reporter.msg)
                                    ?: Regex("(?m)提取成功：(\\S+)").find(reporter.msg)
                                    ?: Regex("(?m)解压成功：(\\S+)").find(reporter.msg)
                                val name = m?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                                if (name != null) justExtractedRootfs = name
                            }
                        },
                    )
                },
            )
            TextButton(
                onClick = { showCustomImageForm = !showCustomImageForm },
                enabled = !isInstalling,
            ) { Text(if (showCustomImageForm) "收起自定义 image ref" else "使用自定义 image ref") }

            if (showCustomImageForm) {
                CustomImageForm(
                    isInstalling = isInstalling,
                    onInstall = { imageRef, customName ->
                        installCustomImageRef(
                            imageRef = imageRef,
                            customName = customName,
                            context = context,
                            reporter = reporter,
                            scope = scope,
                            onStart = { isInstalling = true },
                            onFinish = {
                                isInstalling = false
                                if (reporter.stage == ProgressStage.DONE_SUCCESS) {
                                    val m = Regex("(?m)^安装完成:\\s*(\\S+)").find(reporter.msg)
                                    val name = m?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                                    if (name != null) justExtractedRootfs = name
                                }
                            },
                        )
                    },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // ---- 本地导入：3 个添加入口 ----
            Text(
                text = "本地导入 Rootfs",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
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

            // ---- 安装进度 ----
            if (reporter.stage == ProgressStage.PROCESSING ||
                reporter.stage == ProgressStage.DONE_SUCCESS ||
                reporter.stage == ProgressStage.DONE_FAILURE) {
                ProgressDisplay(reporter)
            }

            // ---- 完成后让用户选用户/完成 ----
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
                fontWeight = FontWeight.SemiBold,
            )

            // ---- 已有 rootfs 列表 ----
            ExistingRootfsList(
                settingVm = settingVm,
                selectedName = selectedExistingRootfs,
                onSelect = { selectedExistingRootfs = it },
            )
        }
    }
}

/**
 * proot-distro 方格列表。宽度 ≥ 600dp 时 4 列，否则 3 列。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProotDistroGrid(
    entries: List<ProotDistroEntry>,
    isInstalling: Boolean,
    onPickEntry: (ProotDistroEntry) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 600.dp) 4 else 3
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(0.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(entries, key = { it.imageRef }) { entry ->
                ProotDistroTile(entry = entry, enabled = !isInstalling, onClick = { onPickEntry(entry) })
            }
        }
    }
}

/**
 * 单个 proot-distro 方格（卡片）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProotDistroTile(
    entry: ProotDistroEntry,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.secondaryContainer,
        onClick = onClick,
        enabled = enabled,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            contentAlignment = Alignment.BottomStart,
        ) {
            Column {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.imageRef,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 自定义 image ref 输入表单。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomImageForm(
    isInstalling: Boolean,
    onInstall: (imageRef: String, customName: String?) -> Unit,
) {
    var imageRef by remember { mutableStateOf("") }
    var customName by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = imageRef,
            onValueChange = { imageRef = it },
            label = { Text("image ref") },
            placeholder = { Text("例如 ubuntu:24.04 或 kalilinux/kali-rolling") },
            enabled = !isInstalling,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = customName,
            onValueChange = { customName = it.filter { ch -> ch.isLetterOrDigit() || ch in "._-" } },
            label = { Text("容器名 (可选)") },
            placeholder = { Text("留空则从 image ref 派生") },
            enabled = !isInstalling,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                val ref = imageRef.trim()
                if (ref.isNotEmpty()) {
                    onInstall(ref, customName.takeIf { it.isNotBlank() })
                }
            },
            enabled = !isInstalling && imageRef.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("下载并安装") }
    }
}

/**
 * 通过 proot-distro 流程安装一个 rootfs。逻辑与 PrepareScreen.installProotDistroRootfs 一致。
 */
private fun installProotDistroRootfs(
    entry: ProotDistroEntry,
    customName: String?,
    context: android.content.Context,
    reporter: SimpleTaskReporter,
    scope: kotlinx.coroutines.CoroutineScope,
    onStart: () -> Unit,
    onFinish: () -> Unit,
) {
    val imageRef = entry.imageRef
    scope.launch(Dispatchers.IO) {
        onStart()
        reporter.msgTitle = "正在下载 $imageRef ..."
        reporter.stage = ProgressStage.PROCESSING
        reporter.progress = 0
        reporter.msg = "日志："
        try {
            val hostArch = ProotDistroArch.getDeviceCpuArch()
            val resolved = if (entry.source == "oci" &&
                entry.altImageRef != null && entry.altSource == "tarball" &&
                hostArch == "aarch64") {
                reporter.msg("检测到本机 arch=$hostArch，自动改为从 ArchLinuxARM rootfs tarball 安装")
                Pair(entry.altImageRef, "tarball")
            } else {
                Pair(imageRef, entry.source)
            }
            val result = when (resolved.second) {
                "tarball" -> ProotDistroInstaller.installFromTarball(
                    imageRef = resolved.first,
                    customName = customName,
                    reporter = reporter,
                )
                else -> ProotDistroInstaller.install(
                    imageRef = resolved.first,
                    customName = customName,
                    reporter = reporter,
                )
            }
            reporter.msg(
                "安装完成: ${result.rootfsDir.name} (${result.arch})",
                "安装成功！\n（日志可点击展开查看）"
            )
            reporter.stage = ProgressStage.DONE_SUCCESS
        } catch (e: Throwable) {
            e.printStackTrace()
            val rawMsg = e.message ?: e::class.simpleName ?: "unknown"
            val shortMsg = rawMsg.lineSequence().firstOrNull()?.take(200) ?: rawMsg
            val (title, hint) = when {
                rawMsg.contains("integrity check failed", ignoreCase = true) ->
                    "下载的 layer SHA-256 校验失败" to "请重新点击重试。"
                rawMsg.contains("manifest", ignoreCase = true) &&
                    (rawMsg.contains("not found", ignoreCase = true) ||
                     rawMsg.contains("does not exist", ignoreCase = true)) ->
                    "在 Docker Hub 找不到该镜像" to "请检查 image ref 是否正确"
                e is java.net.UnknownHostException ||
                    e is java.net.SocketTimeoutException ||
                    e is java.net.ConnectException ->
                    "网络连接失败" to "请检查网络/DNS，重试不需要重新下载全部"
                rawMsg.contains("zstd", ignoreCase = true) ->
                    "该镜像的 layer 使用 zstd 压缩" to "Android 暂不支持 zstd 解压"
                else -> "安装失败: $shortMsg" to "查看完整堆栈。"
            }
            android.util.Log.e("AddContainerScreen", "install failed: $rawMsg", e)
            reporter.msg("✗ $title")
            reporter.msg("  异常: ${e::class.simpleName}: $shortMsg")
            e.stackTrace.take(8).forEach { st -> reporter.msg("    at $st") }
            reporter.msg("✗ 提示: $hint", title)
            reporter.stage = ProgressStage.DONE_FAILURE
        } finally {
            reporter.progress = 100
            onFinish()
        }
    }
}

/**
 * 用用户输入的自定义 image ref 走 proot-distro 流程安装。
 */
private fun installCustomImageRef(
    imageRef: String,
    customName: String?,
    context: android.content.Context,
    reporter: SimpleTaskReporter,
    scope: kotlinx.coroutines.CoroutineScope,
    onStart: () -> Unit,
    onFinish: () -> Unit,
) {
    scope.launch(Dispatchers.IO) {
        onStart()
        reporter.msgTitle = "正在通过 proot-distro 安装 $imageRef ..."
        reporter.stage = ProgressStage.PROCESSING
        reporter.progress = 0
        reporter.msg = "日志："
        try {
            val result = ProotDistroInstaller.install(
                imageRef = imageRef,
                customName = customName,
                reporter = reporter,
            )
            reporter.msg(
                "安装完成: ${result.rootfsDir.name} (${result.arch})",
                "安装成功！\n（日志可点击展开查看）"
            )
            reporter.stage = ProgressStage.DONE_SUCCESS
        } catch (e: Throwable) {
            e.printStackTrace()
            val rawMsg = e.message ?: e::class.simpleName ?: "unknown"
            val shortMsg = rawMsg.lineSequence().firstOrNull()?.take(200) ?: rawMsg
            reporter.msg("✗ 安装失败: $shortMsg", "安装失败")
            reporter.stage = ProgressStage.DONE_FAILURE
        } finally {
            reporter.progress = 100
            onFinish()
        }
    }
}

/**
 * 三个本地添加入口按钮。
 */
@Composable
private fun AddContainerActions(
    reporter: SimpleTaskReporter,
    onExtractAssets: () -> Unit,
    archiveLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    folderLauncher: androidx.activity.result.ActivityResultLauncher<Uri?>,
) {
    val enabled = reporter.stage == ProgressStage.NOT_STARTED || reporter.stage == ProgressStage.DONE_FAILURE
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Button(onClick = onExtractAssets, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("下载默认 rootfs (内置)")
        }
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
 * 把 SAF 选中的外部文件夹符号链接到 [Consts.rootfsAllDir] 下。
 */
private suspend fun linkExternalRootfs(
    context: android.content.Context,
    treeUri: Uri,
    reporter: SimpleTaskReporter,
): File = withContext(Dispatchers.IO) {
    reporter.msg("正在解析外部文件夹 URI: $treeUri")
    val path = treeUri.path?.split(":", limit = 2)?.getOrNull(1) ?: ""
    val fullPath = if (path.isNotEmpty()) "/storage/emulated/0/$path" else ""
    val externalDir = if (fullPath.isNotEmpty()) File(fullPath) else null
    if (externalDir == null || !externalDir.exists() || !externalDir.isDirectory) {
        throw RuntimeException("无法获取外部文件夹路径: $treeUri")
    }
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
    Utils.Rootfs.setAlias(target, externalDir.name)
    Utils.Rootfs.postExtractRootfs(target)
    target
}

/**
 * 刚解压/下载成功的 rootfs：让用户编辑别名 + 选用户 + 设下次启动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JustExtractedSetup(
    rootfsName: String,
    settingVm: SettingViewModel,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
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
 * 已有 rootfs 列表：设为当前、重命名、删除、导出。
 */
@Composable
private fun ExistingRootfsList(
    settingVm: SettingViewModel,
    selectedName: String?,
    onSelect: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
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
                        GeneralRootfsSelect_ExportRootfs(modifier = Modifier, rootfsName = name)
                    }
                }
            }
        }
    }
    ConfirmDialog(dialogState)
}
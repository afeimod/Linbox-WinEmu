package org.github.ewt45.winemulator.ui

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.LaunchedEffect
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

/** 添加方式 */
private enum class AddMethod {
    ONLINE, BUILTIN, ARCHIVE, FOLDER;
}

/**
 * 添加容器页面 - 3 步流程。
 *
 * 第 1 步：选择添加方式（在线 / 内置 / 压缩包 / 文件夹）
 * 第 2 步：根据方式展示具体选择界面（proot-distro 方格 / 启动提取 / 选文件 / 选文件夹）
 * 第 3 步：显示安装进度和日志，完成后让用户选用户/完成
 *
 * 右上角带返回箭头，可随时回退。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContainerScreen(
    settingVm: SettingViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 当前选择的添加方式；null = 在第 1 步
    var selectedMethod by remember { mutableStateOf<AddMethod?>(null) }
    val reporter = rememberTaskReporter(msgTitle = "")
    var justExtractedRootfs by remember { mutableStateOf<String?>(null) }
    var isInstalling by remember { mutableStateOf(false) }
    // 自定义 image ref 输入
    var customImageRef by remember { mutableStateOf("") }
    var customImageName by remember { mutableStateOf("") }

    // 用于外部 rootfs 文件夹的 SAF launcher
    val folderLauncher = rememberLauncherForActivityResult(
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
    }

    // 用于外部 rootfs 压缩包的 SAF launcher
    val archiveLauncher = rememberLauncherForActivityResult(
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
    }

    Scaffold(
        topBar = {
            ChildScreenTopBar(
                title = "添加容器 (第 ${if (selectedMethod == null) 1 else if (reporter.stage != ProgressStage.NOT_STARTED) 3 else 2} 步 / 共 3 步)",
                onBack = {
                    when {
                        reporter.stage == ProgressStage.DONE_SUCCESS && justExtractedRootfs != null -> {
                            justExtractedRootfs = null
                            reporter.stage = ProgressStage.NOT_STARTED
                            reporter.msg = ""
                            reporter.msgTitle = ""
                        }
                        reporter.stage == ProgressStage.PROCESSING -> {
                            // 不允许在安装中退出
                        }
                        selectedMethod != null -> {
                            selectedMethod = null
                            reporter.stage = ProgressStage.NOT_STARTED
                            reporter.msg = ""
                            reporter.msgTitle = ""
                        }
                        else -> onBack()
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------- 第 1 步：选择方式 ----------
            if (selectedMethod == null) {
                Text(
                    text = "请选择一种添加容器的方式：",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                AddMethodTile(
                    title = "在线下载 Rootfs",
                    description = "通过 proot-distro 从 Docker Hub 拉镜像，支持 Ubuntu / Debian / Alpine / Arch 等 10 种发行版",
                    onClick = { selectedMethod = AddMethod.ONLINE },
                )
                AddMethodTile(
                    title = "下载默认 Rootfs (内置)",
                    description = "从 App 自带的 assets 中提取预打包的 rootfs",
                    onClick = { selectedMethod = AddMethod.BUILTIN },
                )
                AddMethodTile(
                    title = "选择外部 Rootfs 压缩包",
                    description = "从本地存储选 .tar.xz / .tar.gz / .tar.zst 压缩包",
                    onClick = {
                        selectedMethod = AddMethod.ARCHIVE
                        archiveLauncher.launch(arrayOf("application/x-xz", "application/gzip", "*/*"))
                    },
                )
                AddMethodTile(
                    title = "选择外部 Rootfs 文件夹",
                    description = "从本地存储选一个已存在的 rootfs 文件夹",
                    onClick = {
                        selectedMethod = AddMethod.FOLDER
                        folderLauncher.launch(null)
                    },
                )
            }

            // ---------- 第 2 步：具体选择 ----------
            else if (reporter.stage == ProgressStage.NOT_STARTED) {
                when (selectedMethod) {
                    AddMethod.ONLINE -> {
                        Text(
                            text = "在线下载 (proot-distro)",
                            style = MaterialTheme.typography.titleMedium,
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
                                            val m = Regex("(?m)^安装完成:\\s*(\\S+)").find(reporter.msg)
                                            val name = m?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                                            if (name != null) justExtractedRootfs = name
                                        }
                                    },
                                )
                            },
                        )
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text(
                            text = "自定义 image ref：",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        OutlinedTextField(
                            value = customImageRef,
                            onValueChange = { customImageRef = it },
                            label = { Text("image ref") },
                            placeholder = { Text("例如 ubuntu:24.04") },
                            enabled = !isInstalling,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = customImageName,
                            onValueChange = { customImageName = it.filter { ch -> ch.isLetterOrDigit() || ch in "._-" } },
                            label = { Text("容器名 (可选)") },
                            placeholder = { Text("留空则从 image ref 派生") },
                            enabled = !isInstalling,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                val ref = customImageRef.trim()
                                if (ref.isNotEmpty()) {
                                    installCustomImageRef(
                                        imageRef = ref,
                                        customName = customImageName.takeIf { it.isNotBlank() },
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
                                }
                            },
                            enabled = !isInstalling && customImageRef.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("下载并安装") }
                    }
                    AddMethod.BUILTIN -> {
                        Text(
                            text = "下载默认 Rootfs (内置)",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "点击下面按钮开始从 App 内置 assets 中提取 rootfs。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = {
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
                                            reporter.msg("未在assets中找到rootfs压缩包", "内置无 rootfs")
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
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("开始提取") }
                    }
                    AddMethod.ARCHIVE -> {
                        Text(
                            text = "选择外部 Rootfs 压缩包",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "点击下面按钮选择 .tar.xz / .tar.gz / .tar.zst 文件。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = {
                                archiveLauncher.launch(arrayOf("application/x-xz", "application/gzip", "*/*"))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("选择压缩包") }
                    }
                    AddMethod.FOLDER -> {
                        Text(
                            text = "选择外部 Rootfs 文件夹",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "点击下面按钮选一个包含 etc + usr 的现有文件夹作为 rootfs。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = { folderLauncher.launch(null) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("选择文件夹") }
                    }
                    else -> Unit
                }
            }

            // ---------- 第 3 步：进度 ----------
            if (reporter.stage == ProgressStage.PROCESSING ||
                reporter.stage == ProgressStage.DONE_SUCCESS ||
                reporter.stage == ProgressStage.DONE_FAILURE) {
                ProgressDisplay(reporter)
            }
            // 失败可以重试
            if (reporter.stage == ProgressStage.DONE_FAILURE) {
                Button(
                    onClick = {
                        // 重试：返回第 2 步（重新选 / 重启 launcher）
                        when (selectedMethod) {
                            AddMethod.ONLINE, AddMethod.BUILTIN -> {
                                reporter.stage = ProgressStage.NOT_STARTED
                                reporter.msg = ""
                            }
                            AddMethod.ARCHIVE -> archiveLauncher.launch(arrayOf("application/x-xz", "application/gzip", "*/*"))
                            AddMethod.FOLDER -> folderLauncher.launch(null)
                            else -> Unit
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("重试") }
            }

            // ---------- 第 3 步：完成后让用户选用户/完成 ----------
            if (reporter.stage == ProgressStage.DONE_SUCCESS && justExtractedRootfs != null) {
                JustExtractedSetup(
                    rootfsName = justExtractedRootfs!!,
                    settingVm = settingVm,
                    onDone = {
                        justExtractedRootfs = null
                        reporter.stage = ProgressStage.NOT_STARTED
                        reporter.msg = ""
                        reporter.msgTitle = ""
                        selectedMethod = null
                    },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // ---------- 已存在的容器列表 ----------
            Text(
                text = "已存在的容器",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            ExistingRootfsList(settingVm = settingVm)
        }
    }
}

/**
 * 添加方式卡片（一行一项）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMethodTile(title: String, description: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick,
    ) {
        Column(modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer4()
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Spacer4() = androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 4.dp))

/**
 * proot-distro 方格列表。宽度 ≥ 600dp 时 4 列，否则 3 列。
 * 用 Row + Column 手画，避免 LazyVerticalGrid 不能嵌套在 verticalScroll 里。
 */
@Composable
private fun ProotDistroGrid(
    entries: List<ProotDistroEntry>,
    isInstalling: Boolean,
    onPickEntry: (ProotDistroEntry) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 600.dp) 4 else 3
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            entries.chunked(columns).forEach { rowItems ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    rowItems.forEach { entry ->
                        ProotDistroTile(
                            entry = entry,
                            enabled = !isInstalling,
                            onClick = { onPickEntry(entry) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columns - rowItems.size) {
                        Box(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProotDistroTile(
    entry: ProotDistroEntry,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.aspectRatio(1f),
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
 * 通过 proot-distro 流程安装一个 rootfs。
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
            reporter.msg("✗ 安装失败: $shortMsg", "安装失败")
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
 * 刚下载成功的 rootfs：让用户编辑别名 + 选用户 + 设下次启动。
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
private fun ExistingRootfsList(settingVm: SettingViewModel) {
    val scope = rememberCoroutineScope()
    val dialogState = rememberConfirmDialogState()
    val currentCanonical = remember { runCatching { Consts.rootfsCurrDir.canonicalFile.name }.getOrDefault("") }
    val rootfsList = remember {
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
                            TextButton(onClick = {
                                if (isCurr) {
                                    dialogState.showConfirm("该 rootfs 当前正在运行，无法删除。")
                                } else {
                                    dialogState.showConfirm("确定删除该 rootfs 吗？\n\n$alias") {
                                        scope.launch {
                                            val result = runCatching {
                                                settingVm.onChangeRootfsName(name, name, org.github.ewt45.winemulator.FuncOnChangeAction.DEL)
                                            }
                                            result.onFailure { dialogState.showConfirm("删除失败：${it.message}") }
                                            settingVm.updateValuesWhenEnterSettings()
                                        }
                                    }
                                }
                            }, enabled = !isCurr) { Text("删除") }
                        }
                        GeneralRootfsSelect_ExportRootfs(modifier = Modifier, rootfsName = name)
                    }
                }
            }
        }
    }
    ConfirmDialog(dialogState)
}
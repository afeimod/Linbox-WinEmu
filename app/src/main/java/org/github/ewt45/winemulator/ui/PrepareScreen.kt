package org.github.ewt45.winemulator.ui


import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.github.ewt45.winemulator.Consts
import org.github.ewt45.winemulator.FuncOnChangeAction
import org.github.ewt45.winemulator.MainEmuActivity
import org.github.ewt45.winemulator.Utils
import org.github.ewt45.winemulator.emu.ProotRootfs
import org.github.ewt45.winemulator.permissions.RequiredPermissions
import org.github.ewt45.winemulator.prootdistro.ProotDistroCatalog
import org.github.ewt45.winemulator.prootdistro.ProotDistroEntry
import org.github.ewt45.winemulator.prootdistro.ProotDistroInstaller
import org.github.ewt45.winemulator.ui.components.ConfirmDialog
import org.github.ewt45.winemulator.ui.components.ProgressDisplay
import org.github.ewt45.winemulator.ui.components.ProgressStage
import org.github.ewt45.winemulator.ui.components.SimpleTaskReporter
import org.github.ewt45.winemulator.ui.components.rememberConfirmDialogState
import org.github.ewt45.winemulator.ui.components.rememberTaskReporter
import org.github.ewt45.winemulator.ui.setting.GeneralRootfsSelect_LoginUserSelect
import org.github.ewt45.winemulator.ui.setting.GeneralRootfsSelect_RootfsName
import org.github.ewt45.winemulator.viewmodel.PrepareViewModel
import org.github.ewt45.winemulator.viewmodel.SettingViewModel
import java.io.File

private val TAG = "PrepareScreen"

@Composable
fun PrepareScreen(prepareVm: PrepareViewModel, settingVm: SettingViewModel, navigateToMainScreen: suspend () -> Unit) {
    //初次进入时 刷新状态
    LaunchedEffect(Unit) {
        prepareVm.updateState()
    }
    PrepareScreenImpl(prepareVm, settingVm, navigateToMainScreen)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrepareScreenImpl(prepareVm: PrepareViewModel, settingVm: SettingViewModel, navigateToMainScreen: suspend () -> Unit) {
    val state by prepareVm.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    // 标题会在后续根据场景动态设置
    val reporter = rememberTaskReporter(msgTitle = "")
    var autoExtractStarted by remember { mutableStateOf(false) } // 标记是否已经开始自动提取
    var isInstalling by remember { mutableStateOf(false) } // 是否正在通过 proot-distro 安装

    // 新增：用于显示重启提示对话框的状态
    var showRestartDialog by remember { mutableStateOf(false) }

    // 重启提示对话框
    if (showRestartDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { },
            title = { Text("需要重启") },
            text = { Text("Rootfs已提取成功并设置完成。为了使配置生效，需要重启应用。是否现在重启？") },
            confirmButton = {
                androidx.compose.material3.Button(
                    onClick = {
                        // 直接关闭应用
                        MainEmuActivity.instance.finish()
                    }
                ) { Text("重启") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showRestartDialog = false
                        prepareVm.onUserSelectFinished()
                    }
                ) { Text("暂不重启，稍后手动重启") }
            }
        )
    }

    // 根据场景设置 reporter 标题
    LaunchedEffect(state.forceNoRootfs, state.noRootfs, state.autoExtractedRootfsName) {
        reporter.msgTitle = when {
            state.forceNoRootfs -> "新建容器"
            state.noRootfs -> "请选择或提取Rootfs"
            state.autoExtractedRootfsName != null -> "Rootfs已提取成功"
            else -> ""
        }
    }

    // 退出prepareScreen
    LaunchedEffect(state.isPrepareFinished) {
        if (!state.isPrepareFinished) return@LaunchedEffect
        navigateToMainScreen()
    }

    // 首次启动时（noRootfs），自动尝试从assets提取rootfs
    // 新建容器时（forceNoRootfs）不自动提取，让用户手动选择
    LaunchedEffect(state.skipPermissions, state.unGrantedPermissions.isEmpty()) {
        val permissionsReady = state.skipPermissions || state.unGrantedPermissions.isEmpty()
        if (permissionsReady && !autoExtractStarted && state.noRootfs && !state.forceNoRootfs) {
            autoExtractStarted = true
            reporter.msgTitle = "正在自动提取Rootfs..."
            reporter.stage = ProgressStage.PROCESSING
            reporter.progress = 0
            reporter.msg = "日志："

            try {
                val extractedRootfs = Utils.Rootfs.installRootfsFromAssets(ctx, reporter)
                if (extractedRootfs != null) {
                    reporter.msg("自动提取rootfs成功：${extractedRootfs.name}", "自动提取成功！\n（日志可点击展开查看）")
                    reporter.stage = ProgressStage.DONE_SUCCESS

                    // 自动设置启动命令为linbox
                    settingVm.onChangeProotStartupCmd("linbox")
                    reporter.msg("已设置启动命令为: linbox")

                    // 自动设置当前rootfs（直接设置符号链接，不调用onChangeRootfsSelect避免触发finish）
                    Utils.Rootfs.makeCurrent(extractedRootfs)
                    // 更新状态，记录提取的rootfs名称，显示用户选择界面
                    prepareVm.onRootfsExtracted(extractedRootfs.name)
                    // 重置autoExtractStarted，以便后续可以从用户选择界面再次触发
                    autoExtractStarted = false
                } else {
                    // 未找到assets中的rootfs，回退到 proot-distro 选择界面
                    reporter.msg("未在assets中找到rootfs压缩包", "请选择一种方式获取Rootfs")
                    reporter.stage = ProgressStage.NOT_STARTED
                    autoExtractStarted = false
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                reporter.msg("自动提取rootfs过程中出现错误：${e.stackTraceToString()}", "自动提取失败，请选择其他方式获取rootfs。\n（日志可点击展开查看）")
                reporter.stage = ProgressStage.DONE_FAILURE
                autoExtractStarted = false
            }
            reporter.progress = 100
        }
    }

    // 新建容器时，用户点击"从App内置提取"按钮后执行提取逻辑
    LaunchedEffect(autoExtractStarted, state.forceNoRootfs) {
        if (autoExtractStarted && state.forceNoRootfs && reporter.stage == ProgressStage.NOT_STARTED) {
            reporter.msgTitle = "正在提取Rootfs..."
            reporter.stage = ProgressStage.PROCESSING
            reporter.progress = 0
            reporter.msg = "日志："

            try {
                val extractedRootfs = Utils.Rootfs.installRootfsFromAssets(ctx, reporter)
                if (extractedRootfs != null) {
                    reporter.msg("提取rootfs成功：${extractedRootfs.name}", "提取成功！\n（日志可点击展开查看）")
                    reporter.stage = ProgressStage.DONE_SUCCESS
                } else {
                    reporter.msg("未在assets中找到rootfs压缩包", "请选择其他方式获取rootfs")
                    reporter.stage = ProgressStage.DONE_FAILURE
                    autoExtractStarted = false
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                reporter.msg("提取rootfs过程中出现错误：${e.stackTraceToString()}", "提取失败，请选择其他方式获取rootfs。\n（日志可点击展开查看）")
                reporter.stage = ProgressStage.DONE_FAILURE
                autoExtractStarted = false
            }
            reporter.progress = 100
        }
    }

    /**
     * 通过 proot-distro 流程安装一个 rootfs。
     * 参考 proot-distro install 命令:
     *   - 解析 image ref
     *   - 拉 manifest, 选 arch
     *   - 下载所有 layer 并校验 sha256
     *   - 解压 (含 OCI whiteout) 到 rootfsAllDir/<alias>
     *   - 写 resolv.conf / hosts / 注册 Android UID / 假 /proc
     */
    fun installProotDistroRootfs(imageRef: String, customName: String?) {
        scope.launch(Dispatchers.IO) {
            isInstalling = true
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
                // 通知 UI 显示用户选择界面
                prepareVm.onRootfsExtracted(result.rootfsDir.name)
            } catch (e: Throwable) {
                e.printStackTrace()
                reporter.msg(
                    "安装失败: ${e.message ?: e::class.simpleName}\n${e.stackTraceToString()}",
                    "安装失败，请检查网络或换个 image ref 重试。\n（日志可点击展开查看）"
                )
                reporter.stage = ProgressStage.DONE_FAILURE
            } finally {
                isInstalling = false
                reporter.progress = 100
            }
        }
    }

    // 准备完成 启动模拟器
    if (state.isPrepareFinished) {
        Box(Modifier.fillMaxSize()) {
            Text("正在启动模拟器...", Modifier.align(Alignment.Center))
        }
    }
    // 加载中
    else if (state.loading) {
        Box(Modifier.fillMaxSize()) {
            Text("加载中...", Modifier.align(Alignment.Center))
        }
    }
    // 显示对应内容
    else {
        val lackPermissions = !(state.skipPermissions || state.unGrantedPermissions.isEmpty())
        var isRequestingPermission by remember { mutableStateOf(false) } // 禁止重复点击授予按钮
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (lackPermissions) {
                PermissionGrant(isRequestingPermission, state.unGrantedPermissions, { prepareVm.onSkipPermissions() }) { permission ->
                    if (isRequestingPermission) return@PermissionGrant
                    isRequestingPermission = true
                    Utils.Permissions.request(permission.permission) { isGranted ->
                        if (isGranted) prepareVm.onGrantedPermission(permission);
                        isRequestingPermission = false
                    }
                }
            } else if (state.noRootfs || state.forceNoRootfs || state.autoExtractedRootfsName != null) {
                // 自动提取成功后显示用户选择界面
                val extractedRootfsName = state.autoExtractedRootfsName
                if (extractedRootfsName != null) {
                    RootfsSelect(
                        getAvailableUsers = { rootfs: String -> ProotRootfs.getUserInfos(File(Consts.rootfsAllDir, rootfs)).map { it.name } },
                        settingVm::onChangeRootfsLoginUser, settingVm::onChangeRootfsName,
                        initReporter = reporter,
                        initRootfsName = extractedRootfsName,
                        initStage = ProgressStage.DONE_SUCCESS,
                        onAutoExtractStart = null, // 自动提取完成后不需要再次触发
                        onRootfsExtracted = { rootfsName ->
                            // 用户在选择界面点击"完成"后显示重启提示
                            showRestartDialog = true
                        },
                        onSetCurrentRootfs = { rootfsName ->
                            scope.launch {
                                // 1. 创建符号链接
                                Utils.Rootfs.makeCurrent(File(Consts.rootfsAllDir, rootfsName))
                                // 2. 停止终端（如果有终端在运行）
                                MainEmuActivity.instance.terminalViewModel.stopTerminal()
                                // 3. 刷新settingVm中的rootfs用户列表，确保读取用户时数据是最新的
                                settingVm.updateValuesWhenEnterSettings()
                            }
                        },
                        onCancel = if (state.forceNoRootfs) { { prepareVm.onCancelForceNoRootfs() } } else null,
                        defaultIsSetCurrent = true // 首次启动自动提取后默认勾选
                    )
                }
                // 首次启动自动提取时，显示进度
                else if (state.noRootfs && !state.forceNoRootfs && autoExtractStarted &&
                    (reporter.stage == ProgressStage.PROCESSING ||
                     reporter.stage == ProgressStage.DONE_SUCCESS)) {
                    RootfsAutoExtractProgress(reporter)
                }
                // 其他情况（新建容器、自动提取失败、手动选择）显示下载选择界面
                else if (state.forceNoRootfs || !autoExtractStarted || reporter.stage == ProgressStage.DONE_FAILURE) {
                    RootfsSelect(
                        getAvailableUsers = { rootfs: String -> ProotRootfs.getUserInfos(File(Consts.rootfsAllDir, rootfs)).map { it.name } },
                        settingVm::onChangeRootfsLoginUser, settingVm::onChangeRootfsName,
                        initReporter = reporter,
                        onAutoExtractStart = { autoExtractStarted = true },
                        onRootfsExtracted = { rootfsName -> prepareVm.onRootfsExtracted(rootfsName) },
                        onSetCurrentRootfs = { rootfsName ->
                            scope.launch {
                                // 1. 创建符号链接
                                Utils.Rootfs.makeCurrent(File(Consts.rootfsAllDir, rootfsName))
                                // 2. 停止终端（如果有终端在运行）
                                MainEmuActivity.instance.terminalViewModel.stopTerminal()
                                // 3. 刷新settingVm中的rootfs用户列表，确保读取用户时数据是最新的
                                settingVm.updateValuesWhenEnterSettings()
                            }
                        },
                        onCancel = if (state.forceNoRootfs) { { prepareVm.onCancelForceNoRootfs() } } else null,
                        defaultIsSetCurrent = !state.forceNoRootfs, // 首次启动默认勾选，新建容器默认不勾选
                        isInstalling = isInstalling,
                        catalog = ProotDistroCatalog.entries,
                        onInstallImage = { entry, customName ->
                            installProotDistroRootfs(entry.imageRef, customName)
                        },
                        onInstallCustomImage = { imageRef, customName ->
                            installProotDistroRootfs(imageRef, customName)
                        }
                    )
                } else {
                    // 等待自动提取完成
                    Box(Modifier.fillMaxSize()) {
                        Text("正在准备中...", Modifier.align(Alignment.Center))
                    }
                }
            }
        }
    }
}

/**
 * 显示用户应该授予的权限
 * @param onRequest 用户点击"授权按钮"的回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionGrant(
    isRequestingPermission: Boolean,
    permissions: List<RequiredPermissions>,
    onSkip: () -> Unit,
    onRequest: (RequiredPermissions) -> Unit
) {
    CenterAlignedTopAppBar(
        title = { Text("权限") },
        actions = { TextButton(onSkip) { Text("跳过") } }
    )
    Spacer(Modifier.height(16.dp))
    Column(Modifier.padding(16.dp)) {
        Text("为确保app正常运行，请授予以下权限。或者点击「跳过」，不授予权限。")
        Spacer(Modifier.height(32.dp))
        permissions.forEach { item ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.displayName, style = MaterialTheme.typography.bodyLarge)
                    if (item.description.isNotBlank())
                        Text(item.description, Modifier.padding(top = 8.dp))
                }
                Button({ onRequest(item) }, enabled = !isRequestingPermission) { Text("授予") }
            }
            if (permissions.last() != item)
                HorizontalDivider(Modifier.padding(24.dp))
        }
    }
}


/**
 * @param getAvailableUsers 传入rootfs名，返回该rootfs可选择的用户列表
 * @param onChangeUser 参考 [SettingViewModel.onChangeRootfsLoginUser]
 * @param onRootfsNameChange 参考 [SettingViewModel.onChangeRootfsName]
 * @param initReporter 可选的初始进度报告器
 * @param onAutoExtractStart 回调函数，当用户触发自动提取时调用
 * @param onRootfsExtracted rootfs提取/选择完成后的回调，用于更新状态
 * @param onSetCurrentRootfs 设置当前rootfs的回调
 * @param onCancel 取消/返回的回调，用于新建容器时返回
 * @param defaultIsSetCurrent "下次启动app运行该容器"选项的默认勾选状态，首次启动时默认true，新建容器时默认false
 * @param isInstalling 是否正在通过 proot-distro 安装
 * @param catalog proot-distro 发行版目录
 * @param onInstallImage 选中某个目录条目时的安装回调
 * @param onInstallCustomImage 用户输入自定义 image ref 时的安装回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RootfsSelect(
    getAvailableUsers: (String) -> List<String>,
    onChangeUser: suspend (String, String) -> Unit,
    onRootfsNameChange: suspend (String, String, FuncOnChangeAction) -> Unit,
    initStage: ProgressStage = ProgressStage.NOT_STARTED,
    initRootfsName: String = "",
    initReporter: SimpleTaskReporter? = null,
    onAutoExtractStart: (() -> Unit)? = null,
    onRootfsExtracted: ((String) -> Unit)? = null,
    onSetCurrentRootfs: (suspend (String) -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    defaultIsSetCurrent: Boolean = true,
    isInstalling: Boolean = false,
    catalog: List<ProotDistroEntry> = emptyList(),
    onInstallImage: ((ProotDistroEntry, String?) -> Unit)? = null,
    onInstallCustomImage: ((String, String?) -> Unit)? = null,
) {
    val TAG = "RootfsSelectScreen"
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val reporter = initReporter ?: rememberTaskReporter(msgTitle = "缺少Rootfs。请选择一个选项获取Rootfs。")
    var rootfsName by remember { mutableStateOf(initRootfsName) }
    var isSetCurrent by remember { mutableStateOf(defaultIsSetCurrent) }
    val dialogState = rememberConfirmDialogState()

    // 自定义 image ref 输入
    var customImageRef by remember { mutableStateOf("") }
    var customName by remember { mutableStateOf("") }
    var showCustomForm by remember { mutableStateOf(false) }

    // 安装完成时,从 reporter.msg 末尾提取 rootfs-xxx 或者 alias
    LaunchedEffect(reporter.stage, reporter.msg) {
        if (reporter.stage == ProgressStage.DONE_SUCCESS && rootfsName.isEmpty()) {
            // 取自: 安装成功！.. / 安装完成: <alias> ...
            val msg = reporter.msg
            val match = Regex("(?m)^安装完成:\\s*(\\S+)").find(msg)
                ?: Regex("rootfs-\\d+").find(msg)
            if (match != null) {
                rootfsName = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
                    ?: match.value
            }
        }
    }

    val readFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        Log.d(TAG, "RootfsSelectScreen: 尝试从contentResolver获取mimetype？${ctx.contentResolver.getType(uri)}")
        reporter.stage = ProgressStage.PROCESSING
        scope.launch {
            reporter.progress = 0
            reporter.msgTitle = "正在解压中，请等待完成。"
            reporter.msg = "日志："
            try {
                rootfsName = Utils.Rootfs.installRootfsArchive(ctx, uri, reporter).name
                reporter.msg("解压rootfs成功。", "解压成功，点击按钮将退出。请手动重启。\n（日志可点击展开查看）")
                reporter.stage = ProgressStage.DONE_SUCCESS
            } catch (e: Throwable) {
                e.printStackTrace()
                reporter.msg(
                    "解压rootfs过程中出现错误，结束。\n" + e.stackTraceToString(),
                    "解压失败。请点击按钮选择一个包含Rootfs的 .tar.xz 或 .tar.gz 压缩包。\n（日志可点击展开查看）"
                )
                reporter.stage = ProgressStage.DONE_FAILURE
            }
            reporter.progress = 100
        }
    }

    ConfirmDialog(dialogState)

    CenterAlignedTopAppBar(
        title = { Text("Rootfs") },
        navigationIcon = {
            if (onCancel != null) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        }
    )
    Spacer(Modifier.height(16.dp))
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 16.dp),
//        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            //显示标题和进度
            ProgressDisplay(reporter)

            // 需要安装/解压时显示选择按钮
            if (reporter.stage == ProgressStage.NOT_STARTED || reporter.stage == ProgressStage.DONE_FAILURE) {
                // 显示提示
                Text(
                    "请选择一种方式获取Rootfs：",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // 发行版目录按钮组
                if (catalog.isNotEmpty()) {
                    Text(
                        "通过 proot-distro 在线安装 (从 Docker Hub 拉镜像)",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    catalog.forEach { entry ->
                        Button(
                            onClick = { onInstallImage?.invoke(entry, null) },
                            enabled = !isInstalling,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Text(entry.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${entry.imageRef}  ·  ${entry.description}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }

                // 自定义 image ref 入口
                if (onInstallCustomImage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { showCustomForm = !showCustomForm },
                        enabled = !isInstalling,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (showCustomForm) "收起自定义 image ref" else "使用自定义 image ref")
                    }
                    if (showCustomForm) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = customImageRef,
                            onValueChange = { customImageRef = it },
                            label = { Text("image ref") },
                            placeholder = { Text("例如 ubuntu:24.04 或 kalilinux/kali-rolling") },
                            enabled = !isInstalling,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = customName,
                            onValueChange = { customName = it.filter { ch -> ch.isLetterOrDigit() || ch in "._-" } },
                            label = { Text("容器名 (可选)") },
                            placeholder = { Text("留空则从 image ref 派生") },
                            enabled = !isInstalling,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                val ref = customImageRef.trim()
                                if (ref.isNotEmpty()) {
                                    onInstallCustomImage(ref, customName.takeIf { it.isNotBlank() })
                                }
                            },
                            enabled = !isInstalling && customImageRef.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("下载并安装") }
                    }
                }

                // 从 App 内置 assets 提取(原来的"自动提取"按钮)
                if (onAutoExtractStart != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(
                        "或使用 App 内置资源 (如果有):",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    Button(
                        onClick = onAutoExtractStart,
                        enabled = !isInstalling,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("从 App 内置提取") }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    "或手动选择已有的 Rootfs 压缩包:",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                Button(
                    onClick = { readFileLauncher.launch(arrayOf("application/x-xz", "application/gzip", "*/*")) },
                    enabled = !isInstalling,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("手动选择压缩包") }
            }
            // 安装/解压成功后显示完成按钮
            else if (reporter.stage == ProgressStage.DONE_SUCCESS) {
                Button({
                    scope.launch {
                        if (isSetCurrent && onSetCurrentRootfs != null) {
                            // 设置当前rootfs（不调用onChangeRootfsSelect避免触发finish）
                            onSetCurrentRootfs(rootfsName)
                        } else if (isSetCurrent) {
                            // 兼容旧逻辑：如果没有提供 onSetCurrentRootfs，使用原来的方式
                            MainEmuActivity.instance.settingViewModel.onChangeRootfsSelect(rootfsName)
                        }
                        // 调用回调，让调用者处理后续逻辑（如显示重启提示）
                        onRootfsExtracted?.invoke(rootfsName)
                    }
                }) { Text("完成") }
            }

            // 安装/解压成功后,显示重命名 / 用户 / 下次启动 等选项
            if (reporter.stage == ProgressStage.DONE_SUCCESS && rootfsName.isNotEmpty()) {
                Log.e(TAG, "RootfsSelectScreen: 安装完成后进入这里检查可登陆用户列表。平时不会进入吧？")
                HorizontalDivider(Modifier.padding(16.dp), 2.dp)
                Text("退出之前，您还可以编辑以下内容")

                var rootfsAlias by remember { mutableStateOf(Utils.Rootfs.getAlias(File(Consts.rootfsAllDir, rootfsName))) }
                GeneralRootfsSelect_RootfsName(
                    rootfsName = rootfsName,
                    rootfsAlias = rootfsAlias,
                    isCurr = false,
                    dialogState = dialogState,
                    onAliasChange = { _, newAlias ->
                        Utils.Rootfs.setAlias(File(Consts.rootfsAllDir, rootfsName), newAlias)
                        rootfsAlias = newAlias
                    }
                    // 不传入 onRootfsNameChange，只修改别名，不修改文件夹名
                )

                val userList = getAvailableUsers(rootfsName)
                var selectedUserName by remember { mutableStateOf(userList.find { it != "root" } ?: "root") }
                GeneralRootfsSelect_LoginUserSelect(rootfsName, selectedUserName, userList) { rootfsName, newUserName ->
                    selectedUserName = newUserName
                    // 使用 runBlocking 确保保存完成后再继续
                    runBlocking {
                        onChangeUser(rootfsName, newUserName)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("下次启动app运行该容器")
                    Checkbox(isSetCurrent, { isSetCurrent = it })
                }
            }
        }
    }
}



/**
 * 自动提取Rootfs时的进度显示组件
 */
@Composable
private fun RootfsAutoExtractProgress(reporter: SimpleTaskReporter) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 显示标题和进度
        ProgressDisplay(reporter)

        // 解压成功后显示提示
        if (reporter.stage == ProgressStage.DONE_SUCCESS) {
            Spacer(modifier = Modifier.height(16.dp))
            Text("Rootfs提取成功！正在设置启动命令...", style = MaterialTheme.typography.bodyLarge)
        }

        // 解压失败后显示提示
        if (reporter.stage == ProgressStage.DONE_FAILURE) {
            Spacer(modifier = Modifier.height(16.dp))
            Text("提取失败，请手动选择rootfs压缩包", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Preview
@Composable
fun PrepareScreenPreview(
    initLackPermissions: Boolean = true,
) {
    val isLoading = false
    val noRootfs = true
    val forceNoRootfs = false
    var unGrantedPermissions by remember {
        mutableStateOf(
            listOf<RequiredPermissions>(
                RequiredPermissions.Storage,
                RequiredPermissions.Notification
            )
        )
    }
    var skipPermissions by remember { mutableStateOf(false) }
    // 加载中
    if (isLoading) {
        Box(Modifier.fillMaxSize()) {
            Text("加载中...", Modifier.align(Alignment.Center))
        }
    }
    // 显示对应内容
    else {
        val lackPermissions = !(skipPermissions || unGrantedPermissions.isEmpty()) && initLackPermissions
        var isRequestingPermission by remember { mutableStateOf(false) } // 禁止重复点击授予按钮
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (lackPermissions) {
                PermissionGrant(isRequestingPermission, unGrantedPermissions, { skipPermissions = true }) { permission ->
                    if (!isRequestingPermission) {
                        isRequestingPermission = true
                        CoroutineScope(Dispatchers.Default).launch {
                            delay(1000)
                            unGrantedPermissions = unGrantedPermissions - permission
                            isRequestingPermission = false
                        }
                    }
                }
            } else if (noRootfs || forceNoRootfs) {
                val stage = ProgressStage.DONE_SUCCESS
                RootfsSelect({ listOf("iuser", "root") }, { _, _ -> }, { _, _, _ -> "" }, stage, "rootfs-1")
            }
        }
    }
}

//@Preview(widthDp = 300, heightDp = 600)
@Composable
private fun PrepareStageScreenFinishPreview() {
    val dialogState = rememberConfirmDialogState()
    ConfirmDialog(dialogState)
    ElevatedCard(Modifier.padding(16.dp)) {
        Column(
            Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val rootfsName = "rootfs-1"
            Text("退出之前，您还可以编辑以下内容。。")

            Spacer(Modifier.height(16.dp))
            GeneralRootfsSelect_RootfsName(
                rootfsName = "rootfs-1",
                rootfsAlias = "rootfs-1",
                isCurr = false,
                dialogState = dialogState,
                onAliasChange = { _, _ -> },
                onRootfsNameChange = { _, _ -> }
            )

            val userList = listOf("root", "aid_u0_a287", "iuser").filter { !it.startsWith("aid_") }.sorted()
            val nonRootUser = userList.find { it != "root" }
            if (nonRootUser != null) {
                var userName by remember { mutableStateOf(nonRootUser) }
                Spacer(Modifier.height(16.dp))
                GeneralRootfsSelect_LoginUserSelect(rootfsName, userName, userList) { _, newUserName -> userName = newUserName }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("下次启动app运行该容器")
                Checkbox(true, {})
            }
        }
    }
}
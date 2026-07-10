package org.github.ewt45.winemulator.ui

import a.io.github.ewt45.winemulator.R
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.github.ewt45.winemulator.Consts
import org.github.ewt45.winemulator.MainEmuActivity
import org.github.ewt45.winemulator.Utils
import org.github.ewt45.winemulator.Utils.Ui.snapToNearestEdgeHalfway
import java.io.File
import org.github.ewt45.winemulator.ui.components.ComposeSpinner
import org.github.ewt45.winemulator.ui.components.ConfirmDialog
import org.github.ewt45.winemulator.ui.components.rememberConfirmDialogState
import org.github.ewt45.winemulator.viewmodel.RootfsContainer
import org.github.ewt45.winemulator.viewmodel.SettingViewModel

/**
 * HomeScreen —— LinBox 的主界面。
 *
 * 顶栏左侧显示 "LinBox / ANDROID" 标题；右上角依次显示：
 *   1. 主题切换 (Palette 图标)
 *   2. 显示终端 (Terminal 图标)
 *   3. 设置 (Settings 图标)
 *   4. 最小化按钮 (原项目已有的 compose-view 缩成小图标)
 *
 * 中间：方格显示所有 rootfs（容器）。点击卡片 = 进入该容器 X11 界面。
 * 卡片右下三个点 = 相关菜单（设置自动执行命令 / 重启容器 / 关闭容器）。
 * 右下角 FAB ➕ = 添加容器（跳转 AddContainerScreen）。
 *
 * @param onLaunchContainer 点击某个容器卡片。已切到对应 rootfs 并准备好启动。
 * @param onAddContainer 点击右下角加号。
 * @param onShowTerminal 点击右上角"显示终端"图标。
 * @param onOpenSettings 点击右上角"设置"图标。
 * @param onMinimize 点击右上角最小化按钮（缩成小图标，原项目行为）。
 * @param onBack 用于子屏幕返回主页的回调（仅子页面需要）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    settingVm: SettingViewModel,
    themeMode: Int,
    containers: List<RootfsContainer>,
    onChangeThemeMode: (Int) -> Unit,
    onLaunchContainer: (RootfsContainer) -> Unit,
    onAddContainer: () -> Unit,
    onShowTerminal: () -> Unit,
    onOpenSettings: () -> Unit,
    onMinimize: () -> Unit,
    /** 三个点菜单项被点击：0=设置自动执行命令，1=重启容器，2=关闭容器 */
    onContainerAction: (RootfsContainer, Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val activity = androidx.activity.compose.LocalActivity.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val miniIconPx = (org.github.ewt45.winemulator.Consts.Ui.minimizedIconSize * density.density).toInt()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            HomeTopBar(
                themeMode = themeMode,
                onChangeThemeMode = onChangeThemeMode,
                onShowTerminal = onShowTerminal,
                onOpenSettings = onOpenSettings,
                onMinimize = onMinimize,
            )
        },
        floatingActionButton = {
            AddContainerFab(onClick = onAddContainer)
        },
    ) { innerPadding ->
        ContainerGrid(
            containers = containers,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            onContainerClick = onLaunchContainer,
            onContainerAction = onContainerAction,
        )
    }
}

/**
 * HomeScreen 顶部 TopBar。左：标题 LinBox / ANDROID。右：4 个图标按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTopBar(
    themeMode: Int,
    onChangeThemeMode: (Int) -> Unit,
    onShowTerminal: () -> Unit,
    onOpenSettings: () -> Unit,
    onMinimize: () -> Unit,
) {
    var themeMenuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Column {
                Text(
                    text = "LinBox",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "ANDROID",
                    style = MaterialTheme.typography.labelSmall,
                    fontStyle = FontStyle.Italic,
                )
            }
        },
        actions = {
            // 主题切换
            Box {
                IconButton(onClick = { themeMenuOpen = true }) {
                    Icon(Icons.Filled.Palette, contentDescription = "主题切换")
                }
                DropdownMenu(
                    expanded = themeMenuOpen,
                    onDismissRequest = { themeMenuOpen = false },
                ) {
                    val options = listOf(0 to "跟随系统", 1 to "暗色主题", 2 to "亮色主题")
                    options.forEach { (mode, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                onChangeThemeMode(mode)
                                themeMenuOpen = false
                            },
                        )
                    }
                }
            }
            // 显示终端
            IconButton(onClick = onShowTerminal) {
                Icon(Icons.Filled.Terminal, contentDescription = "显示终端")
            }
            // 设置
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "设置")
            }
            // 最小化按钮（原项目已有逻辑：把 compose 视图缩成小图标）
            IconButton(onClick = onMinimize) {
                Icon(painterResource(R.drawable.ic_hide), contentDescription = "最小化")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

/**
 * 右下角添加容器的浮动按钮。
 */
@Composable
fun AddContainerFab(onClick: () -> Unit) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
        text = { Text("添加容器") },
        elevation = FloatingActionButtonDefaults.elevation(),
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )
}

/**
 * 容器方格列表。卡片显示容器别名，右下角三个点。
 */
@Composable
fun ContainerGrid(
    containers: List<RootfsContainer>,
    modifier: Modifier = Modifier,
    onContainerClick: (RootfsContainer) -> Unit,
    onContainerAction: (RootfsContainer, Int) -> Unit,
) {
    if (containers.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "暂无容器\n点击右下角 ➕ 添加",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    BoxWithConstraints(modifier = modifier) {
        // 自适应列数：宽度 >= 600dp 时 3 列，否则 2 列
        val columns = if (maxWidth >= 600.dp) 3 else 2
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(containers, key = { it.name }) { c ->
                ContainerCard(
                    container = c,
                    onClick = { onContainerClick(c) },
                    onAction = { action -> onContainerAction(c, action) },
                )
            }
        }
    }
}

/**
 * 单个容器的卡片。点击进入容器，三个点弹菜单。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerCard(
    container: RootfsContainer,
    onClick: () -> Unit,
    onAction: (Int) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // 用户自定义图标加载为 Bitmap（若有）
    val customBitmap = remember(container.iconFile?.absolutePath) {
        container.iconFile?.takeIf { it.exists() }?.let { f ->
            runCatching { android.graphics.BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
        }
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val pickImageLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val rootfsDir = java.io.File(org.github.ewt45.winemulator.Consts.rootfsAllDir, container.name)
                    val tmp = java.io.File(ctx.cacheDir, "icon-${System.currentTimeMillis()}.bin")
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        java.io.FileOutputStream(tmp).use { output -> input.copyTo(output) }
                    }
                    org.github.ewt45.winemulator.Utils.Rootfs.setContainerIcon(rootfsDir, tmp)
                    tmp.delete()
                }
                onAction(5) // 通知 Home 刷新
            }
        }
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = if (container.isCurrent) 4.dp else 1.dp,
        color = if (container.isCurrent)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 自定义图标背景（若有），蒙层 + 别名
            if (customBitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = customBitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // 在图标上面叠一层半透明黑色蒙层，保证别名文字可读
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f)),
                )
            }
            // 主显示：容器别名（按图上 "debian 12" 风格大字体）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.BottomStart,
            ) {
                Text(
                    text = container.alias,
                    style = MaterialTheme.typography.titleLarge,
                    color = androidx.compose.ui.graphics.Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 右下角三个点
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp),
            ) {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "更多",
                        tint = androidx.compose.ui.graphics.Color.White,
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("设置自动执行命令") },
                        onClick = {
                            menuOpen = false
                            onAction(0)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("设置自定义图标") },
                        onClick = {
                            menuOpen = false
                            pickImageLauncher.launch("image/*")
                        },
                    )
                    if (container.iconFile != null) {
                        DropdownMenuItem(
                            text = { Text("移除自定义图标") },
                            onClick = {
                                menuOpen = false
                                onAction(4)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("重启容器") },
                        onClick = {
                            menuOpen = false
                            onAction(1)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("关闭容器") },
                        onClick = {
                            menuOpen = false
                            onAction(2)
                        },
                    )
                }
            }
        }
    }
}

/**
 * 容器三个点菜单的"动作分发"已由 HomeScreen 的 onContainerAction 回调负责。
 * 这里保留为空函数仅为留作后续抽取使用。
 */
@Suppress("unused")
fun handleContainerAction(action: Int, container: RootfsContainer, scope: kotlinx.coroutines.CoroutineScope) {
    val activity = MainEmuActivity.instance
    scope.launch {
        when (action) {
            0 -> { /* 设置自动执行命令，由调用方导航到 ContainerAutoCmdScreen */ }
            1 -> activity.restartContainer()
            2 -> activity.shutdownContainer()
        }
    }
}

/**
 * 通用子页顶栏：标题 + 左上角返回箭头。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildScreenTopBar(
    title: String,
    onBack: () -> Unit,
) {
    CenterAlignedTopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

/**
 * 主题切换全屏页面：与设置里的主题设置一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeScreen(
    themeMode: Int,
    onChangeThemeMode: (Int) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = { ChildScreenTopBar(title = "主题切换", onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "选择界面主题。0 = 跟随系统，1 = 暗色主题，2 = 亮色主题。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val options = listOf(0 to "跟随系统", 1 to "暗色主题", 2 to "亮色主题")
            val currentLabel = options.firstOrNull { it.first == themeMode }?.second ?: "暗色主题"
            ComposeSpinner(
                currKey = themeMode,
                keyList = options.map { it.first },
                nameList = options.map { it.second },
                modifier = Modifier.fillMaxWidth(),
                label = "主题模式",
                onSelectedChange = { _, newMode -> onChangeThemeMode(newMode) },
            )
            // 冗余 fallback：让用户即便 spinner 没显示也能看到当前选择
            Text("当前：$currentLabel")
        }
    }
}

/**
 * "显示终端"全屏页面 —— 与 Destination.Terminal 共用同一个屏幕。
 * 我们直接由调用方传入 ProotTerminalScreen，避免重复。
 */
@Composable
fun ShowTerminalScreen(onBack: () -> Unit, terminalContent: @Composable () -> Unit) {
    Scaffold(
        topBar = { ChildScreenTopBar(title = "终端", onBack = onBack) },
    ) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)) {
            terminalContent()
        }
    }
}

/**
 * 设置页（精简版）—— 与 Destination.Settings 共用同一个屏幕。
 */
@Composable
fun SettingsScreenWrapper(onBack: () -> Unit, settingsContent: @Composable () -> Unit) {
    Scaffold(
        topBar = { ChildScreenTopBar(title = "设置", onBack = onBack) },
    ) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)) {
            settingsContent()
        }
    }
}

/**
 * 容器自动执行命令编辑页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerAutoCmdScreen(
    rootfsName: String,
    settingVm: SettingViewModel,
    onBack: () -> Unit,
) {
    val dir = remember(rootfsName) { File(Consts.rootfsAllDir, rootfsName) }
    val alias = remember(rootfsName) { Utils.Rootfs.getAlias(dir) }
    var cmd by remember(rootfsName) { mutableStateOf(settingVm.getContainerStartupCmd(rootfsName)) }
    var tempCmd by remember(rootfsName) { mutableStateOf(cmd) }
    val dialogState = rememberConfirmDialogState()

    Scaffold(
        topBar = { ChildScreenTopBar(title = "自动执行命令", onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "为容器「$alias」设置启动时自动执行的命令。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "留空则使用全局「PRoot参数」中的启动命令。多个命令可用换行或 ; 分隔。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = tempCmd,
                onValueChange = { tempCmd = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                label = { Text("启动命令（每行一条）") },
                supportingText = { Text("存储于 <rootfs>/.emuconf/start.sh") },
                maxLines = 10,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = {
                    tempCmd = ""
                }) { Text("清空") }
                TextButton(onClick = {
                    settingVm.setContainerStartupCmd(rootfsName, tempCmd)
                    cmd = tempCmd
                    dialogState.showConfirm("已保存。重启容器后生效。")
                }) { Text("保存") }
            }
        }
    }
    ConfirmDialog(dialogState)
}

/**
 * HomeScreen 预览。
 */
@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
fun HomeScreenPreview() {
    val sample = listOf(
        RootfsContainer("rootfs-1", "debian 12", "linbox", isCurrent = true),
        RootfsContainer("rootfs-2", "ubuntu 24.04", "", isCurrent = false),
        RootfsContainer("rootfs-3", "alpine", "", isCurrent = false),
    )
    HomeScreen(
        settingVm = SettingViewModel(),
        themeMode = 1,
        containers = sample,
        onChangeThemeMode = {},
        onLaunchContainer = {},
        onAddContainer = {},
        onShowTerminal = {},
        onOpenSettings = {},
        onMinimize = {},
        onContainerAction = { _, _ -> },
    )
}
/**
 * 实际把 compose_view 缩成小图标的逻辑。供 [doMinimize] 调用。
 */
private fun applyMinimize(view: View, miniIconPx: Int) {
    view.apply {
        val lp = layoutParams as ViewGroup.MarginLayoutParams
        lp.height = miniIconPx
        lp.width = miniIconPx
        lp.leftMargin = 0
        lp.topMargin = 100
        lp.rightMargin = 0
        lp.bottomMargin = 0
        requestLayout()
        post { snapToNearestEdgeHalfway() }
    }
}

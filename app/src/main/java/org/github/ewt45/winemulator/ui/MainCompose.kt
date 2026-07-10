package org.github.ewt45.winemulator.ui

import a.io.github.ewt45.winemulator.R
import android.content.Context
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.MarginLayoutParams
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navigation
import kotlinx.coroutines.launch
import org.github.ewt45.winemulator.Consts
import org.github.ewt45.winemulator.MainEmuActivity
import org.github.ewt45.winemulator.Utils.Ui.snapToNearestEdgeHalfway
import org.github.ewt45.winemulator.ui.theme.MainTheme
import org.github.ewt45.winemulator.viewmodel.DialogType
import org.github.ewt45.winemulator.viewmodel.MainUiState
import org.github.ewt45.winemulator.viewmodel.MainViewModel
import org.github.ewt45.winemulator.viewmodel.PrepareViewModel
import org.github.ewt45.winemulator.viewmodel.RootfsContainer
import org.github.ewt45.winemulator.viewmodel.SettingViewModel
import org.github.ewt45.winemulator.viewmodel.TerminalViewModel
import java.io.File


/**
 * 新版主屏幕入口。新设计：
 * - HomeScreen (LinBox 主界面) 为准备完成后的常驻页。
 * - 点容器卡片 → RouteX11
 * - 点右下角 ➕ → RouteAddContainer
 * - 点右上角主题/终端/设置 → 各自独立页面（带返回箭头）
 * - 点三个点 → 三个菜单项
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    tx11Content: (Context) -> View,
    startDest: Destination,
    mainVm: MainViewModel,
    terminalVm: TerminalViewModel,
    settingVm: SettingViewModel,
    prepareVm: PrepareViewModel,
) {
    val TAG = "MainScreen"
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    val uiState by mainVm.uiState.collectAsState()
    val prepareUiState by prepareVm.uiState.collectAsStateWithLifecycle()
    val themeState by settingVm.themeState.collectAsState()

    // 用于刷新 Home 上的容器列表（点 ➕ 添加完成后回到 Home 时也要刷新）
    var homeRefreshTick by remember { mutableStateOf(0) }
    val containers: List<RootfsContainer> = remember(homeRefreshTick) { settingVm.listRootfsContainers() }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currDestination = appbarDestList.find { navBackStackEntry?.destination?.hasRoute(it.route::class) == true } ?: startDest

    // 跳转到目的地。使用 launchSingleTop 避免同路由重叠。
    val navigateTo: (Destination) -> Unit = { dest -> navController.navigate(dest.route) { launchSingleTop = true } }

    // acitivty通过viewmodel修改目的地时，触发跳转
    LaunchedEffect(Unit) {
        mainVm.navigateToEvent.collect { dest -> navigateTo(dest) }
    }

    // 首次启动：权限未授予完时跳 PermissionScreen 申请权限；都通过/跳过后进 Home。
    // 用户主动添加 rootfs（forceNoRootfs=true）时仍然走 PrepareScreen 走添加流程。
    LaunchedEffect(prepareUiState, prepareVm.uiState.value.forceNoRootfs) {
        if (prepareVm.uiState.value.forceNoRootfs && currDestination != Destination.Prepare) {
            navigateTo(Destination.Prepare)
            return@LaunchedEffect
        }
        // 权限未授予完且没跳过：跳权限页
        val s = prepareUiState
        val needPermission = !s.skipPermissions && s.unGrantedPermissions.isNotEmpty()
        if (needPermission && currDestination != Destination.Permission) {
            navigateTo(Destination.Permission)
        }
        // 权限都已授予/跳过 + 没有 forceNoRootfs：留在 Home（启动后就是 Home）
    }

    // 监听 Home 返回事件（从添加容器页回到 Home 时刷新列表）
    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.collect { entry ->
            // 从 AddContainer / AutoCmd 返回 Home 时，触发一次刷新
            if (entry.destination.hasRoute(RouteHome::class)) {
                homeRefreshTick++
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
            contentAlignment = Alignment.Center,
        ) {
            NavHost(
                navController, startDest.route,
                enterTransition = { scaleIn() },
                exitTransition = { scaleOut() },
            ) {
                composable<RoutePrepare> {
                    PrepareScreen(prepareVm, settingVm) {
                        // 准备完成后直接跳转到 Home
                        navController.navigate(Destination.Home.route) {
                            popUpTo(Destination.Prepare.route) { inclusive = true }
                        }
                    }
                }

                composable<RoutePermission> {
                    PermissionScreen(
                        prepareVm = prepareVm,
                        onFinish = {
                            navController.navigate(Destination.Home.route) {
                                popUpTo(Destination.Permission.route) { inclusive = true }
                            }
                        },
                    )
                }

                // Home —— 新主界面
                composable<RouteHome> {
                    HomeScreen(
                        settingVm = settingVm,
                        themeMode = themeState,
                        containers = containers,
                        onChangeThemeMode = settingVm::onChangeThemeMode,
                        onLaunchContainer = { c ->
                            scope.launch {
                                val dir = File(Consts.rootfsAllDir, c.name)
                                MainEmuActivity.instance.switchToRootfsAndStart(dir) {
                                    navController.navigate(Destination.X11.route)
                                }
                            }
                        },
                        onAddContainer = {
                            navController.navigate(Destination.AddContainer.route)
                        },
                        onShowTerminal = {
                            navController.navigate(Destination.Terminal.route)
                        },
                        onOpenSettings = {
                            navController.navigate(Destination.Settings.route)
                        },
                        onContainerAction = { c, action ->
                            when (action) {
                                0 -> navController.navigate(Destination.ContainerAutoCmd(c.name).route)
                                1 -> scope.launch { MainEmuActivity.instance.restartContainer() }
                                2 -> scope.launch { MainEmuActivity.instance.shutdownContainer() }
                                4 -> {
                                    // 移除自定义图标
                                    settingVm.clearContainerIcon(c.name)
                                    homeRefreshTick++
                                }
                                5 -> {
                                    // 装完图后，Home 列表需要重新读取 iconFile。
                                    homeRefreshTick++
                                }
                            }
                        },
                    )
                }

                composable<RouteX11> { X11Screen(tx11Content, navigateTo, settingVm = settingVm) }

                composable<RouteAddContainer> {
                    AddContainerScreen(
                        settingVm = settingVm,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable<RouteTheme> {
                    ThemeScreen(
                        themeMode = themeState,
                        onChangeThemeMode = settingVm::onChangeThemeMode,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable<RouteShowTerminal> {
                    ShowTerminalScreen(
                        onBack = { navController.popBackStack() },
                        terminalContent = { ProotTerminalScreen(terminalVm) },
                    )
                }

                composable<RouteSettings> {
                    SettingsScreenWrapper(
                        onBack = { navController.popBackStack() },
                        settingsContent = {
                            SettingScreen(settingVm, terminalVm, prepareVm, navigateTo)
                        },
                    )
                }

                composable<RouteContainerAutoCmd> { entry ->
                    val args = entry.arguments
                    val rootfsName = args?.getString("rootfsName")
                        ?: args?.getString("arg0")  // 兼容以不同 key 保存的情况
                        ?: ""
                    ContainerAutoCmdScreen(
                        rootfsName = rootfsName,
                        settingVm = settingVm,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }

        MainDialog(uiState) { mainVm.closeConfirmDialog(it) }
    }
}

@Composable
private fun MainDialog(uiState: MainUiState, onClose: (Boolean) -> Unit) {
    val dialogType = uiState.dialogType
    val isConfirm = uiState.dialogType == DialogType.CONFIRM
    val isBlock = uiState.dialogType == DialogType.BLOCK
    if (dialogType != DialogType.NONE) {
        AlertDialog(
            onDismissRequest = {}, //阻止点击外部区域关闭
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SelectionContainer {
                        Text(uiState.msg, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.verticalScroll(rememberScrollState()))
                    }
                    if (isBlock) {
                        Spacer(modifier = Modifier.height(16.dp))
                        CircularProgressIndicator()
                    }
                }
            },
            confirmButton = {
                if (isConfirm) {
                    TextButton(onClick = { onClose(true) }) { Text(stringResource(android.R.string.ok)) }
                }
            },
            dismissButton = {
                if (isConfirm) {
                    TextButton(onClick = { onClose(false) }) { Text(stringResource(android.R.string.cancel)) }
                }
            }
        )
    }
}


/**
 * 把 compose_view 缩成屏幕上的一个小图标。
 * 来自原 [MinimizeButton]，现在由 HomeScreen.kt 内部直接实现（用 doMinimize lambda），本文件不再保留包装函数。
 */


/**
 * 顶部的AppBar (legacy，仅保留给极少数路径)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MyTopAppBar(
    currDestination: Destination?,
    setDestination: (Destination) -> Unit,
) {
    val selectIdx = currDestination?.let { appbarDestList.indexOf(it) }
    if (selectIdx != null && selectIdx != -1) {
        androidx.compose.material3.TopAppBar(
            title = {
                androidx.compose.material3.PrimaryScrollableTabRow(selectIdx, divider = {}) {
                    appbarDestList.forEachIndexed { idx, dest ->
                        androidx.compose.material3.Tab(
                            selected = selectIdx == idx,
                            onClick = { setDestination(dest) },
                            text = {
                                Text(
                                    text = dest.title,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                        )
                    }
                }
            },
            actions = {
                IconButton({ setDestination(Destination.X11) }) { Icon(painterResource(R.drawable.ic_hide), null) }
            },
        )
    }
}

/** 按钮。点击可将compose部分的视图展开或折叠。
 * 可拖动: 由于x11的acitivity是View视图，所以拖动还是要用view的layoutParam实现。
 */
@Composable
private fun MinimizeButton(
    minimize: Boolean,
    onClick: () -> Unit,
) {
    val TAG = "MinimizeButton"
    val activity = LocalActivity.current
    val miniIconPx = (Consts.Ui.minimizedIconSize * LocalDensity.current.density).toInt()

    //最小化时颜色稍微变化一下吧，否则不容易看到
    val colorSurface = MaterialTheme.colorScheme.surfaceContainerHigh
    val colorContent = MaterialTheme.colorScheme.onSurface
    val colors =
        if (!minimize) IconButtonDefaults.iconButtonColors()
        else IconButtonColors(colorSurface, colorContent, colorSurface, colorContent)

    // 记住最小化时的位置。全屏后再次最小化时恢复到上一次位置而非默认位置
    val margin = remember { mutableListOf(0, 100) }

    IconButton(
        onClick = {
            val view = activity?.findViewById<View>(R.id.compose_view) ?: return@IconButton
            val nextValue = !minimize
            view.apply {
                val lp = layoutParams as MarginLayoutParams
                lp.height = if (nextValue) miniIconPx else MATCH_PARENT
                lp.width = if (nextValue) miniIconPx else MATCH_PARENT
                lp.leftMargin = if (nextValue) margin[0] else 0
                lp.topMargin = if (nextValue) margin[1] else 0
                lp.rightMargin = 0
                lp.bottomMargin = 0
                requestLayout()
                if (nextValue)
                    view.post { view.snapToNearestEdgeHalfway() }
            }
            onClick()
        },
        modifier = Modifier
            .size(Consts.Ui.minimizedIconSize.dp)
            .pointerInput(minimize) {
                if (!minimize)
                    return@pointerInput
                val view = activity?.findViewById<View>(R.id.compose_view) ?: return@pointerInput
                detectDragGestures(
                    onDragEnd = { view.snapToNearestEdgeHalfway() }
                ) { change, dragAmount ->
                    change.consume()
                    val lp = view.layoutParams as MarginLayoutParams
                    lp.leftMargin += dragAmount.x.toInt()
                    lp.topMargin += dragAmount.y.toInt()
                    margin[0] = lp.leftMargin
                    margin[1] = lp.topMargin
                    view.requestLayout()
                }
            },
        colors = colors
    ) {
        Icon(
            painter = painterResource(if (minimize) R.drawable.ic_fullscreen else R.drawable.ic_hide),
            contentDescription = "全屏/最小化",
        )
    }
}

/**
 * 按钮，点击可显示设置界面
 */
@Composable
fun SettingButton(show: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        if (!show) Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = "设置",
        )
        else Icon(
            painter = painterResource(R.drawable.ic_layout),
            contentDescription = "主屏幕",
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
private fun MainScreenPreview() {
    val startDest = Destination.Home
    val navController = rememberNavController()
    val navBackEntry by navController.currentBackStackEntryAsState()
    val currDestination = appbarDestList.find { navBackEntry?.destination?.hasRoute(it.route::class) == true }
    MainTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding()),
                contentAlignment = Alignment.Center,
            ) {
                NavHost(
                    navController, startDest.route,
                    enterTransition = { scaleIn() },
                    exitTransition = { scaleOut() },
                ) {
                    composable<RoutePrepare> { PrepareScreenPreview() }
                    composable<RouteX11> { X11ScreenPreview() }
                    composable<RouteHome> { HomeScreenPreview() }
                }
            }
        }
    }
}

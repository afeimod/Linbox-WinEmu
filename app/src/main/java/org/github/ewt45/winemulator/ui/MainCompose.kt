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
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navigation
import org.github.ewt45.winemulator.Consts
import org.github.ewt45.winemulator.MainEmuActivity
import org.github.ewt45.winemulator.Utils.Ui.snapToNearestEdgeHalfway
import org.github.ewt45.winemulator.ui.theme.MainTheme
import org.github.ewt45.winemulator.viewmodel.DialogType
import org.github.ewt45.winemulator.viewmodel.MainUiState
import org.github.ewt45.winemulator.viewmodel.MainViewModel
import org.github.ewt45.winemulator.viewmodel.PrepareViewModel
import org.github.ewt45.winemulator.viewmodel.SettingViewModel
import org.github.ewt45.winemulator.viewmodel.TerminalViewModel


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

    val uiState by mainVm.uiState.collectAsState()
    val prepareUiState by prepareVm.uiState.collectAsStateWithLifecycle()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currDestination = appbarDestList.find { navBackStackEntry?.destination?.hasRoute(it.route::class) == true } ?: startDest

    // 跳转到目的地。如果返回栈中有该目的地，则弹出到这个位置然后再跳转。
    val navigateTo: (Destination) -> Unit = { navController.navigate(it.route) { popUpTo(it.route) { inclusive = true } } }

    // acitivty通过viewmodel修改目的地时，触发跳转
    LaunchedEffect(Unit) {
        mainVm.navigateToEvent.collect { dest -> navigateTo(dest) }
    }
    // 启动时自动跳转到X11界面（如果设置了自动启动）
    LaunchedEffect(prepareUiState.isPrepareFinished) {
        if (prepareUiState.isPrepareFinished && startDest == Destination.X11) {
            // 如果准备已完成且目标是X11，自动导航到X11
            navigateTo(Destination.X11)
        }
    }
    // 开头或中途 需要进入准备屏幕时
    LaunchedEffect(prepareUiState.isPrepareFinished) {
        if (!prepareUiState.isPrepareFinished && currDestination != Destination.Prepare)
            navigateTo(Destination.Prepare)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            MyTopAppBar(currDestination, { navigateTo(it) })
        },
    ) { innerPadding ->
        // FIXME tx11已经处理键盘高度变更了，这里应该不用innerPadding 否则会有空白
        //  但是使用了scaffold的topbar之后需要应用顶部padding
//            val ignoreSystemInsets = Modifier.padding(innerPadding)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
            contentAlignment = Alignment.Center,
        ) {
//            Column(Modifier.fillMaxHeight().widthIn(max = 600.dp)) { }
            NavHost(
                navController, startDest.route,
                enterTransition = { scaleIn() },
                exitTransition = { scaleOut() },
            ) {
                composable<RoutePrepare> {
                    PrepareScreen(prepareVm, settingVm) {
                        MainEmuActivity.instance.startEmu()
                        // 准备完成后直接跳转到X11界面
                        navController.navigate(Destination.X11.route) { popUpTo(Destination.Prepare.route) { inclusive = true } }
                    }
                }
                composable<RouteX11> { X11Screen(tx11Content, navigateTo, settingVm = settingVm) }
                navigation<RouteExceptX11>(startDestination = RouteTerminal) {
                    composable<RouteTerminal> { ProotTerminalScreen(terminalVm) }
//                        composable<NavDest.Terminal> { TerminalScreen() }
                    composable<RouteSettings> { SettingScreen(settingVm, terminalVm, prepareVm, navigateTo) }
                }
            }
        }

        MainDialog(uiState) { mainVm.closeConfirmDialog(it) }

    }
}

@Composable
private fun MainDialog(uiState: MainUiState, onClose: (Boolean) -> Unit) {
    // 对话框
    val dialogType = uiState.dialogType
    val isConfirm = uiState.dialogType == DialogType.CONFIRM
    val isBlock = uiState.dialogType == DialogType.BLOCK
    if (dialogType != DialogType.NONE) {
        AlertDialog(
            onDismissRequest = {}, //阻止点击外部区域关闭
//                title = { Text("加载中") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth() // 让 Column 填充对话框宽度
                        .wrapContentHeight(), // 根据内容调整高度
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
 * 顶部的AppBar，显示一个tabRow，包含一些导航目的地。如果传入当前目的地不在列表内，则不显示appbar
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MyTopAppBar(
    currDestination: Destination?,
    setDestination: (Destination) -> Unit,
) {
    val selectIdx = currDestination?.let { appbarDestList.indexOf(it) }
    if (selectIdx != null && selectIdx != -1) {
        TopAppBar(
            title = {
                //TODO 改为 navigation  参考 https://developer.android.com/develop/ui/compose/components/tabs?hl=zh-cn
                PrimaryScrollableTabRow(selectIdx, divider = {}) {
                    appbarDestList.forEachIndexed { idx, dest ->
                        Tab(
                            selected = selectIdx == idx,
                            onClick = { setDestination(dest) },
                            text = {
                                Text(
                                    text = dest.title,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        )
                    }
                }
            },
            actions = {
                IconButton({ setDestination(Destination.X11) }) { Icon(painterResource(R.drawable.ic_hide), null) }
            },
//        scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(),
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
    val startDest = Destination.X11
    val navController = rememberNavController()
    val navBackEntry by navController.currentBackStackEntryAsState()
    val currDestination = appbarDestList.find { navBackEntry?.destination?.hasRoute(it.route::class) == true }
    MainTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                MyTopAppBar(currDestination, { navController.navigate(it.route) })
            },
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
                    navigation<RouteExceptX11>(startDestination = RouteTerminal) {
                        composable<RouteTerminal> { ProotTerminalScreenPreview() }
//                        composable<NavDest.Terminal> { TerminalScreenPreview() }
                        composable<RouteSettings> { SettingScreenPreview() }
                    }
                }
            }
//            MainDialog(uiState) { mainVM.closeConfirmDialog(it) }
        }
    }
}
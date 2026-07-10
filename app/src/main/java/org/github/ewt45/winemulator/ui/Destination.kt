package org.github.ewt45.winemulator.ui

import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Serializable
data object RoutePrepare
@Serializable
data object RouteTerminal
@Serializable
data object RouteX11
@Serializable
data object RouteExceptX11
@Serializable
data object RouteSettings
/** 新主界面：LinBox Home */
@Serializable
data object RouteHome
/** 添加容器（点右下角 ➕） */
@Serializable
data object RouteAddContainer
/** 右上角主题切换独立页 */
@Serializable
data object RouteTheme
/** 右上角"显示终端"独立页（带返回箭头） */
@Serializable
data object RouteShowTerminal
/** 容器自动执行命令编辑页（带 rootfsName 参数） */
@Serializable
data class RouteContainerAutoCmd(val rootfsName: String)

enum class Destination(
    val title: String,
    val route: Any,
    val baseRoute: Any = route,
) {
    Prepare(
        "准备阶段",
        RoutePrepare,
    ),
    Home(
        "LinBox",
        RouteHome,
    ),
    X11(
        "x11",
        RouteX11,
    ),
    ExceptX11(
        "非X11",
        RouteExceptX11,
    ),
    Terminal(
        "终端",
        RouteTerminal,
    ),
    Settings(
        "设置",
        RouteSettings,
    ),
    AddContainer(
        "添加容器",
        RouteAddContainer,
    ),
    Theme(
        "主题切换",
        RouteTheme,
    ),
    ShowTerminal(
        "显示终端",
        RouteShowTerminal,
    );

    /** 容器自动执行命令编辑页（带参数） */
    data class ContainerAutoCmd(val rootfsName: String) {
        val route: Any get() = RouteContainerAutoCmd(rootfsName)
    }
}

/** 显示在appbar中的tab (历史遗留，新 Home 已不使用) */
val appbarDestList = listOf(Destination.Terminal, Destination.Settings)
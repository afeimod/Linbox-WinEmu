package org.github.ewt45.winemulator.ui.theme

import androidx.compose.ui.graphics.Color

// ==================== 原有颜色 ====================
val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

// ==================== 终端专用颜色 ====================
// 终端深色主题
val TerminalBackground = Color(0xFF000000)           // 纯黑背景
val TerminalSurface = Color(0xFF121212)              // 表面色
val TerminalSurfaceVariant = Color(0xFF1E1E1E)       // 变体表面
val TerminalOnBackground = Color(0xFFFFFFFF)        // 背景上的文字
val TerminalOnSurface = Color(0xFFE0E0E0)            // 表面上的文字

// 提示符颜色
val TerminalUserGreen = Color(0xFF81C784)            // 用户名绿色 (类Unix提示符)
val TerminalUserBlue = Color(0xFF64B5F6)             // 普通用户蓝色
val TerminalHostCyan = Color(0xFF4DD0E1)             // 主机名青色
val TerminalPathWhite = Color(0xFFFFFFFF)            // 路径白色
val TerminalSymbolYellow = Color(0xFFFFD54F)         // 符号黄色 (@ : $ #)
val TerminalRootWhite = Color(0xFFFF9800)            // root用户橙色

// 状态指示颜色
val TerminalOnlineGreen = Color(0xFF4CAF50)          // 在线状态绿色
val TerminalOfflineRed = Color(0xFFF44336)           // 离线状态红色

// 终端光标颜色
val TerminalCursor = Color(0xFFFFFFFF)               // 光标白色
val TerminalCursorBlock = Color(0xFFFFFFFF).copy(alpha = 0.7f)  // 块状光标

// 错误和警告
val TerminalError = Color(0xFFEF5350)               // 错误红色
val TerminalWarning = Color(0xFFFFCA28)              // 警告黄色
val TerminalInfo = Color(0xFF29B6F6)                 // 信息蓝色

// ==================== 键盘专用颜色 ====================
// 键盘浅色主题
val KeyboardBackground = Color(0xFFF5F5F5)           // 键盘背景浅灰
val KeyboardSurface = Color(0xFFFFFFFF)             // 按键背景白色
val KeyboardSurfaceVariant = Color(0xFFE8E8E8)       // 按键变体灰色
val KeyboardOnBackground = Color(0xFF333333)         // 背景上的文字深灰
val KeyboardOnSurface = Color(0xFF1A1A1A)            // 按键上的文字

// 功能键颜色
val KeyboardFunctionKey = Color(0xFFE0E0E0)          // 功能键背景
val KeyboardFunctionKeyText = Color(0xFF666666)     // 功能键文字

// 按键样式 - 软拟物风格
val KeyboardKeyShadow = Color(0x40000000)           // 按键阴影 (25% 黑色)
val KeyboardKeyHighlight = Color(0x20FFFFFF)         // 按键高光 (12% 白色)
val KeyboardKeyPressed = Color(0xFFD0D0D0)           // 按下状态背景

// 按键边框
val KeyboardKeyBorder = Color(0xFFD0D0D0)           // 按键边框浅灰
val KeyboardKeyBorderDark = Color(0xFFB0B0B0)       // 按键边框深灰

// ==================== 导航栏颜色 ====================
// Tab指示器
val TabIndicatorPurple = Color(0xFF9C27B0)           // Tab指示器紫色
val TabIndicatorLight = Color(0xFFE1BEE7)           // Tab指示器浅紫

// 导航栏背景
val NavBarBackground = Color(0xFF1E1E1E)             // 深色导航栏
val NavBarBackgroundLight = Color(0xFFFAFAFA)       // 浅色导航栏

// ==================== 辅助颜色 ====================
// 透明度变体
val OverlayLight = Color(0x0AFFFFFF)                 // 10% 白色
val OverlayDark = Color(0x1A000000)                  // 10% 黑色

// 分割线
val DividerDark = Color(0xFF2A2A2A)                  // 深色分割线
val DividerLight = Color(0xFFE0E0E0)                 // 浅色分割线

// 选中状态
val SelectionHighlight = Color(0x409C27B0)          // 选中高亮紫色 (25%)

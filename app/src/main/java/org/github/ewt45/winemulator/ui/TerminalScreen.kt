package org.github.ewt45.winemulator.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.view.TerminalView
import org.github.ewt45.winemulator.MainEmuActivity
import org.github.ewt45.winemulator.terminal.ViewClientImpl
import org.github.ewt45.winemulator.viewmodel.TerminalViewModel

/**
 * 使用termux的TerminalView显示终端和交互
 * 增强版：添加美观的状态栏和用户名显示
 */
@Composable
fun TerminalScreen(viewModel: TerminalViewModel) {
    val activity = LocalActivity.current as MainEmuActivity
    
    TerminalScreenImpl(
        getViewClient = { activity.viewClient },
        currentUser = viewModel.currentUser,
        currentHost = viewModel.currentHost,
        currentPath = viewModel.currentPath,
        isConnected = viewModel.isConnected
    )
}

@Composable
private fun TerminalScreenImpl(
    getViewClient: () -> ViewClientImpl?,
    currentUser: String,
    currentHost: String,
    currentPath: String,
    isConnected: Boolean,
) {
    Column(
        Modifier.fillMaxSize(),
    ) {
        // 顶部状态栏 - 显示用户名、主机名和当前目录
        TerminalStatusBar(
            currentUser = currentUser,
            currentHost = currentHost,
            currentPath = currentPath,
            isConnected = isConnected
        )
        
        // 分隔线
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 8.dp),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
        
        /*
        termux中使用TerminalView的xml布局
        <com.termux.view.TerminalView
            android:id="@+id/terminal_view"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:defaultFocusHighlightEnabled="false"
            android:focusableInTouchMode="true"
            android:scrollbarThumbVertical="@drawable/terminal_scroll_shape"
            android:scrollbars="vertical"
            tools:ignore="UnusedAttribute" />
         */
        AndroidView({ ctx ->
            TerminalView(ctx, null).apply {
                getViewClient()?.let { setTerminalViewClient(it) }
                isFocusableInTouchMode = true
                isVerticalScrollBarEnabled = true
                // 启用触摸长按选择文本功能 - 修复长按无法复制的问题
                // TerminalView 使用自定义文本选择系统，需要确保视图可长按
                isLongClickable = true
            }
        }, modifier = Modifier.weight(1f))
    }
}

/**
 * 终端状态栏组件
 * 显示用户名@主机:路径 格式
 */
@Composable
fun TerminalStatusBar(
    currentUser: String,
    currentHost: String,
    currentPath: String,
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    val statusColor = if (isConnected) {
        Color(0xFF4CAF50) // 绿色 - 已连接
    } else {
        Color(0xFFFF9800) // 橙色 - 连接中
    }
    
    val userColor = if (currentUser == "root") {
        Color(0xFFFFFFFF) // 白色 - root用户（普通颜色）
    } else {
        Color(0xFF64B5F6) // 蓝色 - 普通用户（高亮）
    }
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
                shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 用户名@主机
        Text(
            text = "$currentUser@$currentHost",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            color = userColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        
        // 冒号分隔符
        Text(
            text = ":",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        // 当前路径
        Text(
            text = currentPath,
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF81C784), // 浅绿色路径
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        // 连接状态指示器
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(statusColor)
        )
    }
}

/**
 * 简化版状态栏（用于预览）- TerminalScreen专用
 */
@Composable
fun TerminalScreenStatusBar(
    currentUser: String = "root",
    currentHost: String = "localhost",
    currentPath: String = "~",
    isConnected: Boolean = true
) {
    TerminalStatusBar(
        currentUser = currentUser,
        currentHost = currentHost,
        currentPath = currentPath,
        isConnected = isConnected
    )
}

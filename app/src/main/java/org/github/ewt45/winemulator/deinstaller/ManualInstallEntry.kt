package org.github.ewt45.winemulator.deinstaller

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * 容器设置页手动触发入口.
 *
 * 用法:
 *   ManualInstallEntry(rootfs = File(Consts.rootfsAllDir, currentContainer.name))
 */
@Composable
fun ManualInstallEntry(rootfs: File) {
    var show by remember { mutableStateOf(false) }
    val controller = rememberInstallDesktopController()
    if (show) {
        InstallDesktopDialog(
            rootfs = rootfs,
            vm = controller,
            onDismiss = { show = false },
        )
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("图形桌面", style = MaterialTheme.typography.titleMedium)
        Text(
            "未装或想换一个?点下面按钮安装 XFCE4 / KDE, 脚本会自动适配发行版包管理器, " +
                    "并补齐中文 + libxkbcommon-x11 + dbus-x11 + PulseAudio + Vulkan 等依赖.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { show = true }) { Text("安装 / 重装桌面") }
        }
    }
}

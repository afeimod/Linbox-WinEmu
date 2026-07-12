package org.github.ewt45.winemulator.deinstaller

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File

/**
 * 弹窗: 安装图形桌面.
 *
 * 接在 rootfs 下载/解压成功的回调里:
 *
 *   val result = ProotDistroInstaller.install(...)
 *   var show by remember { mutableStateOf<File?>(null) }
 *   LaunchedEffect(result) { show = result.rootfsDir }
 *   show?.let { rootfs ->
 *       InstallDesktopDialog(
 *           rootfs = rootfs,
 *           onDismiss = { show = null; /* 继续后面流程 */ }
 *       )
 *   }
 */
@Composable
fun InstallDesktopDialog(
    rootfs: File,
    onDismiss: () -> Unit,
    vm: InstallDesktopViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    // 第一次进入: 加载已保存的 choice (没记录就 XFCE4), 然后启动安装
    LaunchedEffect(rootfs.absolutePath) {
        if (state.rootfsPath != rootfs.absolutePath) {
            val saved = loadSavedChoice(rootfs)  // 你项目里自己实现, 默认 null
            vm.init(rootfs, saved)
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (state.phase != InstallDesktopViewModel.Phase.RUNNING) onDismiss()
        },
        title = { Text("安装图形桌面") },
        text = {
            Column {
                Text(
                    "rootfs 已就绪 ✨\n" +
                            "自动识别发行版(debian/ubuntu→apt, arch→pacman, fedora→dnf, " +
                            "alpine→apk, opensuse→zypper, void→xbps),并安装中文字体/输入法, " +
                            "以及 libxkbcommon-x11 / dbus-x11 / PulseAudio / Vulkan 等 Linbox 运行 Wine 必备依赖。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.padding(top = 12.dp))

                DesktopChoice.values().forEach { c ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) {
                        RadioButton(
                            selected = state.choice == c,
                            onClick = { vm.setChoice(c) },
                            enabled = state.phase != InstallDesktopViewModel.Phase.RUNNING,
                        )
                        Text(c.displayName, style = MaterialTheme.typography.bodyLarge)
                    }
                }

                if (state.phase == InstallDesktopViewModel.Phase.RUNNING ||
                    state.phase == InstallDesktopViewModel.Phase.SUCCESS ||
                    state.phase == InstallDesktopViewModel.Phase.FAILED
                ) {
                    Spacer(Modifier.padding(top = 12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.phase == InstallDesktopViewModel.Phase.RUNNING) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                        }
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(when (state.phase) {
                                    InstallDesktopViewModel.Phase.RUNNING -> "正在安装…"
                                    InstallDesktopViewModel.Phase.SUCCESS -> "完成 ✅"
                                    InstallDesktopViewModel.Phase.FAILED  -> "失败 ❌"
                                    else -> ""
                                })
                            },
                        )
                    }
                    if (state.log.isNotBlank()) {
                        Spacer(Modifier.padding(top = 8.dp))
                        Text(
                            text = state.log.takeLast(4000),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (state.phase) {
                InstallDesktopViewModel.Phase.IDLE -> {
                    TextButton(onClick = { vm.start() }) { Text("开始安装") }
                }
                InstallDesktopViewModel.Phase.RUNNING -> {
                    TextButton(onClick = { /* 不允许取消 */ }) { Text("安装中…") }
                }
                InstallDesktopViewModel.Phase.FAILED -> {
                    TextButton(onClick = { vm.retry() }) { Text("重试") }
                }
                InstallDesktopViewModel.Phase.SUCCESS -> {
                    TextButton(onClick = {
                        saveChoice(rootfs, state.choice)  // 你项目里自己实现
                        vm.dismissAsHandled()
                        onDismiss()
                    }) { Text("好的") }
                }
            }
        },
        dismissButton = {
            if (state.phase == InstallDesktopViewModel.Phase.IDLE ||
                state.phase == InstallDesktopViewModel.Phase.FAILED
            ) {
                TextButton(onClick = {
                    if (state.phase == InstallDesktopViewModel.Phase.FAILED) vm.dismissAsHandled()
                    onDismiss()
                }) { Text("跳过") }
            }
        },
    )
}

// 你项目里用 SharedPreferences/DataStore 持久化用户选的 DE
// 这里留默认实现 (内存级), 接入时换成自己的
private val savedChoices = mutableMapOf<String, DesktopChoice>()

private fun loadSavedChoice(rootfs: File): DesktopChoice? =
    savedChoices[rootfs.absolutePath]

private fun saveChoice(rootfs: File, choice: DesktopChoice) {
    savedChoices[rootfs.absolutePath] = choice
}

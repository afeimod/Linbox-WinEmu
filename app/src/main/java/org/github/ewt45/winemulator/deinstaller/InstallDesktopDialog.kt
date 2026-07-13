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
import java.io.File

/**
 * 弹窗: 安装图形桌面.
 *
 * 由调用方持有 [vm] (推荐用 [rememberInstallDesktopController] 让 VM 跟着父 Composable),
 * 不在 dialog 里用 [androidx.lifecycle.viewmodel.compose.viewModel] (会随 AlertDialog 重组
 * 销毁/重建, 状态丢失).
 *
 * 用法:
 *   val controller = rememberInstallDesktopController()
 *   if (showDialog) {
 *       InstallDesktopDialog(
 *           rootfs = rootfs,
 *           vm = controller,
 *           onDismiss = { showDialog = false },
 *       )
 *   }
 */
@Composable
fun InstallDesktopDialog(
    rootfs: File,
    vm: InstallDesktopViewModel,
    onDismiss: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()

    // 进入弹窗: 第一次启动安装. 已经在 RUNNING/SUCCESS/FAILED 则不动.
    LaunchedEffect(rootfs.absolutePath) {
        if (state.rootfsPath != rootfs.absolutePath) {
            val saved = loadSavedChoice(rootfs)
            vm.init(rootfs, saved)
            vm.start()
        } else if (state.phase == InstallDesktopViewModel.Phase.IDLE) {
            vm.start()
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
                            "自动识别发行版 (debian/ubuntu→apt, arch→pacman, fedora→dnf, " +
                            "alpine→apk, opensuse→zypper, void→xbps), 并安装中文字体/输入法, " +
                            "以及 libxkbcommon-x11 / dbus-x11 / PulseAudio / Vulkan 等 Linbox 运行 Wine 必备依赖.",
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
                InstallDesktopViewModel.Phase.RUNNING -> {
                    TextButton(onClick = { /* 不可取消 */ }) { Text("安装中…") }
                }
                InstallDesktopViewModel.Phase.FAILED -> {
                    TextButton(onClick = { vm.retry() }) { Text("重试") }
                }
                InstallDesktopViewModel.Phase.SUCCESS -> {
                    TextButton(onClick = {
                        saveChoice(rootfs, state.choice)
                        vm.dismissAsHandled()
                        onDismiss()
                    }) { Text("好的") }
                }
                else -> {}  // IDLE: 自动起, 不需要按钮
            }
        },
        dismissButton = {
            if (state.phase == InstallDesktopViewModel.Phase.RUNNING) {
                // 运行中不显示"跳过"
            } else {
                TextButton(onClick = {
                    vm.dismissAsHandled()
                    onDismiss()
                }) { Text("跳过") }
            }
        },
    )
}

private val savedChoices = mutableMapOf<String, DesktopChoice>()
private fun loadSavedChoice(rootfs: File): DesktopChoice? = savedChoices[rootfs.absolutePath]
private fun saveChoice(rootfs: File, choice: DesktopChoice) {
    savedChoices[rootfs.absolutePath] = choice
}

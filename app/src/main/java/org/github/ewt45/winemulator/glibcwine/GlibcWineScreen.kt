package org.github.ewt45.winemulator.glibcwine

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.github.ewt45.winemulator.Consts
import org.json.JSONObject
import java.io.File

/**
 * glibc wine 容器管理界面。
 *
 * 功能:
 * 1. 显示 imagefs 安装状态
 * 2. 列出/创建/删除 wine 容器
 * 3. 显示容器配置 (屏幕大小、图形驱动、box64 预设等)
 * 4. 启动 wine (独立模式, 通过 proot)
 *
 * 整合架构:
 * - imagefs 安装在 <filesDir>/imagefs/, 与 proot rootfs 不冲突
 * - proot 容器启动时自动绑定 imagefs 到 /opt/glibc-wine
 * - wine 通过 box64 在 proot 内运行, 共享 X11 (:13) 和 PulseAudio
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlibcWineScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = remember { GlibcWineLauncher(context) }
    val imageFs = remember { GlibcImageFs.find(context) }

    var containers by remember { mutableStateOf(launcher.containerManager.getContainers()) }
    var statusMessage by remember { mutableStateOf("") }
    var isLaunching by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }

    // imagefs 状态
    val imageFsValid = imageFs.isValid()
    val imageFsVersion = if (imageFsValid) imageFs.getVersion() else 0
    val box64Exists = imageFs.getBox64Bin().exists()
    val wineExists = File(imageFs.rootDir, GlibcWineConsts.WINE_X86_64_PATH_REL).exists()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("glibc Wine 容器") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        containers = launcher.containerManager.getContainers()
                        statusMessage = "已刷新"
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "创建容器")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ====== imagefs 状态卡片 ======
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "glibc Wine 运行时状态",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("镜像版本: ${if (imageFsVersion > 0) imageFsVersion else "未安装"}")
                    Text("Box64: ${if (box64Exists) "已安装" else "未安装"}")
                    Text("Wine (x86_64): ${if (wineExists) "已安装" else "未安装"}")
                    Text("镜像路径: ${imageFs.rootDir.path}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Text("容器内挂载: ${GlibcWineConsts.CONTAINER_MOUNT_POINT}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Text("X11 显示: ${GlibcWineConsts.DISPLAY} (共享 Termux-X11)", style = MaterialTheme.typography.bodySmall)
                    Text("音频: ${GlibcWineConsts.PULSE_SERVER} (共享 PulseAudio)", style = MaterialTheme.typography.bodySmall)

                    if (!imageFsValid) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    statusMessage = "正在安装 imagefs..."
                                    GlibcImageFsInstaller.installFromAssets(
                                        context,
                                        onComplete = { success ->
                                            statusMessage = if (success) "imagefs 安装成功" else "imagefs 安装失败 (需要手动放置 imagefs.tzst)"
                                        }
                                    )
                                }
                            },
                        ) {
                            Text("安装 imagefs")
                        }
                    }
                }
            }

            // ====== 使用说明 ======
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("使用方法", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("1. 启动 proot 容器 (如 Ubuntu)", style = MaterialTheme.typography.bodySmall)
                    Text("2. 在 proot 终端运行: linbox-wine <exe路径>", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Text("3. 或点击下方「启动Wine」按钮独立启动", style = MaterialTheme.typography.bodySmall)
                    Text("4. Wine 界面显示在 X11 屏幕上 (DISPLAY=:13)", style = MaterialTheme.typography.bodySmall)
                }
            }

            // ====== 容器列表 ======
            Text("Wine 容器 (${containers.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            if (containers.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Text(
                        "暂无 wine 容器, 点击右上角 + 创建",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                containers.forEach { container ->
                    WineContainerCard(
                        container = container,
                        isLaunching = isLaunching,
                        onLaunch = {
                            scope.launch {
                                isLaunching = true
                                statusMessage = "正在启动 wine..."
                                try {
                                    val rootfs = Consts.rootfsCurrDir.canonicalFile
                                    if (!rootfs.isDirectory) {
                                        statusMessage = "错误: 当前 rootfs 无效, 请先启动一个 proot 容器"
                                        isLaunching = false
                                        return@launch
                                    }
                                    val result = launcher.launchStandalone(
                                        rootfs = rootfs,
                                        container = container,
                                        exePath = null, // 启动 winefile
                                    )
                                    statusMessage = if (result.success) {
                                        "Wine 已退出 (code=${result.exitCode})"
                                    } else {
                                        "Wine 启动失败: ${result.error ?: "未知错误"}"
                                    }
                                } catch (e: Exception) {
                                    statusMessage = "启动异常: ${e.message}"
                                } finally {
                                    isLaunching = false
                                }
                            }
                        },
                        onDelete = {
                            launcher.containerManager.removeContainer(container)
                            containers = launcher.containerManager.getContainers()
                            statusMessage = "容器已删除: ${container.name}"
                        },
                    )
                }
            }

            // ====== 状态消息 ======
            if (statusMessage.isNotEmpty()) {
                Text(statusMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    // ====== 创建容器对话框 ======
    if (showCreateDialog) {
        CreateWineContainerDialog(
            onConfirm = { name, screenSize, graphicsDriver, box64Preset ->
                scope.launch {
                    val data = JSONObject()
                    data.put("name", name)
                    data.put("screenSize", screenSize)
                    data.put("graphicsDriver", graphicsDriver)
                    data.put("audioDriver", GlibcWineConsts.DEFAULT_AUDIO_DRIVER)
                    data.put("dxwrapper", GlibcWineConsts.DEFAULT_DXWRAPPER)
                    data.put("wincomponents", GlibcWineConsts.DEFAULT_WINCOMPONENTS)
                    data.put("envVars", GlibcWineConsts.DEFAULT_ENV_VARS)
                    data.put("box64Preset", box64Preset)
                    data.put("box64Version", GlibcWineConsts.DefaultVersion.BOX64)
                    data.put("wineVersion", WineInfo.MAIN_WINE_VERSION.identifier())

                    val container = launcher.containerManager.createContainer(data)
                    if (container != null) {
                        containers = launcher.containerManager.getContainers()
                        statusMessage = "容器创建成功: ${container.name}"
                    } else {
                        statusMessage = "容器创建失败"
                    }
                }
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }
}

@Composable
private fun WineContainerCard(
    container: WineContainer,
    isLaunching: Boolean,
    onLaunch: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(container.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row {
                    IconButton(onClick = onLaunch, enabled = !isLaunching) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "启动")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "删除")
                    }
                }
            }
            Text("屏幕: ${container.screenSize}", style = MaterialTheme.typography.bodySmall)
            Text("图形驱动: ${container.graphicsDriver}", style = MaterialTheme.typography.bodySmall)
            Text("Box64 预设: ${container.box64Preset}", style = MaterialTheme.typography.bodySmall)
            Text("Wine 版本: ${container.wineVersion}", style = MaterialTheme.typography.bodySmall)
            Text("音频: ${container.audioDriver}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CreateWineContainerDialog(
    onConfirm: (name: String, screenSize: String, graphicsDriver: String, box64Preset: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("新容器") }
    var screenSize by remember { mutableStateOf(GlibcWineConsts.DEFAULT_SCREEN_SIZE) }
    var graphicsDriver by remember { mutableStateOf(GlibcWineConsts.DEFAULT_GRAPHICS_DRIVER) }
    var box64Preset by remember { mutableStateOf(Box64Preset.COMPATIBILITY) }

    val screenSizes = listOf("1280x720", "1920x1080", "1024x768", "800x600")
    val graphicsDrivers = listOf("turnip", "virgl-23.1.9", "freedreno")
    val box64Presets = listOf(
        Box64Preset.STABILITY to "稳定性",
        Box64Preset.COMPATIBILITY to "兼容性",
        Box64Preset.INTERMEDIATE to "中级",
        Box64Preset.PERFORMANCE to "性能",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("创建 Wine 容器") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("容器名称") },
                    singleLine = true,
                )
                Text("屏幕尺寸", style = MaterialTheme.typography.labelMedium)
                screenSizes.forEach { size ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = screenSize == size, onClick = { screenSize = size })
                        Text(size)
                    }
                }
                Text("图形驱动", style = MaterialTheme.typography.labelMedium)
                graphicsDrivers.forEach { driver ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = graphicsDriver == driver, onClick = { graphicsDriver = driver })
                        Text(driver)
                    }
                }
                Text("Box64 预设", style = MaterialTheme.typography.labelMedium)
                box64Presets.forEach { (preset, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = box64Preset == preset, onClick = { box64Preset = preset })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, screenSize, graphicsDriver, box64Preset) }) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

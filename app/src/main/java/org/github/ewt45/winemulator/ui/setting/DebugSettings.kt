package org.github.ewt45.winemulator.ui.setting

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.system.OsConstants.SIGCONT
import android.system.OsConstants.SIGSTOP
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.github.ewt45.winemulator.Consts
import org.github.ewt45.winemulator.Utils
import org.github.ewt45.winemulator.MainEmuActivity
import org.github.ewt45.winemulator.Utils.getX11ServicePid
import org.github.ewt45.winemulator.emu.X11Service
import org.github.ewt45.winemulator.ui.components.CollapsePanel
import org.github.ewt45.winemulator.ui.components.ComposeSpinner
import org.github.ewt45.winemulator.ui.components.TaskReporter
import org.github.ewt45.winemulator.ui.Destination
import org.github.ewt45.winemulator.ui.components.rememberNotImplDialog
import org.github.ewt45.winemulator.viewmodel.TerminalViewModel
import java.io.File
import java.nio.file.Files
import kotlin.io.path.pathString

@Composable
fun DebugSettings(terminalVM: TerminalViewModel, navigateTo: (Destination) -> Unit) {
    val ctx: Context = LocalContext.current

    var showFilterSymlink by filterSymlinkDialog()
    var showCompareDir by compareRootfsDirDialog()
    val scope = rememberCoroutineScope()

    DebugSettingsImpl(
        sendSigStop = {
            terminalVM.pauseTerminal()
            android.os.Process.sendSignal(ctx.getX11ServicePid(), SIGSTOP)
        },
        sendSigCont = {
            terminalVM.resumeTerminal()
            android.os.Process.sendSignal(ctx.getX11ServicePid(), SIGCONT)
        },
        gotoSelectRootfs = { navigateTo(Destination.Prepare) },
        findSymlinkToTermux = { showFilterSymlink = true },
        startX11Service = { MainEmuActivity.instance.startService(Intent(MainEmuActivity.instance, X11Service::class.java)) },
        compareRootfsDir = { showCompareDir = true },
        startNativeGlibcTest = {
            scope.launch {
                try {
                    android.util.Log.d("NativeGlibcTest", "按钮点击，开始启动原生glibc")
                    // 诊断：列出assets里的所有文件
                    val assetFiles = ctx.assets.list("") ?: arrayOf()
                    android.util.Log.d("NativeGlibcTest", "assets根目录文件: ${assetFiles.joinToString(", ")}")
                    // 逐个尝试打开rootfs文件
                    val tryNames = listOf("rootfs.tar.gz", "rootfs.tar.xz", "rootfs.tar.zst", "rootfs.tzst")
                    for (name in tryNames) {
                        try {
                            val size = ctx.assets.open(name).use { it.available() }
                            android.util.Log.d("NativeGlibcTest", "找到 $name, size=$size")
                        } catch (e: Exception) {
                            android.util.Log.d("NativeGlibcTest", "未找到 $name: ${e.message}")
                        }
                    }
                    terminalVM.stopTerminal()
                    var rootfs = Utils.Rootfs.getSelectedRootfs()
                    android.util.Log.d("NativeGlibcTest", "getSelectedRootfs = ${rootfs}")
                    if (rootfs == null) {
                        android.util.Log.d("NativeGlibcTest", "rootfs为null，从assets解压...")
                        rootfs = Utils.Rootfs.installRootfsFromAssets(ctx, TaskReporter.Dummy)
                        android.util.Log.d("NativeGlibcTest", "installRootfsFromAssets结果 = ${rootfs}")
                        if (rootfs == null) {
                            android.util.Log.e("NativeGlibcTest", "assets中未找到rootfs压缩包")
                            return@launch
                        }
                    }
                    Utils.Rootfs.makeCurrent(rootfs)
                    android.util.Log.d("NativeGlibcTest", "makeCurrent完成，准备startTerminal")
                    terminalVM.startTerminal(forceNativeGlibc = true)
                    android.util.Log.d("NativeGlibcTest", "startTerminal已调用")
                } catch (e: Exception) {
                    android.util.Log.e("NativeGlibcTest", "启动失败", e)
                }
            }
        },
        startNativeWineCfgTest = {
            // 不要用 Compose rememberCoroutineScope：跳转 X11 会取消它，导致 winecfg 还没启动就中断
            android.util.Log.d("NativeWineCfgTest", "按钮点击，开始原生 winecfg 测试")
            MainEmuActivity.instance.startNativeWineCfgTestAsync {
                navigateTo(Destination.X11)
            }
        },
    )
}

@Composable
fun DebugSettingsImpl(
    sendSigStop: () -> Unit = {},
    sendSigCont: () -> Unit = {},
    gotoSelectRootfs: () -> Unit = {},
    findSymlinkToTermux: () -> Unit = {},
    startX11Service: () -> Unit = {},
    compareRootfsDir: () -> Unit = {},
    startNativeGlibcTest: () -> Unit = {},
    startNativeWineCfgTest: () -> Unit = {},
) {

    var showNotImpl by rememberNotImplDialog()
    val notImplClick = { showNotImpl = true }

    CollapsePanel("调试选项", initExpanded = false) {
        Button(onClick = startX11Service) { Text("手动启动TX11 Service") }
        Button(onClick = findSymlinkToTermux) { Text("检查当前rootfs内文件是否有指向termux的软链接") }
        Button(onClick = sendSigStop) { Text("向终端和x11发送STOP信号") }
        Button(onClick = sendSigCont) { Text("向终端和x11发送CONT信号") }
        Button(onClick = gotoSelectRootfs) { Text("进入选择rootfs界面") }
        Button(onClick = compareRootfsDir) { Text("对比文件夹内文件") }
        Button(onClick = startNativeGlibcTest) { Text("测试原生Glibc（独立启动）") }
        Button(onClick = startNativeWineCfgTest) { Text("测试原生WineCfg（box64+X11）") }
    }
}

@Composable
private fun compareRootfsDirDialog(): MutableState<Boolean> {
    val visibility = remember { mutableStateOf(false) }
    var infoText by remember { mutableStateOf("") }
    var finished by remember { mutableStateOf(true) }

    val rootfsList = Consts.rootfsAllDir.list()?.toList() ?: emptyList()
    if (rootfsList.isEmpty()) return remember { mutableStateOf(false) }
    var rootfs1 by remember { mutableStateOf(rootfsList[0]) }
    var rootfs2 by remember { mutableStateOf(rootfsList.getOrElse(1) { rootfsList[0] }) }
    if (visibility.value) {
        val scope = rememberCoroutineScope()
        finished = true
        AlertDialog(
            {}, confirmButton = {},
            text = {
                Column {
                    ComposeSpinner(rootfs1, rootfsList) { _, new -> rootfs1 = new }
                    ComposeSpinner(rootfs2, rootfsList) { _, new -> rootfs2 = new }
                    Button({
                        scope.launch(Dispatchers.IO) {
                            finished = false
                            val file1 = File(Consts.rootfsAllDir, rootfs1)
                            val file2 = File(Consts.rootfsAllDir, rootfs2)
                            val prefix1Len = file1.absolutePath.length
                            val prefix2Len = file2.absolutePath.length
                            val list1 = File(Consts.rootfsAllDir, rootfs1).walkTopDown().mapNotNull {
                                infoText = it.absolutePath
                                it.absolutePath.substring(prefix1Len).takeIf { path -> path.startsWith("/bin/") || path.startsWith("/lib/") }
                            }.toSet()
                            val list2 = File(Consts.rootfsAllDir, rootfs2).walkTopDown().map {
                                infoText = it.absolutePath
                                it.absolutePath.substring(prefix2Len)
                            }.toSet()
                            val in1ButNotIn2List = list1.subtract(list2)
                            val in2ButNotIn1List = list2.subtract(list1)
                            infoText = "对比结果：" +
                                    "\n\n$rootfs1 中独有的文件：\n" +
                                    in1ButNotIn2List.joinToString("\n") +
                                    "\n\n$rootfs2 中独有的文件： \n" +
                                    in2ButNotIn1List.joinToString("\n")
                            finished = true
                        }
                    }) { Text("开始") }
                    if (finished) Button({ visibility.value = false }) { Text("关闭") }
                    Text(
                        infoText,
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        )


    }
//    //显示dialog之后，开始检查
//    LaunchedEffect(finished) {
//        if (!finished) {
//            withContext(Dispatchers.IO) {
//                val list1 = File(Consts.rootfsAllDir, rootfs1).walkTopDown().map { it.absolutePath }.toSet()
//                val list2 = File(Consts.rootfsAllDir, rootfs2).walkTopDown().map { it.absolutePath }.toSet()
//                val in1ButNotIn2List = list1.subtract(list2)
//                val in2ButNotIn1List = list2.subtract(list1)
//                infoText = "对比结果：" +
//                        "\n\n$rootfs1 中独有的文件：\n" +
//                        in1ButNotIn2List.joinToString("\n") +
//                        "\n\n$rootfs2 中独有的文件： \n" +
//                        in2ButNotIn1List.joinToString("\n")
//                finished = true
//            }
//        }
//    }
    return visibility

}

@SuppressLint("SdCardPath")
@Composable
private fun filterSymlinkDialog(): MutableState<Boolean> {
    val visibility = remember { mutableStateOf(false) }
    var infoText by remember { mutableStateOf("") }
    var finished by remember { mutableStateOf(false) }
    if (visibility.value) {
        AlertDialog(
            {},
            confirmButton = {},
            text = {
                Column {
                    if (finished) Button({ visibility.value = false }) { Text("关闭") }
                    Text(infoText, modifier = Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
                }
            }
        )
    }
    //显示dialog之后，开始检查
    LaunchedEffect(visibility.value) {
        if (visibility.value) {
            withContext(Dispatchers.IO) {
                finished = false
                val prefixLen = Consts.rootfsCurrDir.absolutePath.length
                val linkPointToTermuxList = mutableListOf<String>()
                val l2sNotInL2sDirList = mutableListOf<String>()
                for (file in Consts.rootfsCurrDir.walkTopDown()) {
                    infoText = file.absolutePath.let { if (it.length > prefixLen) it.substring(prefixLen) else it }
                    //1. 任何符号链接，指向/data/data/com.termux 的
                    try {
                        file.toPath().takeIf { Files.isSymbolicLink(it) }?.let { Files.readSymbolicLink(it) }
                            ?.pathString?.takeIf { it.startsWith("/data/data/com.termux") || it.contains("/com.termux/") }
                            ?.let { linkPointToTermuxList.add("${file.absolutePath} -> $it") }
                    } catch (e: Exception) {
                        linkPointToTermuxList.add(e.stackTraceToString())
                    }
                    //2. .l2s. 开头文件 不在 .l2s 文件夹内的
                    try {
                        file.takeIf { it.name.startsWith(".l2s.") && it.parentFile!!.name != ".l2s" }?.let { l2sNotInL2sDirList.add(it.absolutePath) }
                    } catch (e: Exception) {
                        l2sNotInL2sDirList.add(e.stackTraceToString())
                    }
                }

                infoText = "读取完毕。\n\n以下路径为符号链接但指向了/data/data/termux目录或路径包含 /com.termux/:" +
                        linkPointToTermuxList.joinToString("\n") +
                        "\n\n以下路径为以 .l2s. 开头的文件但不在.l2s文件夹内" +
                        l2sNotInL2sDirList.joinToString("\n")

                finished = true
            }
        }
    }
    return visibility
}
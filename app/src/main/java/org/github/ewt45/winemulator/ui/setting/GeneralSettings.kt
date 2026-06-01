package org.github.ewt45.winemulator.ui.setting

import a.io.github.ewt45.winemulator.R
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.github.ewt45.winemulator.CompressedType
import org.github.ewt45.winemulator.Consts
import org.github.ewt45.winemulator.FuncOnChange
import org.github.ewt45.winemulator.FuncOnChangeAction
import org.github.ewt45.winemulator.Utils
import org.github.ewt45.winemulator.emu.ProotRootfs
import org.github.ewt45.winemulator.ui.AnimatedVertical
import org.github.ewt45.winemulator.ui.components.CollapsePanel
import org.github.ewt45.winemulator.ui.components.ComposeSpinner
import org.github.ewt45.winemulator.ui.Destination
import org.github.ewt45.winemulator.ui.components.ProgressStage
import org.github.ewt45.winemulator.ui.components.TextFieldOption
import org.github.ewt45.winemulator.ui.components.TitleAndContent
import org.github.ewt45.winemulator.ui.components.ConfirmDialog
import org.github.ewt45.winemulator.ui.components.ConfirmDialogState
import org.github.ewt45.winemulator.ui.components.ProgressDisplay
import org.github.ewt45.winemulator.ui.components.TaskReporter
import org.github.ewt45.winemulator.ui.components.rememberConfirmDialogState
import org.github.ewt45.winemulator.ui.components.rememberTaskReporter
import org.github.ewt45.winemulator.viewmodel.PrepareViewModel
import org.github.ewt45.winemulator.viewmodel.SettingViewModel
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Random

private val TAG = "GeneralSettings"


@Composable
fun GeneralSettings(
    settingVM: SettingViewModel,
    prepareVm: PrepareViewModel,
    navigateTo: (Destination) -> Unit,
) {

    val state by settingVM.generalState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()


    CollapsePanel("一般选项", vPadding = 32.dp) {
        // 分辨率设置已移动到X11显示设置中
        // GeneralResolution(settingVM.resolutionText, settingVM::onChangeResolutionText)
        GeneralRootfsLang(state.rootfsLang, listOf("en_US.utf8", "zh_CN.utf8"), settingVM::onChangeRootfsLang)
        GeneralShareDir(state.sharedExtPath, settingVM::onChangeShareExtPath)
//        MoreContent {

        GeneralRootfsSelect(
            Consts.rootfsCurrDir.canonicalFile.name,
            state.localRootfsLoginUsersMap,
            settingVM.rootfsUsersOptions.value.mapValues { it.value.map { info -> info.name } },
            settingVM.rootfsAliasMap.value,
            settingVM::onChangeRootfsName,
            settingVM::onChangeRootfsSelect,
            { rootfs, user -> scope.launch { settingVM.onChangeRootfsLoginUser(rootfs, user) } },
            settingVM::onChangeRootfsAlias,
            { prepareVm.setForceNoRootfs() },
        )
//        }
    }
}

/**
 * 主题模式选择
 * 0 = 跟随系统, 1 = 暗色主题, 2 = 亮色主题
 */
@Composable
fun GeneralThemeMode(
    themeMode: Int,
    onThemeModeChange: (Int) -> Unit,
) {
    val themeOptions = listOf("跟随系统", "暗色主题", "亮色主题")
    val themeModeNames = mapOf(0 to "跟随系统", 1 to "暗色主题", 2 to "亮色主题")
    val currentThemeName = themeModeNames[themeMode] ?: "暗色主题"
    
    TitleAndContent("主题模式", "选择界面主题。默认使用暗色主题。") {
        ComposeSpinner(currentThemeName, themeOptions, modifier = Modifier.fillMaxWidth()) { _, new ->
            val newMode = themeOptions.indexOf(new)
            if (newMode >= 0) {
                onThemeModeChange(newMode)
            }
        }
    }
}


@Composable
fun GeneralRootfsLang(
    currLang: String,
    langOptions: List<String>,
    onLangChange: (String) -> Unit,
) {
    TitleAndContent("容器语言", "启动容器时作为环境变量 LANG 的值。") {
        ComposeSpinner(currLang, langOptions, modifier = Modifier.fillMaxWidth()) { _, new -> onLangChange(new) }
    }
}


/**
 * Rootfs切换，删除，重命名，添加
 * @param currRootfs 当前正在运行的rootfs名，rootfsList中的一项，为 [Consts.rootfsCurrDir] 指向的真实路径，应该将此项禁用禁止编辑
 * @param onRootfsNameChange 文件夹重命名/删除时
 * @param rootfsToLoginUserMap 每个rootfs及其当前选择的登陆用户名。参考：[Consts.Pref.Local.rootfs_login_user_json]。请传入前确保每个rootfs都在其中有key，且对应value 的user符合 [ProotRootfs.getPreferredUser]
 * @param loginUsersOptions 每个rootfs及其对应的全部可使用用户名。 请传入前确保rootfs不包含[Consts.rootfsCurrDir]
 * @param rootfsAliasMap rootfs 别名映射，key 为文件夹名，value 为别名
 * @param onRootfsSelectChange 当前使用的rootfs变更时
 * @param onUserSelectChange 某个rootfs的登陆用户变化时。参数1是rootfs名，参数2是用户名
 */
@Composable
fun GeneralRootfsSelect(
    currRootfs: String,
    rootfsToLoginUserMap: Map<String, String>,
    loginUsersOptions: Map<String, List<String>>,
    rootfsAliasMap: Map<String, String>,
    onRootfsNameChange: suspend (String, String, FuncOnChangeAction) -> Unit,
    onRootfsSelectChange: suspend (String) -> Unit,
    onUserSelectChange: (String, String) -> Unit,
    onAliasChange: (String, String) -> Unit,
    navigateToNewRootfs: () -> Unit,
) {

    val scope = rememberCoroutineScope()
//    TODO 排一下序之后没问题了，之前重命名用list.minus.plus 然后重命名之后rootfs名往上挪了一位，user名还没变。出错原理是什么？
    val sortedRootfsList = loginUsersOptions.keys.sortedWith(compareBy<String> { it != currRootfs }.thenBy { it })
    val dialogState = rememberConfirmDialogState()

    val TYPE_SEL = 0 // 切换
    val TYPE_DEL = 1 // 删除
    fun onClickBtn(type: Int, rootfsName: String, rootfsAlias: String, isCurr: Boolean, newRootfsName: String? = null) = scope.launch {
        if (type == TYPE_SEL && !isCurr) {
            dialogState.showConfirm("将此文件夹设置为Proot使用的rootfs？\n确定后将退出app, 请手动重启。\n\n$rootfsAlias") {
                onRootfsSelectChange(rootfsName)
            }
        } else if (type == TYPE_DEL) {
            // 如果是当前正在运行的rootfs，直接显示提示，不执行删除
            if (isCurr) {
                dialogState.showConfirm("该Rootfs当前正在运行，无法删除。\n\n$rootfsAlias")
            } else {
                dialogState.showConfirm("确定删除该Rootfs吗？\n其内部所有文件都将被删除，请谨慎操作！\n\n$rootfsAlias") {
                    onRootfsNameChange(rootfsName, rootfsName, FuncOnChangeAction.DEL)
                }
            }
        }
    }

    ConfirmDialog(dialogState)

    TitleAndContent("Rootfs切换", "切换Proot使用的rootfs，添加/重命名/删除。修改后需要重启app生效。") {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            //添加
            FilledTonalIconButton(onClick = navigateToNewRootfs) { Icon(Icons.Filled.Add, null) }

            for (rootfsName in sortedRootfsList) {
                val isCurr = rootfsName == currRootfs
                val rootfsAlias = rootfsAliasMap[rootfsName] ?: rootfsName
                val userNameOptions = loginUsersOptions[rootfsName]
                val userName = rootfsToLoginUserMap[rootfsName]
                if (userNameOptions == null || userName == null) {
                    //TODO 目前没有实现等待加载机制（sealed interface），所以初次传入的值可能不准确，直接忽略即可。稍后应该会更新传入准确的数据
                    continue
                }
                if (!userNameOptions.contains(userName)) {
                    throw RuntimeException("请确保传入的loginUsersOptions包含userName！loginUsersOptions=$userNameOptions, rootfsName=$rootfsName, username=$userName")
                }

                Box {
                    Row(Modifier.padding(0.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1F)) {
                            // 设置页面：只修改别名，不修改容器文件夹名
                            GeneralRootfsSelect_RootfsName(rootfsName, rootfsAlias, isCurr, dialogState, onAliasChange)
                            Spacer(Modifier.height(8.dp))
                            GeneralRootfsSelect_LoginUserSelect(rootfsName, userName, userNameOptions, onUserSelectChange)
                        }

                        Spacer(Modifier.width(8.dp))
                        OutlinedCard(
                            shape = RoundedCornerShape(100.dp),
                            border = CardDefaults.outlinedCardBorder(),
                        ) {
                            val btnModifier = Modifier.size(40.dp)//.padding(4.dp)
                            Column {
                                IconButton(onClick = { onClickBtn(TYPE_SEL, rootfsName, rootfsAlias, isCurr) }, btnModifier) {
                                    if (isCurr) Icon(Icons.Filled.Check, null)
                                    else Icon(painterResource(R.drawable.ic_switch), null)
                                }
                                IconButton(onClick = { onClickBtn(TYPE_DEL, rootfsName, rootfsAlias, isCurr) }, btnModifier)
                                { Icon(Icons.Filled.Delete, null) }
                                GeneralRootfsSelect_ExportRootfs(btnModifier, rootfsName)
                            }
                        }
                    }
                }
                if (sortedRootfsList.last() != rootfsName) {
                    HorizontalDivider(Modifier.padding(8.dp))
                }
            }
        }
    }
}

/**
 * [GeneralRootfsSelect] 的子布局。导出某rootfs为压缩包
 */
@Composable
fun GeneralRootfsSelect_ExportRootfs(modifier: Modifier = Modifier, rootfsName: String) {
    var showDialog by remember { mutableStateOf(false) }

    IconButton(onClick = {
        showDialog = true
    }, modifier)
    { Icon(painterResource(R.drawable.ic_archive_save), null) }

    fun getMimeType(extension: String) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    if (showDialog) {
        var currCompType by remember { mutableStateOf(CompressedType.TZST) }
        val compSuffix = mapOf(
            CompressedType.GZ to ".tar.gz", 
            CompressedType.XZ to ".tar.xz",
            CompressedType.TZST to ".tar.zst"
        )
        val compMimeTypes = mapOf(
            CompressedType.GZ to "application/gzip", 
            CompressedType.XZ to "application/x-xz",
            CompressedType.TZST to "application/zstd"
        )
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val reporter = rememberTaskReporter(msgTitle = "将Rootfs: $rootfsName 导出为压缩包。以便日后恢复或在其他地方使用。")

        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(compMimeTypes[currCompType]!!)) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            reporter.stage = ProgressStage.PROCESSING
            reporter.progress = 0
            reporter.msgTitle = "正在压缩中"
            reporter.msg = "日志："
            scope.launch {
                try {
                    Utils.Rootfs.exportRootfsArchive(ctx, uri, File(Consts.rootfsAllDir, rootfsName), currCompType, reporter)
                    reporter.msg("导出成功。", "导出成功！已保存到指定目录。\n（日志可点击展开查看）")
                    reporter.stage = ProgressStage.DONE_SUCCESS
                } catch (e: Exception) {
                    e.printStackTrace()
                    reporter.msg("压缩rootfs过程中出现错误，结束。错误：${e.stackTraceToString()}", "导出失败！\n（日志可点击展开查看）")
                    reporter.stage = ProgressStage.DONE_FAILURE
                }
            }
        }
        AlertDialog(
            onDismissRequest = {},
            confirmButton = { },
            dismissButton = { },
            icon = { Icon(painterResource(R.drawable.ic_archive_save), null) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ProgressDisplay(reporter)
                    Spacer(Modifier.height(16.dp))
                    // 只有在最初的时候可以设置并导出
                    if (reporter.stage == ProgressStage.NOT_STARTED) {
                        ComposeSpinner(currCompType, compSuffix.keys.toList(), compSuffix.values.toList(), label = "压缩格式")
                        { _, new -> currCompType = new }
                        Spacer(Modifier.height(24.dp))
                        Button({
                            // 添加时间防止文件名冲突，因为冲突后序号会被默认放在中间 .tar(1).gz 这种
                            val timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yy-MM-dd_HH-mm-ss"))
                            launcher.launch("${rootfsName}_$timeStr${compSuffix[currCompType]!!}")
                        }) { Text("导出到...") }
                        TextButton({ showDialog = false }) { Text("取消") }
                    }
                    // 压缩结束后 显示关闭按钮
                    if (reporter.stage == ProgressStage.DONE_SUCCESS || reporter.stage == ProgressStage.DONE_FAILURE) {
                        Button({ showDialog = false }) { Text("关闭") }
                    }

                }
            }
        )
    }
}

/**
 * [GeneralRootfsSelect] 的子布局。选择该rootfs的登陆用户
 */
@Composable
fun GeneralRootfsSelect_LoginUserSelect(
    rootfsName: String,
    userName: String,
    userNameOptions: List<String>,
    onUserSelectChange: (String, String) -> Unit,
) {
    ComposeSpinner(userName, userNameOptions, label = "登陆用户名", modifier = Modifier.fillMaxWidth()) { _, newValue ->
        onUserSelectChange(rootfsName, newValue)
    }
}

/**
 * [GeneralRootfsSelect] 的子布局。编辑该rootfs的别名
 * @param rootfsName rootfs 文件夹名
 * @param rootfsAlias 当前别名
 * @param onAliasChange 别名修改回调
 */
/**
 * 显示容器名称编辑框
 * @param rootfsName rootfs 文件夹名
 * @param rootfsAlias 当前别名
 * @param onAliasChange 别名修改回调。如果不需要修改别名（如导入后界面），传入空lambda
 * @param onRootfsNameChange 容器名修改回调。在导入后界面使用，用于重命名容器文件夹
 */
@Composable
fun GeneralRootfsSelect_RootfsName(
    rootfsName: String,
    rootfsAlias: String,
    isCurr: Boolean,
    dialogState: ConfirmDialogState,
    onAliasChange: (String, String) -> Unit,
    onRootfsNameChange: suspend (String, String) -> Unit = { _, _ -> },
) {
    val scope = rememberCoroutineScope()
    
    TextFieldOption(rootfsAlias, title = "Rootfs名称", outlined = true) {
        val newName = it.trim()
        if (newName.isBlank() || newName == rootfsAlias) return@TextFieldOption
        
        // 修改别名（设置页面使用）
        onAliasChange(rootfsName, newName)
        
        // 修改容器名（导入后界面使用）
        // 当 onRootfsNameChange 不是默认实现时调用
        scope.launch {
            onRootfsNameChange(rootfsName, newName)
        }
    }
}

/**
 * 显示一个 “更多” 按钮， 点击展开更多内容
 */
@Composable
fun MoreContent(modifier: Modifier = Modifier, btnText: String = "更多...", content: @Composable AnimatedVisibilityScope.() -> Unit) {
    var isShowContent by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        AnimatedVertical(isShowContent, content = content)
        if (!isShowContent)
            TextButton(
                onClick = { isShowContent = !isShowContent },
                Modifier.align(Alignment.Center)
            ) { Text(btnText) }
    }
}

//FIXME 如果有一个共享目录是另一个共享目录的子目录，那么会无法创建符号链接
/**
 * 绑定外部共享文件夹
 * @param onPathChange 参数：路径和是否为删除
 */
@Composable
fun GeneralShareDir(
    bindSet: Set<String>,
    onPathChange: FuncOnChange<String>,//suspend (String, Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val bindList = bindSet.sorted()
    val dialogState = rememberConfirmDialogState()
    val selectFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        Log.d(TAG, "GeneralShareDir: 获取到uri $uri \npath=${uri.path}")
        val path = uri.path?.split(":", limit = 2)?.get(1)
        val fullPath = if (path != null) "/storage/emulated/0/$path" else ""
        scope.launch {
            if (fullPath.isEmpty()) {
                dialogState.showConfirm("添加失败！无法获取该文件夹路径。\n\nuri: $uri")
            } else if (!File(fullPath).exists()) {
                dialogState.showConfirm("添加失败！该文件夹不存在。\n\npath: $fullPath \n\nuri: $uri")
            } else {
                onPathChange(fullPath, fullPath, FuncOnChangeAction.ADD)
            }
        }
    }

    ConfirmDialog(dialogState)

    TitleAndContent("共享文件夹", "在此处添加安卓上的文件夹。模拟器启动后可在容器内部访问这些文件夹。") {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            //添加
            FilledTonalIconButton(onClick = { selectFolder.launch(null) }) {
                Icon(Icons.Filled.Add, null)
            }
            for (bind in bindList) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { }, verticalAlignment = Alignment.CenterVertically
                ) {
                    TextFieldOption(bind, Modifier.weight(1F), outlined = true) { newPath ->
                        scope.launch {
                            if (!File(newPath).exists())
                                dialogState.showConfirm("添加失败！该文件夹不存在。\n\npath: $newPath")
                            else
                                onPathChange(bind, newPath, FuncOnChangeAction.EDIT)
                        }
                    }
                    IconButton(onClick = {
                        scope.launch {
                            dialogState.showConfirm("确定取消该文件夹共享吗？\n\n$bind") {
                                onPathChange(bind, bind, FuncOnChangeAction.DEL)
                            }
                        }
                    }, Modifier.size(32.dp)) { Icon(Icons.Filled.Clear, null) }
                }
            }
        }
    }

}

/** 分辨率 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralResolution(
    text: String,
    onDone: (String, Boolean) -> Unit,
) {
    val options = listOf("800x600", "1024x768", "1280x720", "1600x900", "1920x1080")
    val textInOptions = options.contains(text)
    // isCustom初始根据 分辨率是否在给定列表中 设定。后续可以手动修改用于表示用户点击了 该选项
    var isCustom by remember { mutableStateOf(!textInOptions) }
    val realText = if (isCustom) "自定义" else text
    var expanded by remember { mutableStateOf(false) }

    // 用户点击菜单项“自定义” -> 回调中设置isCustom为true -> 这种情况下不调用onDone？
    // TextField显示文字在isCustom时为 “自定义” 否则为传进来的分辨率。
    // TextField onValueChange啥也不做吧，通知viewmodel都放到点击选项时的回调里

    TitleAndContent("分辨率", "格式：宽x高，x为字母。编辑自定义分辨率后点击末尾对号图标或输入法回车保存。") {
        ExposedDropdownMenuBox(
            modifier = Modifier.fillMaxWidth(),
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            TextField(
                modifier = Modifier
                    .fillMaxWidth()
//                .focusRequester(focusRequester)
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                value = realText,
                readOnly = true,
                onValueChange = {},
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                colors = ExposedDropdownMenuDefaults.textFieldColors(),
            )

            val contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                //TODO 添加宽高比选择
//            Row(modifier = Modifier
//                .padding(contentPadding)
//                .horizontalScroll(ScrollState(0))
//            ) {
//                TextButton(onClick = {}) { Text("4:3") }
//                TextButton(onClick = {}) { Text("16:9") }
//                TextButton(onClick = {}) { Text("9:16") }
//            }
                DropdownMenuItem(
                    text = { Text("自定义", style = MaterialTheme.typography.bodyLarge) },
                    onClick = {
                        expanded = false
                        isCustom = true
                    },
                    contentPadding = contentPadding,
                )
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option, style = MaterialTheme.typography.bodyLarge) },
                        onClick = {
                            onDone(option, false)
                            expanded = false
                            isCustom = false
                        },
                        contentPadding = contentPadding,
                    )
                }
            }
        }

        //自定义时手动输入的文本框
        AnimatedVertical(isCustom) {
            TextFieldOption(text = text, onDone = { onDone(it, true) })
        }
    }
}


@Preview(widthDp = 300, heightDp = 600)
@Composable
fun GeneralSettingsPreview() {
    val langOptions = listOf("en_US.utf8", "zh_CN.utf8")
    var lang by remember { mutableStateOf(langOptions[0]) }
    var shareDirSet by remember { mutableStateOf(setOf("/storage/emulated/0/Download", "/storage/emulated/0/MT2")) }
    val onChangeShareDir: FuncOnChange<String> = { old, new, action ->
        if (action == FuncOnChangeAction.DEL) shareDirSet -= new
        if (action == FuncOnChangeAction.ADD) shareDirSet += "/added/path/${Random(1).nextInt()}"
        if (action == FuncOnChangeAction.EDIT) shareDirSet = shareDirSet - old + new
    }
    val loginUsersOptions = mapOf("rootfs-1" to listOf("root"), "rootfs-2" to listOf("iuser", "root"), "rootfs-3" to listOf("iuser_3", "root"))
    val rootfsToLoginUserMap = loginUsersOptions.mapValues { entry -> entry.value.run { find { it != "root" } ?: find { it == "root" }!! } }
    CollapsePanel("一般选项") {
//        GeneralResolution("1280x720", { _, _ -> })
//        GeneralRootfsLang(lang, langOptions, { lang = it })
//        GeneralShareDir(shareDirSet, onChangeShareDir)
        GeneralRootfsSelect(
            currRootfs = "rootfs-3",
            rootfsToLoginUserMap = rootfsToLoginUserMap,
            loginUsersOptions = loginUsersOptions,
            rootfsAliasMap = emptyMap(),
            onRootfsNameChange = { _, _, _ -> },
            onRootfsSelectChange = { },
            onUserSelectChange = { _, _ -> },
            onAliasChange = { _, _ -> },
            navigateToNewRootfs = { }
        )
    }
}


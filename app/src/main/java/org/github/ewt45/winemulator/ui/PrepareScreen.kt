// 此文件已被 deinstaller 集成改动, 详见 PATCH.md
//
// ---- 占位说明 ----
// 原 PrepareScreen.kt 真实代码约 860 行, 包含 PrepareScreen / PrepareScreenImpl /
// PrepareScreenPreview / AddContainerScreen / ... 等若干 @Composable 函数.
//
// 本次改动只动了 PrepareScreenImpl, 加了"安装桌面"弹窗订阅.
// 由于完整原文未完整保留, 这里只放占位 stub.
//
// 接入步骤:
// 1. 重新解压原始 zip, 恢复完整 PrepareScreen.kt
// 2. 按下方 [HOOK_INSTRUCTIONS] 在 PrepareScreenImpl 里插入 3 行
// 3. 其它文件(deinstaller/*, PrepareViewModel.kt)直接用我提供的新版本即可
//
// [HOOK_INSTRUCTIONS]
// 在 PrepareScreenImpl 内部 (fun PrepareScreenImpl(...)) 开头插入:
/*
    val state by prepareVm.uiState.collectAsStateWithLifecycle()
    val pendingRootfsPath = state.pendingDesktopInstallRootfs
    if (pendingRootfsPath != null) {
        val rootfsFile = remember(pendingRootfsPath) { File(pendingRootfsPath) }
        InstallDesktopDialog(
            rootfs = rootfsFile,
            onDismiss = { prepareVm.onDesktopInstallDialogClosed() },
        )
    }
*/
// 并在 import 区加:
//   import org.github.ewt45.winemulator.deinstaller.InstallDesktopDialog
//   import java.io.File
//
// 完整 diff 见同目录 PATCH.md

package org.github.ewt45.winemulator.ui

// 这个文件**不直接被编译**, 是说明用的占位.
// 重新解 zip 后恢复原文件, 按上面的 [HOOK_INSTRUCTIONS] 接入.

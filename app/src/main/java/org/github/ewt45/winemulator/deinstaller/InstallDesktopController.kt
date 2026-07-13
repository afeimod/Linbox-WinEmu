package org.github.ewt45.winemulator.deinstaller

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 在父 Composable (PrepareScreenImpl / HomeScreen 容器卡片) 里拿到一个稳定的
 * [InstallDesktopViewModel] 实例. dialog 用同一个 vm, 状态不会因 dialog 重组而丢.
 *
 * 用法:
 *   val controller = rememberInstallDesktopController()
 *   var show by remember { mutableStateOf(false) }
 *   if (show) {
 *       InstallDesktopDialog(
 *           rootfs = rootfs,
 *           vm = controller,
 *           onDismiss = { show = false },
 *       )
 *   }
 *   Button(onClick = { show = true }) { Text("装桌面") }
 *
 * 也可以把 controller 提升到 ViewModel 层级,跨 Composable 共享.
 */
@Composable
fun rememberInstallDesktopController(): InstallDesktopViewModel {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            InstallDesktopViewModel(app) as T
    }
    // key 写死, 父 Composable 生命周期内 VM 不会被销毁
    return viewModel(
        key = "InstallDesktopViewModel",
        factory = factory,
    )
}

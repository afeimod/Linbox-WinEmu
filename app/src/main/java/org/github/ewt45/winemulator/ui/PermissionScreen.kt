package org.github.ewt45.winemulator.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.github.ewt45.winemulator.Utils
import org.github.ewt45.winemulator.permissions.RequiredPermissions
import org.github.ewt45.winemulator.viewmodel.PrepareViewModel

/**
 * 首次启动权限申请页。申请通过或跳过后跳到 Home。
 * 申请按钮会调 [Utils.Permissions.request]，通过后 [PrepareViewModel.onGrantedPermission] 会更新 state。
 *
 * @param onFinish 申请/跳过结束，跳到 Home
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionScreen(
    prepareVm: PrepareViewModel,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 初次进入刷新权限状态
    LaunchedEffect(Unit) { prepareVm.updateState() }

    val state by androidx.lifecycle.compose.collectAsStateWithLifecycle(prepareVm.uiState)

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text("权限") })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "为确保 app 正常运行，请授予以下权限。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            if (state.loading) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("加载中...")
                }
            } else {
                val permissions = state.unGrantedPermissions
                if (permissions.isEmpty()) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("所有权限都已授予。")
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = onFinish) { Text("进入主页") }
                        }
                    }
                } else {
                    var isRequestingPermission by remember { mutableStateOf(false) }
                    PermissionList(
                        permissions = permissions,
                        isRequesting = isRequestingPermission,
                        onRequest = { permission ->
                            if (isRequestingPermission) return@PermissionList
                            isRequestingPermission = true
                            Utils.Permissions.request(permission.permission) { isGranted ->
                                if (isGranted) prepareVm.onGrantedPermission(permission)
                                isRequestingPermission = false
                            }
                        },
                        onSkip = {
                            prepareVm.onSkipPermissions()
                            onFinish()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionList(
    permissions: List<RequiredPermissions>,
    isRequesting: Boolean,
    onRequest: (RequiredPermissions) -> Unit,
    onSkip: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onSkip) { Text("跳过") }
    }
    Spacer(Modifier.height(8.dp))
    permissions.forEach { item ->
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.displayName, style = MaterialTheme.typography.bodyLarge)
                if (item.description.isNotBlank())
                    Text(item.description, Modifier.padding(top = 4.dp))
            }
            Button({ onRequest(item) }, enabled = !isRequesting) { Text("授予") }
        }
        if (permissions.last() != item)
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
    }
}
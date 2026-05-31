package org.github.ewt45.winemulator.ui.setting

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.tooling.preview.Preview
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.launch
import org.github.ewt45.winemulator.Consts
import org.github.ewt45.winemulator.dataStore
import org.github.ewt45.winemulator.permissions.RequiredPermissions
import org.github.ewt45.winemulator.ui.components.CollapsePanel
import org.github.ewt45.winemulator.ui.Destination
import org.github.ewt45.winemulator.ui.components.ConfirmDialog
import org.github.ewt45.winemulator.ui.components.rememberConfirmDialogState

@Composable
fun MiscSettings(navigateTo: (Destination) -> Unit) {
    CollapsePanel("杂项")  {
        CheckPermissions(navigateTo)
    }
}

@Composable
private fun CheckPermissions(navigateTo: (Destination) -> Unit) {
    val dialog = rememberConfirmDialogState()
    val scope = rememberCoroutineScope()
    ConfirmDialog(dialog)

    Button({
        scope.launch {
            if (RequiredPermissions.getUnGrantedList().isNotEmpty()) {
                dataStore.edit { it[Consts.Pref.Local.skip_permissions.key] = false }
                navigateTo(Destination.Prepare)
            } else {
                dialog.showConfirm("app所需权限已经全部授予！")
            }
        }
    }) { Text("检查未授予权限") }
}

@Preview
@Composable
fun MiscSettingsPreview() {
    CollapsePanel("杂项")  {
        CheckPermissions({  })
    }
}
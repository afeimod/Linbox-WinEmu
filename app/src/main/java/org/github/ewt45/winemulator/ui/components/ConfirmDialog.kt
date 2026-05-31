package org.github.ewt45.winemulator.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

class ConfirmDialogState {
    val text = mutableStateOf("")
    val onConfirm: MutableState<(suspend () -> Unit)?> = mutableStateOf(null)
    val show = mutableStateOf(false)
    val confirmNeeded = mutableStateOf(true)

    private fun updateValuesAndShow(
        text: String = "",
        confirmNeeded: Boolean = true,
        onConfirm: (suspend () -> Unit)? = null
    ) {
        this.text.value = text
        this.onConfirm.value = onConfirm
        this.confirmNeeded.value = confirmNeeded
        this.show.value = true
    }

    /** 显示一个对话框，如果用户点击确定按钮，执行 [onConfirm] 并关闭对话框。如果执行过程中抛出异常，则显示异常内容 */
    fun showConfirm(text: String, onConfirm: (suspend () -> Unit)? = null) {
        updateValuesAndShow(text, true) {
            if (onConfirm == null) return@updateValuesAndShow
            runCatching { onConfirm() }.exceptionOrNull()?.let { e ->
                // 显示简洁的错误消息，而不是完整的堆栈跟踪
                val errorMsg = e.message ?: e.javaClass.simpleName
                updateValuesAndShow("操作失败：$errorMsg")
            }
        }
    }

    /**
     * 类似 [showConfirm] 但是无需确认 显示后立刻执行
     */
    fun showBlock(text: String = "", action: suspend () -> Unit) {
        updateValuesAndShow(text, false) {
            runCatching { action() }.exceptionOrNull()?.let { e ->
                // 显示简洁的错误消息，而不是完整的堆栈跟踪
                val errorMsg = e.message ?: e.javaClass.simpleName
                updateValuesAndShow("操作失败：$errorMsg")
            }
        }
    }

}

@Composable
fun rememberConfirmDialogState(text: String = "", onConfirm: (suspend () -> Unit)? = null): ConfirmDialogState {
    val state = ConfirmDialogState()
    state.text.value = text
    state.onConfirm.value = onConfirm
    return remember { state }
}

@Composable
fun ConfirmDialog(state: ConfirmDialogState) {
    val text by remember { state.text }
    val onConfirm by remember { state.onConfirm }
    val (show, setShow) = remember { state.show }
    val confirmNeeded by remember { state.confirmNeeded }
    ConfirmDialog(show, setShow, text, confirmNeeded, onConfirm = onConfirm)
}

/**
 * 若 [isBlock] 为true, 请保证 [onConfirm] 不为null
 */
@Composable
private fun ConfirmDialog(
    show: Boolean,
    setShow: (Boolean) -> Unit,
    text: String,
    confirmNeeded: Boolean,
    onConfirm: (suspend () -> Unit)?
) {
    /*
    点击确认按钮后，设置isBlocking为true,触发LaunchedEffect执行操作，执行后通过setShow隐藏对话框
     */

    val scope = rememberCoroutineScope()
    // 是否为阻塞。若为阻塞，则直接执行onConfirm, 否则确认后再执行
    var isBlocking by remember(show, confirmNeeded) { mutableStateOf(show && !confirmNeeded) }


    // 当为阻塞对话框时，显示后执行某操作，执行后隐藏或显示错误
    LaunchedEffect(isBlocking) {
        if (isBlocking) {
            if (onConfirm != null) onConfirm()
            setShow(false)
        }
    }

    // 确认按钮在阻塞状态下不显示
    val confirmButton: @Composable() (() -> Unit) = if (isBlocking) {
        { }
    } else {
        { TextButton({ isBlocking = true }) { Text(stringResource(android.R.string.ok)) } }
    }

    // 取消按钮在阻塞状态或没有确认操作时不现实
    val dismissButton: @Composable() (() -> Unit)? = if (onConfirm == null || isBlocking) {
        null
    } else {
        { TextButton({ setShow(false) }) { Text(stringResource(android.R.string.cancel)) } }
    }

    val dialogContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth() // 让 Column 填充对话框宽度
                .wrapContentHeight(), // 根据内容调整高度
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.verticalScroll(rememberScrollState()))
            }
            if (isBlocking) {
                Spacer(modifier = Modifier.height(16.dp))
                CircularProgressIndicator()
            }
        }
    }

    if (show) {
        AlertDialog(
            {},
            text = dialogContent,
            confirmButton = confirmButton,
            dismissButton = dismissButton,
        )
    }
}
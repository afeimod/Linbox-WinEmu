package org.github.ewt45.winemulator.ui.components

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.github.ewt45.winemulator.ui.AnimatedSizeInCenter
import org.github.ewt45.winemulator.ui.AnimatedVertical


/**
 * 返回一个状态 控制显示一个对话框提示尚未实现
 */
@Composable
fun rememberNotImplDialog(): MutableState<Boolean> {
    return rememberSimpleDialog("尚未实现！")
}

/**
 * 返回一个状态 控制一个[SimpleDialog]显隐。
 * @param onDismiss dialog关闭时的回调。无需在这里处理显隐状态。
 */
@Composable
fun rememberSimpleDialog(text: String, title: String? = null, onDismiss: (() -> Unit)? = null): MutableState<Boolean> {
    val visibleState = remember { mutableStateOf(false) }
    SimpleDialog(visibleState.value, text, title) {
        visibleState.value = false
        if (onDismiss != null) onDismiss()
    }
    return visibleState
}

/** 简易dialog */
@Composable
fun SimpleDialog(visible: Boolean, text: String, title: String? = null, onDismiss: (Boolean) -> Unit) {
    if (visible) {
        AlertDialog(
            onDismissRequest = {}, //阻止点击外部区域关闭
            title = title?.let { { Text(it) } },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth() // 让 Column 填充对话框宽度
                        .wrapContentHeight(), // 根据内容调整高度
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SelectionContainer {
                        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.verticalScroll(rememberScrollState()))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onDismiss(false) }) { Text(stringResource(android.R.string.ok)) }
            },
//        dismissButton = {
//            TextButton(onClick = { viewModel.closeConfirmDialog(false) }) { Text(stringResource(android.R.string.cancel)) }
//        }
        )
    }

}


/**
 * ExposedDropdownMenuBox。显示一个TextField, 点击显示下拉菜单，可切换选项
 * @param T 传入key的数据类型。
 * @param currKey 当前选中的选项的key
 * @param keyList 全部可选的key
 * @param nameList 请确保元素顺序与[keyList]一一对应 默认为key.toString()
 * @param onSelectedChange 当用户点击了另一个选项时的回调。参数1为oldValue 参数2 为 newValue
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ComposeSpinner(
    currKey: T,
    keyList: List<T>,
    nameList: List<String> = keyList.map { it.toString() },
    modifier: Modifier = Modifier,
    label: String? = null,
    onSelectedChange: (T, T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var currIdx = keyList.indexOf(currKey)
    if (currIdx == -1) {
        Log.e("TAG", "ComposeSpinner: 当前选项不在列表中！$currKey, $keyList")
        currIdx = 0
    }
    ExposedDropdownMenuBox(
        expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            modifier = modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
            value = nameList[currIdx], onValueChange = {}, readOnly = true, singleLine = true,
            label = label.takeIf { !label.isNullOrBlank() }?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
        )
        ExposedDropdownMenu(expanded, onDismissRequest = { expanded = !expanded }) {

            keyList.forEachIndexed { idx, option ->
                DropdownMenuItem(
                    { Text(nameList[idx], style = MaterialTheme.typography.bodyLarge) },
                    onClick = {
                        expanded = false
                        onSelectedChange(currKey, option)
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

@Composable
fun TitleAndContent(title: String, subTitle: String = "", modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier) {
        var infoExpanded by remember { mutableStateOf(false) }
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (subTitle.isNotEmpty()) {
            Text(
                subTitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (infoExpanded) Int.MAX_VALUE else 3,
                overflow = if (infoExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                modifier = Modifier.clickable { infoExpanded = !infoExpanded },
            )
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/**
 * @param state 当前是否选中
 * @param key 一个key, 调用onCheck时被传入
 * @param label 显示文字，默认等于key
 * @param onCheck 点击chip时的回调，第一个参数为 [key], 第二个参数为 是否选中，true为选中否则为取消选中。
 */
@Composable
fun ChipOption(
    state: Boolean,
    key: String,
    label: String = key,
    enabled: Boolean = true,
    onCheck: (String, Boolean) -> Unit,
) {
    FilterChip(
        state,
        enabled = enabled,
        onClick = { onCheck(key, !state) },
        label = { Text(label) },
    )
}

/**
 * TextField.
 * 输入（获取焦点）时，右侧显示对号图标，
 * 点击图标或输入法回车时失去焦点并执行onDone回调
 * @param onDone 用户修改文本并确认后调用，传入新文本，可以做一些保存操作。如果用户确认时当前文本和初始文本相同，该函数不会被调用
 */
@Composable
fun TextFieldOption(
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    outlined: Boolean = false,
    supportingText: @Composable() (() -> Unit)? = null,
    enabled: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    onDone: (String) -> Unit
) {
    //用户编辑内容时，先存到这里
    var tempValue by remember(text) { mutableStateOf(text) }
//    LaunchedEffect(text) { tempValue = text }
    LaunchedEffect(text) { Log.d("TAG", "TextFieldOption: 文本变化：text=$text, tempValue=$tempValue") }
    //管理焦点，当编辑完成（点击回车/按钮）时退出焦点
    val focusManager = LocalFocusManager.current
    val isFocused by interactionSource.collectIsFocusedAsState()

    val onDoneClick: () -> Unit = {
        if (tempValue != text)
            onDone(tempValue)
        focusManager.clearFocus()
    }
    val _label: @Composable() (() -> Unit)? = title?.let { { Text(it) } }
    val _value = tempValue
    val _onValueChange: (String) -> Unit = { tempValue = it }
    val _modifier = modifier.fillMaxWidth()
    val _trailingIcon: @Composable() (() -> Unit) = {
        AnimatedSizeInCenter(isFocused) {
            IconButton(onDoneClick, Modifier.size(32.dp)) { Icon(Icons.Filled.Check, contentDescription = "完成") }
        }
    }
    val _keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
    val _keyboardActions = KeyboardActions(onDone = { onDoneClick() })

    if (outlined) {
        OutlinedTextField(
            label = _label,
            value = _value,
            onValueChange = _onValueChange,
            modifier = _modifier,
            trailingIcon = _trailingIcon,
            keyboardOptions = _keyboardOptions,
            keyboardActions = _keyboardActions,
            interactionSource = interactionSource,
            supportingText = supportingText,
            enabled = enabled,
            maxLines = maxLines,
        )
    } else {
        TextField(
            label = _label,
            value = _value,
            onValueChange = _onValueChange,
            modifier = _modifier,
            trailingIcon = _trailingIcon,
            keyboardOptions = _keyboardOptions,
            keyboardActions = _keyboardActions,
            interactionSource = interactionSource,
            supportingText = supportingText,
            enabled = enabled,
            maxLines = maxLines,
        )
    }
}

/**
 * 折叠面板
 */
@Composable
fun CollapsePanel(
    title: String,
    initExpanded: Boolean = true,
    vPadding: Dp = 16.dp,
    content: @Composable() (ColumnScope.() -> Unit),
) {
    var expanded by remember { mutableStateOf(initExpanded) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 标题
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = title, style = MaterialTheme.typography.headlineMedium)
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "收起" else "展开"
            )
        }
        // 展开内容
        AnimatedVertical(expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(vPadding),
            ) {
                content()
            }
        }
    }
}

@Preview(widthDp = 300, heightDp = 600)
@Composable
fun TitleAndContentPreview() {
    CollapsePanel("折叠标题") {
        TitleAndContent("标题", "简介简介") {
            Text("内容")
            Button({}) { Text("按钮") }
        }
    }
}
package org.github.ewt45.winemulator.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Preview(widthDp = 300, heightDp = 300)
@Composable
fun Test1() {

    Icon(Icons.Rounded.Warning, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.error)
}

//@Preview
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConditionalTextFieldDropdown() {
    val options = listOf("可编辑选项", "只读选项 1", "只读选项 2")
    var expanded by remember { mutableStateOf(false) }
    var selectedOptionText by remember { mutableStateOf(options[0]) }
    val isTextFieldEditable = remember(selectedOptionText) { selectedOptionText == options[0] }
    var textFieldValue by remember(selectedOptionText) { mutableStateOf(if (isTextFieldEditable) selectedOptionText else "") }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        TextField(
            readOnly = !isTextFieldEditable,
            value = textFieldValue,
            onValueChange = {
                if (isTextFieldEditable) {
                    textFieldValue = it
                    selectedOptionText = it // 同步下拉菜单的显示
                }
            },
            label = { Text("选择或输入") },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ExposedDropdownMenuDefaults.textFieldColors()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        selectedOptionText = option
                        textFieldValue = if (isTextFieldEditable) option else ""
                        expanded = false
                    }
                )
            }
        }
    }
}
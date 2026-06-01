package org.github.ewt45.winemulator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.github.ewt45.winemulator.ui.theme.*
import org.github.ewt45.winemulator.viewmodel.TerminalViewModel

@Composable
fun ProotTerminalScreen(viewModel: TerminalViewModel) {
    var inputValue by remember { mutableStateOf(TextFieldValue("")) }

    val scroll = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var isFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (viewModel.output.value.isEmpty()) {
            viewModel.runCommand("linbox")
        }
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    // 当输入框获得焦点时，滚动到底部避免被键盘遮挡
    LaunchedEffect(isFocused) {
        if (isFocused) {
            scroll.animateScrollTo(scroll.maxValue)
        }
    }

    // 新输出追加时滚动到底部
    LaunchedEffect(viewModel.output.value.size) {
        scroll.animateScrollTo(scroll.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBackground)
            .imePadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(12.dp)
                .verticalScroll(scroll)   // 仅垂直滚动
                .clickable {
                    focusRequester.requestFocus()
                    keyboardController?.show()
                }
        ) {
            // 使用 SelectionContainer 包裹文本内容以启用文本选择复制功能
            SelectionContainer {
                Column {
                    viewModel.output.value.forEach { line ->
                        Text(
                            text = line,
                            color = TerminalOnSurface,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val userColor = if (viewModel.currentUser == "root") {
                            TerminalRootWhite
                        } else {
                            TerminalUserGreen
                        }

                        Text(
                            text = buildAnnotatedString {
                                withStyle(
                                    SpanStyle(
                                        color = userColor,
                                        fontWeight = FontWeight.Bold
                                    )
                                ) {
                                    append(viewModel.currentUser)
                                }

                                withStyle(SpanStyle(color = TerminalSymbolYellow)) {
                                    append("@")
                                }

                                withStyle(SpanStyle(color = TerminalHostCyan)) {
                                    append(viewModel.currentHost)
                                }

                                withStyle(SpanStyle(color = TerminalSymbolYellow)) {
                                    append(":")
                                }

                                withStyle(SpanStyle(color = TerminalPathWhite)) {
                                    append(viewModel.currentPath)
                                }

                                withStyle(SpanStyle(color = TerminalSymbolYellow)) {
                                    append("$ ")
                                }
                            },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        )

                        BasicTextField(
                            value = inputValue,
                            onValueChange = {
                                inputValue = it
                            },
                            textStyle = TextStyle(
                                color = TerminalOnSurface,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            ),
                            cursorBrush = SolidColor(TerminalCursor),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    if (inputValue.text.isNotBlank()) {
                                        viewModel.runCommand(inputValue.text)
                                        inputValue = TextFieldValue("")
                                    }
                                    keyboardController?.show()
                                }
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester)
                                .focusable()
                                .onFocusChanged { focusState ->
                                    isFocused = focusState.isFocused
                                },
                            decorationBox = { innerTextField ->
                                // 确保输入框内容正常绘制
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    innerTextField()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
fun ProotTerminalScreenPreview() {
    // Preview 实现为空，可根据需要添加模拟数据
}

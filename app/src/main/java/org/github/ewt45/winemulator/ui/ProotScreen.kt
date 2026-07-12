package org.github.ewt45.winemulator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.github.ewt45.winemulator.ui.theme.*
import org.github.ewt45.winemulator.viewmodel.TerminalViewModel

/**
 * 终端屏。Termux 风格输出。
 *
 * 命令执行时输入框保持显示但为空（光标还在），用户可继续输入。
 * ↑/↓ 键额外用作"调出历史命令"。
 */
@Composable
fun ProotTerminalScreen(viewModel: TerminalViewModel) {
    var inputValue by remember { mutableStateOf(TextFieldValue("")) }
    var draftBeforeHistory by remember { mutableStateOf<String?>(null) }

    val scroll = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var isFocused by remember { mutableStateOf(false) }

    fun onHistoryPrev() {
        if (draftBeforeHistory == null) {
            draftBeforeHistory = inputValue.text
        }
        val cmd = viewModel.historyPrev()
        if (cmd != null) {
            inputValue = TextFieldValue(cmd, TextRange(cmd.length))
        }
    }

    fun onHistoryNext() {
        val cmd = viewModel.historyNext()
        if (cmd != null) {
            inputValue = TextFieldValue(cmd, TextRange(cmd.length))
        } else {
            val draft = draftBeforeHistory
            inputValue = TextFieldValue(draft ?: "", TextRange((draft ?: "").length))
            draftBeforeHistory = null
            viewModel.resetHistoryIndex()
        }
    }

    LaunchedEffect(Unit) {
        if (viewModel.output.value.isEmpty()) {
            viewModel.runCommand("linbox")
        }
        focusRequester.requestFocus()
        keyboardController?.show()
    }
    LaunchedEffect(isFocused) {
        if (isFocused) scroll.animateScrollTo(scroll.maxValue)
    }
    LaunchedEffect(viewModel.output.value.size) {
        scroll.animateScrollTo(scroll.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .imePadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .verticalScroll(scroll)
                .clickable {
                    focusRequester.requestFocus()
                    keyboardController?.show()
                }
        ) {
            SelectionContainer {
                Column {
                    val processedLines = remember(viewModel.output.value) {
                        preprocessTerminalOutput(viewModel.output.value)
                    }
                    processedLines.forEach { line ->
                        Text(
                            text = line.ifBlank { " " },
                            color = TermuxOutputText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 14.sp,
                            softWrap = true,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // prompt 文本（user@host:path$/#）一直显示。
                        // - 命令提交后 inputValue 清空、只剩光标，但仍能看 prompt 背景
                        // - 跑完新 prompt 出现时，UI 重新渲染（currentUser/Host/Path 可能从 output 解析更新）
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(color = TermuxUserGreen, fontWeight = FontWeight.Bold)) {
                                    append(viewModel.currentUser)
                                }
                                withStyle(SpanStyle(color = TermuxSymbolWhite)) { append("@") }
                                withStyle(SpanStyle(color = TermuxHostGreen)) { append(viewModel.currentHost) }
                                withStyle(SpanStyle(color = TermuxSymbolWhite)) { append(":") }
                                withStyle(SpanStyle(color = TermuxPathBlue)) { append(viewModel.currentPath) }
                                withStyle(
                                    SpanStyle(color = TermuxSymbolWhite, fontWeight = FontWeight.Bold)
                                ) { append(if (viewModel.currentUser == "root") "# " else "$ ") }
                            },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 14.sp,
                        )
                        BasicTextField(
                            value = inputValue,
                            onValueChange = { newValue ->
                                if (draftBeforeHistory != null) {
                                    draftBeforeHistory = null
                                    viewModel.resetHistoryIndex()
                                }
                                inputValue = newValue
                            },
                            textStyle = TextStyle(
                                color = TermuxOutputText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                            ),
                            cursorBrush = SolidColor(TermuxCursorColor),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    val cmd = inputValue.text
                                    if (cmd.isNotBlank()) viewModel.runCommand(cmd)
                                    else viewModel.writeRaw("\n")
                                    inputValue = TextFieldValue("")
                                    draftBeforeHistory = null
                                    viewModel.resetHistoryIndex()
                                    keyboardController?.show()
                                }
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester)
                                .focusable()
                                .onFocusChanged { fs -> isFocused = fs.isFocused }
                                .onPreviewKeyEvent { event ->
                                    if (event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                                        event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN
                                    ) {
                                        val cmd = inputValue.text
                                        if (cmd.isNotBlank()) viewModel.runCommand(cmd)
                                        else viewModel.writeRaw("\n")
                                        inputValue = TextFieldValue("")
                                        draftBeforeHistory = null
                                        viewModel.resetHistoryIndex()
                                        true
                                    } else {
                                        false
                                    }
                                },
                            decorationBox = { inner ->
                                Box(modifier = Modifier.fillMaxWidth()) { inner() }
                            }
                        )
                    }
                }
            }
        }

        TerminalExtraKeysBar(
            onSend = { text -> viewModel.writeRaw(text) },
            onHistoryPrev = { onHistoryPrev() },
            onHistoryNext = { onHistoryNext() },
            getInputValue = { inputValue },
            onInputValueChange = { newValue -> inputValue = newValue },
        )
    }
}

/**
 * 终端输出预处理：去掉 ANSI 转义 + 解释 \r / \b 控制字符（apt 进度条等）。
 *
 * 每行原始字符串里可能含：普通字符 / \b（退一格） / \r（行首） / \n（结束）。逐字符扫描，
 * 维护一个临时 buffer（表示当前正在 “重写” 的一行）。遇到普通字符 append，遇到 \b 删
 *  buffer 末一字符，遇到 \r 重置 buffer 为空并标记 “覆盖”，遇到 \n 把 buffer 提交
 * 为最终行（如果该行起始是 \r 则代替 result 末行）。
 */
private fun preprocessTerminalOutput(rawLines: List<String>): List<String> {
    if (rawLines.isEmpty()) return emptyList()
    val result = mutableListOf<String>()
    for (raw in rawLines) {
        val noAnsi = stripAnsi(raw)
        // 本次原始行是否以 \r 开头：表示 “覆盖” result 末行
        var startsWithCr = false
        // buffer：null 表示 “这次原始行还没出现 \r，后面都按 append 处理”
        //        非 null 表示 “已进入覆盖模式”
        var buffer: StringBuilder? = null

        for (ch in noAnsi) {
            when {
                ch == '\r' -> {
                    if (buffer == null) {
                        if (result.isNotEmpty()) {
                            buffer = StringBuilder()
                        } else {
                            result.add("")
                            buffer = StringBuilder()
                        }
                        startsWithCr = true
                    } else {
                        buffer.setLength(0)
                        startsWithCr = true
                    }
                }
                ch == '\b' -> {
                    if (buffer == null) {
                        // 普通 append 模式：append 到 result 末行
                        if (result.isEmpty()) {
                            result.add("")
                        }
                        val last = result.removeAt(result.lastIndex)
                        result.add(if (last.isNotEmpty()) last.dropLast(1) else last)
                    } else {
                        if (buffer.isNotEmpty()) buffer.setLength(buffer.length - 1)
                    }
                }
                ch == '\n' -> {
                    val text = buffer?.toString() ?: ""
                    if (buffer == null) {
                        result.add("")
                    } else if (startsWithCr && result.isNotEmpty()) {
                        val last = result.removeAt(result.lastIndex)
                        if (text.isNotEmpty()) result.add(last + text)
                    } else {
                        result.add(text)
                    }
                    buffer = null
                    startsWithCr = false
                }
                else -> {
                    if (buffer == null) {
                        // append 模式：追加到 result 末行
                        if (result.isEmpty()) result.add("")
                        val last = result.removeAt(result.lastIndex)
                        result.add(last + ch)
                    } else {
                        buffer.append(ch)
                    }
                }
            }
        }

        // 行结束（没 \n）：如果还在 buffer 里（覆盖模式），提交它
        if (buffer != null) {
            val text = buffer.toString()
            if (startsWithCr && result.isNotEmpty()) {
                val last = result.removeAt(result.lastIndex)
                if (text.isNotEmpty()) result.add(last + text)
            } else {
                result.add(text)
            }
        }
    }
    // 去除末尾空行
    while (result.isNotEmpty() && result.last().isEmpty()) result.removeAt(result.lastIndex)
    return result
}

private val ansiRegex = Regex("\u001b\\[[0-9;?]*[A-Za-z]")
private fun stripAnsi(s: String): String = ansiRegex.replace(s, "")

/**
 * 终端底部 extra keys 栏。两行按键。
 * 控制键（ESC / TAB / END / - / : / ← / →）直接操作 inputValue，不发 ANSI。
 * ↑↓ 调历史命令。
 */
@Composable
private fun TerminalExtraKeysBar(
    onSend: (String) -> Unit,
    onHistoryPrev: () -> Unit = {},
    onHistoryNext: () -> Unit = {},
    getInputValue: () -> TextFieldValue = { TextFieldValue("") },
    onInputValueChange: (TextFieldValue) -> Unit = {},
) {
    var activeModifier by remember { mutableStateOf<ModifierKey?>(null) }

    // 工具：在输入框末尾追加字符并把光标放到末尾
    fun appendText(s: String) {
        val cur = getInputValue()
        val newText = cur.text + s
        onInputValueChange(TextFieldValue(newText, TextRange(newText.length)))
    }

    Surface(
        color = Color(0xFF101010),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp, horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // 第 1 行：ESC | TAB | CTRL | ALT | - | ↑ | ↓
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    ExtraKeyButton("ESC", isActive = false,
                        onClick = {
                            onInputValueChange(TextFieldValue(""))
                            activeModifier = null
                        })
                }
                Box(Modifier.weight(1f)) {
                    ExtraKeyButton("TAB", isActive = false,
                        onClick = {
                            appendText("\t")
                            activeModifier = null
                        })
                }
                Box(Modifier.weight(1f)) {
                    ExtraKeyButton("CTRL", isActive = activeModifier == ModifierKey.CTRL,
                        onClick = { activeModifier = if (activeModifier == ModifierKey.CTRL) null else ModifierKey.CTRL })
                }
                Box(Modifier.weight(1f)) {
                    ExtraKeyButton("ALT", isActive = activeModifier == ModifierKey.ALT,
                        onClick = { activeModifier = if (activeModifier == ModifierKey.ALT) null else ModifierKey.ALT })
                }
                Box(Modifier.weight(1f)) {
                    ExtraKeyButton("-", isActive = false,
                        onClick = {
                            appendText("-")
                            activeModifier = null
                        })
                }
                Box(Modifier.weight(1f)) {
                    ExtraKeyButton("↑", isActive = false,
                        onClick = { onHistoryPrev() })
                }
                Box(Modifier.weight(1f)) {
                    ExtraKeyButton("↓", isActive = false,
                        onClick = { onHistoryNext() })
                }
            }
            // 第 2 行：INS | END | SHIFT | : | ← | →
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    ExtraKeyButton("INS", isActive = false,
                        onClick = { /* 占位 */ })
                }
                Box(Modifier.weight(1f)) {
                    // END：光标移到末尾
                    ExtraKeyButton("END", isActive = false,
                        onClick = {
                            val cur = getInputValue()
                            onInputValueChange(TextFieldValue(cur.text, TextRange(cur.text.length)))
                            activeModifier = null
                        })
                }
                Box(Modifier.weight(1f)) {
                    ExtraKeyButton("SHIFT", isActive = activeModifier == ModifierKey.SHIFT,
                        onClick = { activeModifier = if (activeModifier == ModifierKey.SHIFT) null else ModifierKey.SHIFT })
                }
                Box(Modifier.weight(1f)) {
                    ExtraKeyButton(":", isActive = false,
                        onClick = {
                            appendText(":")
                            activeModifier = null
                        })
                }
                Box(Modifier.weight(1f)) {
                    // ←：删除光标前一个字符
                    ExtraKeyButton("←", isActive = false,
                        onClick = {
                            val cur = getInputValue()
                            val sel = cur.selection
                            if (sel.collapsed && sel.start > 0) {
                                val newText = cur.text.substring(0, sel.start - 1) + cur.text.substring(sel.start)
                                onInputValueChange(TextFieldValue(newText, TextRange(sel.start - 1)))
                            }
                            activeModifier = null
                        })
                }
                Box(Modifier.weight(1f)) {
                    // →：光标向后一格
                    ExtraKeyButton("→", isActive = false,
                        onClick = {
                            val cur = getInputValue()
                            val sel = cur.selection
                            if (sel.collapsed && sel.start < cur.text.length) {
                                onInputValueChange(TextFieldValue(cur.text, TextRange(sel.start + 1)))
                            }
                            activeModifier = null
                        })
                }
            }
        }
    }
}

private enum class ModifierKey { CTRL, ALT, SHIFT }

@Composable
private fun ExtraKeyButton(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .height(40.dp)
            .clickable(onClick = onClick),
        color = if (isActive) Color(0xFF3D5AFE) else Color(0xFF1E1E1E),
        shape = RoundedCornerShape(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = Color(0xFFE0E0E0),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, heightDp = 400)
@Composable
fun ProotTerminalScreenPreview() {
    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Spacer(modifier = Modifier.weight(1f))
        TerminalExtraKeysBar(onSend = {})
    }
}
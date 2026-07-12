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
 * 终端屏。Termux 风格输出：
 *   - 顶部 [ChildScreenTopBar] 带返回箭头（外部包）
 *   - 中间输出区（按 ANSI 转义近似解析：\r 回车覆盖当前行；\b 退格；颜色代码保留为可读文本）
 *   - 底部输入行（提示符配色：绿 user@host，蓝 path，白 $/#）
 *   - 底部 extra keys 栏（ESC / TAB / CTRL / ALT / - / ↑↓ / INS / END / SHIFT / : / ←→）
 */
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
        // 中间：输出 + 输入
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
                    // 把原始行做基本 ANSI 处理：\r 视为覆盖当前行
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
                    // 输入行（提示符 + BasicTextField）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val userColor = TermuxUserGreen
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(color = userColor, fontWeight = FontWeight.Bold)) {
                                    append(viewModel.currentUser)
                                }
                                withStyle(SpanStyle(color = TermuxSymbolWhite)) { append("@") }
                                withStyle(SpanStyle(color = TermuxHostGreen)) { append(viewModel.currentHost) }
                                withStyle(SpanStyle(color = TermuxSymbolWhite)) { append(":") }
                                withStyle(SpanStyle(color = TermuxPathBlue)) { append(viewModel.currentPath) }
                                withStyle(
                                    SpanStyle(
                                        color = if (viewModel.currentUser == "root") TermuxSymbolWhite else TermuxSymbolWhite,
                                        fontWeight = FontWeight.Bold,
                                    )
                                ) { append(if (viewModel.currentUser == "root") "# " else "$ ") }
                            },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 14.sp,
                        )
                        BasicTextField(
                            value = inputValue,
                            onValueChange = { inputValue = it },
                            textStyle = TextStyle(
                                color = TermuxOutputText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                            ),
                            cursorBrush = SolidColor(TermuxCursorColor),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
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
                                .onFocusChanged { fs -> isFocused = fs.isFocused },
                            decorationBox = { inner ->
                                Box(modifier = Modifier.fillMaxWidth()) { inner() }
                            }
                        )
                    }
                }
            }
        }

        // 底部：Extra keys 栏
        TerminalExtraKeysBar(
            onSend = { text -> viewModel.writeRaw(text) },
        )
    }
}

/**
 * 极简的终端输出预处理：把 \r 视为覆盖当前行末尾。
 * - 以 \n 切分原始缓冲为多行
 * - 如果某行包含 \r，仅保留最后一个 \r 之后的内容（前面的视为被覆盖）
 * - 把行内的 ANSI 颜色 escape code 去掉，保留可读文本
 */
private fun preprocessTerminalOutput(rawLines: List<String>): List<String> {
    if (rawLines.isEmpty()) return emptyList()
    // 极简终端输出预处理：
    // - 去掉每行 ANSI 转义序列
    // - 如果某行以 \r 开头，视为"覆盖上一行"：上一行 + 当前 \r 后内容拼接
    // - 否则作为新行追加
    val result = mutableListOf<String>()
    for (raw in rawLines) {
        val clean = stripAnsi(raw).trimEnd('\n')
        if (clean.startsWith('\r') && result.isNotEmpty()) {
            val tail = clean.substring(1)
            val prev = result.removeLast()
            if (tail.isNotEmpty()) {
                result.add(prev + tail)
            }
            // tail 为空表示"清空上一行"，不加占位
        } else {
            result.add(clean)
        }
    }
    return result
}

/**
 * 去掉 ANSI 转义序列：\u001b[...m、\u001b[?25l/h、\u001b[K 等。
 */
private val ansiRegex = Regex("\u001b\\[[0-9;?]*[A-Za-z]")

private fun stripAnsi(s: String): String = ansiRegex.replace(s, "")

/**
 * 终端底部 extra keys 栏。两行按键，点击发送 ANSI/控制字符到 stdin。
 */
@Composable
private fun TerminalExtraKeysBar(onSend: (String) -> Unit) {
    var activeModifier by remember { mutableStateOf<ModifierKey?>(null) }

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
                        onClick = { onSend("\u001b"); activeModifier = null })
                }
                Box(Modifier.weight(1f)) {
                    ExtraKeyButton("TAB", isActive = false,
                        onClick = { onSend("\t"); activeModifier = null })
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
                        onClick = { onSend("-"); activeModifier = null })
                }
                Box(Modifier.weight(1f)) {
                    ExtraKeyButton("↑", isActive = false,
                        onClick = { onSend("\u001b[A"); activeModifier = null })
                }
                Box(Modifier.weight(1f)) {
                    ExtraKeyButton("↓", isActive = false,
                        onClick = { onSend("\u001b[B"); activeModifier = null })
                }
            }
            // 第 2 行：INS | END | SHIFT | : | ← | →
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    ExtraKeyButton("INS", isActive = false,
                        onClick = { onSend("\u001b[2~"); activeModifier = null })
                }
                Box(Modifier.weight(1f)) {
                    ExtraKeyButton("END", isActive = false,
                        onClick = { onSend("\u001b[F"); activeModifier = null })
                }
                Box(Modifier.weight(1f)) {
                    ExtraKeyButton("SHIFT", isActive = activeModifier == ModifierKey.SHIFT,
                        onClick = { activeModifier = if (activeModifier == ModifierKey.SHIFT) null else ModifierKey.SHIFT })
                }
                Box(Modifier.weight(1f)) {
                    ExtraKeyButton(":", isActive = false,
                        onClick = { onSend(":"); activeModifier = null })
                }
                Box(Modifier.weight(1f)) {
                    ExtraKeyButton("←", isActive = false,
                        onClick = { onSend("\u001b[D"); activeModifier = null })
                }
                Box(Modifier.weight(1f)) {
                    ExtraKeyButton("→", isActive = false,
                        onClick = { onSend("\u001b[C"); activeModifier = null })
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
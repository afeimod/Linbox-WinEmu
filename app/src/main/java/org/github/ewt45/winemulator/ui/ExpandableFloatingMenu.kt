package org.github.ewt45.winemulator.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import org.github.ewt45.winemulator.Consts
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 可展开的悬浮菜单按钮
 * 点击主按钮展开子菜单，子菜单以向上弯曲的弧形排列显示在主按钮上方
 */
@Composable
fun ExpandableFloatingMenu(
    modifier: Modifier = Modifier,
    parentWidth: Float,
    parentHeight: Float,
    onMainMenuClick: () -> Unit,
    onGeneralSettingsClick: () -> Unit,
    onVirtualKeysClick: () -> Unit,
    onX11SettingsClick: () -> Unit,
    onTerminalClick: () -> Unit = {},
    onMinimizeClick: () -> Unit = {},
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val buttonSizePx = with(density) { Consts.Ui.minimizedIconSize.dp.toPx() }
    val miniButtonSizePx = with(density) { 36.dp.toPx() }
    val dragThreshold = with(density) { 30.dp.toPx() }
    val initialX = with(density) { 48.dp.toPx() }
    val initialY = with(density) { 100.dp.toPx() }

    var isExpanded by remember { mutableStateOf(false) }
    var offsetX by rememberSaveable { mutableStateOf(initialX) }
    var offsetY by rememberSaveable { mutableStateOf(initialY) }
    var hasDragged by rememberSaveable { mutableStateOf(false) }
    var pressStartX by rememberSaveable { mutableFloatStateOf(0f) }
    var pressStartY by rememberSaveable { mutableFloatStateOf(0f) }

    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 45f else 0f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "rotation"
    )

    // 加载自定义图标
    val moveIconBitmap = remember {
        ContextCompat.getDrawable(context, a.io.github.ewt45.winemulator.R.drawable.icon_move)?.toBitmap()
    }
    val homeIconBitmap = remember {
        ContextCompat.getDrawable(context, a.io.github.ewt45.winemulator.R.drawable.icon_home)?.toBitmap()
    }
    val settingsIconBitmap = remember {
        ContextCompat.getDrawable(context, a.io.github.ewt45.winemulator.R.drawable.icon_settings)?.toBitmap()
    }
    val gamepadIconBitmap = remember {
        ContextCompat.getDrawable(context, a.io.github.ewt45.winemulator.R.drawable.icon_gamepad)?.toBitmap()
    }
    val displaySettingsIconBitmap = remember {
        ContextCompat.getDrawable(context, a.io.github.ewt45.winemulator.R.drawable.icon_display_settings)?.toBitmap()
    }
    val hideIconBitmap = remember {
        ContextCompat.getDrawable(context, a.io.github.ewt45.winemulator.R.drawable.ic_hide)?.toBitmap()
    }
    // 终端图标：Canvas 画一个黑底 + “$_” 文字
    val terminalIconBitmap = remember {
        val size = miniButtonSizePx.toInt().coerceAtLeast(48)
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor("#1E1E1E")
        }
        canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), 12f, 12f, paint)
        val textPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor("#4CAF50")
            textSize = size * 0.45f
            isFakeBoldText = true
            textAlign = android.graphics.Paint.Align.CENTER
        }
        // 画 “$_” 标志
        val cx = size / 2f
        val cy = size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText("$_", cx, cy, textPaint)
        bmp
    }

    LaunchedEffect(parentWidth, parentHeight, buttonSizePx) {
        if (parentWidth > 0 && parentHeight > 0) {
            offsetX = offsetX.coerceIn(0f, parentWidth - buttonSizePx)
            offsetY = offsetY.coerceIn(0f, parentHeight - buttonSizePx)
        }
    }

    val isOnLeftSide = offsetX < parentWidth / 2

    Box(modifier = modifier.fillMaxSize()) {
        if (isExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { isExpanded = false }
            )
        }

        if (isExpanded) {
            // 使用自定义图标
            val menuItems = listOf(
                Triple(homeIconBitmap, "主菜单", onMainMenuClick),
                Triple(terminalIconBitmap, "终端", onTerminalClick),
                Triple(settingsIconBitmap, "一般设置", onGeneralSettingsClick),
                Triple(gamepadIconBitmap, "虚拟按键设置", onVirtualKeysClick),
                Triple(displaySettingsIconBitmap, "X11显示设置", onX11SettingsClick),
                Triple(hideIconBitmap, "最小化", onMinimizeClick),
            )

            val arcRadius = with(density) { 60.dp.toPx() }
            val arcSpread = 120f

            val mainCenterX = offsetX + buttonSizePx / 2
            val mainCenterY = offsetY + buttonSizePx / 2
            val direction = if (isOnLeftSide) 1f else -1f

            menuItems.forEachIndexed { index, (bitmap, description, onClick) ->
                val fraction = index.toFloat() / (menuItems.size - 1)
                val angleDeg = arcSpread * (fraction - 0.5f)
                val angleRad = Math.toRadians(angleDeg.toDouble()).toFloat()

                val offsetXFromCenter = direction * arcRadius * cos(angleRad)
                val offsetYFromCenter = arcRadius * sin(angleRad)

                val x = mainCenterX + offsetXFromCenter - miniButtonSizePx / 2
                val y = mainCenterY + offsetYFromCenter - miniButtonSizePx / 2

                Box(
                    modifier = Modifier
                        .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                        .size(36.dp)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            isExpanded = false
                            onClick()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = description,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }

        // 主按钮 - 使用自定义移动图标
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .size(Consts.Ui.minimizedIconSize.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            pressStartX = offset.x
                            pressStartY = offset.y
                            hasDragged = false
                        },
                        onDragEnd = {
                            if (hasDragged) {
                                offsetX = offsetX.coerceIn(0f, parentWidth - buttonSizePx)
                                offsetY = offsetY.coerceIn(0f, parentHeight - buttonSizePx)
                                val halfWidth = buttonSizePx / 2
                                val newX = if (offsetX + halfWidth < parentWidth / 2) 0f else parentWidth - buttonSizePx
                                offsetX = newX
                                offsetY = offsetY.coerceIn(0f, parentHeight - buttonSizePx)
                            }
                            hasDragged = false
                        },
                        onDragCancel = { hasDragged = false },
                        onDrag = { change, dragAmount ->
                            val totalDragX = change.position.x - pressStartX
                            val totalDragY = change.position.y - pressStartY
                            val totalDistance = abs(totalDragX) + abs(totalDragY)
                            if (totalDistance > dragThreshold) hasDragged = true
                            if (hasDragged) {
                                change.consume()
                                offsetX = offsetX + dragAmount.x
                                offsetY = offsetY + dragAmount.y
                            }
                        }
                    )
                }
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {
                    if (!hasDragged) isExpanded = !isExpanded
                },
            contentAlignment = Alignment.Center
        ) {
            // 展开时显示关闭图标，收起时显示移动图标
            if (isExpanded) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "收起菜单",
                    modifier = Modifier
                        .size(36.dp)
                        .graphicsLayer {
                            rotationZ = rotationAngle
                        },
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                // 使用自定义移动图标
                if (moveIconBitmap != null) {
                    Image(
                        bitmap = moveIconBitmap.asImageBitmap(),
                        contentDescription = "展开菜单",
                        modifier = Modifier
                            .size(36.dp)
                            .graphicsLayer {
                                rotationZ = rotationAngle
                            },
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "展开菜单",
                        modifier = Modifier
                            .size(36.dp)
                            .graphicsLayer {
                                rotationZ = rotationAngle
                            },
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

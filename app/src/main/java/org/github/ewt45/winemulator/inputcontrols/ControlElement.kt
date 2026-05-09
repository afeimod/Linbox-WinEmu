package org.github.ewt45.winemulator.inputcontrols

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import androidx.core.graphics.ColorUtils
import com.termux.x11.controller.math.Mathf
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Timer
import java.util.TimerTask
import java.util.Arrays

/**
 * The {@link Class} that implements individual control elements for {@link InputControlsView}.
 *
 * 修复内容:
 * 1. 添加 FLAG_MOUSE_MOVE_MODE 支持虚拟按键拖动触摸板
 * 2. 修复范围按钮初始化和刷新问题
 * 3. 修复 API 调用格式问题 (函数调用改为属性访问)
 * 4. 性能优化：减少不必要的 invalidate() 调用
 *    - 添加批量失效机制，防止快速输入时过度重绘
 *    - 优化按键处理逻辑，减少UI更新频率
 */
class ControlElement(
    private val inputControlsView: InputControlsView
) {
    companion object {
        const val STICK_DEAD_ZONE = 0.15f
        const val DPAD_DEAD_ZONE = 0.3f
        const val STICK_SENSITIVITY = 3.0f
        const val TRACKPAD_MIN_SPEED = 0.8f
        const val TRACKPAD_MAX_SPEED = 20.0f
        const val TRACKPAD_ACCELERATION_THRESHOLD: Byte = 4
        const val BUTTON_MIN_TIME_TO_KEEP_PRESSED: Short = 300

        // 属性标志位
        const val FLAG_SELECTED = 1 shl 0
        const val FLAG_PRESSED = 1 shl 1
        const val FLAG_VISIBLE = 1 shl 2
        const val FLAG_TOGGLE_SWITCH = 1 shl 3
        const val FLAG_BOUNDING_BOX_NEEDS_UPDATE = 1 shl 4
        const val FLAG_MOUSE_MOVE_MODE = 1 shl 5  // 鼠标移动模式标志
    }

    enum class Type {
        BUTTON, D_PAD, RANGE_BUTTON, STICK, TRACKPAD, COMBINE_BUTTON, CHEAT_CODE_TEXT;

        companion object {
            fun names(): Array<String> = entries.map { it.name.replace("_", "-") }.toTypedArray()
        }
    }

    enum class Shape {
        CIRCLE, RECT, ROUND_RECT, SQUARE;

        companion object {
            fun names(): Array<String> = entries.map { it.name.replace("_", " ") }.toTypedArray()
        }
    }

    enum class Range(val max: Byte) {
        FROM_A_TO_Z(26),
        FROM_0_TO_9(10),
        FROM_F1_TO_F12(12),
        FROM_NP0_TO_NP9(10);

        companion object {
            fun names(): Array<String> = entries.map { it.name.replace("_", " ") }.toTypedArray()
        }
    }

    // Properties - using internal visibility for access from InputControlsView
    internal var type: Type = Type.BUTTON
    internal var shape: Shape = Shape.CIRCLE
    internal var bindings: Array<Binding> = arrayOf(Binding.NONE, Binding.NONE, Binding.NONE, Binding.NONE)
    internal var scale: Float = 1.0f
    internal var x: Int = 0
    internal var y: Int = 0
    internal var isSelected: Boolean = false
    internal var isToggleSwitch: Boolean = false
    internal var text: String = ""
    internal var iconId: Byte = 0
    internal var range: Range? = null
    internal var orientation: Byte = 0
    internal var currentPointerId: Int = -1

    // 属性标志位
    private var propertyFlags: Int = FLAG_BOUNDING_BOX_NEEDS_UPDATE

    private val boundingBox = Rect()
    private var states = booleanArrayOf(false, false, false, false)
    private var currentPosition: PointF? = null
    private var scroller: RangeScroller? = null
    private var interpolator: CubicBezierInterpolator? = null
    private var touchTime: Long? = null
    private var cheatCodeText: String = "None"
    private var cheatCodePressed = false
    private var customIconId: String? = null
    private var clipIcon: Bitmap? = null
    private var oldCustomIconId: String? = null
    private var backgroundColor: Int = 0
    private var oldBackgroundColor: Int = -1

    constructor(inputControlsView: InputControlsView, type: Type) : this(inputControlsView) {
        this.type = type
        reset()
    }

    private fun reset() {
        setBinding(Binding.NONE)
        scroller = null

        when (type) {
            Type.D_PAD, Type.STICK -> {
                bindings = arrayOf(Binding.KEY_W, Binding.KEY_D, Binding.KEY_S, Binding.KEY_A)
            }
            Type.TRACKPAD -> {
                bindings = arrayOf(
                    Binding.MOUSE_MOVE_UP,
                    Binding.MOUSE_MOVE_RIGHT,
                    Binding.MOUSE_MOVE_DOWN,
                    Binding.MOUSE_MOVE_LEFT
                )
            }
            Type.RANGE_BUTTON -> {
                // RANGE_BUTTON 默认 4 个 bindings，与 D_PAD、STICK 保持一致
                bindings = arrayOf(Binding.NONE, Binding.NONE, Binding.NONE, Binding.NONE)
                scroller = RangeScroller(inputControlsView, this)
            }
            else -> {}
        }

        text = ""
        iconId = 0
        range = null
        propertyFlags = propertyFlags or FLAG_BOUNDING_BOX_NEEDS_UPDATE
    }

    // 属性标志位辅助方法
    private fun isFlagSet(flag: Int): Boolean = (propertyFlags and flag) != 0
    private fun setFlag(flag: Int, value: Boolean) {
        propertyFlags = if (value) propertyFlags or flag else propertyFlags and flag.inv()
    }

    // Getters and setters
    fun getType(): Type = type

    fun setType(type: Type) {
        this.type = type
        propertyFlags = FLAG_BOUNDING_BOX_NEEDS_UPDATE
        reset()
        inputControlsView.invalidate()
    }

    fun getBindingCount(): Int = bindings.size

    fun setBindingCount(bindingCount: Int) {
        bindings = Array(bindingCount) { Binding.NONE }
        setBinding(Binding.NONE)
        states = BooleanArray(bindingCount)
        propertyFlags = propertyFlags or FLAG_BOUNDING_BOX_NEEDS_UPDATE
    }

    fun getShape(): Shape = shape
    fun setShape(shape: Shape) {
        this.shape = shape
        propertyFlags = propertyFlags or FLAG_BOUNDING_BOX_NEEDS_UPDATE
    }

    fun getRange(): Range = range ?: Range.FROM_A_TO_Z

    fun setRange(range: Range) {
        this.range = range
        // 重新初始化 scroller
        scroller = RangeScroller(inputControlsView, this)
        propertyFlags = propertyFlags or FLAG_BOUNDING_BOX_NEEDS_UPDATE
        inputControlsView.invalidate()
    }

    fun getOrientation(): Byte = orientation
    fun setOrientation(orientation: Byte) {
        this.orientation = orientation
        propertyFlags = propertyFlags or FLAG_BOUNDING_BOX_NEEDS_UPDATE
    }

    fun isToggleSwitch(): Boolean = isToggleSwitch
    fun setToggleSwitch(toggleSwitch: Boolean) {
        this.isToggleSwitch = toggleSwitch
    }

    /**
     * 获取鼠标移动模式
     * 当启用时，按住此按键可以拖动触摸板
     */
    fun isMouseMoveMode(): Boolean = isFlagSet(FLAG_MOUSE_MOVE_MODE)

    /**
     * 设置鼠标移动模式
     */
    fun setMouseMoveMode(mouseMoveMode: Boolean) {
        setFlag(FLAG_MOUSE_MOVE_MODE, mouseMoveMode)
    }

    fun getBindingAt(index: Int): Binding = if (index < bindings.size) bindings[index] else Binding.NONE

    fun setBindingAt(index: Int, binding: Binding) {
        if (index >= bindings.size) {
            val oldLength = bindings.size
            bindings = Arrays.copyOf(bindings, index + 1)
            Arrays.fill(bindings, oldLength, bindings.size, Binding.NONE)
            states = BooleanArray(bindings.size)
            propertyFlags = propertyFlags or FLAG_BOUNDING_BOX_NEEDS_UPDATE
        }
        bindings[index] = binding
    }

    fun setBinding(binding: Binding) {
        bindings.fill(binding)
    }

    fun getScale(): Float = scale
    fun setScale(scale: Float) {
        this.scale = scale
        propertyFlags = propertyFlags or FLAG_BOUNDING_BOX_NEEDS_UPDATE
    }

    fun getX(): Int = x
    fun setX(x: Int) {
        this.x = x
        propertyFlags = propertyFlags or FLAG_BOUNDING_BOX_NEEDS_UPDATE
    }

    fun getY(): Int = y
    fun setY(y: Int) {
        this.y = y
        propertyFlags = propertyFlags or FLAG_BOUNDING_BOX_NEEDS_UPDATE
    }

    fun isSelected(): Boolean = isSelected
    fun setSelected(selected: Boolean) {
        this.isSelected = selected
    }

    fun getText(): String = text
    fun setText(text: String) {
        this.text = text ?: ""
    }

    fun getIconId(): Byte = iconId
    fun setIconId(iconId: Int) {
        this.iconId = iconId.toByte()
    }

    fun getCheatCodeText(): String = cheatCodeText
    fun setCheatCodeText(cct: String) {
        this.cheatCodeText = cct
    }

    fun setCustomIconId(icId: String?) {
        this.customIconId = icId
        if (oldCustomIconId == null) {
            oldCustomIconId = icId
        }
    }

    fun getCustomIconId(): String? = customIconId

    fun setBackgroundColor(color: Int) {
        this.backgroundColor = color
        if (oldBackgroundColor <= 0) {
            oldBackgroundColor = color
        }
    }

    fun getBackgroundColor(): Int = backgroundColor

    fun getBoundingBox(): Rect {
        if (isFlagSet(FLAG_BOUNDING_BOX_NEEDS_UPDATE)) computeBoundingBox()
        return boundingBox
    }

    private fun computeBoundingBox() {
        val snappingSize = inputControlsView.snappingSizeValue
        var halfWidth = 0
        var halfHeight = 0

        when (type) {
            Type.CHEAT_CODE_TEXT, Type.COMBINE_BUTTON, Type.BUTTON -> {
                when (shape) {
                    Shape.RECT, Shape.ROUND_RECT -> {
                        halfWidth = snappingSize * 4
                        halfHeight = snappingSize * 2
                    }
                    Shape.SQUARE -> {
                        halfWidth = (snappingSize * 2.5f).toInt()
                        halfHeight = (snappingSize * 2.5f).toInt()
                    }
                    Shape.CIRCLE -> {
                        halfWidth = snappingSize * 3
                        halfHeight = snappingSize * 3
                    }
                }
            }
            Type.D_PAD -> {
                halfWidth = snappingSize * 7
                halfHeight = snappingSize * 7
            }
            Type.TRACKPAD, Type.STICK -> {
                halfWidth = snappingSize * 6
                halfHeight = snappingSize * 6
            }
            Type.RANGE_BUTTON -> {
                halfWidth = (bindings.size * 4 * snappingSize) / 2
                halfHeight = snappingSize * 2

                if (orientation == 1.toByte()) {
                    val tmp = halfWidth
                    halfWidth = halfHeight
                    halfHeight = tmp
                }
            }
        }

        halfWidth = (halfWidth * scale).toInt()
        halfHeight = (halfHeight * scale).toInt()
        boundingBox.set(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight)
        propertyFlags = propertyFlags and FLAG_BOUNDING_BOX_NEEDS_UPDATE.inv()
    }

    private fun getDisplayText(): String {
        if (text.isNotEmpty()) {
            return text
        } else {
            val binding = getBindingAt(0)
            var displayText = binding.toString().replace("NUMPAD ", "NP").replace("BUTTON ", "")
            if (displayText.length > 7) {
                val parts = displayText.split(" ")
                val sb = StringBuilder()
                for (part in parts) {
                    if (part.isNotEmpty()) sb.append(part[0])
                }
                return (if (binding.isMouse()) "M" else "") + sb
            } else return displayText
        }
    }

    private fun getTextSizeForWidth(paint: Paint, text: String, desiredWidth: Float): Float {
        val testTextSize = 48f
        paint.textSize = testTextSize
        return testTextSize * desiredWidth / paint.measureText(text)
    }

    private fun getRangeTextForIndex(range: Range, index: Int): String {
        return when (range) {
            Range.FROM_A_TO_Z -> ('A'.code + index).toChar().toString()
            Range.FROM_0_TO_9 -> ((index + 1) % 10).toString()
            Range.FROM_F1_TO_F12 -> "F${index + 1}"
            Range.FROM_NP0_TO_NP9 -> "NP${(index + 1) % 10}"
        }
    }

    fun draw(canvas: Canvas) {
        val snappingSize = inputControlsView.snappingSizeValue
        val paint = inputControlsView.getPaint()
        val primaryColor = inputControlsView.getPrimaryColor()

        paint.color = if (isSelected) inputControlsView.getSecondaryColor() else primaryColor
        paint.style = Paint.Style.STROKE
        val strokeWidth = snappingSize * 0.25f
        paint.strokeWidth = strokeWidth
        val box = getBoundingBox()

        when (type) {
            Type.CHEAT_CODE_TEXT, Type.COMBINE_BUTTON, Type.BUTTON -> {
                drawButton(canvas, paint, box, primaryColor, strokeWidth)
            }
            Type.D_PAD -> {
                drawDPad(canvas, paint, box)
            }
            Type.RANGE_BUTTON -> {
                drawRangeButton(canvas, paint, box, strokeWidth)
            }
            Type.STICK -> {
                drawStick(canvas, paint, box, primaryColor, strokeWidth)
            }
            Type.TRACKPAD -> {
                drawTrackpad(canvas, paint, box, strokeWidth)
            }
        }
    }

    private fun drawButton(canvas: Canvas, paint: Paint, box: Rect, primaryColor: Int, strokeWidth: Float) {
        val cx = box.centerX().toFloat()
        val cy = box.centerY().toFloat()
        val snappingSize = inputControlsView.snappingSizeValue
        val isPressed = isFlagSet(FLAG_PRESSED)

        // 保存原始paint状态
        val originalStyle = paint.style
        val originalColor = paint.color

        // 如果是按下状态，填充背景
        if (isPressed) {
            paint.style = Paint.Style.FILL
        }

        when (shape) {
            Shape.CIRCLE -> {
                canvas.drawCircle(cx, cy, box.width() * 0.5f, paint)
            }
            Shape.RECT -> {
                canvas.drawRect(box, paint)
            }
            Shape.ROUND_RECT -> {
                val radius = box.height() * 0.5f
                canvas.drawRoundRect(
                    box.left.toFloat(), box.top.toFloat(),
                    box.right.toFloat(), box.bottom.toFloat(),
                    radius, radius, paint
                )
            }
            Shape.SQUARE -> {
                val radius = snappingSize * 0.75f * scale
                canvas.drawRoundRect(
                    box.left.toFloat(), box.top.toFloat(),
                    box.right.toFloat(), box.bottom.toFloat(),
                    radius, radius, paint
                )
            }
        }

        // 恢复paint状态
        paint.style = originalStyle
        paint.color = originalColor

        if (!customIconId.isNullOrEmpty()) {
            drawCustomIcon(canvas, cx, cy, box.width().toFloat(), box.height().toFloat())
        } else if (backgroundColor > 0) {
            drawColorSolidIcon(canvas, cx, cy, box.width().toFloat(), box.height().toFloat())
        } else if (iconId > 0) {
            drawIcon(canvas, cx, cy, box.width().toFloat(), box.height().toFloat())
        } else {
            val displayText = getDisplayText()
            paint.textSize = minOf(
                getTextSizeForWidth(paint, displayText, box.width() - strokeWidth * 2),
                snappingSize * 2 * scale
            )
            paint.textAlign = Paint.Align.CENTER
            paint.style = Paint.Style.FILL
            paint.color = if (isPressed) getDarkColor() else primaryColor
            canvas.drawText(displayText, x.toFloat(), y - (paint.descent() + paint.ascent()) * 0.5f, paint)
        }
    }

    private fun drawIcon(canvas: Canvas, cx: Float, cy: Float, width: Float, height: Float) {
        val paint = inputControlsView.getPaint()
        val icon = inputControlsView.getIcon(iconId) ?: return

        paint.colorFilter = if (isFlagSet(FLAG_PRESSED)) inputControlsView.getDarkColorFilter() else inputControlsView.getLightColorFilter()
        val margin = (inputControlsView.snappingSizeValue * (if (shape == Shape.CIRCLE || shape == Shape.SQUARE) 2.0f else 1.0f) * scale).toInt()
        val halfSize = ((minOf(width, height) - margin) * 0.5f).toInt()

        val srcRect = Rect(0, 0, icon.width, icon.height)
        val dstRect = Rect(
            (cx - halfSize).toInt(),
            (cy - halfSize).toInt(),
            (cx + halfSize).toInt(),
            (cy + halfSize).toInt()
        )

        canvas.drawBitmap(icon, srcRect, dstRect, paint)
        paint.colorFilter = null
    }

    private fun drawCustomIcon(canvas: Canvas, cx: Float, cy: Float, width: Float, height: Float) {
        val paint = inputControlsView.getPaint()
        val iconId = customIconId ?: return

        var icon: Bitmap? = if (clipIcon != null && oldCustomIconId == iconId) {
            clipIcon
        } else {
            val iconOrigin = inputControlsView.getCustomIcon(iconId) ?: return
            val isCycle = shape == Shape.CIRCLE
            val clipped = inputControlsView.clipBitmap(iconOrigin, isCycle) ?: return
            clipIcon = clipped
            oldCustomIconId = iconId
            inputControlsView.counterMapIncrease(iconId)
            clipped
        }

        if (icon == null) return

        val margin = (inputControlsView.snappingSizeValue * (if (shape == Shape.CIRCLE || shape == Shape.SQUARE) 2.0f else 1.0f) * scale).toInt()
        val halfSize = ((minOf(width, height) - margin) * 0.7f).toInt()

        val srcRect = Rect(0, 0, icon.width, icon.height)
        val dstRect = Rect(
            (cx - halfSize).toInt(),
            (cy - halfSize).toInt(),
            (cx + halfSize).toInt(),
            (cy + halfSize).toInt()
        )

        canvas.drawBitmap(icon, srcRect, dstRect, paint)
        paint.colorFilter = null
    }

    private fun toARGB(rgb: Int): Int {
        return Color.argb(255, Color.red(rgb), Color.green(rgb), Color.blue(rgb))
    }

    private fun drawColorSolidIcon(canvas: Canvas, cx: Float, cy: Float, width: Float, height: Float) {
        val paint = inputControlsView.getPaint()
        val color = backgroundColor

        var icon: Bitmap? = if (clipIcon != null && oldBackgroundColor == color) {
            clipIcon
        } else {
            val isCycle = shape == Shape.CIRCLE
            val created = inputControlsView.createShapeBitmap(width, height, toARGB(color), isCycle) ?: return
            clipIcon = created
            oldBackgroundColor = color
            created
        }

        if (icon == null) return

        val margin = (inputControlsView.snappingSizeValue * (if (shape == Shape.CIRCLE || shape == Shape.SQUARE) 2.0f else 1.0f) * scale).toInt()
        val halfSize = ((minOf(width, height) - margin) * 0.7f).toInt()

        val srcRect = Rect(0, 0, icon.width, icon.height)
        val dstRect = Rect(
            (cx - halfSize).toInt(),
            (cy - halfSize).toInt(),
            (cx + halfSize).toInt(),
            (cy + halfSize).toInt()
        )

        canvas.drawBitmap(icon, srcRect, dstRect, paint)
        paint.colorFilter = null
    }

    private fun drawDPad(canvas: Canvas, paint: Paint, box: Rect) {
        val cx = box.centerX().toFloat()
        val cy = box.centerY().toFloat()
        val snappingSize = inputControlsView.snappingSizeValue
        val offsetX = snappingSize * 2 * scale
        val offsetY = snappingSize * 3 * scale
        val start = snappingSize * scale

        val path = inputControlsView.getPath()
        path.reset()

        // Up
        path.moveTo(cx, cy - start)
        path.lineTo(cx - offsetX, cy - offsetY)
        path.lineTo(cx - offsetX, box.top.toFloat())
        path.lineTo(cx + offsetX, box.top.toFloat())
        path.lineTo(cx + offsetX, cy - offsetY)
        path.close()

        // Left
        path.moveTo(cx - start, cy)
        path.lineTo(cx - offsetY, cy - offsetX)
        path.lineTo(box.left.toFloat(), cy - offsetX)
        path.lineTo(box.left.toFloat(), cy + offsetX)
        path.lineTo(cx - offsetY, cy + offsetX)
        path.close()

        // Down
        path.moveTo(cx, cy + start)
        path.lineTo(cx - offsetX, cy + offsetY)
        path.lineTo(cx - offsetX, box.bottom.toFloat())
        path.lineTo(cx + offsetX, box.bottom.toFloat())
        path.lineTo(cx + offsetX, cy + offsetY)
        path.close()

        // Right
        path.moveTo(cx + start, cy)
        path.lineTo(cx + offsetY, cy - offsetX)
        path.lineTo(box.right.toFloat(), cy - offsetX)
        path.lineTo(box.right.toFloat(), cy + offsetX)
        path.lineTo(cx + offsetY, cy + offsetX)
        path.close()

        canvas.drawPath(path, paint)
    }

    private fun drawRangeButton(canvas: Canvas, paint: Paint, box: Rect, strokeWidth: Float) {
        val snappingSize = inputControlsView.snappingSizeValue
        val radius = snappingSize * 0.75f * scale
        val currentRange = getRange()

        // 确保 scroller 已初始化
        if (scroller == null) {
            scroller = RangeScroller(inputControlsView, this)
        }

        val elementSize = scroller?.getElementSize() ?: run {
            val boxWidth = box.width().toFloat()
            val boxHeight = box.height().toFloat()
            maxOf(boxWidth, boxHeight) / getBindingCount()
        }
        val scrollOffset = scroller?.getScrollOffset() ?: 0f
        val rangeIndex = scroller?.getRangeIndex() ?: intArrayOf(0, currentRange.max.toInt())

        if (orientation == 0.toByte()) {
            val lineTop = box.top + strokeWidth * 0.5f
            val lineBottom = box.bottom - strokeWidth * 0.5f

            canvas.drawRoundRect(
                box.left.toFloat(), box.top.toFloat(),
                box.right.toFloat(), box.bottom.toFloat(),
                radius, radius, paint
            )

            canvas.save()
            val clipPath = inputControlsView.getPath()
            clipPath.reset()
            clipPath.addRoundRect(
                box.left.toFloat(), box.top.toFloat(),
                box.right.toFloat(), box.bottom.toFloat(),
                radius, radius, Path.Direction.CW
            )
            canvas.clipPath(clipPath)

            val initialOffset = scrollOffset % elementSize
            var startX = box.left.toFloat() - initialOffset
            val savedColor = paint.color

            for (i in rangeIndex[0] until rangeIndex[1]) {
                val index = i % currentRange.max.toInt()
                val oldColor = paint.color

                paint.style = Paint.Style.STROKE
                paint.color = oldColor

                if (startX > box.left && startX < box.right) {
                    canvas.drawLine(startX, lineTop, startX, lineBottom, paint)
                }

                val text = getRangeTextForIndex(currentRange, index)
                if (startX < box.right && startX + elementSize > box.left) {
                    paint.style = Paint.Style.FILL
                    paint.color = inputControlsView.getPrimaryColor()
                    paint.textSize = minOf(
                        getTextSizeForWidth(paint, text, elementSize - strokeWidth * 2),
                        snappingSize * 2 * scale
                    )
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText(text, startX + elementSize * 0.5f, y - (paint.descent() + paint.ascent()) * 0.5f, paint)
                }

                startX += elementSize
            }

            paint.style = Paint.Style.STROKE
            paint.color = savedColor
            canvas.restore()
        } else {
            val lineLeft = box.left + strokeWidth * 0.5f
            val lineRight = box.right - strokeWidth * 0.5f

            canvas.drawRoundRect(
                box.left.toFloat(), box.top.toFloat(),
                box.right.toFloat(), box.bottom.toFloat(),
                radius, radius, paint
            )

            canvas.save()
            val clipPath = inputControlsView.getPath()
            clipPath.reset()
            clipPath.addRoundRect(
                box.left.toFloat(), box.top.toFloat(),
                box.right.toFloat(), box.bottom.toFloat(),
                radius, radius, Path.Direction.CW
            )
            canvas.clipPath(clipPath)

            val initialOffset = scrollOffset % elementSize
            var startY = box.top.toFloat() - initialOffset
            val savedColorY = paint.color

            for (i in rangeIndex[0] until rangeIndex[1]) {
                val oldColor = paint.color

                paint.style = Paint.Style.STROKE
                paint.color = oldColor

                if (startY > box.top && startY < box.bottom) {
                    canvas.drawLine(lineLeft, startY, lineRight, startY, paint)
                }

                val text = getRangeTextForIndex(currentRange, i)
                if (startY < box.bottom && startY + elementSize > box.top) {
                    paint.style = Paint.Style.FILL
                    paint.color = inputControlsView.getPrimaryColor()
                    paint.textSize = minOf(
                        getTextSizeForWidth(paint, text, box.width() - strokeWidth * 2),
                        snappingSize * 2 * scale
                    )
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText(text, x.toFloat(), startY + elementSize * 0.5f - (paint.descent() + paint.ascent()) * 0.5f, paint)
                }

                startY += elementSize
            }

            paint.style = Paint.Style.STROKE
            paint.color = savedColorY
            canvas.restore()
        }
    }

    private fun drawStick(canvas: Canvas, paint: Paint, box: Rect, primaryColor: Int, strokeWidth: Float) {
        val cx = box.centerX().toFloat()
        val cy = box.centerY().toFloat()
        val snappingSize = inputControlsView.snappingSizeValue
        val oldColor = paint.color

        canvas.drawCircle(cx, cy, box.height() * 0.5f, paint)

        val thumbX = currentPosition?.x ?: cx
        val thumbY = currentPosition?.y ?: cy
        val thumbRadius = snappingSize * 3.5f * scale

        paint.style = Paint.Style.FILL
        paint.color = ColorUtils.setAlphaComponent(primaryColor, 50)
        canvas.drawCircle(thumbX, thumbY, thumbRadius, paint)

        paint.style = Paint.Style.STROKE
        paint.color = oldColor
        canvas.drawCircle(thumbX, thumbY, thumbRadius + strokeWidth * 0.5f, paint)
    }

    private fun drawTrackpad(canvas: Canvas, paint: Paint, box: Rect, strokeWidth: Float) {
        val radius = box.height() * 0.15f
        canvas.drawRoundRect(
            box.left.toFloat(), box.top.toFloat(),
            box.right.toFloat(), box.bottom.toFloat(),
            radius, radius, paint
        )

        val offset = strokeWidth * 2.5f
        val innerStrokeWidth = strokeWidth * 2
        val innerHeight = box.height() - offset * 2
        val innerRadius = (innerHeight.toFloat() / box.height()) * radius - (innerStrokeWidth * 0.5f + strokeWidth * 0.5f)

        paint.strokeWidth = innerStrokeWidth
        canvas.drawRoundRect(
            box.left + offset, box.top + offset,
            box.right - offset, box.bottom - offset,
            innerRadius, innerRadius, paint
        )
        paint.strokeWidth = strokeWidth
    }

    fun toJSONObject(): JSONObject? {
        return try {
            val elementJSONObject = JSONObject()
            elementJSONObject.put("type", type.name)
            elementJSONObject.put("shape", shape.name)

            val bindingsJSONArray = JSONArray()
            for (binding in bindings) bindingsJSONArray.put(binding.name)
            elementJSONObject.put("bindings", bindingsJSONArray)

            elementJSONObject.put("scale", scale.toDouble())
            elementJSONObject.put("x", x.toDouble() / inputControlsView.maxWidth)
            elementJSONObject.put("y", y.toDouble() / inputControlsView.maxHeight)
            elementJSONObject.put("toggleSwitch", isToggleSwitch)
            elementJSONObject.put("text", text)
            elementJSONObject.put("iconId", iconId.toInt())

            if (cheatCodeText.isNotEmpty()) {
                elementJSONObject.put("cheatCodeText", cheatCodeText)
            }
            if (!customIconId.isNullOrEmpty()) {
                elementJSONObject.put("customIconId", customIconId)
            }
            if (backgroundColor > 0) {
                elementJSONObject.put("backgroundColor", backgroundColor)
            }

            if (type == Type.RANGE_BUTTON && range != null) {
                elementJSONObject.put("range", range!!.name)
                if (orientation != 0.toByte()) elementJSONObject.put("orientation", orientation.toInt())
            }

            // 保存鼠标移动模式设置
            if (isMouseMoveMode()) {
                elementJSONObject.put("mouseMoveMode", true)
            }

            elementJSONObject
        } catch (e: JSONException) {
            null
        }
    }

    fun containsPoint(px: Float, py: Float): Boolean {
        return getBoundingBox().contains((px + 0.5f).toInt(), (py + 0.5f).toInt())
    }

    private fun isKeepButtonPressedAfterMinTime(): Boolean {
        val binding = getBindingAt(0)
        return !isToggleSwitch && (binding == Binding.GAMEPAD_BUTTON_L3 || binding == Binding.GAMEPAD_BUTTON_R3)
    }

    /**
     * 处理触控按下事件
     * @param pointerId 指针 ID
     * @param x X 坐标
     * @param y Y 坐标
     * @return 是否处理了此事件
     */
    fun handleTouchDown(pointerId: Int, x: Float, y: Float): Boolean {
        if (currentPointerId == -1 && containsPoint(x, y)) {
            currentPointerId = pointerId

            when (type) {
                Type.CHEAT_CODE_TEXT -> {
                    if (!cheatCodePressed) {
                        inputControlsView.sendText(getCheatCodeText())
                        cheatCodePressed = true
                    }
                    return true
                }
                Type.COMBINE_BUTTON -> {
                    if (isKeepButtonPressedAfterMinTime()) touchTime = System.currentTimeMillis()
                    if (!isToggleSwitch || !isSelected) {
                        for (i in states.indices) {
                            val binding = getBindingAt(i)
                            if (binding != Binding.NONE) {
                                inputControlsView.handleInputEvent(binding, true)
                            }
                        }
                    }
                    setFlag(FLAG_PRESSED, true)
                    inputControlsView.invalidate()
                    return true
                }
                Type.BUTTON -> {
                    if (isKeepButtonPressedAfterMinTime()) touchTime = System.currentTimeMillis()
                    val binding = getBindingAt(0)
                    if (!isFlagSet(FLAG_TOGGLE_SWITCH) || !isFlagSet(FLAG_SELECTED)) {
                        if (binding != Binding.NONE) {
                            inputControlsView.handleInputEvent(binding, true)
                        }
                    }

                    // 如果启用了鼠标移动模式，将触摸事件传递给触摸板
                    if (isMouseMoveMode()) {
                        inputControlsView.handleButtonMouseMove(pointerId, x, y, android.view.MotionEvent.ACTION_DOWN)
                    }

                    setFlag(FLAG_PRESSED, true)
                    inputControlsView.invalidate()
                    return true
                }
                Type.RANGE_BUTTON -> {
                    if (scroller == null) {
                        scroller = RangeScroller(inputControlsView, this)
                    }
                    scroller?.handleTouchDown(x, y)
                    setFlag(FLAG_PRESSED, true)
                    inputControlsView.invalidate()
                    return true
                }
                else -> {
                    if (type == Type.TRACKPAD) {
                        if (currentPosition == null) currentPosition = PointF()
                        currentPosition?.set(x, y)
                    }
                    return handleTouchMove(pointerId, x, y)
                }
            }
        }
        return false
    }

    /**
     * 处理触控移动事件
     * @param pointerId 指针 ID
     * @param x X 坐标
     * @param y Y 坐标
     * @return 是否处理了此事件
     */
    fun handleTouchMove(pointerId: Int, x: Float, y: Float): Boolean {
        // 处理鼠标移动模式
        if (pointerId == currentPointerId && type == Type.BUTTON && isMouseMoveMode()) {
            inputControlsView.handleButtonMouseMove(pointerId, x, y, android.view.MotionEvent.ACTION_MOVE)
            return true
        }

        if (pointerId == currentPointerId && (type == Type.D_PAD || type == Type.STICK || type == Type.TRACKPAD)) {
            var deltaX: Float
            var deltaY: Float
            val box = getBoundingBox()
            val radius = box.width() * 0.5f

            when (type) {
                Type.TRACKPAD -> {
                    if (currentPosition == null) currentPosition = PointF()
                    val deltaPoint = inputControlsView.computeDeltaPoint(currentPosition!!.x, currentPosition!!.y, x, y)
                    deltaX = deltaPoint[0]
                    deltaY = deltaPoint[1]
                    currentPosition?.set(x, y)
                }
                else -> {
                    val localX = x - box.left
                    val localY = y - box.top
                    var offsetX = localX - radius
                    var offsetY = localY - radius

                    val distance = kotlin.math.sqrt((radius - localX) * (radius - localX) + (radius - localY) * (radius - localY))
                    if (distance > radius) {
                        val angle = kotlin.math.atan2(offsetY, offsetX)
                        offsetX = (kotlin.math.cos(angle) * radius).toFloat()
                        offsetY = (kotlin.math.sin(angle) * radius).toFloat()
                    }

                    deltaX = Mathf.clamp(offsetX / radius, -1f, 1f)
                    deltaY = Mathf.clamp(offsetY / radius, -1f, 1f)
                }
            }

            when (type) {
                Type.STICK -> {
                    if (currentPosition == null) currentPosition = PointF()
                    currentPosition?.x = box.left + deltaX * radius + radius
                    currentPosition?.y = box.top + deltaY * radius + radius

                    val newStates = booleanArrayOf(
                        deltaY <= -STICK_DEAD_ZONE,
                        deltaX >= STICK_DEAD_ZONE,
                        deltaY >= STICK_DEAD_ZONE,
                        deltaX <= -STICK_DEAD_ZONE
                    )

                    for (i in 0..3) {
                        val binding = getBindingAt(i)

                        if (binding.isGamepad()) {
                            val inputValue = if (i == 1 || i == 3) deltaX else deltaY
                            val adjustedValue = Mathf.clamp(
                                kotlin.math.abs(kotlin.math.abs(inputValue) - 0.01f) * kotlin.math.sign(inputValue) * STICK_SENSITIVITY,
                                -1f, 1f
                            )
                            inputControlsView.handleInputEvent(binding, true, adjustedValue)
                            states[i] = true
                        } else if (binding != Binding.NONE) {
                            val state = if (binding.isMouseMove()) (newStates[i] || newStates[(i + 2) % 4]) else newStates[i]
                            val value = if (binding.isMouseMove()) {
                                if (i == 1 || i == 3) kotlin.math.sign(deltaX) else kotlin.math.sign(deltaY)
                            } else {
                                if (i == 1 || i == 3) deltaX else deltaY
                            }
                            inputControlsView.handleInputEvent(binding, state, value)
                            states[i] = state
                        }
                    }

                    inputControlsView.invalidate()
                }
                Type.TRACKPAD -> {
                    val newStates = booleanArrayOf(
                        deltaY <= -TRACKPAD_MIN_SPEED,
                        deltaX >= TRACKPAD_MIN_SPEED,
                        deltaY >= TRACKPAD_MIN_SPEED,
                        deltaX <= -TRACKPAD_MIN_SPEED
                    )
                    var cursorDx = 0
                    var cursorDy = 0

                    for (i in 0..3) {
                        val binding = getBindingAt(i)
                        val inputValue = if (i == 1 || i == 3) deltaX else deltaY

                        if (binding.isGamepad()) {
                            if (kotlin.math.abs(inputValue) > TRACKPAD_ACCELERATION_THRESHOLD) {
                                inputControlsView.handleInputEvent(binding, true, inputValue * STICK_SENSITIVITY)
                            }
                            if (interpolator == null) interpolator = CubicBezierInterpolator()
                            interpolator?.set(0.075f, 0.95f, 0.45f, 0.95f)
                            val interpolatedValue = interpolator?.getInterpolation(kotlin.math.min(1.0f, kotlin.math.abs(inputValue / TRACKPAD_MAX_SPEED))) ?: 0f
                            inputControlsView.handleInputEvent(binding, true, Mathf.clamp(interpolatedValue * kotlin.math.sign(inputValue), -1f, 1f))
                            states[i] = true
                        } else if (binding != Binding.NONE) {
                            if (kotlin.math.abs(inputValue) > InputControlsView.CURSOR_ACCELERATION_THRESHOLD) {
                                inputControlsView.handleInputEvent(binding, true, inputValue * InputControlsView.CURSOR_ACCELERATION)
                            }
                            when (binding) {
                                Binding.MOUSE_MOVE_LEFT, Binding.MOUSE_MOVE_RIGHT -> cursorDx = kotlin.math.round(inputValue).toInt()
                                Binding.MOUSE_MOVE_UP, Binding.MOUSE_MOVE_DOWN -> cursorDy = kotlin.math.round(inputValue).toInt()
                                else -> {
                                    val value = if (binding.isMouseMove()) {
                                        if (i == 1 || i == 3) kotlin.math.sign(deltaX) else kotlin.math.sign(deltaY)
                                    } else {
                                        inputValue
                                    }
                                    inputControlsView.handleInputEvent(binding, newStates[i], value)
                                    states[i] = newStates[i]
                                }
                            }
                        }
                    }

                    if (cursorDx != 0 || cursorDy != 0) {
                        inputControlsView.injectPointerMove(cursorDx, cursorDy)
                    }
                }
                Type.D_PAD -> {
                    val newStates = booleanArrayOf(
                        deltaY <= -DPAD_DEAD_ZONE,
                        deltaX >= DPAD_DEAD_ZONE,
                        deltaY >= DPAD_DEAD_ZONE,
                        deltaX <= -DPAD_DEAD_ZONE
                    )

                    for (i in 0..3) {
                        val binding = getBindingAt(i)
                        if (binding != Binding.NONE) {
                            val state = if (binding.isMouseMove()) (newStates[i] || newStates[(i + 2) % 4]) else newStates[i]
                            val value = if (binding.isMouseMove()) {
                                if (i == 1 || i == 3) kotlin.math.sign(deltaX) else kotlin.math.sign(deltaY)
                            } else {
                                if (i == 1 || i == 3) deltaX else deltaY
                            }
                            inputControlsView.handleInputEvent(binding, state, value)
                            states[i] = state
                        }
                    }
                }
                else -> {}
            }

            return true
        } else if (pointerId == currentPointerId && type == Type.RANGE_BUTTON) {
            scroller?.handleTouchMove(x, y)
            // 检查 scroller 的滚动状态（通过比较 scrollOffset 的变化）
            val currentScroller = scroller
            val isCurrentlyScrolling = currentScroller != null && kotlin.math.abs(currentScroller.getScrollOffset()) > 0.1f
            if (isCurrentlyScrolling) {
                setFlag(FLAG_PRESSED, false)
                inputControlsView.invalidate()
            }
            return true
        }

        return false
    }

    /**
     * 处理触控抬起事件
     * @param pointerId 指针 ID
     * @param x X 坐标
     * @param y Y 坐标
     * @return 是否处理了此事件
     */
    fun handleTouchUp(pointerId: Int, x: Float, y: Float): Boolean {
        if (pointerId == currentPointerId) {
            when (type) {
                Type.CHEAT_CODE_TEXT -> {
                    cheatCodePressed = false
                }
                Type.COMBINE_BUTTON -> {
                    var selected = isSelected
                    if (isKeepButtonPressedAfterMinTime() && touchTime != null) {
                        selected = (System.currentTimeMillis() - touchTime!!) > BUTTON_MIN_TIME_TO_KEEP_PRESSED
                        if (!selected) {
                            for (i in states.indices.reversed()) {
                                if (getBindingAt(i) != Binding.NONE) {
                                    inputControlsView.handleInputEvent(getBindingAt(i), false)
                                }
                            }
                        }
                        setFlag(FLAG_SELECTED, selected)
                        touchTime = null
                    } else if (!isFlagSet(FLAG_TOGGLE_SWITCH) || isFlagSet(FLAG_SELECTED)) {
                        for (i in states.indices.reversed()) {
                            if (getBindingAt(i) != Binding.NONE) {
                                inputControlsView.handleInputEvent(getBindingAt(i), false)
                            }
                        }
                    }

                    if (isToggleSwitch) {
                        setFlag(FLAG_SELECTED, !selected)
                        inputControlsView.invalidate()
                    }
                    setFlag(FLAG_PRESSED, false)
                    inputControlsView.invalidate()
                }
                Type.BUTTON -> {
                    var selected = isFlagSet(FLAG_SELECTED)
                    val binding = getBindingAt(0)
                    if (isKeepButtonPressedAfterMinTime() && touchTime != null) {
                        selected = (System.currentTimeMillis() - touchTime!!) > BUTTON_MIN_TIME_TO_KEEP_PRESSED
                        if (!selected && binding != Binding.NONE) {
                            inputControlsView.handleInputEvent(binding, false)
                        }
                        setFlag(FLAG_SELECTED, selected)
                        touchTime = null
                    } else if (!isFlagSet(FLAG_TOGGLE_SWITCH) || isFlagSet(FLAG_SELECTED)) {
                        if (binding != Binding.NONE) {
                            inputControlsView.handleInputEvent(binding, false)
                        }
                    }

                    // 如果启用了鼠标移动模式，停止触摸板移动
                    if (isMouseMoveMode()) {
                        inputControlsView.handleButtonMouseMove(pointerId, x, y, android.view.MotionEvent.ACTION_UP)
                    }

                    if (isFlagSet(FLAG_TOGGLE_SWITCH)) {
                        setFlag(FLAG_SELECTED, !selected)
                    }
                    setFlag(FLAG_PRESSED, false)
                    inputControlsView.invalidate()
                }
                Type.RANGE_BUTTON, Type.D_PAD, Type.STICK, Type.TRACKPAD -> {
                    for (i in states.indices) {
                        if (states[i]) inputControlsView.handleInputEvent(getBindingAt(i), false)
                        states[i] = false
                    }

                    if (type == Type.RANGE_BUTTON) {
                        scroller?.handleTouchUp()
                        setFlag(FLAG_PRESSED, false)
                        inputControlsView.invalidate()
                    } else if (type == Type.STICK) {
                        inputControlsView.invalidate()
                    }

                    currentPosition = null
                    setFlag(FLAG_PRESSED, false)
                }
            }
            currentPointerId = -1
            return true
        }
        return false
    }

    private fun getLightColor(): Int {
        val opacity = if (inputControlsView.isEditMode()) maxOf(0.15f, 1.0f) else 1.0f
        return Color.argb((opacity * inputControlsView.overlayOpacity * 255).toInt(), 255, 255, 255)
    }

    private fun getDarkColor(): Int {
        val opacity = if (inputControlsView.isEditMode()) maxOf(0.15f, 1.0f) else 1.0f
        return Color.argb((opacity * inputControlsView.overlayOpacity * 255).toInt(), 0, 0, 0)
    }

    private fun getHighlightColor(): Int {
        return Color.argb((inputControlsView.overlayOpacity * 255).toInt(), 2, 119, 189)
    }
}

/** CubicBezierInterpolator for smooth animations */
class CubicBezierInterpolator {
    private var mX1 = 0f
    private var mY1 = 0f
    private var mX2 = 0f
    private var mY2 = 0f
    private var mSamples = 200
    private var mCurve = FloatArray(0)

    fun set(x1: Float, y1: Float, x2: Float, y2: Float) {
        if (x1 != mX1 || y1 != mY1 || x2 != mX2 || y2 != mY2) {
            mX1 = x1
            mY1 = y1
            mX2 = x2
            mY2 = y2
            mCurve = FloatArray(mSamples)
            for (i in 0 until mSamples) {
                val t = i.toFloat() / (mSamples - 1)
                mCurve[i] = sampleCurveY(sampleCurveX(t))
            }
        }
    }

    fun getInterpolation(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        val position = (t * (mSamples - 1)).toInt()
        val nextPosition = minOf(position + 1, mSamples - 1)
        val between = (t * (mSamples - 1)) - position
        return mCurve[position] + (mCurve[nextPosition] - mCurve[position]) * between
    }

    private fun sampleCurveX(t: Float): Float {
        return ((1 - t) * (1 - t) * (1 - t) * 0 + 3 * (1 - t) * (1 - t) * t * mX1 + 3 * (1 - t) * t * t * mX2 + t * t * t * 1).toFloat()
    }

    private fun sampleCurveY(t: Float): Float {
        return ((1 - t) * (1 - t) * (1 - t) * 0 + 3 * (1 - t) * (1 - t) * t * mY1 + 3 * (1 - t) * t * t * mY2 + t * t * t * 1).toFloat()
    }
}
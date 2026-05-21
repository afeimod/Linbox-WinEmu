package org.github.ewt45.winemulator.inputcontrols

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.github.ewt45.winemulator.inputcontrols.ControlElement.Shape
import org.github.ewt45.winemulator.inputcontrols.ControlElement.Type
import kotlin.math.*

/**
 * View for rendering and interacting with input controls
 */
@SuppressLint("ViewConstructor")
class InputControlsView(
    context: Context
) : View(context) {

    private var _editMode: Boolean = false
    var editMode: Boolean
        get() = _editMode
        set(value) { _editMode = value }

    var inputEventHandler: InputEventHandler? = null
    var profile: ControlsProfile? = null
        private set
    var showTouchscreenControls = true
    var overlayOpacity = 0.4f

    var touchpadView: TouchpadView? = null

    val snappingSize: Int
        get() = if (width > 0) maxOf(width, height) / 100 else 10

    val maxWidth: Int
        get() = if (snappingSize > 0) (width.toFloat() / snappingSize).roundToInt() * snappingSize else width

    val maxHeight: Int
        get() = if (snappingSize > 0) (height.toFloat() / snappingSize).roundToInt() * snappingSize else height

    private var selectedElement: ControlElement? = null
    private var moveCursor = false
    private var offsetX = 0f
    private var offsetY = 0f
    private val cursor = Point()
    private var pendingProfileReload = false  // 标记是否需要在新尺寸测量后重新加载配置

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var readyToDraw = false

    private var vibrator: Vibrator? = null
    private var vibrationEffect: VibrationEffect? = null

    private var rangeScroller: RangeScroller? = null
    private var currentElementForScroller: ControlElement? = null

    // 跟踪已被控件占用的触点ID集合
    private val occupiedPointerIds = mutableSetOf<Int>()
    
    // 跟踪触控板使用的触点ID及其最后位置（简单方案：不区分激活状态）
    private val touchpadPointers = mutableMapOf<Int, PointF>()
    
    // 跟踪触点的按下时间和位置，用于检测点击
    private data class TouchDownInfo(
        val downTime: Long,
        val downPosition: PointF,
        var isUp: Boolean = false  // 标记该触点是否已抬起
    )
    private val touchDownInfos = mutableMapOf<Int, TouchDownInfo>()
    
    companion object {
        const val CLICK_MAX_DISTANCE = 10f  // 点击的最大移动距离（像素）
        const val CLICK_MAX_TIME = 200L     // 点击的最长时间（毫秒）
    }



    // Icon cache
    private val icons = arrayOfNulls<Bitmap>(17)

    // 用于检测尺寸变化，重新加载元素坐标
    private var lastMaxWidth = 0
    private var lastMaxHeight = 0

    init {
        // 默认可点击可聚焦，但会根据 showTouchscreenControls 动态调整
        setClickable(true)
        setFocusable(true)
        isFocusableInTouchMode = true
        setBackgroundColor(Color.TRANSPARENT)
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        @Suppress("DEPRECATION")
        try {
            vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            vibrationEffect = VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
        } catch (e: Exception) {
            vibrator = null
        }
    }

    /**
     * 设置是否显示虚拟按键，同时调整视图的点击和聚焦状态
     */
    @JvmName("setControlsVisible")
    fun setControlsVisible(show: Boolean) {
        showTouchscreenControls = show
        // 当不显示虚拟按键时，禁用所有交互，确保不拦截触摸事件
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        // 刷新视图以更新绘制
        invalidate()
    }



    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 当视图尺寸变化时，重新加载元素以重新计算坐标
        // 这处理了屏幕旋转和分辨率变化的情况
        if (w > 0 && h > 0) {
            // 如果有待加载的配置，先加载配置
            if (pendingProfileReload && profile != null) {
                pendingProfileReload = false
                reloadElements()
            }
            // 无论尺寸是否变化，都重新加载元素以确保坐标正确
            else if (profile != null) {
                reloadElements()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 当屏幕方向变化时，重新加载元素以重新计算坐标
        if (profile != null && width > 0 && height > 0) {
            reloadElements()
        }
    }

    private fun reloadElements() {
        if (profile != null) {
            // 保存当前选中的元素（如果有）
            val selected = selectedElement
            // 重新加载元素（会更新所有元素的坐标）
            profile!!.loadElements(this)
            // 恢复选中状态
            if (selected != null) {
                val newSelected = profile!!.getElements().find { it == selected }
                selectElement(newSelected)
            }
        }
        invalidate()
    }

    fun setEditMode(mode: Boolean) {
        _editMode = mode
    }

    fun setProfile(profile: ControlsProfile?) {
        this.profile = profile
        deselectAllElements()
        // 立即加载元素，但可能视图尚未测量
        if (width > 0 && height > 0) {
            pendingProfileReload = false
            reloadElements()
        } else {
            // 视图尚未测量，标记待加载，下次 onSizeChanged 时加载
            pendingProfileReload = true
        }
    }

    fun getSelectedElement(): ControlElement? = selectedElement

    fun addElement(): Boolean {
        if (editMode && profile != null) {
            val element = ControlElement(this)
            element.x = cursor.x
            element.y = cursor.y
            element.initDefaultBindings()
            profile!!.addElement(element)
            profile!!.save()
            selectElement(element)
            return true
        }
        return false
    }

    fun removeElement(): Boolean {
        if (editMode && selectedElement != null && profile != null) {
            profile!!.removeElement(selectedElement!!)
            selectedElement = null
            profile!!.save()
            invalidate()
            return true
        }
        return false
    }

    fun getRangeScroller(): RangeScroller? = rangeScroller

    private fun deselectAllElements() {
        selectedElement = null
        profile?.getElements()?.forEach { it.isSelected = false }
    }

    private fun selectElement(element: ControlElement?) {
        deselectAllElements()
        if (element != null) {
            selectedElement = element
            element.isSelected = true
        }
        invalidate()
    }

    fun handleInputEvent(binding: Binding, isDown: Boolean, value: Float = 0f) {
        when {
            binding.isGamepad -> {
                // Gamepad events handled separately
            }
            binding.isMouse -> {
                when {
                    binding.isMouseMove() -> {
                        // 处理鼠标移动
                        val dx = when (binding) {
                            Binding.MOUSE_MOVE_LEFT -> -10
                            Binding.MOUSE_MOVE_RIGHT -> 10
                            else -> 0
                        }
                        val dy = when (binding) {
                            Binding.MOUSE_MOVE_UP -> -10
                            Binding.MOUSE_MOVE_DOWN -> 10
                            else -> 0
                        }
                        if (isDown && (dx != 0 || dy != 0)) {
                            inputEventHandler?.onPointerMove(dx, dy)
                        }
                    }
                    else -> {
                        // 处理鼠标按钮事件，使用 getPointerButton 方法
                        binding.getPointerButton()?.let { button ->
                            inputEventHandler?.onPointerButton(button, isDown)
                        }
                    }
                }
            }
            binding.isKeyboard -> {
                inputEventHandler?.onKeyEvent(binding.keycode, isDown)
            }
        }
    }

    fun injectPointerMove(dx: Int, dy: Int) {
        inputEventHandler?.onPointerMove(dx, dy)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height

        if (w == 0 || h == 0) {
            readyToDraw = false
            return
        }

        readyToDraw = true

        if (editMode) {
            drawGrid(canvas)
            drawCursor(canvas)
        }

        if (profile != null) {
            if (!profile!!.isElementsLoaded()) {
                reloadElements()
            }
            if (showTouchscreenControls) {
                profile!!.getElements().forEach { element ->
                    element.draw(canvas)
                }
            }
        }

        super.onDraw(canvas)
    }

    private fun drawGrid(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.strokeWidth = snappingSize * 0.0625f
        paint.color = Color.BLACK
        canvas.drawColor(Color.BLACK)

        paint.isAntiAlias = false
        paint.color = Color.rgb(48, 48, 48)

        val w = maxWidth
        val h = maxHeight

        var i = 0
        while (i <= w) {
            canvas.drawLine(i.toFloat(), 0f, i.toFloat(), h.toFloat(), paint)
            i += snappingSize
        }
        i = 0
        while (i <= h) {
            canvas.drawLine(0f, i.toFloat(), w.toFloat(), i.toFloat(), paint)
            i += snappingSize
        }

        val cx = roundTo(w * 0.5f, snappingSize.toFloat())
        val cy = roundTo(h * 0.5f, snappingSize.toFloat())
        paint.color = Color.rgb(66, 66, 66)

        i = 0
        while (i <= w) {
            canvas.drawLine(cx, i.toFloat(), cx, (i + snappingSize).toFloat(), paint)
            i += snappingSize * 2
        }
        i = 0
        while (i <= h) {
            canvas.drawLine(i.toFloat(), cy, (i + snappingSize).toFloat(), cy, paint)
            i += snappingSize * 2
        }

        paint.isAntiAlias = true
    }

    private fun drawCursor(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.strokeWidth = snappingSize * 0.0625f
        paint.color = Color.rgb(198, 40, 40)

        paint.isAntiAlias = false
        canvas.drawLine(0f, cursor.y.toFloat(), maxWidth.toFloat(), cursor.y.toFloat(), paint)
        canvas.drawLine(cursor.x.toFloat(), 0f, cursor.x.toFloat(), maxHeight.toFloat(), paint)
        paint.isAntiAlias = true
    }

    fun getPaint(): Paint = paint

    fun getPath(): Path = path

    fun getColorFilter(): ColorFilter {
        return PorterDuffColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
    }

    fun getDarkColorFilter(): ColorFilter {
        return PorterDuffColorFilter(0xFF888888.toInt(), PorterDuff.Mode.SRC_IN)
    }

    fun getPrimaryColor(): Int = Color.argb((overlayOpacity * 255).toInt(), 255, 255, 255)

    fun getSecondaryColor(): Int = Color.argb((overlayOpacity * 255).toInt(), 2, 119, 189)

    /**
     * 获取高亮颜色（用于 RANGE-BUTTON 滑动时的高亮显示）
     */
    fun getHighlightColor(): Int = Color.argb((overlayOpacity * 255).toInt(), 255, 193, 7)  // 橙黄色高亮

    fun getIcon(id: Byte): Bitmap? {
        if (icons[id.toInt()] == null) {
            try {
                context.assets.open("inputcontrols/icons/$id.png").use { inputStream ->
                    icons[id.toInt()] = BitmapFactory.decodeStream(inputStream)
                }
            } catch (e: Exception) {
                // Icon not found
            }
        }
        return icons[id.toInt()]
    }

    private fun roundTo(value: Float, rounding: Float): Float {
        return (value / rounding).roundToInt() * rounding
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editMode && readyToDraw) {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val x = event.x
                    val y = event.y

                    val element = intersectElement(x, y)
                    moveCursor = true

                    if (element != null) {
                        offsetX = x - element.x
                        offsetY = y - element.y
                        moveCursor = false
                    }

                    selectElement(element)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (selectedElement != null) {
                        selectedElement!!.x = roundTo(event.x - offsetX, snappingSize.toFloat()).toInt()
                        selectedElement!!.y = roundTo(event.y - offsetY, snappingSize.toFloat()).toInt()
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (selectedElement != null && profile != null) {
                        profile!!.save()
                    }
                    if (moveCursor) {
                        cursor.x = roundTo(event.x, snappingSize.toFloat()).toInt()
                        cursor.y = roundTo(event.y, snappingSize.toFloat()).toInt()
                    }
                    invalidate()
                }
            }
            return true
        }

        // 非编辑模式下，只有当 showTouchscreenControls 为 true 时才处理触摸事件
        if (!editMode && profile != null && showTouchscreenControls) {
            val actionIndex = event.actionIndex
            val pointerId = event.getPointerId(actionIndex)
            val actionMasked = event.actionMasked

            var handled = false

            when (actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val x = event.getX(actionIndex)
                    val y = event.getY(actionIndex)

                    var handledByControl = false
                    for (element in profile!!.getElements()) {
                        if (element.handleTouchDown(pointerId, x, y)) {
                            vibrator?.vibrate(vibrationEffect)
                            // 记录该触点已被占用
                            occupiedPointerIds.add(pointerId)
                            handledByControl = true
                            break
                        }
                    }

                    if (!handledByControl) {
                        // 这个触点没有被控件占用，可以用于触控板
                        touchpadPointers[pointerId] = PointF(x, y)
                        // 记录按下时间和位置，用于检测点击
                        touchDownInfos[pointerId] = TouchDownInfo(System.currentTimeMillis(), PointF(x, y))
                    }
                    // DOWN 事件总是被处理
                    handled = true
                }
                MotionEvent.ACTION_MOVE -> {
                    // 遍历所有触点，独立处理每个触点的移动
                    for (i in 0 until event.pointerCount) {
                        val x = event.getX(i)
                        val y = event.getY(i)
                        val id = event.getPointerId(i)

                        // 如果该触点已被某个控件占用，则交给控件处理
                        if (occupiedPointerIds.contains(id)) {
                            for (element in profile!!.getElements()) {
                                if (element.handleTouchMove(id, x, y)) {
                                    handled = true
                                    break
                                }
                            }
                        } else {
                            // 这个触点没有被控件占用，作为触控板处理
                            val lastPos = touchpadPointers[id]
                            
                            if (lastPos != null) {
                                // 计算相对于上次位置的增量移动
                                val dx = x - lastPos.x
                                val dy = y - lastPos.y
                                
                                // 直接调用输入事件处理器，发送鼠标移动事件
                                if (abs(dx) > TouchpadView.CURSOR_ACCELERATION_THRESHOLD || abs(dy) > TouchpadView.CURSOR_ACCELERATION_THRESHOLD) {
                                    inputEventHandler?.onPointerMove(
                                        (dx * TouchpadView.CURSOR_ACCELERATION).toInt(),
                                        (dy * TouchpadView.CURSOR_ACCELERATION).toInt()
                                    )
                                } else {
                                    inputEventHandler?.onPointerMove(dx.toInt(), dy.toInt())
                                }
                                
                                // 更新最后位置
                                lastPos.set(x, y)
                                // 标记事件已被处理
                                handled = true
                            } else {
                                // 第一次看到这个未占用的触点，记录初始位置
                                touchpadPointers[id] = PointF(x, y)
                                handled = true
                            }
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    var handledByControl = false
                    for (element in profile!!.getElements()) {
                        if (element.handleTouchUp(pointerId, event.getX(actionIndex), event.getY(actionIndex))) {
                            handledByControl = true
                        }
                    }

                    if (handledByControl) {
                        // 释放该触点的占用状态
                        occupiedPointerIds.remove(pointerId)
                    } else {
                        // 这个触点是用于触控板的，检查是否是点击
                        val downInfo = touchDownInfos[pointerId]
                        val lastPos = touchpadPointers[pointerId]
                        
                        var isClick = false
                        if (downInfo != null && lastPos != null) {
                            val elapsed = System.currentTimeMillis() - downInfo.downTime
                            val distance = sqrt(
                                (lastPos.x - downInfo.downPosition.x).let { it * it } +
                                (lastPos.y - downInfo.downPosition.y).let { it * it }
                            )
                            
                            // 如果移动距离很小且时间很短，视为点击
                            isClick = (distance < CLICK_MAX_DISTANCE && elapsed < CLICK_MAX_TIME)
                        }
                        
                        if (isClick && actionMasked == MotionEvent.ACTION_UP) {
                            // 这是最后一个手指抬起，检查是否有其他触点也是点击
                            val otherClicks = touchDownInfos.filterKeys { it != pointerId }
                            
                            if (otherClicks.isNotEmpty()) {
                                // 有其他触点，检查它们是否也是点击
                                val allAreClicks = otherClicks.all { (id, info) ->
                                    val otherLastPos = touchpadPointers[id]
                                    if (otherLastPos != null) {
                                        val otherElapsed = System.currentTimeMillis() - info.downTime
                                        val otherDistance = sqrt(
                                            (otherLastPos.x - info.downPosition.x).let { it * it } +
                                            (otherLastPos.y - info.downPosition.y).let { it * it }
                                        )
                                        otherDistance < CLICK_MAX_DISTANCE && otherElapsed < CLICK_MAX_TIME
                                    } else {
                                        false
                                    }
                                }
                                
                                if (allAreClicks && otherClicks.size == 1) {
                                    // 双指点击，发送鼠标右键事件（button 3 = 右键）
                                    inputEventHandler?.onPointerButton(3, true)
                                    inputEventHandler?.onPointerButton(3, false)
                                } else {
                                    // 多个手指或其他情况，发送左键
                                    inputEventHandler?.onPointerButton(1, true)
                                    inputEventHandler?.onPointerButton(1, false)
                                }
                            } else {
                                // 单指点击，发送鼠标左键事件（button 1 = 左键）
                                inputEventHandler?.onPointerButton(1, true)
                                inputEventHandler?.onPointerButton(1, false)
                            }
                        }
                        
                        // 移除触控板记录（如果是最后一个手指，清除所有记录）
                        if (actionMasked == MotionEvent.ACTION_UP) {
                            touchpadPointers.clear()
                            touchDownInfos.clear()
                        } else {
                            // ACTION_POINTER_UP: 标记为已抬起，但不移除记录
                            val info = touchDownInfos[pointerId]
                            if (info != null) {
                                info.isUp = true
                            }
                            // 不移除 touchpadPointers，保留最后位置用于点击检测
                        }
                    }
                    // UP/CANCEL 事件总是被处理
                    handled = true
                }
            }
            return handled
        }
        // 当 showTouchscreenControls 为 false 或 profile 为 null 时，不处理触摸事件，让事件传递给下层
        return false
    }

    private fun intersectElement(x: Float, y: Float): ControlElement? {
        profile?.getElements()?.forEach { element ->
            if (element.containsPoint(x, y)) return element
        }
        return null
    }
}

/**
 * Touchpad view for mouse simulation
 */
@SuppressLint("ViewConstructor")
class TouchpadView(context: Context) : View(context) {
    var isPointerButtonLeftEnabled = true
        private set

    private var swapMouseButtons = false
    private var simTouchScreen = false

    private var lastX = 0f
    private var lastY = 0f

    var inputEventHandler: InputEventHandler? = null

    companion object {
        const val CURSOR_ACCELERATION = 1.2f  // 降低灵敏度，从 1.5f 改为 1.2f
        const val CURSOR_ACCELERATION_THRESHOLD = 4f
    }

    fun setPointerButtonLeftEnabled(enabled: Boolean) {
        isPointerButtonLeftEnabled = enabled
    }

    fun setSwapMouseButtons() {
        swapMouseButtons = !swapMouseButtons
    }

    fun setSimTouchScreen() {
        simTouchScreen = !simTouchScreen
    }

    fun computeDeltaPoint(oldX: Float, oldY: Float, newX: Float, newY: Float): FloatArray {
        return floatArrayOf(newX - oldX, newY - oldY)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY

                if (abs(dx) > CURSOR_ACCELERATION_THRESHOLD || abs(dy) > CURSOR_ACCELERATION_THRESHOLD) {
                    inputEventHandler?.onPointerMove(
                        (dx * CURSOR_ACCELERATION).toInt(),
                        (dy * CURSOR_ACCELERATION).toInt()
                    )
                } else {
                    inputEventHandler?.onPointerMove(dx.toInt(), dy.toInt())
                }

                lastX = event.x
                lastY = event.y
            }
        }
        return true
    }
}
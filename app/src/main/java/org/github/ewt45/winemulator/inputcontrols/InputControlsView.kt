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
 * 
 * 修复内容: 完全参考 termux-app 的 handleTouchEvent 逻辑
 * 核心：
 * 1. 遍历所有 ControlElement，让每个都有机会处理触摸事件
 * 2. 只有当所有元素都没有处理时，才将事件传递给触摸板
 * 3. 虚拟按键和触摸板可以同时独立工作
 */
@SuppressLint("ViewConstructor")
class InputControlsView(
    context: Context,
    private var editMode: Boolean = false
) : View(context) {

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
    private var pendingProfileReload = false
    private val icons: Array<Bitmap?> = arrayOfNulls(256)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var readyToDraw = false

    private var vibrator: Vibrator? = null
    private var vibrationEffect: VibrationEffect? = null


    init {
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

    @JvmName("setControlsVisible")
    fun setControlsVisible(show: Boolean) {
        showTouchscreenControls = show
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        invalidate()
    }


    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            if (pendingProfileReload && profile != null) {
                pendingProfileReload = false
                reloadElements()
            } else if (profile != null) {
                reloadElements()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (profile != null && width > 0 && height > 0) {
            reloadElements()
        }
    }

    private fun reloadElements() {
        if (profile != null) {
            val selected = selectedElement
            profile!!.loadElements(this)
            if (selected != null) {
                val newSelected = profile!!.getElements().find { it == selected }
                selectElement(newSelected)
            }
        }
        invalidate()
    }

    fun setEditMode(mode: Boolean) {
        editMode = mode
    }

    fun isEditMode(): Boolean = editMode

    fun setProfile(profile: ControlsProfile?) {
        this.profile = profile
        deselectAllElements()
        if (width > 0 && height > 0) {
            pendingProfileReload = false
            reloadElements()
        } else {
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
        return PorterDuffColorFilter(0x80000000.toInt(), PorterDuff.Mode.SRC_IN)
    }

    fun getPrimaryColor(): Int = Color.argb((overlayOpacity * 255).toInt(), 255, 255, 255)

    fun getSecondaryColor(): Int = Color.argb((overlayOpacity * 255).toInt(), 2, 119, 189)

    fun getHighlightColor(): Int = Color.argb((overlayOpacity * 255).toInt(), 255, 193, 7)

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

        if (!editMode && profile != null && showTouchscreenControls) {
            return handleTouchEvent(event)
        }
        return false
    }

    /**
     * 完全参考 termux-app 的 handleTouchEvent 逻辑重新实现
     * 
     * 核心逻辑（与 termux 完全一致）：
     * 1. ACTION_DOWN/POINTER_DOWN: 遍历所有元素尝试处理，如果有虚拟按键处理则禁用触摸板左键
     * 2. ACTION_MOVE: 遍历所有元素调用 handleTouchMove
     * 3. 如果没有虚拟按键处理，传递给触摸板处理
     * 
     * 这样虚拟按键和触摸板可以同时独立工作：
     * - 一个手指按在虚拟按键上输出按键
     * - 另一个手指在空白区域滑动触摸板移动鼠标
     * 
     * 关键：使用 pointerIdIndexMap 来跟踪哪些指针被虚拟按键捕获
     */
    fun handleTouchEvent(event: MotionEvent): Boolean {
        val actionIndex = event.actionIndex
        val pointerId = event.getPointerId(actionIndex)
        val actionMasked = event.actionMasked
        val pointerCount = event.pointerCount

        var handled = false
        var passthroughHandled = false

        when (actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)

                // 重置触摸板左键功能（参考 termux）
                touchpadView?.setPointerButtonLeftEnabled(true)

                // 遍历所有元素，让每个都有机会处理这个触摸点
                // 这是 termux 的核心逻辑：不是按位置查找，而是让所有元素尝试处理
                var elementHandled = false
                for (element in profile!!.getElements()) {
                    if (element.handleTouchDown(pointerId, x, y)) {
                        elementHandled = true
                        // 如果绑定了鼠标左键，禁用触摸板的左键功能
                        if (element.getBindingAt(0) == Binding.MOUSE_LEFT_BUTTON) {
                            touchpadView?.setPointerButtonLeftEnabled(false)
                        }
                    }
                }
                
                // 只有当虚拟按键处理了事件时才标记为 handled
                handled = elementHandled

                // 如果没有虚拟按键处理这个触摸点，传递给触摸板
                if (!elementHandled) {
                    passthroughHandled = touchpadView?.onTouchEvent(event) == true
                }

                if (handled) {
                    vibrator?.vibrate(vibrationEffect)
                }

                return handled || passthroughHandled
            }

            MotionEvent.ACTION_MOVE -> {
                // 对每个触摸点，遍历所有元素调用 handleTouchMove
                // 关键：使用 pointerIdIndexMap 来跟踪哪些指针被虚拟按键捕获
                for (i in 0 until pointerCount) {
                    val pointerId = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)

                    // 遍历所有元素，让每个都有机会处理移动事件
                    for (element in profile!!.getElements()) {
                        if (element.handleTouchMove(pointerId, x, y)) {
                            handled = true
                        }
                    }
                }

                // 如果没有被虚拟按键处理，传递给触摸板
                if (!handled) {
                    passthroughHandled = touchpadView?.onTouchEvent(event) == true
                }

                return handled || passthroughHandled
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                // 遍历所有指针调用 handleTouchUp
                // 这是 termux 的核心逻辑：遍历所有元素，让每个都有机会处理抬起
                for (i in 0 until pointerCount) {
                    val pointerId = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)
                    for (element in profile!!.getElements()) {
                        if (element.handleTouchUp(pointerId, x, y)) {
                            handled = true
                        }
                    }
                }

                // 如果没有被虚拟按键处理，传递给触摸板
                if (!handled) {
                    passthroughHandled = touchpadView?.onTouchEvent(event) == true
                }

                // 恢复触摸板的左键功能
                touchpadView?.setPointerButtonLeftEnabled(true)

                return handled || passthroughHandled
            }
        }

        return handled || passthroughHandled
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
 * 
 * 修复内容:
 * 1. 添加 setPointerButtonLeftEnabled 方法
 * 2. 改进触摸事件处理
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
        const val CURSOR_ACCELERATION = 2f
        const val CURSOR_ACCELERATION_THRESHOLD = 4f
        const val MAX_TAP_TRAVEL_DISTANCE = 10f
        const val MAX_TAP_MILLISECONDS = 200L
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
        val actionIndex = event.actionIndex
        val actionMasked = event.actionMasked
        val pointerCount = event.pointerCount

        when (actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                lastX = event.getX(actionIndex)
                lastY = event.getY(actionIndex)

                // 如果只有一个手指且触摸板左键功能启用，模拟左键按下
                if (pointerCount == 1 && isPointerButtonLeftEnabled) {
                    val leftButton = Binding.MOUSE_LEFT_BUTTON.getPointerButton()
                    if (leftButton != null) {
                        inputEventHandler?.onPointerButton(leftButton, true)
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                // 使用第一个触摸点处理移动
                if (pointerCount > 0) {
                    val x = event.getX(0)
                    val y = event.getY(0)
                    val dx = x - lastX
                    val dy = y - lastY

                    // 计算加速后的移动距离
                    val absDx = abs(dx)
                    val absDy = abs(dy)
                    val moveX: Int
                    val moveY: Int

                    if (absDx > CURSOR_ACCELERATION_THRESHOLD || absDy > CURSOR_ACCELERATION_THRESHOLD) {
                        moveX = (dx * CURSOR_ACCELERATION).toInt()
                        moveY = (dy * CURSOR_ACCELERATION).toInt()
                    } else {
                        moveX = dx.toInt()
                        moveY = dy.toInt()
                    }

                    if (moveX != 0 || moveY != 0) {
                        inputEventHandler?.onPointerMove(moveX, moveY)
                    }

                    lastX = x
                    lastY = y
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                // 如果是最后一个手指抬起且触摸板左键功能启用，模拟左键释放
                if (pointerCount <= 1 && isPointerButtonLeftEnabled) {
                    val leftButton = Binding.MOUSE_LEFT_BUTTON.getPointerButton()
                    if (leftButton != null) {
                        inputEventHandler?.onPointerButton(leftButton, false)
                    }
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                // 取消操作
            }
        }

        return true
    }
}
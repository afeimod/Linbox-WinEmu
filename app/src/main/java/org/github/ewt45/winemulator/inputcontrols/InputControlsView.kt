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
 * 修复内容:
 * 1. 改进多点触摸处理，允许触摸板和虚拟按键同时工作
 * 2. 改进D-pad按键的持续输出功能
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
    private var pendingProfileReload = false  // 标记是否需要在新尺寸测量后重新加载配置
    private val icons: Array<Bitmap?> = arrayOfNulls(256)  // Icon缓存数组

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var readyToDraw = false

    private var vibrator: Vibrator? = null
    private var vibrationEffect: VibrationEffect? = null

    // 用于追踪每个指针ID对应的元素
    // Key: pointerId, Value: ControlElement
    private val pointerToElementMap = mutableMapOf<Int, ControlElement?>()
    
    // 用于追踪每个指针的初始触摸位置
    // Key: pointerId, Value: Pair<x, y>
    private val pointerInitialPos = mutableMapOf<Int, Pair<Float, Float>>()


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
        editMode = mode
    }

    fun isEditMode(): Boolean = editMode

    fun setProfile(profile: ControlsProfile?) {
        this.profile = profile
        deselectAllElements()
        // 清空指针映射
        pointerToElementMap.clear()
        pointerInitialPos.clear()
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

    /**
     * 获取深色滤镜（按下状态时使用）
     * 使图标变暗以表示按键按下状态
     */
    fun getDarkColorFilter(): ColorFilter {
        return PorterDuffColorFilter(0x80000000.toInt(), PorterDuff.Mode.SRC_IN)
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
            return handleTouchEvent(event)
        }
        // 当 showTouchscreenControls 为 false 或 profile 为 null 时，不处理触摸事件，让事件传递给下层
        return false
    }

    /**
     * 处理触摸事件，支持多点触摸
     * 允许触摸板和虚拟按键同时工作
     */
    private fun handleTouchEvent(event: MotionEvent): Boolean {
        val actionIndex = event.actionIndex
        val pointerId = event.getPointerId(actionIndex)
        val actionMasked = event.actionMasked

        var handled = false
        var passthroughHandled = false
        var passthroughDispatched = false

        when (actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)
                
                // 记录初始触摸位置
                pointerInitialPos[pointerId] = Pair(x, y)
                
                // 先检查是否触摸到了任何虚拟按键元素
                var elementHandled = false
                for (element in profile!!.getElements()) {
                    if (element.handleTouchDown(pointerId, x, y)) {
                        // 将此指针ID映射到该元素
                        pointerToElementMap[pointerId] = element
                        elementHandled = true
                        handled = true
                        
                        // 如果绑定的是鼠标左键，禁用触摸板的左键功能
                        if (element.getBindingAt(0) == Binding.MOUSE_LEFT_BUTTON) {
                            touchpadView?.setPointerButtonLeftEnabled(false)
                        }
                        
                        vibrator?.vibrate(vibrationEffect)
                        break
                    }
                }
                
                // 如果没有元素处理这个触摸，让触摸板尝试处理
                // 注意：触摸板只处理未被虚拟按键占用的指针
                if (!elementHandled) {
                    // 检查触摸板是否可以接受这个新指针
                    if (touchpadView == null || pointerToElementMap.isEmpty()) {
                        val touchpadHandled = touchpadView?.onTouchEvent(event) == true
                        if (touchpadHandled) {
                            // 将此指针ID映射到触摸板（用null表示）
                            pointerToElementMap[pointerId] = null
                            passthroughHandled = true
                            passthroughDispatched = true
                        }
                    }
                }
            }
            
            MotionEvent.ACTION_MOVE -> {
                // 处理所有移动的指针
                for (i in 0 until event.pointerCount) {
                    val currentPointerId = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)
                    
                    // 检查这个指针是否对应一个虚拟按键元素
                    val element = pointerToElementMap[currentPointerId]
                    if (element != null) {
                        // 虚拟按键处理移动事件
                        if (element.handleTouchMove(currentPointerId, x, y)) {
                            handled = true
                        }
                    }
                }
                
                // 触摸板处理所有未映射到虚拟按键的指针的移动
                val touchpadPointers = pointerToElementMap.filter { it.key != null && it.value == null }.keys
                if (touchpadPointers.isNotEmpty() && touchpadView != null) {
                    passthroughHandled = touchpadView?.onTouchEvent(event) == true
                }
            }
            
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                // 获取抬起或取消的指针
                val upPointerId = if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
                    // ACTION_UP 和 ACTION_CANCEL 通常使用 pointerId 0
                    0
                } else {
                    pointerId
                }
                
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)
                
                // 检查这个指针是否对应一个虚拟按键元素
                val element = pointerToElementMap[upPointerId]
                if (element != null) {
                    // 虚拟按键处理抬起事件
                    if (element.handleTouchUp(upPointerId)) {
                        handled = true
                    }
                    // 移除映射
                    pointerToElementMap.remove(upPointerId)
                    pointerInitialPos.remove(upPointerId)
                    
                    // 恢复触摸板的左键功能
                    touchpadView?.setPointerButtonLeftEnabled(true)
                } else {
                    // 没有映射到虚拟按键的指针，传递给触摸板
                    if (!passthroughDispatched) {
                        passthroughHandled = touchpadView?.onTouchEvent(event) == true
                        passthroughDispatched = true
                    }
                }
            }
        }
        
        // 如果是 ACTION_CANCEL，清空所有指针映射
        if (actionMasked == MotionEvent.ACTION_CANCEL) {
            for ((id, _) in pointerToElementMap) {
                val element = pointerToElementMap[id]
                if (element != null) {
                    element.handleTouchUp(id)
                }
            }
            pointerToElementMap.clear()
            pointerInitialPos.clear()
            touchpadView?.setPointerButtonLeftEnabled(true)
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

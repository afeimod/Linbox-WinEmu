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
 * 3. 修复虚拟按键使用鼠标左键时与触摸板左键功能的冲突
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
     * 
     * 关键修复：
     * - 每个触摸点由各自对应的元素独立处理
     * - 虚拟按键区域的触摸由对应的 ControlElement 处理
     * - 非虚拟按键区域的触摸传递给触摸板（用于移动鼠标）
     */
    private fun handleTouchEvent(event: MotionEvent): Boolean {
        val actionIndex = event.actionIndex
        val pointerId = event.getPointerId(actionIndex)
        val actionMasked = event.actionMasked
        val pointerCount = event.pointerCount

        when (actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)
                
                // 记录初始触摸位置
                pointerInitialPos[pointerId] = Pair(x, y)
                
                // 遍历所有元素，检查触摸点落在哪个元素上
                var foundElement: ControlElement? = null
                for (element in profile!!.getElements()) {
                    if (element.containsPoint(x, y)) {
                        foundElement = element
                        break
                    }
                }
                
                if (foundElement != null) {
                    // 触摸在虚拟按键上，由该元素处理
                    if (foundElement.handleTouchDown(pointerId, x, y)) {
                        // 将此指针ID映射到该元素
                        pointerToElementMap[pointerId] = foundElement
                        
                        // 如果绑定的是鼠标左键，禁用触摸板的左键功能
                        if (foundElement.getBindingAt(0) == Binding.MOUSE_LEFT_BUTTON) {
                            touchpadView?.setPointerButtonLeftEnabled(false)
                        }
                        
                        vibrator?.vibrate(vibrationEffect)
                        return true
                    }
                } else {
                    // 触摸不在任何虚拟按键上，由触摸板处理
                    pointerToElementMap[pointerId] = null
                    return touchpadView?.onTouchEvent(event) == true
                }
            }
            
            MotionEvent.ACTION_MOVE -> {
                var anyElementHandled = false
                var anyTouchpadHandled = false
                
                // 处理所有移动的指针
                for (i in 0 until pointerCount) {
                    val currentPointerId = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)
                    
                    // 检查这个指针是否对应一个虚拟按键元素
                    val element = pointerToElementMap[currentPointerId]
                    if (element != null) {
                        // 虚拟按键处理移动事件
                        if (element.handleTouchMove(currentPointerId, x, y)) {
                            anyElementHandled = true
                        }
                    } else {
                        // 未映射到虚拟按键的指针，传递给触摸板
                        val tv = touchpadView
                        if (tv != null) {
                            if (tv.onTouchEvent(event)) {
                                anyTouchpadHandled = true
                            }
                        }
                    }
                }
                
                return anyElementHandled || anyTouchpadHandled
            }
            
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                // 获取抬起或取消的指针ID
                // ACTION_POINTER_UP 时用 pointerId，ACTION_UP 时用第一个指针
                val upPointerId = if (actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    pointerId
                } else {
                    // 对于 ACTION_UP，需要找到正在抬起的指针
                    // 遍历找到不在新 pointerCount 中的旧指针
                    val downPointerIds = mutableSetOf<Int>()
                    for (i in 0 until pointerCount) {
                        downPointerIds.add(event.getPointerId(i))
                    }
                    // 找到已抬起的指针
                    val oldPointerIds = pointerToElementMap.keys.toSet() + pointerInitialPos.keys.toSet()
                    oldPointerIds.firstOrNull { it !in downPointerIds } ?: 0
                }
                
                // 检查这个指针是否对应一个虚拟按键元素
                val element = pointerToElementMap[upPointerId]
                if (element != null) {
                    // 虚拟按键处理抬起事件
                    element.handleTouchUp(upPointerId)
                    // 移除映射
                    pointerToElementMap.remove(upPointerId)
                    pointerInitialPos.remove(upPointerId)
                    
                    // 恢复触摸板的左键功能
                    touchpadView?.setPointerButtonLeftEnabled(true)
                    return true
                } else {
                    // 没有映射到虚拟按键的指针，传递给触摸板
                    pointerToElementMap.remove(upPointerId)
                    pointerInitialPos.remove(upPointerId)
                    return touchpadView?.onTouchEvent(event) == true
                }
            }
            
            MotionEvent.ACTION_CANCEL -> {
                // 取消所有触摸
                for ((id, elem) in pointerToElementMap) {
                    if (elem != null) {
                        elem.handleTouchUp(id)
                    }
                }
                pointerToElementMap.clear()
                pointerInitialPos.clear()
                touchpadView?.setPointerButtonLeftEnabled(true)
                return true
            }
        }
        
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
 * 
 * 修复内容:
 * 1. 添加 setPointerButtonLeftEnabled 方法，允许外部控制左键功能
 * 2. 改进多点触摸处理，与虚拟按键协调工作
 */
@SuppressLint("ViewConstructor")
class TouchpadView(context: Context) : View(context) {
    
    // 控制触摸板左键功能是否启用
    // 当虚拟按键使用鼠标左键绑定时，此值应为 false
    var isPointerButtonLeftEnabled = true
        private set

    private var swapMouseButtons = false
    private var simTouchScreen = false

    // 用于追踪多个手指
    private val fingers = mutableMapOf<Int, FingerData>()
    private var lastX = 0f
    private var lastY = 0f

    var inputEventHandler: InputEventHandler? = null

    companion object {
        const val CURSOR_ACCELERATION = 2f
        const val CURSOR_ACCELERATION_THRESHOLD = 4f
        const val MAX_FINGERS = 10  // 最大支持的手指数量
        const val MAX_TAP_TRAVEL_DISTANCE = 10f
        const val MAX_TAP_MILLISECONDS = 200L
    }

    /**
     * 设置触摸板左键功能是否启用
     * 当虚拟按键使用鼠标左键绑定时，调用此方法禁用触摸板的左键功能
     * 避免左键事件被触摸板和虚拟按键同时处理
     */
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

    /**
     * 内部类，用于追踪手指数据
     */
    inner class FingerData {
        var x: Float = 0f
        var y: Float = 0f
        val startX: Float
        val startY: Float
        val touchTime: Long
        
        constructor(x: Float, y: Float) {
            this.x = x
            this.y = y
            this.startX = x
            this.startY = y
            this.touchTime = System.currentTimeMillis()
        }
        
        fun update(newX: Float, newY: Float) {
            this.x = newX
            this.y = newY
        }
        
        fun travelDistance(): Float {
            return sqrt((x - startX) * (x - startX) + (y - startY) * (y - startY))
        }
        
        fun isTap(): Boolean {
            return (System.currentTimeMillis() - touchTime) < MAX_TAP_MILLISECONDS && travelDistance() < MAX_TAP_TRAVEL_DISTANCE
        }
        
        fun deltaX(): Float {
            return x - startX
        }
        
        fun deltaY(): Float {
            return y - startY
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val actionIndex = event.actionIndex
        val pointerId = event.getPointerId(actionIndex)
        val actionMasked = event.actionMasked
        val pointerCount = event.pointerCount
        
        when (actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerId < MAX_FINGERS) {
                    fingers[pointerId] = FingerData(event.getX(actionIndex), event.getY(actionIndex))
                    lastX = event.getX(actionIndex)
                    lastY = event.getY(actionIndex)
                    
                    // 如果只有一个手指，模拟左键按下（如果启用）
                    if (pointerCount == 1 && isPointerButtonLeftEnabled) {
                        val leftButton = Binding.MOUSE_LEFT_BUTTON.getPointerButton()
                        if (leftButton != null) {
                            inputEventHandler?.onPointerButton(leftButton, true)
                        }
                    }
                }
            }
            
            MotionEvent.ACTION_MOVE -> {
                // 更新所有手指的位置
                for (i in 0 until pointerCount) {
                    val pid = event.getPointerId(i)
                    fingers[pid]?.update(event.getX(i), event.getY(i))
                }
                
                // 处理移动
                if (fingers.isNotEmpty()) {
                    val firstFinger = fingers.values.firstOrNull() ?: return true
                    val dx = firstFinger.x - lastX
                    val dy = firstFinger.y - lastY

                    // 计算加速后的移动距离
                    val absDx = abs(dx)
                    val absDy = abs(dy)
                    val moveX: Int
                    val moveY: Int
                    
                    if (absDx > CURSOR_ACCELERATION_THRESHOLD || absDy > CURSOR_ACCELERATION_THRESHOLD) {
                        // 高速移动时应用加速
                        moveX = (dx * CURSOR_ACCELERATION).toInt()
                        moveY = (dy * CURSOR_ACCELERATION).toInt()
                    } else {
                        // 低速移动
                        moveX = dx.toInt()
                        moveY = dy.toInt()
                    }
                    
                    if (moveX != 0 || moveY != 0) {
                        inputEventHandler?.onPointerMove(moveX, moveY)
                    }
                    
                    lastX = firstFinger.x
                    lastY = firstFinger.y
                }
            }
            
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                // 找到正在抬起的手指
                if (fingers.containsKey(pointerId)) {
                    val finger = fingers[pointerId]
                    if (finger != null && pointerCount == 1 && isPointerButtonLeftEnabled) {
                        // 如果是最后一个手指抬起，且触摸板左键功能启用，模拟左键释放
                        // 检测是否是点击（轻触后抬起）
                        if (finger.isTap() && finger.travelDistance() < MAX_TAP_TRAVEL_DISTANCE * 2) {
                            // 这是点击，不做任何事（点击已在按下时处理）
                        }
                        val leftButton = Binding.MOUSE_LEFT_BUTTON.getPointerButton()
                        if (leftButton != null) {
                            inputEventHandler?.onPointerButton(leftButton, false)
                        }
                    }
                    fingers.remove(pointerId)
                }
            }
            
            MotionEvent.ACTION_CANCEL -> {
                // 取消所有手指
                fingers.clear()
            }
        }
        
        return true
    }
}
package org.github.ewt45.winemulator.inputcontrols

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.termux.x11.controller.math.Mathf
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.HashMap
import java.util.Timer
import java.util.TimerTask

/**
 * InputControlsView - Adapted for Linbox compatibility
 *
 * This class implements virtual input controls for the Linbox X11 app.
 * It uses the InputEventHandler interface to send input events to the X11 session.
 *
 * 修复内容:
 * 1. 添加虚拟按键拖动触摸板支持 (通过 isMouseMoveMode 属性)
 * 2. 改进触摸板和虚拟按键的交互逻辑 (多触控支持)
 * 3. 添加 WASD 等键盘按键的持续输入支持 (KEY_REPEAT)
 */
class InputControlsView(context: Context?) : View(context) {
    companion object {
        const val DEFAULT_OVERLAY_OPACITY = 0.4f
        const val MAX_TAP_TRAVEL_DISTANCE: Byte = 10
        const val MAX_TAP_MILLISECONDS: Short = 200
        const val CURSOR_ACCELERATION = 1.25f
        const val CURSOR_ACCELERATION_THRESHOLD: Byte = 6

        // 持续按键重复间隔
        const val KEY_REPEAT_DELAY = 250L  // 首次重复延迟
        const val KEY_REPEAT_INTERVAL = 50L  // 重复间隔
    }

    // Public properties for external access
    var inputEventHandler: InputEventHandler? = null
    var showTouchscreenControlsVal: Boolean = true
    var overlayOpacityVal: Float = DEFAULT_OVERLAY_OPACITY

    // 触摸板视图引用，用于虚拟按键拖动触摸板功能
    var touchpadView: TouchpadViewCompat? = null

    internal val snappingSizeValue: Int
        get() = snappingSize

    private var editMode = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val colorFilter = PorterDuffColorFilter(0xffffffff.toInt(), PorterDuff.Mode.SRC_IN)
    private val cursor = Point()
    private var readyToDraw = false
    private var moveCursor = false
    private var moveElement = false
    private var snappingSize = 0
    private var offsetX = 0f
    private var offsetY = 0f
    private var selectedElement: ControlElement? = null
    private var profile: ControlsProfile? = null
    private val icons = arrayOfNulls<Bitmap>(17)
    private var mouseMoveTimer: Timer? = null
    private val mouseMoveOffset = PointF()
    private val counterMap = HashMap<String, Int>()

    // Track which pointer button is currently enabled for left-click
    private var pointerButtonLeftEnabled = true

    // 持续按键定时器
    private val keyRepeatTimers = HashMap<Binding, Timer>()
    private val keyRepeatHandlers = HashMap<Binding, Runnable>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun counterMapIncrease(iconId: String) {
        var v = counterMap[iconId]
        if (v == null) {
            v = 0
        }
        v++
        counterMap[iconId] = v
    }

    fun counterMapDecrease(iconId: String) {
        var v = counterMap[iconId]
        if (v != null) {
            v--
            counterMap[iconId] = v
        }
    }

    fun counterMapZero(iconId: String): Boolean {
        val v = counterMap[iconId]
        if (v == null) {
            return true
        }
        return v <= 0
    }

    init {
        setClickable(true)
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(0x00000000)
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    fun setEditMode(editMode: Boolean) {
        this.editMode = editMode
    }

    fun isEditMode(): Boolean = editMode

    fun getSnappingSize(): Int {
        return snappingSize
    }

    fun setShowTouchscreenControlsValue(showVal: Boolean) {
        this.showTouchscreenControlsVal = showVal
    }

    override fun onDraw(canvas: Canvas) {
        val width = width
        val height = height

        if (width == 0 || height == 0) {
            readyToDraw = false
            return
        }
        snappingSize = maxOf(width, height) / 100

        readyToDraw = true

        if (editMode) {
            drawGrid(canvas)
            drawCursor(canvas)
        }
        if (profile != null) {
            if (!profile!!.isElementsLoaded()) {
                profile!!.loadElements(this)
            }
            if (showTouchscreenControls) {
                for (element in profile!!.getElements()) {
                    element.draw(canvas)
                }
            }
        }

        super.onDraw(canvas)
    }

    private fun drawGrid(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.strokeWidth = snappingSize * 0.0625f
        paint.color = 0xff000000.toInt()
        canvas.drawColor(Color.BLACK)

        paint.isAntiAlias = false
        paint.color = 0xff303030.toInt()

        val width = maxWidth
        val height = maxHeight

        var i = 0
        while (i < width) {
            canvas.drawLine(i.toFloat(), 0f, i.toFloat(), height.toFloat(), paint)
            canvas.drawLine(0f, i.toFloat(), width.toFloat(), i.toFloat(), paint)
            i += snappingSize
        }

        val cx = Mathf.roundTo(width * 0.5f, snappingSize.toFloat())
        val cy = Mathf.roundTo(height * 0.5f, snappingSize.toFloat())
        paint.color = 0xff424242.toInt()

        i = 0
        while (i < width) {
            canvas.drawLine(cx, i.toFloat(), cx, (i + snappingSize).toFloat(), paint)
            canvas.drawLine(i.toFloat(), cy, (i + snappingSize).toFloat(), cy, paint)
            i += snappingSize * 2
        }

        paint.isAntiAlias = true
    }

    private fun drawCursor(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.strokeWidth = snappingSize * 0.0625f
        paint.color = 0xffc62828.toInt()

        paint.isAntiAlias = false
        canvas.drawLine(0f, cursor.y.toFloat(), maxWidth.toFloat(), cursor.y.toFloat(), paint)
        canvas.drawLine(cursor.x.toFloat(), 0f, cursor.x.toFloat(), maxHeight.toFloat(), paint)

        paint.isAntiAlias = true
    }

    fun addElement(): Boolean {
        if (editMode && profile != null) {
            val element = ControlElement(this)
            element.x = cursor.x
            element.y = cursor.y
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

    fun getSelectedElement(): ControlElement? {
        return selectedElement
    }

    private fun deselectAllElements() {
        selectedElement = null
        if (profile != null) {
            for (element in profile!!.getElements()) {
                element.isSelected = false
            }
        }
    }

    private fun selectElement(element: ControlElement?) {
        deselectAllElements()
        if (element != null) {
            selectedElement = element
            selectedElement!!.isSelected = true
        }
        invalidate()
    }

    fun getProfile(): ControlsProfile? {
        return profile
    }

    fun setProfile(profile: ControlsProfile?) {
        if (profile != null) {
            this.profile = profile
            deselectAllElements()
            invalidate()  // 立即刷新视图
        } else {
            this.profile = null
            invalidate()  // 立即刷新视图
        }
    }

    var showTouchscreenControls: Boolean
        get() = showTouchscreenControlsVal
        set(value) {
            showTouchscreenControlsVal = value
            invalidate()  // 立即刷新视图
        }

    var overlayOpacity: Float
        get() = overlayOpacityVal
        set(value) {
            overlayOpacityVal = value
            invalidate()  // 立即刷新视图
        }

    fun getPrimaryColor(): Int {
        return Color.argb((overlayOpacity * 255).toInt(), 255, 255, 255)
    }

    fun getSecondaryColor(): Int {
        return Color.argb((overlayOpacity * 255).toInt(), 2, 119, 189)
    }

    fun getLightColorFilter(): ColorFilter {
        return colorFilter
    }

    fun getDarkColorFilter(): ColorFilter {
        return PorterDuffColorFilter(0xff000000.toInt(), PorterDuff.Mode.SRC_IN)
    }

    private fun intersectElement(x: Float, y: Float): ControlElement? {
        if (profile != null) {
            for (element in profile!!.getElements()) {
                if (element.containsPoint(x, y)) return element
            }
        }
        return null
    }

    fun getPaint(): Paint {
        return paint
    }

    fun getPath(): Path {
        return path
    }

    fun getColorFilter(): ColorFilter {
        return colorFilter
    }

    val maxWidth: Int
        get() = Mathf.roundTo(width.toFloat(), snappingSize.toFloat()).toInt()

    val maxHeight: Int
        get() = Mathf.roundTo(height.toFloat(), snappingSize.toFloat()).toInt()

    private fun createMouseMoveTimer() {
        if (profile != null && mouseMoveTimer == null) {
            val cursorSpeed = profile!!.cursorSpeed
            mouseMoveTimer = Timer()
            mouseMoveTimer!!.schedule(object : TimerTask() {
                override fun run() {
                    val handler = inputEventHandler
                    if (handler != null && (mouseMoveOffset.x != 0f || mouseMoveOffset.y != 0f)) {
                        handler.onPointerMove(
                            (mouseMoveOffset.x * 10 * cursorSpeed).toInt(),
                            (mouseMoveOffset.y * 10 * cursorSpeed).toInt()
                        )
                    }
                }
            }, 0, 1000 / 60)
        }
    }

    private fun processJoystickInput(controller: ExternalController) {
        var controllerBinding: ExternalControllerBinding?
        val axes = intArrayOf(
            MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
            MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y
        )
        val values = floatArrayOf(
            controller.state.thumbLX, controller.state.thumbLY,
            controller.state.thumbRX, controller.state.thumbRY,
            controller.state.getDPadX().toFloat(), controller.state.getDPadY().toFloat()
        )

        for (i in axes.indices) {
            if (kotlin.math.abs(values[i]) > ControlElement.STICK_DEAD_ZONE) {
                controllerBinding = controller.getControllerBinding(
                    ExternalControllerBinding.getKeyCodeForAxis(axes[i], Mathf.sign(values[i]).toInt())
                )
                if (controllerBinding != null) {
                    handleInputEvent(controllerBinding.binding!!, true, values[i])
                }
            } else {
                controllerBinding = controller.getControllerBinding(
                    ExternalControllerBinding.getKeyCodeForAxis(axes[i], 1)
                )
                if (controllerBinding != null) {
                    handleInputEvent(controllerBinding.binding!!, false, values[i])
                }
                controllerBinding = controller.getControllerBinding(
                    ExternalControllerBinding.getKeyCodeForAxis(axes[i], -1)
                )
                if (controllerBinding != null) {
                    handleInputEvent(controllerBinding.binding!!, false, values[i])
                }
            }
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!editMode && profile != null) {
            val controller = profile!!.getController(event.deviceId)
            if (controller != null && controller.updateStateFromMotionEvent(event)) {
                var controllerBinding: ExternalControllerBinding?
                controllerBinding = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_L2)
                if (controllerBinding != null) {
                    handleInputEvent(controllerBinding.binding!!, controller.state.isPressed(ExternalController.IDX_BUTTON_L2.toInt()))
                }

                controllerBinding = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_R2)
                if (controllerBinding != null) {
                    handleInputEvent(controllerBinding.binding!!, controller.state.isPressed(ExternalController.IDX_BUTTON_R2.toInt()))
                }

                processJoystickInput(controller)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        // Hover events are handled by the input event handler
        // This is a stub for compatibility
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editMode && readyToDraw) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val x = event.x
                    val y = event.y

                    val element = intersectElement(x, y)
                    moveCursor = true
                    moveElement = false
                    if (element != null) {
                        offsetX = x - element.x
                        offsetY = y - element.y
                        moveCursor = false
                    }
                    selectElement(element)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (selectedElement != null) {
                        val dx = kotlin.math.abs(event.x - event.x)
                        val dy = kotlin.math.abs(event.y - event.y)

                        // 检测是否超过阈值开始移动元素
                        if (dx >= MAX_TAP_TRAVEL_DISTANCE || dy >= MAX_TAP_TRAVEL_DISTANCE) {
                            moveElement = true
                        }

                        if (moveElement) {
                            selectedElement!!.x = Mathf.roundTo(event.x - offsetX, snappingSize.toFloat()).toInt()
                            selectedElement!!.y = Mathf.roundTo(event.y - offsetY, snappingSize.toFloat()).toInt()
                            invalidate()
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (selectedElement != null && profile != null && moveElement) profile!!.save()
                    if (moveCursor) {
                        cursor.set(
                            Mathf.roundTo(event.x, snappingSize.toFloat()).toInt(),
                            Mathf.roundTo(event.y, snappingSize.toFloat()).toInt()
                        )
                    }
                    invalidate()
                }
            }
            return true
        }
        // 在非编辑模式下，处理虚拟按键的触摸事件
        return handleTouchEvent(event)
    }

    /**
     * 处理虚拟按键触摸事件 (修复问题1: 支持多触控)
     * 允许虚拟按键和触摸板同时独立响应不同的触控手势
     */
    fun handleTouchEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            // Mouse events are handled directly
            return true
        }
        if (!editMode && profile != null) {
            val actionIndex = event.actionIndex
            val actionMasked = event.actionMasked
            var handled = false

            when (actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val x = event.getX(actionIndex)
                    val y = event.getY(actionIndex)
                    val pointerId = event.getPointerId(actionIndex)
                    pointerButtonLeftEnabled = true

                    for (element in profile!!.getElements()) {
                        if (element.handleTouchDown(pointerId, x, y)) {
                            handled = true
                            val binding = element.getBindingAt(0)
                            if (binding == Binding.MOUSE_LEFT_BUTTON) {
                                pointerButtonLeftEnabled = false
                            }
                        }
                    }

                    // 如果没有虚拟按键处理，将事件传递给触摸板
                    if (!handled && touchpadView != null) {
                        touchpadView?.onTouchEvent(event)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    // 遍历所有指针并处理移动事件
                    for (i in 0 until event.pointerCount) {
                        val x = event.getX(i)
                        val y = event.getY(i)
                        val pointerId = event.getPointerId(i)

                        var elementHandled = false
                        for (element in profile!!.getElements()) {
                            if (element.handleTouchMove(pointerId, x, y)) {
                                elementHandled = true
                            }
                        }

                        // 如果元素没有处理且支持鼠标移动模式，将事件传递给触摸板
                        if (!elementHandled && touchpadView != null) {
                            touchpadView?.onTouchEvent(event)
                        }
                    }
                    handled = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    val ptrId = event.getPointerId(actionIndex)
                    val x = event.getX(actionIndex)
                    val y = event.getY(actionIndex)

                    var elementHandled = false
                    for (element in profile!!.getElements()) {
                        if (element.handleTouchUp(ptrId, x, y)) {
                            elementHandled = true
                        }
                    }

                    // 如果没有虚拟按键处理，将事件传递给触摸板
                    if (!elementHandled && touchpadView != null) {
                        touchpadView?.onTouchEvent(event)
                    }
                    handled = true
                }
            }
            return handled
        }
        return false
    }

    /**
     * 处理虚拟按键拖动触摸板的功能
     * 当虚拟按键设置了鼠标移动模式时，按住按键可以拖动触摸板
     */
    fun handleButtonMouseMove(pointerId: Int, x: Float, y: Float, action: Int) {
        touchpadView?.mouseMove(x, y, action)
    }

    fun handleInputEvent(binding: Binding, isActionDown: Boolean) {
        handleInputEvent(binding, isActionDown, 0f)
    }

    fun handleInputEvent(binding: Binding, isActionDown: Boolean, offset: Float) {
        val handler = inputEventHandler ?: return

        if (binding.isGamepad) {
            // Gamepad events are handled by the gamepad state management
            val state = profile?.getGamepadState()
            val buttonIdx = binding.ordinal - Binding.GAMEPAD_BUTTON_A.ordinal
            if (buttonIdx <= 11) {
                state?.setPressed(buttonIdx, isActionDown)
            } else if (binding === Binding.GAMEPAD_LEFT_THUMB_UP || binding === Binding.GAMEPAD_LEFT_THUMB_DOWN) {
                if (state != null) state.thumbLY = if (isActionDown) offset else 0f
            } else if (binding === Binding.GAMEPAD_LEFT_THUMB_LEFT || binding === Binding.GAMEPAD_LEFT_THUMB_RIGHT) {
                if (state != null) state.thumbLX = if (isActionDown) offset else 0f
            } else if (binding === Binding.GAMEPAD_RIGHT_THUMB_UP || binding === Binding.GAMEPAD_RIGHT_THUMB_DOWN) {
                if (state != null) state.thumbRY = if (isActionDown) offset else 0f
            } else if (binding === Binding.GAMEPAD_RIGHT_THUMB_LEFT || binding === Binding.GAMEPAD_RIGHT_THUMB_RIGHT) {
                if (state != null) state.thumbRX = if (isActionDown) offset else 0f
            } else if (binding === Binding.GAMEPAD_DPAD_UP || binding === Binding.GAMEPAD_DPAD_RIGHT ||
                binding === Binding.GAMEPAD_DPAD_DOWN || binding === Binding.GAMEPAD_DPAD_LEFT) {
                if (state != null) {
                    state.dpad[binding.ordinal - Binding.GAMEPAD_DPAD_UP.ordinal] = isActionDown
                }
            }
        } else {
            if (binding === Binding.MOUSE_MOVE_LEFT || binding === Binding.MOUSE_MOVE_RIGHT) {
                mouseMoveOffset.x = if (isActionDown) {
                    if (offset != 0f) offset else {
                        if (binding === Binding.MOUSE_MOVE_LEFT) -1f else 1f
                    }
                } else {
                    0f
                }
                if (isActionDown) createMouseMoveTimer()
            } else if (binding === Binding.MOUSE_MOVE_DOWN || binding === Binding.MOUSE_MOVE_UP) {
                mouseMoveOffset.y = if (isActionDown) {
                    if (offset != 0f) offset else {
                        if (binding === Binding.MOUSE_MOVE_UP) -1f else 1f
                    }
                } else {
                    0f
                }
                if (isActionDown) createMouseMoveTimer()
            } else {
                val pointerButton = binding.getPointerButton()
                if (isActionDown) {
                    if (pointerButton != null) {
                        handler.onPointerButton(pointerButton.ordinal, true)
                    } else {
                        handler.onKeyEvent(binding.toEvdev(), true)
                    }

                    // 修复问题3: 对于键盘按键，添加持续输入支持
                    // 这确保了 WASD 等移动键可以持续发送按键事件
                    if (pointerButton == null) {
                        startKeyRepeat(binding)
                    }
                } else {
                    if (pointerButton != null) {
                        handler.onPointerButton(pointerButton.ordinal, false)
                    } else {
                        handler.onKeyEvent(binding.toEvdev(), false)
                    }

                    // 停止持续输入
                    stopKeyRepeat(binding)
                }
            }
        }
    }

    /**
     * 开始按键重复
     * 用于支持 WASD 等键盘按键的持续输入
     */
    private fun startKeyRepeat(binding: Binding) {
        // 如果已经有定时器在运行，不重复启动
        if (keyRepeatTimers.containsKey(binding)) {
            return
        }

        // 创建重复处理器
        val repeatHandler = object : Runnable {
            override fun run() {
                val handler = inputEventHandler ?: return
                handler.onKeyEvent(binding.toEvdev(), true)
            }
        }
        keyRepeatHandlers[binding] = repeatHandler

        // 创建定时器
        val timer = Timer(true)
        keyRepeatTimers[binding] = timer

        // 延迟后开始重复
        mainHandler.postDelayed({
            // 先发送一次按键按下（如果还没有发送的话）
            val handler = inputEventHandler ?: return@postDelayed
            handler.onKeyEvent(binding.toEvdev(), true)

            // 然后开始定时重复
            timer.schedule(object : TimerTask() {
                override fun run() {
                    mainHandler.post(repeatHandler)
                }
            }, KEY_REPEAT_INTERVAL, KEY_REPEAT_INTERVAL)
        }, KEY_REPEAT_DELAY)
    }

    /**
     * 停止按键重复
     */
    private fun stopKeyRepeat(binding: Binding) {
        val timer = keyRepeatTimers.remove(binding)
        timer?.cancel()
        keyRepeatHandlers.remove(binding)
    }

    /**
     * 停止所有按键重复
     */
    fun stopAllKeyRepeats() {
        val timers = HashMap(keyRepeatTimers)
        keyRepeatTimers.clear()
        keyRepeatHandlers.clear()
        timers.values.forEach { it.cancel() }
    }

    fun sendText(text: String?) {
        // Text sending is not directly supported in the InputEventHandler interface
        // This would need to be implemented through key events or clipboard
    }

    fun getIcon(id: Byte): Bitmap? {
        if (icons[id.toInt()] == null) {
            val context = context
            try {
                context.assets.open("inputcontrols/icons/$id.png").use { inputStream ->
                    icons[id.toInt()] = BitmapFactory.decodeStream(inputStream)
                }
            } catch (e: IOException) {
                // Icon not found
            }
        }
        return icons[id.toInt()]
    }

    fun getCustomIcon(iconId: String?): Bitmap? {
        val buttonIconFile = File(context.filesDir.path + "/home/.buttonIcons", iconId + ".png")
        if (!buttonIconFile.exists()) {
            return null
        }
        return BitmapFactory.decodeFile(buttonIconFile.path)
    }

    fun clipBitmap(bitmap: Bitmap?, isCircular: Boolean): Bitmap? {
        if (bitmap == null) return null
        val clippedBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(clippedBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        paint.shader = shader
        if (isCircular) {
            val centerX = bitmap.width / 2
            val centerY = bitmap.height / 2
            val radius = minOf(centerX, centerY)
            canvas.drawCircle(centerX.toFloat(), centerY.toFloat(), radius.toFloat(), paint)
        } else {
            val rect = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
            canvas.drawRect(rect, paint)
        }
        return clippedBitmap
    }

    fun createShapeBitmap(width: Float, height: Float, color: Int, isCircular: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color

        if (isCircular) {
            val radius = (minOf(width, height) / 2).toInt()
            canvas.drawCircle(width / 2, height / 2, radius.toFloat(), paint)
        } else {
            val rect = RectF(0f, 0f, width, height)
            canvas.drawRect(rect, paint)
        }
        return bitmap
    }

    /**
     * Inject pointer movement - sends mouse motion event
     */
    fun injectPointerMove(dx: Int, dy: Int) {
        inputEventHandler?.onPointerMove(dx, dy)
    }

    /**
     * Compute delta point for trackpad - calculates movement delta from touch position
     */
    fun computeDeltaPoint(oldX: Float, oldY: Float, newX: Float, newY: Float): FloatArray {
        return floatArrayOf(newX - oldX, newY - oldY)
    }
}

/**
 * 触摸板视图兼容类
 * 用于支持虚拟按键拖动触摸板功能
 */
class TouchpadViewCompat(private val inputControlsView: InputControlsView) {
    private var pointerButtonLeftEnabled = true
    private var moveCursorToTouchpoint = false

    fun onTouchEvent(event: MotionEvent): Boolean {
        // 处理触摸板事件
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                pointerButtonLeftEnabled = true
            }
            MotionEvent.ACTION_MOVE -> {
                // 处理移动事件
                handleMouseMove(event)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                // 处理抬起事件
            }
        }
        return true
    }

    private fun handleMouseMove(event: MotionEvent) {
        if (event.pointerCount > 0) {
            val x = event.x
            val y = event.y
            val dx = event.x - event.x
            val dy = event.y - event.y

            // 计算移动增量
            if (kotlin.math.abs(dx) > InputControlsView.CURSOR_ACCELERATION_THRESHOLD) {
                val cursorDx = kotlin.math.round(dx * InputControlsView.CURSOR_ACCELERATION).toInt()
                inputControlsView.injectPointerMove(cursorDx, 0)
            }
            if (kotlin.math.abs(dy) > InputControlsView.CURSOR_ACCELERATION_THRESHOLD) {
                val cursorDy = kotlin.math.round(dy * InputControlsView.CURSOR_ACCELERATION).toInt()
                inputControlsView.injectPointerMove(0, cursorDy)
            }
        }
    }

    fun mouseMove(x: Float, y: Float, action: Int) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                // 开始移动
            }
            MotionEvent.ACTION_MOVE -> {
                // 处理移动
                if (moveCursorToTouchpoint) {
                    // 移动光标到触摸点
                    val handler = inputControlsView.inputEventHandler
                    handler?.onPointerMove(x.toInt(), y.toInt())
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 结束移动
            }
        }
    }

    fun setPointerButtonLeftEnabled(enabled: Boolean) {
        pointerButtonLeftEnabled = enabled
    }

    fun isPointerButtonLeftEnabled(): Boolean = pointerButtonLeftEnabled

    fun setMoveCursorToTouchpoint(move: Boolean) {
        moveCursorToTouchpoint = move
    }

    fun isMoveCursorToTouchpoint(): Boolean = moveCursorToTouchpoint
}
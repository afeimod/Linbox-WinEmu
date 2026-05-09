package org.github.ewt45.winemulator.inputcontrols

import android.content.Context
import android.graphics.*
import android.os.Handler
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * InputControlsView - Adapted for Linbox compatibility
 *
 * This class implements virtual input controls for the Linbox X11 app.
 * It uses the InputEventHandler interface to send input events to the X11 session.
 *
 * Fixed issues:
 * 1. Multi-touch support: Virtual buttons and touchpad can be used simultaneously
 * 2. Key repeat support for WASD and other keyboard keys
 * 3. Performance optimization: Fixed stuttering/lag when using WASD for movement
 *    - Replaced multiple Timer threads with single ScheduledExecutorService
 *    - Batched invalidate() calls to reduce overdraw
 *    - Optimized event posting to main thread
 */
class InputControlsView(context: Context?) : View(context) {
    companion object {
        const val DEFAULT_OVERLAY_OPACITY = 0.4f
        const val MAX_TAP_TRAVEL_DISTANCE: Byte = 10
        const val MAX_TAP_MILLISECONDS: Short = 200
        const val CURSOR_ACCELERATION = 1.25f
        const val CURSOR_ACCELERATION_THRESHOLD: Byte = 6
        // Key repeat intervals - 优化以获得更快的响应
        // 初始延迟应该足够短以保持即时响应，重复间隔应该足够短以保证流畅移动
        const val KEY_REPEAT_DELAY = 80L      // 初始延迟：80ms - 快速响应
        const val KEY_REPEAT_INTERVAL = 20L   // 重复间隔：20ms ≈ 50fps，极流畅移动
        
        /**
         * Convert evdev keycode to Android keycode
         * This mapping follows the Linux evdev to Android keycode conversion
         */
        fun evdevToAndroidKeycode(evdev: Int): Int {
            return when (evdev) {
                // Escape and special keys
                1 -> KeyEvent.KEYCODE_ESCAPE
                
                // Function keys F1-F12
                59 -> KeyEvent.KEYCODE_F1
                60 -> KeyEvent.KEYCODE_F2
                61 -> KeyEvent.KEYCODE_F3
                62 -> KeyEvent.KEYCODE_F4
                63 -> KeyEvent.KEYCODE_F5
                64 -> KeyEvent.KEYCODE_F6
                65 -> KeyEvent.KEYCODE_F7
                66 -> KeyEvent.KEYCODE_F8
                67 -> KeyEvent.KEYCODE_F9
                68 -> KeyEvent.KEYCODE_F10
                87 -> KeyEvent.KEYCODE_F11
                88 -> KeyEvent.KEYCODE_F12
                
                // Numbers row (with shift)
                2 -> KeyEvent.KEYCODE_1
                3 -> KeyEvent.KEYCODE_2
                4 -> KeyEvent.KEYCODE_3
                5 -> KeyEvent.KEYCODE_4
                6 -> KeyEvent.KEYCODE_5
                7 -> KeyEvent.KEYCODE_6
                8 -> KeyEvent.KEYCODE_7
                9 -> KeyEvent.KEYCODE_8
                10 -> KeyEvent.KEYCODE_9
                11 -> KeyEvent.KEYCODE_0
                
                // Letters Q-Z
                16 -> KeyEvent.KEYCODE_Q
                17 -> KeyEvent.KEYCODE_W
                18 -> KeyEvent.KEYCODE_E
                19 -> KeyEvent.KEYCODE_R
                20 -> KeyEvent.KEYCODE_T
                21 -> KeyEvent.KEYCODE_Y
                22 -> KeyEvent.KEYCODE_U
                23 -> KeyEvent.KEYCODE_I
                24 -> KeyEvent.KEYCODE_O
                25 -> KeyEvent.KEYCODE_P
                26 -> KeyEvent.KEYCODE_LEFT_BRACKET
                27 -> KeyEvent.KEYCODE_RIGHT_BRACKET
                28 -> KeyEvent.KEYCODE_ENTER
                29 -> KeyEvent.KEYCODE_CTRL_LEFT  // Left Control
                
                // Letters A-L
                30 -> KeyEvent.KEYCODE_A
                31 -> KeyEvent.KEYCODE_S
                32 -> KeyEvent.KEYCODE_D
                33 -> KeyEvent.KEYCODE_F
                34 -> KeyEvent.KEYCODE_G
                35 -> KeyEvent.KEYCODE_H
                36 -> KeyEvent.KEYCODE_J
                37 -> KeyEvent.KEYCODE_K
                38 -> KeyEvent.KEYCODE_L
                39 -> KeyEvent.KEYCODE_SEMICOLON
                40 -> KeyEvent.KEYCODE_APOSTROPHE
                41 -> KeyEvent.KEYCODE_GRAVE  // Backtick/Tilde
                
                // Modifiers
                42 -> KeyEvent.KEYCODE_SHIFT_LEFT
                43 -> KeyEvent.KEYCODE_BACKSLASH
                44 -> KeyEvent.KEYCODE_Z
                45 -> KeyEvent.KEYCODE_X
                46 -> KeyEvent.KEYCODE_C
                47 -> KeyEvent.KEYCODE_V
                48 -> KeyEvent.KEYCODE_B
                49 -> KeyEvent.KEYCODE_N
                50 -> KeyEvent.KEYCODE_M
                51 -> KeyEvent.KEYCODE_COMMA
                52 -> KeyEvent.KEYCODE_PERIOD
                53 -> KeyEvent.KEYCODE_SLASH
                54 -> KeyEvent.KEYCODE_SHIFT_RIGHT
                55 -> KeyEvent.KEYCODE_NUMPAD_MULTIPLY
                56 -> KeyEvent.KEYCODE_ALT_LEFT
                57 -> KeyEvent.KEYCODE_SPACE
                58 -> KeyEvent.KEYCODE_CAPS_LOCK
                
                // Navigation cluster
                72 -> KeyEvent.KEYCODE_DPAD_UP  // Up arrow
                73 -> KeyEvent.KEYCODE_PAGE_UP
                74 -> KeyEvent.KEYCODE_PAGE_DOWN
                75 -> KeyEvent.KEYCODE_NUMPAD_4  // Keypad 4 (also used as Left on some keyboards)
                76 -> KeyEvent.KEYCODE_NUMPAD_5  // Keypad 5
                77 -> KeyEvent.KEYCODE_NUMPAD_6  // Keypad 6 (also used as Right on some keyboards)
                78 -> KeyEvent.KEYCODE_NUMPAD_1  // Keypad 1 (also used as End on some keyboards)
                79 -> KeyEvent.KEYCODE_NUMPAD_7  // Keypad 7 (also used as Home on some keyboards)
                80 -> KeyEvent.KEYCODE_DPAD_DOWN  // Down arrow
                81 -> KeyEvent.KEYCODE_NUMPAD_0  // Keypad 0 (also used as Insert on some keyboards)
                82 -> KeyEvent.KEYCODE_NUMPAD_SUBTRACT
                83 -> KeyEvent.KEYCODE_NUMPAD_DOT  // Keypad Delete/Decimal
                84 -> KeyEvent.KEYCODE_NUMPAD_DIVIDE
                85 -> KeyEvent.KEYCODE_NUMPAD_MULTIPLY
                86 -> KeyEvent.KEYCODE_NUMPAD_ADD
                
                // Additional navigation keys
                102 -> KeyEvent.KEYCODE_MOVE_HOME
                104 -> KeyEvent.KEYCODE_PAGE_UP
                105 -> KeyEvent.KEYCODE_DPAD_LEFT
                106 -> KeyEvent.KEYCODE_DPAD_RIGHT
                107 -> KeyEvent.KEYCODE_MOVE_END
                109 -> KeyEvent.KEYCODE_PAGE_DOWN
                110 -> KeyEvent.KEYCODE_INSERT
                111 -> KeyEvent.KEYCODE_FORWARD_DEL
                
                // Keypad enter (different from regular enter)
                96 -> KeyEvent.KEYCODE_NUMPAD_ENTER
                
                // Right side modifiers
                97 -> KeyEvent.KEYCODE_CTRL_RIGHT
                98 -> KeyEvent.KEYCODE_NUMPAD_DIVIDE  // Actually this is Print Screen on some keyboards
                99 -> KeyEvent.KEYCODE_SYSRQ  // Print Screen/SysRq
                
                // Additional keys
                100 -> KeyEvent.KEYCODE_ALT_RIGHT  // Alt Gr / Right Alt
                
                else -> {
                    // For unknown keycodes, try to use the keycode directly if it's in a valid Android range
                    if (evdev in 1..255) evdev else 0
                }
            }
        }
    }

    // Public properties for external access
    var inputEventHandler: InputEventHandler? = null
        set(value) {
            field = value
            // 当 inputEventHandler 被设置时，同时更新 touchpadView
            touchpadView?.inputEventHandler = value
        }
    var showTouchscreenControlsVal: Boolean = true
    var overlayOpacityVal: Float = DEFAULT_OVERLAY_OPACITY
    // Touchpad view reference
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
    
    // Key repeat optimization: Use single ScheduledExecutorService instead of multiple Timer threads
    // This significantly reduces thread overhead and prevents stuttering when multiple keys are pressed
    private val keyRepeatExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(1) { r ->
        Thread(r, "KeyRepeatExecutor").also { it.isDaemon = true }
    }
    private val keyRepeatFutures = ConcurrentHashMap<Binding, ScheduledFuture<*>>()
    
    // Batched invalidation to reduce overdraw
    private var pendingInvalidation = false
    private val invalidationRunnable = Runnable { 
        pendingInvalidation = false
        this.invalidate()
    }

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
        // 初始化触摸板视图，用于光标控制
        touchpadView = TouchpadViewCompat(this)
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
            invalidate()
        } else {
            this.profile = null
            invalidate()
        }
    }

    var showTouchscreenControls: Boolean
        get() = showTouchscreenControlsVal
        set(value) {
            showTouchscreenControlsVal = value
            invalidate()
        }

    var overlayOpacity: Float
        get() = overlayOpacityVal
        set(value) {
            overlayOpacityVal = value
            invalidate()
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

        // 非编辑模式下，只有当 showTouchscreenControls 为 true 时才处理触摸事件
        // 返回 handleTouchEvent 的结果，让事件正确传递
        if (!editMode && profile != null && showTouchscreenControls) {
            return handleTouchEvent(event)
        }

        // 当 showTouchscreenControls 为 false 或 profile 为 null 时，不处理触摸事件，让事件传递给下层
        return false
    }

    /**
     * Handle touch events for virtual controls
     * Allows virtual buttons and touchpad to be used simultaneously
     *
     * Fixed:
     * 1. Each pointer is tracked independently
     * 2. Each pointer determines if it should be handled by an element or touchpad
     * 3. Correct event delegation based on pointer ID
     * 4. Properly handle mouse/touchpad input without early returns
     */
    fun handleTouchEvent(event: MotionEvent): Boolean {
        // Handle mouse input - pass through to touchpad for cursor movement
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            // Process mouse events for cursor movement
            handleMouseInput(event)
            return true
        }

        if (editMode) {
            // Edit mode handling
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

        // Non-edit mode: handle virtual controls and touchpad
        if (profile != null) {
            val actionIndex = event.actionIndex
            val actionMasked = event.actionMasked
            val actionPointerId = event.getPointerId(actionIndex)

            when (actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val x = event.getX(actionIndex)
                    val y = event.getY(actionIndex)
                    var handled = false
                    touchpadView?.setPointerButtonLeftEnabled(true)

                    for (element in profile!!.getElements()) {
                        if (element.handleTouchDown(actionPointerId, x, y)) {
                            handled = true
                            val binding = element.getBindingAt(0)
                            if (binding == Binding.MOUSE_LEFT_BUTTON) {
                                touchpadView?.setPointerButtonLeftEnabled(false)
                            }
                        }
                    }

                    // 只有当没有虚拟按钮处理时才传递给 touchpad
                    if (!handled) {
                        touchpadView?.onTouchEvent(event)
                    }
                    // 如果虚拟按钮处理了，返回true；否则返回false让LorieView处理
                    return handled
                }

                MotionEvent.ACTION_MOVE -> {
                    // Track each pointer to see if it's handled by an element
                    val unhandledPointers = mutableListOf<MotionEvent.PointerCoords>()
                    val unhandledPointerProperties = mutableListOf<MotionEvent.PointerProperties>()

                    for (i in 0 until event.pointerCount) {
                        val pointerId = event.getPointerId(i)
                        val x = event.getX(i)
                        val y = event.getY(i)
                        var pointerHandled = false

                        for (element in profile!!.getElements()) {
                            if (element.handleTouchMove(pointerId, x, y)) {
                                pointerHandled = true
                                break
                            }
                        }

                        // If this pointer is not handled by any element, record it
                        if (!pointerHandled) {
                            val properties = MotionEvent.PointerProperties()
                            event.getPointerProperties(i, properties)
                            unhandledPointerProperties.add(properties)

                            val coords = MotionEvent.PointerCoords()
                            event.getPointerCoords(i, coords)
                            unhandledPointers.add(coords)
                        }
                    }

                    // Pass unhandled pointers to touchpad for cursor movement
                    if (unhandledPointers.isNotEmpty()) {
                        val newEvent = MotionEvent.obtain(
                            event.downTime, event.eventTime, event.action,
                            unhandledPointers.size, unhandledPointerProperties.toTypedArray(),
                            unhandledPointers.toTypedArray(), event.metaState, event.buttonState,
                            event.xPrecision, event.yPrecision, event.deviceId, event.edgeFlags,
                            event.source, event.flags
                        )
                        touchpadView?.onTouchEvent(newEvent)
                        newEvent.recycle()
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    val x = event.getX(actionIndex)
                    val y = event.getY(actionIndex)
                    var handled = false

                    for (element in profile!!.getElements()) {
                        if (element.handleTouchUp(actionPointerId, x, y)) {
                            handled = true
                        }
                    }

                    // 只有当没有虚拟按钮处理时才传递给 touchpad
                    if (!handled) {
                        touchpadView?.onTouchEvent(event)
                    }
                    // 如果虚拟按钮处理了，返回true；否则返回false让LorieView处理
                    return handled
                }
            }
        } else {
            // No profile configured, pass events to touchpad for cursor movement
            touchpadView?.onTouchEvent(event)
            // 返回false让LorieView处理原生X11鼠标控制
            return false
        }
        return false
    }

    /**
     * Handle mouse input events from external mouse
     * Provides smooth cursor movement with acceleration
     */
    private fun handleMouseInput(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE -> {
                // For mouse move events
                if (event.pointerCount > 0) {
                    // Use axis values for relative mice
                    val relDx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
                    val relDy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)

                    if (relDx != 0f || relDy != 0f) {
                        // Relative mouse - directly use the values
                        sendPointerMovement(relDx, relDy)
                    } else {
                        // For non-relative input, use position directly with acceleration
                        val dx = event.x
                        val dy = event.y
                        val cursorSpeed = profile?.cursorSpeed ?: 1.0f
                        val accelDx = dx * CURSOR_ACCELERATION * cursorSpeed
                        val accelDy = dy * CURSOR_ACCELERATION * cursorSpeed
                        val intDx = kotlin.math.round(accelDx).toInt()
                        val intDy = kotlin.math.round(accelDy).toInt()

                        if (intDx != 0 || intDy != 0) {
                            inputEventHandler?.onPointerMove(intDx, intDy)
                        }
                    }
                }
            }

            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_DOWN -> {
                val button = event.buttonState
                if (button and MotionEvent.BUTTON_PRIMARY != 0) {
                    inputEventHandler?.onPointerButton(0, true)
                }
                if (button and MotionEvent.BUTTON_SECONDARY != 0) {
                    inputEventHandler?.onPointerButton(2, true)
                }
                if (button and MotionEvent.BUTTON_TERTIARY != 0) {
                    inputEventHandler?.onPointerButton(1, true)
                }
            }

            MotionEvent.ACTION_BUTTON_RELEASE, MotionEvent.ACTION_UP -> {
                val button = event.actionButton
                when (button) {
                    MotionEvent.BUTTON_PRIMARY -> inputEventHandler?.onPointerButton(0, false)
                    MotionEvent.BUTTON_SECONDARY -> inputEventHandler?.onPointerButton(2, false)
                    MotionEvent.BUTTON_TERTIARY -> inputEventHandler?.onPointerButton(1, false)
                }
            }

            MotionEvent.ACTION_SCROLL -> {
                val scrollY = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val scrollX = event.getAxisValue(MotionEvent.AXIS_HSCROLL)

                if (kotlin.math.abs(scrollY) > 0.1f) {
                    val button = if (scrollY > 0) 4 else 5
                    inputEventHandler?.onPointerButton(button, true)
                    inputEventHandler?.onPointerButton(button, false)
                }

                if (kotlin.math.abs(scrollX) > 0.1f) {
                    val button = if (scrollX > 0) 6 else 7
                    inputEventHandler?.onPointerButton(button, true)
                    inputEventHandler?.onPointerButton(button, false)
                }
            }
        }
    }

    /**
     * Send pointer movement with acceleration
     */
    private fun sendPointerMovement(dx: Float, dy: Float) {
        val cursorSpeed = profile?.cursorSpeed ?: 1.0f
        val accelDx = dx * CURSOR_ACCELERATION * cursorSpeed
        val accelDy = dy * CURSOR_ACCELERATION * cursorSpeed

        val intDx = kotlin.math.round(accelDx).toInt()
        val intDy = kotlin.math.round(accelDy).toInt()

        if (intDx != 0 || intDy != 0) {
            inputEventHandler?.onPointerMove(intDx, intDy)
        }
    }

    /**
     * Handle virtual button dragging touchpad functionality
     */
    fun handleButtonMouseMove(pointerId: Int, x: Float, y: Float, action: Int) {
        touchpadView?.mouseMove(x, y, action)
    }

    fun handleInputEvent(binding: Binding, isActionDown: Boolean) {
        handleInputEvent(binding, isActionDown, 0f)
    }

    fun handleInputEvent(binding: Binding, isActionDown: Boolean, offset: Float) {
        val handler = inputEventHandler ?: return

        if (binding.isGamepad()) {
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
                        // X11 按钮号从 1 开始，ordinal 是 0-based，所以要 +1
                        handler.onPointerButton(pointerButton.ordinal + 1, true)
                    } else {
                        // 创建 KeyEvent 并发送给 handler - 使用包含eventTime的构造函数
                        val evdevKeycode = binding.toEvdev()
                        val androidKeycode = evdevToAndroidKeycode(evdevKeycode)
                        if (androidKeycode != 0) {
                            val eventTime = System.currentTimeMillis()
                            val keyEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, androidKeycode, 0, 0, 0, 0)
                            handler.onKeyEvent(keyEvent)
                        }
                    }
                    // Key repeat for keyboard keys - 只对键盘按键启用
                    if (pointerButton == null && binding != Binding.NONE) {
                        startKeyRepeat(binding)
                    }
                } else {
                    if (pointerButton != null) {
                        // X11 按钮号从 1 开始，ordinal 是 0-based，所以要 +1
                        handler.onPointerButton(pointerButton.ordinal + 1, false)
                    } else {
                        // 创建 KeyEvent 并发送给 handler - 使用包含eventTime的构造函数
                        val evdevKeycode = binding.toEvdev()
                        val androidKeycode = evdevToAndroidKeycode(evdevKeycode)
                        if (androidKeycode != 0) {
                            val eventTime = System.currentTimeMillis()
                            val keyEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, androidKeycode, 0, 0, 0, 0)
                            handler.onKeyEvent(keyEvent)
                        }
                    }
                    // 停止 key repeat
                    stopKeyRepeat(binding)
                }
            }
        }
    }

    /**
     * Start key repeat for continuous input
     * Optimized to use single ScheduledExecutorService instead of multiple Timer threads
     * This prevents stuttering when multiple keys (like WASD) are pressed simultaneously
     */
    private fun startKeyRepeat(binding: Binding) {
        // 检查是否已经有重复任务在运行
        if (keyRepeatFutures.containsKey(binding)) {
            return
        }

        val handler = inputEventHandler ?: return
        
        // Capture keycode and handler reference to avoid issues with lambda capture
        val keycode = binding.toEvdev()
        
        // 延迟后开始重复发送按键事件（使用 initialDelay）
        // 关键修复：使用 KeyEvent.ACTION_MULTIPLE 来正确模拟持续按键
        // 这解决了之前的"一走一停"问题，因为接收端会将 ACTION_MULTIPLE 识别为持续的按住状态
        val future = keyRepeatExecutor.scheduleAtFixedRate({
            try {
                // 创建 ACTION_MULTIPLE 事件来表示持续的按键按住
                val androidKeycode = evdevToAndroidKeycode(keycode)
                if (androidKeycode != 0) {
                    val eventTime = System.currentTimeMillis()
                    val keyEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_MULTIPLE, androidKeycode, 1)
                    handler.onKeyEvent(keyEvent)
                }
            } catch (e: Exception) {
                // 忽略异常，防止定时任务中断
            }
        }, KEY_REPEAT_DELAY, KEY_REPEAT_INTERVAL, TimeUnit.MILLISECONDS)
        
        keyRepeatFutures[binding] = future
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Cleanup: Cancel all pending key repeat tasks and shutdown executor
        stopAllKeyRepeats()
        keyRepeatExecutor.shutdown()
        try {
            if (!keyRepeatExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                keyRepeatExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            keyRepeatExecutor.shutdownNow()
        }
        
        // Cancel mouse move timer
        mouseMoveTimer?.cancel()
        mouseMoveTimer = null
    }

    /**
     * Stop key repeat
     */
    private fun stopKeyRepeat(binding: Binding) {
        keyRepeatFutures.remove(binding)?.cancel(false)
    }

    /**
     * Stop all key repeats
     */
    fun stopAllKeyRepeats() {
        keyRepeatFutures.values.forEach { it.cancel(false) }
        keyRepeatFutures.clear()
    }
    
    /**
     * Batch invalidation calls to reduce overdraw
     * Instead of immediately invalidating on each input event, 
     * we post a delayed invalidation that coalesces multiple calls
     */
    fun postBatchedInvalidation() {
        if (!pendingInvalidation) {
            pendingInvalidation = true
            post(invalidationRunnable)
        }
    }

    fun sendText(text: String?) {
        // Text sending is not directly supported
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

    fun injectPointerMove(dx: Int, dy: Int) {
        inputEventHandler?.onPointerMove(dx, dy)
    }

    fun computeDeltaPoint(oldX: Float, oldY: Float, newX: Float, newY: Float): FloatArray {
        return floatArrayOf(newX - oldX, newY - oldY)
    }
}

/**
 * Touchpad view compatibility class
 * Handles touchpad/mouse input events and converts them to cursor movements
 */
class TouchpadViewCompat(private val inputControlsView: InputControlsView) {
    // Input event handler for sending cursor movements
    var inputEventHandler: InputEventHandler? = null
    
    private var pointerButtonLeftEnabled = true
    private var moveCursorToTouchpoint = false
    private var lastX = 0f
    private var lastY = 0f
    private var initialX = 0f
    private var initialY = 0f
    private var isDragging = false
    private var activePointerId = -1

    // Track button state
    private var leftButtonPressed = false
    private var rightButtonPressed = false

    fun onTouchEvent(event: MotionEvent): Boolean {
        val actionMasked = event.actionMasked

        when (actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val actionIndex = event.actionIndex
                val pointerId = event.getPointerId(actionIndex)
                if (activePointerId == -1) {
                    activePointerId = pointerId
                    lastX = event.getX(actionIndex)
                    lastY = event.getY(actionIndex)
                    initialX = lastX
                    initialY = lastY
                    isDragging = false
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // Handle all pointer movements
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    if (pid == activePointerId) {
                        val px = event.getX(i)
                        val py = event.getY(i)

                        // Calculate delta from last position
                        val dx = px - lastX
                        val dy = py - lastY
                        lastX = px
                        lastY = py

                        // Track if we've moved enough to be considered dragging
                        if (!isDragging) {
                            val totalDx = kotlin.math.abs(px - initialX)
                            val totalDy = kotlin.math.abs(py - initialY)
                            if (totalDx > 3 || totalDy > 3) {
                                isDragging = true
                            }
                        }

                        // Send cursor movement
                        if (kotlin.math.abs(dx) > 0.5f || kotlin.math.abs(dy) > 0.5f) {
                            sendCursorMove(dx, dy)
                        }
                        break
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = -1
                isDragging = false
                return true
            }
        }
        return true
    }

    /**
     * Send cursor movement with acceleration
     */
    private fun sendCursorMove(dx: Float, dy: Float) {
        // Apply acceleration based on movement speed
        var cursorDx = dx
        var cursorDy = dy

        // Apply acceleration factor
        val acceleration = InputControlsView.CURSOR_ACCELERATION
        cursorDx *= acceleration
        cursorDy *= acceleration

        // Round to integer for precise movement
        val intDx = kotlin.math.round(cursorDx).toInt()
        val intDy = kotlin.math.round(cursorDy).toInt()

        // Only send if there's actual movement
        if (intDx != 0 || intDy != 0) {
            inputEventHandler?.onPointerMove(intDx, intDy)
        }
    }

    private fun handleMouseMove(event: MotionEvent) {
        if (event.pointerCount > 0) {
            val x = event.x
            val y = event.y
            val dx = x - lastX
            val dy = y - lastY
            lastX = x
            lastY = y

            // Use improved sendCursorMove for better precision
            sendCursorMove(dx, dy)
        }
    }

    fun mouseMove(x: Float, y: Float, action: Int) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = x
                lastY = y
                initialX = x
                initialY = y
                isDragging = false

                // Send left button down
                if (leftButtonPressed) {
                    inputEventHandler?.onPointerButton(0, false)
                }
                inputEventHandler?.onPointerButton(0, true)
                leftButtonPressed = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (moveCursorToTouchpoint) {
                    val dx = x - lastX
                    val dy = y - lastY
                    lastX = x
                    lastY = y
                    sendCursorMove(dx, dy)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Send left button up
                if (leftButtonPressed) {
                    inputEventHandler?.onPointerButton(0, false)
                    leftButtonPressed = false
                }
                isDragging = false
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

    fun isLeftButtonPressed(): Boolean = leftButtonPressed

    fun isDragging(): Boolean = isDragging
}
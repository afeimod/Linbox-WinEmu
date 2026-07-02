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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import android.util.SparseArray

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
    }

    // Public properties for external access
    var inputEventHandler: InputEventHandler? = null
        set(value) {
            field = value
            // 当 inputEventHandler 被设置时，同时更新 touchpadView
            touchpadView?.inputEventHandler = value
        }
    /**
     * 虚拟按键开关的 backing field。
     * showTouchscreenControls / showTouchscreenControlsVal 两个名字都委托给这个字段,
     * 外部代码用哪个名字写都能触发同样的 set 副作用 (释放按键 + 调可点击状态)。
     */
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

    /**
     * 跟踪每一个 pointer 在本 View 上的状态。
     * key = pointerId, value = TouchPointerState (lastX/lastY/isPointerDown/isCapturedByElement)。
     *
     * 背景: Android 多点触控派发下, 一旦 InputControlsView 在某个 pointer 的 ACTION_DOWN 里
     * return true, 后续该手势内所有 pointer 的所有事件 (包括其他手的) 都会派给本 View,
     * 不会回流到 LorieView. 所以本 View 必须自己处理"非按键区域的手指",
     * 把它转成相对鼠标事件 + (可选) 左键事件, 否则用户的另一只手就动不了.
     *
     * 每个手指用 pointerId 独立跟踪, 互不干扰:
     * - 命中按键的手指: 由 ControlElement 跟踪 (currentPointerId 机制), 本字段不参与
     * - 落在空白处的手指: 走 touchpad 路径, 维护 lastX/lastY 计算 delta
     * - 双手多指时: 每个 pointerId 独立的 lastX/lastY, 不会因为多手指同时存在而错位
     */
    private val touchPointers = SparseArray<TouchPointerState>()

    /**
     * 单个 pointer 的跟踪状态
     *
     * - lastX/lastY: 上一帧位置, 用于算 delta (发 onPointerMove)
     * - startX/startY: DOWN 时的起点, 用于判断总位移是否超过阈值
     * - downTimeMs: DOWN 的时间戳 (SystemClock.uptimeMillis), 用于 UP 时判断"长按 vs 轻点"
     */
    private class TouchPointerState(
        var lastX: Float,
        var lastY: Float,
        val startX: Float,
        val startY: Float,
        val downTimeMs: Long
    )

    /**
     * 触摸板 "轻点 vs 拖动" 阈值 (像素).
     * 手指总位移 < 这个值且按压时长 < tapMaxDurationMs 才视为 "轻点" = 单击,
     * DOWN 期间不发 down 也不发 MOVE, UP 时补发 down + 立即 up.
     *
     * 总位移 >= 阈值: 视为"拖动", 期间只发 onPointerMove (不按下任何键),
     * UP 时啥都不补发 (因为没 down).
     */
    private val touchpadTapThresholdPx: Float = 8f

    /**
     * "轻点" 最大按压时长 (毫秒). DOWN 起到 UP 起, 总时长 < 这个值才算轻点.
     * 超时视为 "长按", UP 不补发单击事件 (避免长按误触发点击).
     */
    private val touchpadTapMaxDurationMs: Long = 500L

    /**
     * 兼容字段, 旧代码用过的 touchpadLeftButtonDown 状态标记.
     * 纯移动 + 轻点模式下, DOWN 期间不发 down, UP 时直接补 down/up, 不需要维护
     * "是否在按 left" 的状态. 这个字段保留只是为代码一致性.
     */
    @Suppress("unused")
    private var touchpadLeftButtonDown = false

    /**
     * touchpad 双指右键状态.
     * 当第二根手指落在空白处时发 right button down, 全部空白处手指抬起时发 right button up.
     * 类比笔记本触摸板 "两指点按 = 右键".
     */
    private var rightButtonDown = false
    
    // Key repeat support for continuous press
    private val pressedKeys = mutableSetOf<Binding>()
    private val keyRepeatScheduler = Executors.newSingleThreadScheduledExecutor()
    private var keyRepeatTask: ScheduledFuture<*>? = null
    
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

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 修复: 兜底释放所有按下的虚拟按键, 防止"卡键" / "角色还在走"这种 bug
        releaseAllPressedKeys()
        // 清理 touchpad 状态: 释放所有未抬起的 left button down, 避免"切换窗口后鼠标卡在按下"
        releaseAllTouchpadButtons()
        touchPointers.clear()
        // 清理按键重复定时器资源，防止内存泄漏
        stopKeyRepeat()
        keyRepeatScheduler.shutdown()
        // 清理鼠标移动定时器
        mouseMoveTimer?.cancel()
        mouseMoveTimer = null
    }

    fun setEditMode(editMode: Boolean) {
        this.editMode = editMode
    }

    fun isEditMode(): Boolean = editMode

    fun getSnappingSize(): Int {
        return snappingSize
    }

    fun setShowTouchscreenControlsValue(showVal: Boolean) {
        // 走 property setter, 自动同步字段 + 释放按键 + 调整可点击状态
        this.showTouchscreenControls = showVal
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
        // 切配置时清理触摸状态, 避免新配置继承了旧配置的 touchpad 状态
        releaseAllTouchpadButtons()
        touchPointers.clear()
        if (profile != null) {
            this.profile = profile
            deselectAllElements()
            invalidate()
        } else {
            this.profile = null
            invalidate()
        }
    }

    /**
     * 虚拟按键总开关。
     * - true:  正常显示虚拟按键并消费对应位置的触摸
     * - false: 不绘制虚拟按键，且不拦截任何触摸事件 (onTouchEvent/handleTouchEvent/onHoverEvent
     *          /onGenericMotionEvent 都 return false, 让事件透传给下层 LorieView)
     *
     * 关闭时还会兜底释放所有按下的虚拟按键, 防止"看不见了但角色还在走"这种幽灵按 bug。
     */
    var showTouchscreenControls: Boolean
        get() = showTouchscreenControlsVal
        set(value) {
            if (showTouchscreenControlsVal == value) return
            showTouchscreenControlsVal = value
            if (!value) {
                // 关闭时释放所有按下的虚拟按键, 避免幽灵按
                releaseAllPressedKeys()
                // 关闭时同步释放 touchpad 未抬起的 left button, 避免幽灵按
                releaseAllTouchpadButtons()
                touchPointers.clear()
                // 关闭时让 view 不再拦截事件, 透传给下层 LorieView
                isClickable = false
                isFocusable = false
                isFocusableInTouchMode = false
            } else {
                isClickable = true
                isFocusable = true
                isFocusableInTouchMode = true
            }
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
        // 关闭虚拟按键时不消费 motion event, 透传给下层 LorieView
        if (!showTouchscreenControls) return false
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
        // 关闭虚拟按键时不消费悬停事件, 透传给下层 LorieView
        if (!showTouchscreenControls) return false
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 关闭虚拟按键时不消费任何触摸事件, 返回 false 让事件透传给下层 (LorieView)
        if (!showTouchscreenControls) return false
        if (editMode && readyToDraw) {
            when (event.actionMasked) {
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
                        selectedElement!!.setX(Mathf.roundTo(event.x - offsetX, snappingSize.toFloat()).toInt())
                        selectedElement!!.setY(Mathf.roundTo(event.y - offsetY, snappingSize.toFloat()).toInt())
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (selectedElement != null && profile != null) profile!!.save()
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
        // 关闭虚拟按键时不消费任何触摸事件, 透传给下层 (LorieView)
        // 兜底拦截: 防止有人绕过 onTouchEvent 直接调 handleTouchEvent
        if (!showTouchscreenControls) return false
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
                    if (element != null) {
                        offsetX = x - element.x
                        offsetY = y - element.y
                        moveCursor = false
                    }
                    selectElement(element)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (selectedElement != null) {
                        selectedElement!!.setX(Mathf.roundTo(event.x - offsetX, snappingSize.toFloat()).toInt())
                        selectedElement!!.setY(Mathf.roundTo(event.y - offsetY, snappingSize.toFloat()).toInt())
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (selectedElement != null && profile != null) profile!!.save()
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

        // Non-edit mode: 按 pointer 独立路由, 支持一只手按虚拟按键 + 另一只手在空白处拖鼠标
        //
        // Android 多点触控派发语义提醒: 一旦本 View 在某个 pointer 的 ACTION_DOWN 里
        // return true, 该手势内所有 pointer 的所有事件都会派给本 View, 不会回流给
        // LorieView. 所以本 View 必须为"落在空白处的手指"自己提供鼠标控制逻辑
        // (相对鼠标移动 + 左键按下/释放), 不能依赖 LorieView 帮我们处理.
        //
        // 逐 pointer 路由:
        // - 命中虚拟按键的手指: 走 ControlElement.handleTouchDown/Move/Up, 按键响应
        // - 落在空白处的手指: 走 touchpad 路径, 维护 lastX/lastY 计算 delta, 转 onPointerMove
        //
        // 关键: 每个 pointer 独立维护 lastX/lastY (用 SparseArray 按 pointerId 跟踪),
        // 避免"多个手指共用一个 lastX 导致 delta 错位". 左手按按键右手拖鼠标时,
        // 右手的滑动只会计算右手自己的 delta, 不会跟左手位置混在一起.
        if (profile != null) {
            val actionMasked = event.actionMasked
            val actionIndex = event.actionIndex
            val actionPointerId = event.getPointerId(actionIndex)

            when (actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val x = event.getX(actionIndex)
                    val y = event.getY(actionIndex)

                    // 先检查当前 pointer 是否命中虚拟按键
                    var elementHandled = false
                    for (element in profile!!.getElements()) {
                        if (element.handleTouchDown(actionPointerId, x, y)) {
                            elementHandled = true
                            break
                        }
                    }

                    if (!elementHandled) {
                        // 空白处的手指: 纯移动 + 轻点点击 + 双指右键
                        //
                        // 行为:
                        // - 单指 DOWN: 只记录 startX/Y + downTime, 不发 button down.
                        //              避免 MOVE 变成 drag.
                        // - 双指 DOWN (第二根手指落下时):
                        //              取消单指的"轻点单击"待定状态, 发 right button down.
                        //              类比笔记本触摸板"两指点按 = 右键".
                        //              后续任何 finger UP 不会发 right down (只发一次).
                        // - MOVE: 阈值内 (总位移 < 8px) 不发 onPointerMove, 避免单击时轻微
                        //         抖动导致光标意外飘. 超阈值后正常发 onPointerMove (移动光标).
                        //         因为 DOWN 期间永远没 down, MOVE 不会变成 drag.
                        // - UP:   最后一根 touchpad 手指抬起时:
                        //         - 如果在双指右键状态: 发 right button up, 清状态.
                        //         - 如果之前是单击 (单指) + 总位移 < 阈值 + 时长 < 500ms:
                        //           补发 down + up (单击)
                        //         - 其他: 不补发 (拖动 / 长按)
                        touchPointers.put(
                            actionPointerId,
                            TouchPointerState(
                                lastX = x,
                                lastY = y,
                                startX = x,
                                startY = y,
                                downTimeMs = android.os.SystemClock.uptimeMillis()
                            )
                        )
                        // 双指右键: 第二根手指落在空白处时, 立即发 right down.
                        // 之前"单指可能是单击"的待定状态 (通过 touchPointers.size() 判断)
                        // 会被 UP 路径自动检测到 touchPointers.size()==0 时不再走单击补发逻辑.
                        if (!rightButtonDown && touchPointers.size() >= 2) {
                            inputEventHandler?.onPointerButton(3, true) // X11 button 3 = right
                            rightButtonDown = true
                        }
                    }

                    // 返回 true: 本手势必须由本 View 接管, 否则后续事件收不到
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    // 对每个 pointer 独立判断:
                    // - 命中按键的: 交给 ControlElement 处理 (会决定是否还在按键上, 离开则释放)
                    // - 落在空白处的: 纯移动模式, 阈值内 (总位移 < 8px) 不发 onPointerMove,
                    //                 避免单击时光标意外飘. 超阈值后正常发 onPointerMove.
                    //                 因为 DOWN 期间永远没 down, MOVE 不会变成 drag.
                    for (i in 0 until event.pointerCount) {
                        val pointerId = event.getPointerId(i)
                        val x = event.getX(i)
                        val y = event.getY(i)

                        var hitElement = false
                        for (element in profile!!.getElements()) {
                            if (element.handleTouchMove(pointerId, x, y)) {
                                hitElement = true
                                break
                            }
                        }

                        if (!hitElement) {
                            val state = touchPointers.get(pointerId)
                            if (state != null) {
                                // 总位移 < 阈值 -> 视为"未离开点击状态", 不发 MOVE (避免抖)
                                val totalDx = x - state.startX
                                val totalDy = y - state.startY
                                val totalDist = kotlin.math.abs(totalDx) + kotlin.math.abs(totalDy)
                                if (totalDist < touchpadTapThresholdPx) {
                                    // 仅更新 lastX/Y, 方便超阈值后能算正确 delta
                                    state.lastX = x
                                    state.lastY = y
                                    continue
                                }
                                val dx = x - state.lastX
                                val dy = y - state.lastY
                                state.lastX = x
                                state.lastY = y
                                if (dx != 0f || dy != 0f) {
                                    inputEventHandler?.onPointerMove(dx.toInt(), dy.toInt())
                                }
                            }
                        }
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    val x = event.getX(actionIndex)
                    val y = event.getY(actionIndex)

                    // 先尝试从按键上释放
                    for (element in profile!!.getElements()) {
                        if (element.handleTouchUp(actionPointerId, x, y)) {
                            break
                        }
                    }

                    // touchpad (空白处) 手指抬起:
                    // - 拿 state 算总位移 + 按压时长
                    // - 最后一根 touchpad 手指抬起时:
                    //   - 如果是双指右键状态: 发 right up, 清状态
                    //   - 否则: 评估"轻点" vs "拖动" vs "长按", 轻点补发单击
                    val state = touchPointers.get(actionPointerId)
                    if (state != null) {
                        touchPointers.remove(actionPointerId)
                        // 最后一根 touchpad 手指抬起 -> 处理收尾
                        if (touchPointers.size() == 0) {
                            if (rightButtonDown) {
                                // 双指右键: 之前第二根 DOWN 时发了 right down, 这里补 right up
                                inputEventHandler?.onPointerButton(3, false) // X11 button 3 = right
                                rightButtonDown = false
                            } else {
                                // 单击 vs 拖动 vs 长按 判定
                                val totalDx = x - state.startX
                                val totalDy = y - state.startY
                                val totalDist = kotlin.math.abs(totalDx) + kotlin.math.abs(totalDy)
                                val durationMs = android.os.SystemClock.uptimeMillis() - state.downTimeMs

                                if (totalDist < touchpadTapThresholdPx && durationMs < touchpadTapMaxDurationMs) {
                                    // 轻点: 补发单击 down + up
                                    inputEventHandler?.onPointerButton(1, true)
                                    inputEventHandler?.onPointerButton(1, false)
                                }
                                // 其他情况 (拖动 / 长按) 都不补发, 符合"空白处纯移动"的语义
                            }
                        }
                    }

                    // 兜底: 同一个 pointer 不会同时在按键 + touchpad, 上面互斥.
                    // 如果啥都没匹配 (View 收到陌生 pointer up), 也保持 return true
                    // 保证该手势其他已 claim 的 pointer 还能继续接收事件.
                    return true
                }
            }
        }
        // profile == null 或未匹配到 action 分支: 不拦截, 透传给下层 LorieView
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

    /**
     * Mouse button binding -> X11InputSender.sendMouseButtonEvent 期望的 Int 编号.
     * 1=LEFT 2=MIDDLE 3=RIGHT 4=SCROLL_UP 5=SCROLL_DOWN.
     * 不复用 Binding.getPointerButton() (返回 Pointer.Button?), 那个返回 AAR 里的 enum,
     * 跟 X11InputSender 内部用的 Int 编号不一定一致; 这里直接映射成 Int 避免出错.
     */
    private fun getPointerButtonInt(binding: Binding): Int? {
        return when (binding) {
            Binding.MOUSE_LEFT_BUTTON -> 1
            Binding.MOUSE_MIDDLE_BUTTON -> 2
            Binding.MOUSE_RIGHT_BUTTON -> 3
            Binding.MOUSE_SCROLL_UP -> 4
            Binding.MOUSE_SCROLL_DOWN -> 5
            else -> null
        }
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
            } else if (binding === Binding.MOUSE_LEFT_BUTTON
                || binding === Binding.MOUSE_MIDDLE_BUTTON
                || binding === Binding.MOUSE_RIGHT_BUTTON
                || binding === Binding.MOUSE_SCROLL_UP
                || binding === Binding.MOUSE_SCROLL_DOWN) {
                // 鼠标按钮 / 滚轮: 走 InputEventHandler.onPointerButton 路径
                // 不能走 onKeyEvent, 因为 toEvdev() 对这些 binding 返回 0,
                // X11InputSender.sendEvdevKeyEvent 会过滤掉. sendMouseButtonEvent 的
                // button 参数跟下面 getPointerButtonInt() 定义的 Int 编号一致:
                //   1=LEFT 2=MIDDLE 3=RIGHT 4=SCROLL_UP 5=SCROLL_DOWN.
                val button = getPointerButtonInt(binding)
                if (button != null) {
                    handler.onPointerButton(button, isActionDown)
                }
            } else {
                // 键盘按键处理
                if (isActionDown) {
                    // 按下：发送按下事件并添加到pressedKeys
                    handler.onKeyEvent(binding.toEvdev(), true)
                    if (!pressedKeys.contains(binding)) {
                        pressedKeys.add(binding)
                        // 启动按键重复定时器（如果尚未运行）
                        startKeyRepeatIfNeeded()
                    }
                } else {
                    // 释放：发送释放事件并从pressedKeys移除
                    handler.onKeyEvent(binding.toEvdev(), false)
                    pressedKeys.remove(binding)
                    if (pressedKeys.isEmpty()) {
                        stopKeyRepeat()
                    }
                }
            }
        }
    }

    /**
     * Update keyboard key state for D-PAD/STICK continuous press
     * Call this from ControlElement when a direction state changes on a D-PAD or STICK
     * @param binding The keyboard binding to update
     * @param isActive True if this direction is now active, false if inactive
     */
    fun updateKeyState(binding: Binding, isActive: Boolean) {
        val handler = inputEventHandler ?: return

        // 鼠标 binding 走 onPointerButton 路径 (跟 handleInputEvent 保持一致)
        val mouseButton = getPointerButtonInt(binding)
        if (mouseButton != null) {
            if (isActive) {
                handler.onPointerButton(mouseButton, true)
            } else {
                handler.onPointerButton(mouseButton, false)
            }
            return
        }

        // 获取当前按键的实际状态
        val isCurrentlyPressed = pressedKeys.contains(binding)

        // 状态变化时的处理
        if (isActive && !isCurrentlyPressed) {
            // 从释放变为按下
            handler.onKeyEvent(binding.toEvdev(), true)
            pressedKeys.add(binding)
            startKeyRepeatIfNeeded()
        } else if (!isActive && isCurrentlyPressed) {
            // 从按下变为释放
            handler.onKeyEvent(binding.toEvdev(), false)
            pressedKeys.remove(binding)
            if (pressedKeys.isEmpty()) {
                stopKeyRepeat()
            }
        }
        // 如果状态没有变化（持续按住或持续释放），不执行任何操作
    }

    /**
     * 发送按键按下事件并添加到重复队列（用于Toggle按钮等需要手动控制的场景）
     * 注意：这个方法会发送按下事件并将按键添加到pressedKeys，启动重复定时器
     */
    fun sendKeyDown(binding: Binding) {
        val handler = inputEventHandler ?: return
        // 鼠标 binding 走 onPointerButton 路径 (跟 handleInputEvent 保持一致)
        val mouseButton = getPointerButtonInt(binding)
        if (mouseButton != null) {
            handler.onPointerButton(mouseButton, true)
            return
        }
        // 发送按下事件
        handler.onKeyEvent(binding.toEvdev(), true)
        // 添加到pressedKeys以启用重复机制
        if (!pressedKeys.contains(binding)) {
            pressedKeys.add(binding)
            startKeyRepeatIfNeeded()
        }
    }

    /**
     * 发送按键释放事件并从重复队列移除（用于Toggle按钮等需要手动控制的场景）
     * 注意：这个方法会发送释放事件并将按键从pressedKeys移除
     */
    fun sendKeyUp(binding: Binding) {
        val handler = inputEventHandler ?: return
        // 鼠标 binding 走 onPointerButton 路径 (跟 handleInputEvent 保持一致)
        val mouseButton = getPointerButtonInt(binding)
        if (mouseButton != null) {
            handler.onPointerButton(mouseButton, false)
            return
        }
        // 发送释放事件
        handler.onKeyEvent(binding.toEvdev(), false)
        // 从pressedKeys移除
        pressedKeys.remove(binding)
        if (pressedKeys.isEmpty()) {
            stopKeyRepeat()
        }
    }

    /**
     * 检查并刷新按键状态，防止意外的状态中断
     * 这个方法会检查所有已按下的按键，确保它们的状态正确
     */
    fun refreshKeyStates() {
        val handler = inputEventHandler ?: return
        // 只在有按键被按下时刷新
        if (pressedKeys.isNotEmpty()) {
            for (binding in pressedKeys.toList()) {
                handler.onKeyEvent(binding.toEvdev(), true)
            }
        }
    }

    /**
     * Start key repeat timer if not already running
     */
    private fun startKeyRepeatIfNeeded() {
        if (keyRepeatTask == null || keyRepeatTask!!.isCancelled) {
            keyRepeatTask = keyRepeatScheduler.scheduleAtFixedRate({
                onKeyRepeat()
            }, KEY_REPEAT_DELAY, KEY_REPEAT_INTERVAL, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Stop key repeat timer
     */
    private fun stopKeyRepeat() {
        keyRepeatTask?.cancel(false)
        keyRepeatTask = null
    }

    /**
     * Send repeat events for all currently pressed keys
     * This is called periodically by the key repeat timer
     */
    private fun onKeyRepeat() {
        val handler = inputEventHandler ?: return

        // 修复: 旧实现 "pressedKeys.filter { it in pressedKeys }" 是个 no-op,
        // 真实存在的"快照"应该是 .toList() 而不是 .filter。原先的注释说
        // "防止已释放的按键继续发送重复事件", 实际靠的是 Handler.post 内部
        // 的 contains 检查, 那个是有效的; 这里顺手把 toList 改对。
        val keysToRepeat = pressedKeys.toList()

        // 发送到主线程处理
        Handler(Looper.getMainLooper()).post {
            for (binding in keysToRepeat) {
                // 再次检查按键是否仍在pressedKeys中，确保线程安全
                if (pressedKeys.contains(binding)) {
                    handler.onKeyEvent(binding.toEvdev(), true)
                }
            }
        }
    }

    /**
     * 释放所有按下的虚拟按键。
     *
     * 调用场景 (跟 abc-fix 的 forceResetMouseButtons 思路一致, 关键路径兜底):
     * - 视图销毁 / 切到后台
     * - 切 profile / 切显示开关
     * - Activity onPause 期间手指还停在屏幕上
     *
     * 修这条之前, 上面这些情况都会留下"按下的虚拟键"在 X server 那边,
     * 表现出来就是: 角色在 W 键卡住 / 鼠标左键吸住不松 / Shift 一直粘着。
     */
    fun releaseAllPressedKeys() {
        val handler = inputEventHandler ?: return
        // 1. 释放已跟踪的键盘 binding (在 pressedKeys 里的)
        if (pressedKeys.isNotEmpty()) {
            val toRelease = pressedKeys.toList()
            pressedKeys.clear()
            stopKeyRepeat()
            for (binding in toRelease) {
                handler.onKeyEvent(binding.toEvdev(), false)
            }
        }
        // 2. 释放所有鼠标 button (MOUSE_LEFT/MIDDLE/RIGHT), 这些 binding 不进 pressedKeys
        //    但切后台/切 profile 时候要丢释放事件避免 X server 端永久卡住按下状态.
        for (binding in arrayOf(Binding.MOUSE_LEFT_BUTTON, Binding.MOUSE_MIDDLE_BUTTON, Binding.MOUSE_RIGHT_BUTTON)) {
            val button = getPointerButtonInt(binding) ?: continue
            handler.onPointerButton(button, false)
        }
    }

    /**
     * 释放所有 touchpad (空白处) 状态.
     * 用于 onDetachedFromWindow / setProfile / showTouchscreenControls=false 等场景
     * 避免"切后台后右键卡在按下"或"重复补发单击".
     */
    private fun releaseAllTouchpadButtons() {
        val handler = inputEventHandler ?: return
        // 如果 right down 处于按下状态, 兑底发 right up
        if (rightButtonDown) {
            handler.onPointerButton(3, false) // X11 button 3 = right
            rightButtonDown = false
        }
        // DOWN 期间从未发过 left down, 所以不需要发 left up.
        // 但需要清空 pointer 跟踪, 避免下次启动时状态错乱 (这个由调方 clear()).
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
                    // 发送左键按下事件
                    if (pointerButtonLeftEnabled && !leftButtonPressed) {
                        inputEventHandler?.onPointerButton(0, true)
                        leftButtonPressed = true
                    }
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
                // 发送左键释放事件
                if (leftButtonPressed) {
                    inputEventHandler?.onPointerButton(0, false)
                    leftButtonPressed = false
                }
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
package org.github.ewt45.winemulator.inputcontrols

import android.os.Handler
import android.os.Looper
import org.github.ewt45.winemulator.inputcontrols.ControlElement.Range

/**
 * Handles scrolling for range button elements
 * 完全按照 termux-x11 实现
 */
class RangeScroller(
    private val inputControlsView: InputControlsView,
    private val element: ControlElement
) {
    private var scrollOffset: Float = 0f
    private var currentOffset: Float = 0f
    private var lastPosition: Float = 0f
    private var touchTime: Long = 0
    private var binding: Binding = Binding.NONE
    private var isActionDown: Boolean = false
    private var scrolling: Boolean = false
    private var timer: java.util.Timer? = null

    companion object {
        const val MAX_TAP_MILLISECONDS: Long = 200
        const val MAX_TAP_TRAVEL_DISTANCE: Float = 10f
    }

    fun getElementSize(): Float {
        val boundingBox = element.getBoundingBox()
        return maxOf(boundingBox.width().toFloat(), boundingBox.height().toFloat()) / element.getBindingCount()
    }

    fun getScrollSize(): Float {
        val range = element.range ?: Range.FROM_A_TO_Z
        return getElementSize() * range.max.toFloat()
    }

    fun getScrollOffset(): Float = scrollOffset

    fun setScrollOffset(offset: Float) {
        scrollOffset = offset
    }

    fun getCurrentBinding(): Binding = binding

    fun getRangeIndex(): IntArray {
        val range = element.range ?: Range.FROM_A_TO_Z
        val elementSize = getElementSize()
        var from = kotlin.math.floor((scrollOffset / elementSize) % range.max.toDouble()).toInt()
        if (from < 0) from = range.max.toInt() + from
        val to = from + element.getBindingCount() + 1
        return intArrayOf(from, to)
    }

    private fun getBindingByPosition(x: Float, y: Float): Binding {
        val boundingBox = element.getBoundingBox()
        val range = element.range ?: Range.FROM_A_TO_Z
        val orientation = element.orientation.toInt()

        val offset = if (orientation == 0) {
            x - boundingBox.left - currentOffset
        } else {
            y - boundingBox.top - currentOffset
        }

        val elementSize = getElementSize()
        var index = kotlin.math.floor((offset / elementSize) % range.max.toDouble()).toInt()
        if (index < 0) index = range.max.toInt() + index

        return when (range) {
            Range.FROM_A_TO_Z -> {
                if (index in 0..25) Binding.fromString("KEY_${('A'.code + index).toChar()}") else Binding.NONE
            }
            Range.DIGITS -> {
                if (index in 0..9) Binding.fromString("KEY_${(index + 1) % 10}") else Binding.NONE
            }
            Range.FUNCTION_KEYS -> {
                if (index in 0..11) Binding.fromString("KEY_F${index + 1}") else Binding.NONE
            }
            Range.NUMPAD_DIGITS -> {
                if (index in 0..9) Binding.fromString("KEY_KP_${(index + 1) % 10}") else Binding.NONE
            }
        }
    }

    private fun isTap(): Boolean {
        return System.currentTimeMillis() - touchTime < MAX_TAP_MILLISECONDS
    }

    private fun destroyTimer() {
        timer?.cancel()
        timer = null
    }

    fun handleTouchDown(element: ControlElement, x: Float, y: Float) {
        destroyTimer()

        scrolling = false
        isActionDown = true
        binding = getBindingByPosition(x, y)
        touchTime = System.currentTimeMillis()
        lastPosition = if (element.orientation.toInt() == 0) x else y
        this.element.setBinding(Binding.NONE)

        // 使用 Timer 延迟发送按键按下事件
        timer = java.util.Timer(true)
        timer?.schedule(object : java.util.TimerTask() {
            override fun run() {
                if (!scrolling) {
                    inputControlsView.post {
                        inputControlsView.handleInputEvent(binding, true)
                    }
                }
            }
        }, MAX_TAP_MILLISECONDS)
    }

    fun handleTouchMove(element: ControlElement, x: Float, y: Float) {
        if (isActionDown) {
            val position = if (element.orientation.toInt() == 0) x else y
            val deltaPosition = position - lastPosition

            if (kotlin.math.abs(deltaPosition) >= MAX_TAP_TRAVEL_DISTANCE) {
                scrolling = true
                destroyTimer()
            }

            if (scrolling) {
                currentOffset += deltaPosition

                val scrollSize = getScrollSize()
                scrollOffset = -currentOffset % scrollSize
                if (scrollOffset < 0) scrollOffset = scrollSize + scrollOffset

                lastPosition = position
                inputControlsView.invalidate()
            }
        }
    }

    fun handleTouchUp() {
        if (isActionDown) {
            destroyTimer()
            if (isTap() && !scrolling) {
                inputControlsView.handleInputEvent(binding, true)
                val finalBinding = binding
                inputControlsView.postDelayed({
                    inputControlsView.handleInputEvent(finalBinding, false)
                }, 30)
            } else {
                inputControlsView.handleInputEvent(binding, false)
            }
        }
        isActionDown = false
    }

    fun isScrolling(): Boolean = scrolling
}
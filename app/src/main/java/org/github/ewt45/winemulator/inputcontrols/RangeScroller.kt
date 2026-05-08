package org.github.ewt45.winemulator.inputcontrols

import android.graphics.Rect
import java.util.Timer
import java.util.TimerTask

/** The {@link Class} that implements scrolling behavior for {@link ControlElement} range buttons. */
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
    private var timer: Timer? = null

    fun getElementSize(): Float {
        val boundingBox = element.getBoundingBox()
        return maxOf(boundingBox.width().toFloat(), boundingBox.height().toFloat()) / element.getBindingCount()
    }

    fun getScrollSize(): Float {
        return getElementSize() * element.getRange().max
    }

    fun getScrollOffset(): Float {
        return scrollOffset
    }

    fun getRangeIndex(): IntArray {
        val range = element.getRange()
        var from = kotlin.math.floor((scrollOffset / getElementSize()) % range.max).toInt()
        if (from < 0) from = (range.max + from).toInt()
        val to = from + element.getBindingCount() + 1
        return intArrayOf(from, to)
    }

    private fun getBindingByPosition(x: Float, y: Float): Binding {
        val boundingBox = element.getBoundingBox()
        val range = element.getRange()
        val offset = if (element.getOrientation() == 0.toByte()) {
            x - boundingBox.left - currentOffset
        } else {
            y - boundingBox.top - currentOffset
        }
        var index = kotlin.math.floor((offset / getElementSize()) % range.max).toInt()
        if (index < 0) index = (range.max + index).toInt()

        return try {
            when (range) {
                ControlElement.Range.FROM_A_TO_Z -> Binding.valueOf("KEY_${('A'.code + index).toChar()}")
                ControlElement.Range.FROM_0_TO_9 -> Binding.valueOf("KEY_${(index + 1) % 10}")
                ControlElement.Range.FROM_F1_TO_F12 -> Binding.valueOf("KEY_F${index + 1}")
                ControlElement.Range.FROM_NP0_TO_NP9 -> Binding.valueOf("KEY_KP_${(index + 1) % 10}")
            }
        } catch (e: IllegalArgumentException) {
            Binding.NONE
        }
    }

    private fun isTap(): Boolean {
        return (System.currentTimeMillis() - touchTime) < InputControlsView.MAX_TAP_MILLISECONDS
    }

    private fun destroyTimer() {
        timer?.cancel()
        timer = null
    }

    fun handleTouchDown(x: Float, y: Float) {
        destroyTimer()

        scrolling = false
        isActionDown = true
        binding = getBindingByPosition(x, y)
        touchTime = System.currentTimeMillis()
        lastPosition = if (element.getOrientation() == 0.toByte()) x else y
        element.setBinding(Binding.NONE)

        timer = Timer(true)
        timer?.schedule(object : TimerTask() {
            override fun run() {
                if (!scrolling) {
                    inputControlsView.post {
                        inputControlsView.handleInputEvent(binding, true)
                    }
                }
            }
        }, InputControlsView.MAX_TAP_MILLISECONDS.toLong())
    }

    fun handleTouchMove(x: Float, y: Float) {
        if (isActionDown) {
            val position = if (element.getOrientation() == 0.toByte()) x else y
            val deltaPosition = position - lastPosition

            if (kotlin.math.abs(deltaPosition) >= InputControlsView.MAX_TAP_TRAVEL_DISTANCE) {
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

    fun isScrolling(): Boolean {
        return scrolling
    }
}

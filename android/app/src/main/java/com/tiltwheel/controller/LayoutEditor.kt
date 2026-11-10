package com.tiltwheel.controller

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import kotlin.math.hypot

/** Uses the driving view's drawing and geometry, with gestures that never drive the pad. */
class LayoutEditor(
    context: Context,
    private val onChange: (List<Protocol.Box>, String?) -> Unit,
) : ButtonLayer(context, { _, _, _ -> }) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var selected: String? = null
    private data class Drag(
        val id: Int, val original: Protocol.Box, val x: Float, val y: Float, val resize: Boolean,
    )
    private data class Pinch(
        val firstId: Int, val secondId: Int, val original: Protocol.Box, val span: Double,
    )
    private var drag: Drag? = null
    private var pinch: Pinch? = null

    fun show(boxes: List<Protocol.Box>) {
        drag = null
        pinch = null
        selected = null
        this.boxes = boxes
        invalidate()
        onChange(boxes, null)
    }

    fun removeSelected() {
        val remaining = boxes.filterNot { it.target == selected }
        show(remaining)
    }

    private fun handle(rect: RectF): RectF {
        val size = minOf(24 * density, rect.width(), rect.height())
        return RectF(rect.right - size, rect.bottom - size, rect.right, rect.bottom)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val box = boxes.firstOrNull { it.target == selected } ?: return
        val rect = rectOf(box)
        paint.color = Theme.TEXT
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2 * density
        canvas.drawRoundRect(rect, 8 * density, 8 * density, paint)
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(handle(rect), 4 * density, 4 * density, paint)
    }

    private fun span(event: MotionEvent, first: Int, second: Int): Double = hypot(
        (event.getX(first) - event.getX(second)).toDouble(),
        (event.getY(first) - event.getY(second)).toDouble(),
    )

    private fun beginPinch(event: MotionEvent) {
        val active = drag ?: return
        if (event.pointerCount != 2) return
        // Start from the current size and position, including any preceding drag.
        drag = null
        val box = boxes.firstOrNull { it.target == active.original.target } ?: return
        val first = event.findPointerIndex(active.id)
        val second = event.actionIndex
        if (first < 0) return
        pinch = Pinch(active.id, event.getPointerId(second), box, span(event, first, second).coerceAtLeast(1.0))
    }

    private fun resizePinch(event: MotionEvent, active: Pinch) {
        val first = event.findPointerIndex(active.firstId)
        val second = event.findPointerIndex(active.secondId)
        if (first < 0 || second < 0 || width == 0 || height == 0) return
        val box = active.original
        val minW = (44.0 * density / width).coerceAtLeast(0.01)
        val minH = (44.0 * density / height).coerceAtLeast(0.01)
        // A saved button may already be smaller than the editor's current minimum.
        val minScale = maxOf(minW / box.w, minH / box.h).coerceAtMost(1.0)
        val maxScale = 1.0 / maxOf(box.w, box.h)
        val scale = (span(event, first, second) / active.span).coerceIn(minScale, maxScale)
        val w = (box.w * scale).coerceAtMost(1.0)
        val h = (box.h * scale).coerceAtMost(1.0)
        updateBox(box.copy(
            x = (box.x + (box.w - w) / 2).coerceIn(0.0, 1 - w),
            y = (box.y + (box.h - h) / 2).coerceIn(0.0, 1 - h),
            w = w,
            h = h,
        ))
    }

    private fun updateBox(updated: Protocol.Box) {
        boxes = boxes.map { if (it.target == updated.target) updated else it }
        onChange(boxes, selected)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pinch = null
                val x = event.x
                val y = event.y
                val old = boxes.firstOrNull { it.target == selected }
                val resize = old != null && handle(rectOf(old)).apply {
                    inset(-6 * density, -6 * density)
                }.contains(x, y)
                val box = if (resize) old else hit(x, y)?.let(boxes::get)
                selected = box?.target
                drag = box?.let { Drag(event.getPointerId(0), it, x, y, resize) }
                invalidate()
                onChange(boxes, selected)
            }
            MotionEvent.ACTION_POINTER_DOWN -> beginPinch(event)
            MotionEvent.ACTION_MOVE -> {
                pinch?.let {
                    resizePinch(event, it)
                    return true
                }
                val active = drag ?: return true
                val i = event.findPointerIndex(active.id)
                if (i < 0 || width == 0 || height == 0) return true
                val dx = (event.getX(i) - active.x).toDouble() / width
                val dy = (event.getY(i) - active.y).toDouble() / height
                val b = active.original
                val updated = if (active.resize) {
                    val maxW = 1 - b.x
                    val maxH = 1 - b.y
                    b.copy(
                        w = (b.w + dx).coerceIn(minOf(44.0 * density / width, maxW).coerceAtLeast(0.01), maxW.coerceAtLeast(0.01)),
                        h = (b.h + dy).coerceIn(minOf(44.0 * density / height, maxH).coerceAtLeast(0.01), maxH.coerceAtLeast(0.01)),
                    )
                } else {
                    b.copy(x = (b.x + dx).coerceIn(0.0, 1 - b.w), y = (b.y + dy).coerceIn(0.0, 1 - b.h))
                }
                updateBox(updated)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val lifted = event.getPointerId(event.actionIndex)
                if (lifted == pinch?.firstId || lifted == pinch?.secondId) pinch = null
                if (lifted == drag?.id) drag = null
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drag = null
                pinch = null
            }
        }
        return true
    }
}

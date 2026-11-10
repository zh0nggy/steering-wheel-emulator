package com.tiltwheel.controller

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent

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
    private var drag: Drag? = null

    fun show(boxes: List<Protocol.Box>) {
        drag = null
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

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
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
            MotionEvent.ACTION_MOVE -> {
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
                boxes = boxes.map { if (it.target == b.target) updated else it }
                onChange(boxes, selected)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == drag?.id) drag = null
            }
            MotionEvent.ACTION_CANCEL -> drag = null
        }
        return true
    }
}

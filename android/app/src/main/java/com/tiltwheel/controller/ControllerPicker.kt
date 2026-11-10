package com.tiltwheel.controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ToggleButton
import kotlin.math.roundToInt

/** Native toggle buttons positioned on a scalable drawing based on controller_view.py. */
class ControllerPicker(context: Context, onToggle: (String) -> Unit) : ViewGroup(context) {
    private data class Zone(val target: String, val x: Int, val y: Int, val w: Int, val h: Int, val label: String)
    private val zones = listOf(
        Zone("LT", 106, 23, 84, 30, "LT"), Zone("RT", 334, 23, 84, 30, "RT"),
        Zone("LB", 125, 62, 90, 30, "LB"), Zone("RB", 315, 62, 90, 30, "RB"),
        Zone("LS", 106, 138, 45, 45, "LS"), Zone("RS", 268, 208, 45, 45, "RS"),
        Zone("GUIDE", 220, 111, 42, 36, "Xbox"),
        Zone("VIEW", 169, 142, 54, 32, "View"), Zone("MENU", 269, 148, 54, 32, "Menu"),
        Zone("DPAD_UP", 170, 175, 32, 30, "▲"), Zone("DPAD_DOWN", 170, 235, 32, 30, "▼"),
        Zone("DPAD_LEFT", 138, 205, 32, 30, "◀"), Zone("DPAD_RIGHT", 202, 205, 32, 30, "▶"),
        Zone("Y", 347, 115, 36, 36, "Y"), Zone("B", 376, 145, 36, 36, "B"),
        Zone("A", 347, 175, 36, 36, "A"), Zone("X", 318, 145, 36, 36, "X"),
    )
    private val toggles = zones.map { zone ->
        ToggleButton(context).apply {
            isAllCaps = false
            textOn = zone.label
            textOff = zone.label
            text = zone.label
            contentDescription = ButtonLayer.LABELS[zone.target] ?: zone.label
            setPadding(0, 0, 0, 0)
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            gravity = Gravity.CENTER
            setTextColor(Theme.TEXT)
            setOnClickListener { onToggle(zone.target) }
            addView(this)
        }
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = Path().apply {
        moveTo(220f, 83f)
        cubicTo(148f, 81f, 89f, 80f, 55f, 123f)
        cubicTo(36f, 150f, 15f, 232f, 21f, 270f)
        cubicTo(25f, 304f, 53f, 312f, 78f, 289f)
        cubicTo(108f, 259f, 129f, 228f, 163f, 228f)
        lineTo(277f, 228f)
        cubicTo(311f, 228f, 332f, 259f, 362f, 289f)
        cubicTo(387f, 312f, 415f, 304f, 419f, 270f)
        cubicTo(425f, 232f, 404f, 150f, 385f, 123f)
        cubicTo(351f, 80f, 292f, 81f, 220f, 83f)
        close()
    }
    private var scale = 1f

    init {
        setWillNotDraw(false)
        show(emptySet())
    }

    fun show(selected: Set<String>) {
        for ((i, zone) in zones.withIndex()) {
            val checked = zone.target in selected
            val tint = ButtonLayer.FACE_COLORS[zone.target] ?: Theme.STEER
            toggles[i].apply {
                isChecked = checked
                setTextColor(if (checked) android.graphics.Color.WHITE else Theme.TEXT)
                background = GradientDrawable().apply {
                    setColor(if (checked) tint else Theme.TROUGH)
                    setStroke(resources.displayMetrics.density.roundToInt(), if (checked) Theme.TEXT else tint)
                    cornerRadius = (if (zone.w > 50) 6f else 14f) * resources.displayMetrics.density
                }
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        scale = minOf(w / 440f, h / 308f)
        for ((i, zone) in zones.withIndex()) {
            val textSize = maxOf(1f, 15 * scale)
            if (toggles[i].textSize != textSize) {
                toggles[i].setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
            }
            toggles[i].measure(
                MeasureSpec.makeMeasureSpec((zone.w * scale).roundToInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((zone.h * scale).roundToInt(), MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val x0 = (width - 440 * scale) / 2
        val y0 = (height - 308 * scale) / 2
        for ((i, zone) in zones.withIndex()) {
            val toggle = toggles[i]
            val x = (x0 + zone.x * scale - toggle.measuredWidth / 2f).roundToInt()
            val y = (y0 + zone.y * scale - toggle.measuredHeight / 2f).roundToInt()
            toggle.layout(x, y, x + toggle.measuredWidth, y + toggle.measuredHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate((width - 440 * scale) / 2, (height - 308 * scale) / 2)
        canvas.scale(scale, scale)
        paint.style = Paint.Style.FILL
        paint.color = Theme.TROUGH
        canvas.drawPath(body, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Theme.BORDER
        canvas.drawPath(body, paint)
        canvas.restore()
    }
}

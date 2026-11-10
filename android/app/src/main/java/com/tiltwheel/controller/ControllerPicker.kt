package com.tiltwheel.controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.ToggleButton
import kotlin.math.roundToInt

/** Windows receiver artwork with native Android toggle targets. */
class ControllerPicker(context: Context, onToggle: (String) -> Unit) : ViewGroup(context) {
    private data class Zone(
        val target: String,
        val shape: Path,
        val x: Float,
        val y: Float,
        val label: String = target,
        val icon: Path? = null,
        val touchRadius: Float? = null,
    ) {
        val bounds = RectF().apply {
            if (touchRadius != null) {
                set(x - touchRadius, y - touchRadius, x + touchRadius, y + touchRadius)
            } else {
                shape.computeBounds(this, true)
            }
        }
        val isDirection = target.startsWith("DPAD_")
    }

    // The shell and front controls match PhoneControllerView in phone_layout.py.
    private val leftShell = listOf(
        340 to 120, 224 to 120, 154 to 124, 112 to 161,
        79 to 191, 33 to 329, 13 to 409,
        1 to 458, 1 to 504, 22 to 540,
        39 to 569, 70 to 586, 88 to 573,
        119 to 550, 174 to 482, 202 to 452,
        219 to 433, 229 to 432, 340 to 432,
    )
    private val body = trace(leftShell + mirror(leftShell.dropLast(1).asReversed()))
    private val leftBumper = listOf(
        112 to 161, 123 to 141, 126 to 132, 149 to 120,
        174 to 107, 207 to 102, 222 to 101,
        230 to 100, 238 to 114, 247 to 123,
        201 to 133, 150 to 144, 112 to 161,
    )
    private val leftShoulderX = referenceX(188)
    private val rightShoulderX = referenceX(492)
    private val shoulders = listOf(
        Zone("LT", trigger(leftShoulderX), leftShoulderX, 24f),
        Zone("RT", trigger(rightShoulderX), rightShoulderX, 24f),
        Zone("LB", trace(leftBumper), leftShoulderX, referenceY(117)),
        Zone("RB", trace(mirror(leftBumper)), rightShoulderX, referenceY(117)),
    )
    private val sticks = listOf(
        Zone("LS", circle(108f, 112f, 19f), 108f, 112f, touchRadius = 30f),
        Zone("RS", circle(273f, 187f, 19f), 273f, 187f, touchRadius = 30f),
    )
    private val stickRings = sticks.map { circle(it.x, it.y, 30f) }
    private val dpadX = 161f
    private val dpadY = 183f
    private val dpadRing = circle(dpadX, dpadY, 36f)
    private val cross = polygon(dpadX, dpadY, listOf(
        -11 to -29, 11 to -29, 11 to -11, 29 to -11, 29 to 11, 11 to 11,
        11 to 29, -11 to 29, -11 to 11, -29 to 11, -29 to -11, -11 to -11,
    ))
    private val directions = listOf(
        direction("UP", 0, -1), direction("DOWN", 0, 1),
        direction("LEFT", -1, 0), direction("RIGHT", 1, 0),
    )
    private val front = sticks + directions + listOf(
        Zone("Y", circle(328f, 99f, 16f), 328f, 99f),
        Zone("B", circle(360f, 131f, 16f), 360f, 131f),
        Zone("A", circle(328f, 163f, 16f), 328f, 163f),
        Zone("X", circle(296f, 131f, 16f), 296f, 131f),
        Zone("GUIDE", circle(220f, 79f, 17f), 220f, 79f, icon = polygon(220f, 79f, listOf(
            -12 to -8, -9 to -10, -5 to -9, 0 to -6, 5 to -9, 9 to -10, 12 to -8,
            8 to -6, 4 to -3, 8 to 2, 11 to 7, 12 to 10, 9 to 11, 6 to 7, 3 to 3,
            0 to 0, -3 to 3, -6 to 7, -9 to 11, -12 to 10, -11 to 7, -8 to 2,
            -4 to -3, -8 to -6,
        ))),
        Zone("VIEW", circle(187f, 122f, 11f), 187f, 122f, "▣", touchRadius = 18f),
        Zone("MENU", circle(253f, 122f, 11f), 253f, 122f, "≡", touchRadius = 18f),
    )
    private val zones = shoulders + front
    private val artworkBounds = RectF().apply {
        body.computeBounds(this, true)
        for (zone in zones) union(zone.bounds)
        // Leave room for the outlines, including the thicker selected border.
        inset(-2f, -2f)
    }
    private val toggles = zones.map { zone ->
        ToggleButton(context).apply {
            textOn = ""
            textOff = ""
            text = ""
            contentDescription = ButtonLayer.LABELS[zone.target] ?: zone.target
            background = null
            stateListAnimator = null
            setOnClickListener { onToggle(zone.target) }
            addView(this)
        }
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val inkBounds = Rect()
    private var selected = emptySet<String>()
    private var scale = 1f
    private var drawingLeft = 0f
    private var drawingTop = 0f
    private var toolbarHeight = 0
    private var toolbarSideWidth = 0f

    init {
        setWillNotDraw(false)
    }

    fun show(selected: Set<String>) {
        this.selected = selected
        for ((i, zone) in zones.withIndex()) toggles[i].isChecked = zone.target in selected
        invalidate()
    }

    fun setToolbarClearance(height: Int, sideWidth: Float) {
        if (toolbarHeight == height && toolbarSideWidth == sideWidth) return
        toolbarHeight = height
        toolbarSideWidth = sideWidth
        forceLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val fullScale = minOf(w / artworkBounds.width(), h / artworkBounds.height())
        val topInset = if (fitsBesideToolbar(w, h, fullScale)) 0 else minOf(toolbarHeight, h)
        scale = minOf(fullScale, (h - topInset) / artworkBounds.height())
        drawingLeft = w / 2f - artworkBounds.centerX() * scale
        drawingTop = (h + topInset) / 2f - artworkBounds.centerY() * scale
        for ((i, zone) in zones.withIndex()) {
            toggles[i].measure(
                MeasureSpec.makeMeasureSpec((zone.bounds.width() * scale).roundToInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((zone.bounds.height() * scale).roundToInt(), MeasureSpec.EXACTLY),
            )
        }
    }

    private fun fitsBesideToolbar(width: Int, height: Int, scale: Float): Boolean {
        if (toolbarHeight == 0 || scale <= 0f) return true
        // Convert the toolbar's bottom edge and open center to drawing coordinates.
        val cutoff = artworkBounds.centerY() + (toolbarHeight - height / 2f) / scale + 2f
        if (cutoff <= artworkBounds.top) return true
        val halfOpening = (width / 2f - toolbarSideWidth) / scale - 2f
        if (halfOpening <= 0f) return false
        val left = artworkBounds.centerX() - halfOpening
        val right = artworkBounds.centerX() + halfOpening
        if (zones.any { it.bounds.top < cutoff && (it.bounds.left < left || it.bounds.right > right) }) {
            return false
        }

        // Only the upper slice of the shell can overlap the toolbar.
        val band = rectangle(artworkBounds.left, artworkBounds.top, artworkBounds.right, cutoff)
        val upperShell = Path()
        if (!upperShell.op(body, band, Path.Op.INTERSECT)) return false
        if (upperShell.isEmpty) return true
        val bounds = RectF()
        upperShell.computeBounds(bounds, true)
        return bounds.left >= left && bounds.right <= right
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for ((i, zone) in zones.withIndex()) {
            val toggle = toggles[i]
            val x = (drawingLeft + zone.bounds.left * scale).roundToInt()
            val y = (drawingTop + zone.bounds.top * scale).roundToInt()
            toggle.layout(x, y, x + toggle.measuredWidth, y + toggle.measuredHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(drawingLeft, drawingTop)
        canvas.scale(scale, scale)
        shoulders.forEach { drawButton(canvas, it) }
        drawShape(canvas, body, BODY, OUTLINE)
        shoulders.forEach { drawLabel(canvas, it) }

        for (ring in stickRings) drawShape(canvas, ring, RING, OUTLINE)
        drawShape(canvas, dpadRing, RING, OUTLINE)
        drawShape(canvas, cross, BUTTON, OUTLINE)
        for (zone in front) {
            drawButton(canvas, zone)
            drawLabel(canvas, zone)
        }
        canvas.restore()
    }

    private fun drawButton(canvas: Canvas, zone: Zone) {
        when {
            zone.target in selected -> drawShape(canvas, zone.shape,
                ButtonLayer.FACE_COLORS[zone.target] ?: SELECTED, SELECTED_OUTLINE, 3f)
            zone.target == "GUIDE" -> drawShape(canvas, zone.shape, BODY, LOGO)
            !zone.isDirection -> drawShape(canvas, zone.shape, BUTTON, OUTLINE)
        }
    }

    private fun drawLabel(canvas: Canvas, zone: Zone) {
        val color = when {
            zone.target in selected -> Color.WHITE
            zone.target == "GUIDE" -> LOGO
            else -> ButtonLayer.FACE_COLORS[zone.target] ?: LABEL
        }
        if (zone.icon != null) {
            fill.color = color
            canvas.drawPath(zone.icon, fill)
            return
        }
        text.color = color
        text.textSize = if (zone.target in ButtonLayer.FACE_COLORS) 15f else 11f
        text.getTextBounds(zone.label, 0, zone.label.length, inkBounds)
        canvas.drawText(zone.label, zone.x - inkBounds.exactCenterX(),
            zone.y - inkBounds.exactCenterY(), text)
    }

    private fun drawShape(canvas: Canvas, path: Path, color: Int, outline: Int, width: Float = 2f) {
        fill.color = color
        canvas.drawPath(path, fill)
        stroke.color = outline
        stroke.strokeWidth = width
        canvas.drawPath(path, stroke)
    }

    private fun direction(name: String, dx: Int, dy: Int): Zone {
        val x = dpadX + 20 * dx
        val y = dpadY + 20 * dy
        val halfWidth = if (dx == 0) 11f else 9f
        val halfHeight = if (dy == 0) 11f else 9f
        val arrow = listOf(4 to 0, -4 to 4, -4 to -4).map { (forward, side) ->
            (forward * dx - side * dy) to (forward * dy + side * dx)
        }
        return Zone("DPAD_$name", rectangle(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight),
            x, y, icon = polygon(x, y, arrow))
    }

    private fun trace(points: List<Pair<Int, Int>>) = Path().apply {
        moveTo(referenceX(points[0].first), referenceY(points[0].second))
        for (i in 1 until points.size step 3) {
            val (x1, y1) = points[i]
            val (x2, y2) = points[i + 1]
            val (x3, y3) = points[i + 2]
            cubicTo(referenceX(x1), referenceY(y1), referenceX(x2), referenceY(y2),
                referenceX(x3), referenceY(y3))
        }
        close()
    }

    private fun mirror(points: List<Pair<Int, Int>>) = points.map { (x, y) -> (680 - x) to y }
    private fun referenceX(x: Int) = 220f + (x - 340) * 0.6f
    private fun referenceY(y: Int) = 47f + (y - 120) * 0.6f

    private fun polygon(x: Float, y: Float, points: List<Pair<Int, Int>>) = Path().apply {
        moveTo(x + points[0].first, y + points[0].second)
        for (i in 1 until points.size) lineTo(x + points[i].first, y + points[i].second)
        close()
    }

    private fun circle(x: Float, y: Float, radius: Float) = Path().apply {
        addCircle(x, y, radius, Path.Direction.CW)
    }

    private fun trigger(x: Float) = Path().apply {
        moveTo(x - 13f, 32f)
        cubicTo(x - 12f, 17f, x - 10f, 2f, x, 2f)
        cubicTo(x + 10f, 2f, x + 12f, 17f, x + 13f, 32f)
        close()
    }

    private fun rectangle(left: Float, top: Float, right: Float, bottom: Float) = Path().apply {
        addRect(left, top, right, bottom, Path.Direction.CW)
    }

    private companion object {
        const val BODY = 0xFF39383F.toInt()
        const val OUTLINE = 0xFFB8B5C3.toInt()
        const val RING = 0xFF24232B.toInt()
        const val BUTTON = 0xFF625E75.toInt()
        const val LABEL = 0xFFF5F4F8.toInt()
        const val LOGO = 0xFF67D7FF.toInt()
        const val SELECTED = 0xFF2F4F78.toInt()
        const val SELECTED_OUTLINE = 0xFF2B7DE9.toInt()
    }
}

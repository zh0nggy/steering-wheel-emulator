package com.tiltwheel.controller

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

/** The Windows Android profile, sized for touch and scrollable on short landscape screens. */
class TuningPanel @JvmOverloads constructor(
    context: Context, private val onChange: (Tuning) -> Unit = {},
) : ScrollView(context) {
    private data class Slider(
        val bar: SeekBar, val value: TextView, val minimum: Int, val step: Int,
        val read: (Tuning) -> Int, val format: (Int) -> String,
    )
    private val sliders = ArrayList<Slider>()
    private var tuning = Tuning()
    private var binding = false
    private val invert = CheckBox(context)
    private val gameRumble = CheckBox(context)
    private val boost: Slider

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(value: String, color: Int = Theme.TEXT) = TextView(context).apply {
        text = value
        textSize = 14f
        setTextColor(color)
    }

    init {
        isFillViewport = true
        // Keep the scrollbar in its own gutter, clear of the numeric values.
        scrollBarStyle = View.SCROLLBARS_OUTSIDE_INSET
        setPadding(0, 0, dp(8), 0)
        clipToPadding = true
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        addView(column, LayoutParams(-1, -2))
        val decimal: (Int) -> String = { String.format(Locale.US, "%.2f", it / 100.0) }
        slider(column, "Range (deg)", 10, 90, 1, { it.range }, { t, v -> t.copy(range = v) })
        slider(column, "Deadzone", 0, 30, 1, { it.deadzone }, { t, v -> t.copy(deadzone = v) }, decimal)
        slider(column, "Gamma", 100, 300, 5, { it.gamma }, { t, v -> t.copy(gamma = v) }, decimal)
        slider(column, "Smoothing", 0, 90, 5, { it.smooth }, { t, v -> t.copy(smooth = v) }, decimal)
        boost = slider(column, "Rumble boost (%)", 100, 250, 1,
            { it.rumbleBoost }, { t, v -> t.copy(rumbleBoost = v) })
        val toggles = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        for ((box, title) in listOf(invert to "Invert steering", gameRumble to "Game rumble")) {
            box.text = title
            box.textSize = 14f
            box.setTextColor(Theme.TEXT)
            box.setButtonDrawable(R.drawable.tuning_checkbox)
            box.buttonTintList = null // Preserve the white checkmark on devices with custom themes.
            box.minHeight = dp(48)
            toggles.addView(box, LinearLayout.LayoutParams(0, -2, 1f))
        }
        column.addView(toggles)
        invert.setOnCheckedChangeListener { _, checked ->
            if (!binding) change(tuning.copy(invert = checked))
        }
        gameRumble.setOnCheckedChangeListener { _, checked ->
            if (!binding) change(tuning.copy(gameRumble = checked))
        }
        show(tuning)
    }

    private fun slider(
        column: LinearLayout, title: String, minimum: Int, maximum: Int, step: Int,
        read: (Tuning) -> Int, write: (Tuning, Int) -> Tuning,
        format: (Int) -> String = { it.toString() },
    ): Slider {
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val bar = SeekBar(context).apply {
            id = View.generateViewId()
            contentDescription = title
            max = (maximum - minimum) / step
            minimumHeight = dp(48)
        }
        row.addView(label(title).apply { labelFor = bar.id }, LinearLayout.LayoutParams(dp(144), -2))
        row.addView(bar, LinearLayout.LayoutParams(0, dp(48), 1f))
        val value = label("").apply { gravity = Gravity.END }
        row.addView(value, LinearLayout.LayoutParams(dp(52), -2))
        column.addView(row, LinearLayout.LayoutParams(-1, -2))
        val slider = Slider(bar, value, minimum, step, read, format)
        sliders.add(slider)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) change(write(tuning, minimum + progress * step))
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        return slider
    }

    private fun change(value: Tuning) {
        show(value)
        onChange(value)
    }

    fun show(value: Tuning) {
        tuning = value
        binding = true
        for (slider in sliders) {
            slider.bar.progress = (slider.read(value) - slider.minimum) / slider.step
            slider.value.text = slider.format(slider.read(value))
        }
        invert.isChecked = value.invert
        gameRumble.isChecked = value.gameRumble
        boost.bar.isEnabled = value.gameRumble
        boost.value.alpha = if (value.gameRumble) 1f else 0.5f
        binding = false
    }
}

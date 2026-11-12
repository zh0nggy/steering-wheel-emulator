package com.tiltwheel.controller

import android.Manifest.permission.ACCESS_LOCAL_NETWORK
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_DENIED
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** Colours matching the Mac sender in dark mode (app_common.Bar and friends). */
object Theme {
    val BACKGROUND = Color.rgb(30, 30, 30)
    val TEXT = Color.rgb(230, 230, 230)
    val MUTED = Color.rgb(150, 150, 150)
    val TROUGH = Color.rgb(0x2c, 0x2c, 0x2e)
    val BORDER = Color.rgb(0x5a, 0x5a, 0x5e)
    val CENTRE_LINE = Color.rgb(0x9a, 0x9a, 0x9a)
    val STEER = Color.parseColor("#2b7de9")
    val BOX = Color.parseColor("#2f4f78")       // same as phone_layout.py
    val BOX_LINE = Color.parseColor("#8fb4e8")
}

/** The same editor is available offline and during a driving session. */
class MainActivity : Activity() {
    private enum class Page { PICK, EDIT, CONNECT, DRIVE }
    private var current = Page.PICK
    private lateinit var sensor: SteeringSensor
    private lateinit var sender: Sender
    private lateinit var rumble: GameRumble
    private lateinit var discovery: Discovery

    private lateinit var connectPage: View
    private lateinit var steerPage: View
    private lateinit var pickPage: View
    private lateinit var editPage: View
    private lateinit var picker: ControllerPicker
    private lateinit var editor: LayoutEditor
    private lateinit var pickedCount: TextView
    private lateinit var removeControl: Button
    private lateinit var applyLayout: Button
    private lateinit var cancelLayout: Button
    private lateinit var editorHint: TextView
    private lateinit var pcList: LinearLayout
    private lateinit var localNetworkButton: Button
    private lateinit var hostField: EditText
    private lateinit var targetLabel: TextView
    private lateinit var steerBar: Bar
    private lateinit var buttonLayer: ButtonLayer
    private lateinit var emptyHint: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("tiltwheel", MODE_PRIVATE) }
    private var shownPcs: List<Discovery.Pc>? = null
    private var port = Protocol.DEFAULT_PORT
    private var resumed = false
    private var savedLayout: List<Protocol.Box>? = null // null means setup has never been completed
    private var draft: List<Protocol.Box> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sensor = SteeringSensor(this)
        rumble = GameRumble(this)
        rumble.setEnabled(prefs.getBoolean("game_rumble", true))
        rumble.setBoost(prefs.getInt("rumble_boost", RumbleStrength.DEFAULT_BOOST))
        sender = Sender(sensor, rumble)
        discovery = Discovery(this)
        savedLayout = prefs.getString("control_layout", null)?.let(ControlLayout::parseRows)
        draft = savedInstanceState?.getString("draft")?.let(ControlLayout::parseRows)
            ?: savedLayout.orEmpty()
        setContentView(buildLayout())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) { if (!handleBack()) finish() }
        }
        val restored = savedInstanceState?.getString("page")
        showPage(when (restored) {
            Page.PICK.name -> Page.PICK
            Page.EDIT.name -> Page.EDIT
            else -> if (savedLayout == null) Page.PICK else Page.CONNECT
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", current.name)
        outState.putString("draft", ControlLayout.rows(draft))
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        sensor.start()
        updateLocalNetworkAccess()
        ui.post(refresh)
    }

    override fun onPause() {
        resumed = false
        // Stop releases the pad. An unfinished edit stays open as an offline draft.
        buttonLayer.release()
        sender.stop()
        sensor.stop()
        discovery.stop()
        ui.removeCallbacks(refresh)
        if (current == Page.DRIVE) showPage(Page.CONNECT)
        super.onPause()
    }

    override fun onDestroy() {
        rumble.close()
        super.onDestroy()
    }

    @Deprecated("Fallback for versions before the back dispatcher.")
    override fun onBackPressed() {
        if (!handleBack()) @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun handleBack(): Boolean {
        when (current) {
            Page.DRIVE -> stopSending()
            Page.EDIT -> showPage(Page.PICK)
            Page.PICK -> if (sender.isRunning || savedLayout != null) finishEditing(false)
                else return false
            Page.CONNECT -> return false
        }
        return true
    }

    // --- layout ---

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(value: String, size: Float = 14f, color: Int = Theme.TEXT) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun page() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun buildLayout(): View {
        connectPage = buildConnectPage()
        steerPage = buildSteerPage()
        pickPage = buildPickPage()
        editPage = buildEditPage()
        val topGap = dp(4)
        val bottomGap = dp(16)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Theme.BACKGROUND)
            setPadding(dp(24), topGap, dp(24), 0)
            addView(connectPage, FrameLayout.LayoutParams(-1, -1).apply {
                topMargin = dp(16) - topGap
                bottomMargin = bottomGap
            })
            addView(steerPage, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = bottomGap })
            addView(pickPage)
            addView(editPage, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = bottomGap })
        }
        // Android 15+ draws apps under the status and navigation bars.
        // Pad by their size (and any camera cutout) so nothing is hidden.
        // Older Android leaves room for the bars itself.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left + dp(24), bars.top + topGap, bars.right + dp(24), bars.bottom)
                insets
            }
        }
        return root
    }

    /** Page 1, laid out like the Mac app's "Windows PC" box. */
    private fun buildConnectPage(): View {
        val column = page()
        column.addView(text("Windows PC", 13f, Theme.MUTED).apply { setPadding(dp(4), 0, 0, dp(2)) })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setStroke(dp(1), Theme.BORDER)
                cornerRadius = dp(6).toFloat()
            }
        }
        pcList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(pcList)
        localNetworkButton = button(getString(R.string.allow_local_network)) { requestLocalNetworkAccess() }
        box.addView(localNetworkButton)

        val ipRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        ipRow.addView(text("IP"), LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.WRAP_CONTENT))
        hostField = EditText(this).apply {
            setText(prefs.getString("host", ""))
            hint = "192.168.x.x"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setTextColor(Theme.TEXT)
            setHintTextColor(Theme.MUTED)
        }
        ipRow.addView(hostField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        ipRow.addView(
            button("Start sending") { startSending() },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) },
        )
        box.addView(ipRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        column.addView(box)
        val rumbleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val testRumble = button("Test rumble") { rumble.test() }.apply {
            isEnabled = rumble.isSupported && prefs.getBoolean("game_rumble", true)
        }
        val gameRumbleToggle = CheckBox(this).apply {
            text = if (rumble.isSupported) "Game rumble" else "Game rumble (unavailable)"
            setTextColor(Theme.TEXT)
            isChecked = prefs.getBoolean("game_rumble", true)
            isEnabled = rumble.isSupported
        }
        rumbleRow.addView(gameRumbleToggle, LinearLayout.LayoutParams(0, -2, 1f))
        rumbleRow.addView(testRumble)
        column.addView(rumbleRow)
        val boost = prefs.getInt("rumble_boost", RumbleStrength.DEFAULT_BOOST)
            .coerceIn(RumbleStrength.MIN_BOOST, RumbleStrength.MAX_BOOST)
        val strengthLabel = text("Rumble boost: $boost%", 13f, Theme.MUTED)
        val strengthSlider = SeekBar(this).apply {
            max = RumbleStrength.MAX_BOOST - RumbleStrength.MIN_BOOST
            progress = boost - RumbleStrength.MIN_BOOST
            isEnabled = rumble.isSupported && rumble.amplitudeControl && prefs.getBoolean("game_rumble", true)
            contentDescription = "Rumble boost"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    val value = progress + RumbleStrength.MIN_BOOST
                    strengthLabel.text = "Rumble boost: $value%"
                    if (fromUser) {
                        rumble.setBoost(value)
                        prefs.edit().putInt("rumble_boost", value).apply()
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }
        gameRumbleToggle.setOnCheckedChangeListener { _, checked ->
            rumble.setEnabled(checked)
            prefs.edit().putBoolean("game_rumble", checked).apply()
            testRumble.isEnabled = rumble.isSupported && checked
            strengthSlider.isEnabled = rumble.isSupported && rumble.amplitudeControl && checked
        }
        column.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(strengthLabel)
            addView(strengthSlider, LinearLayout.LayoutParams(0, dp(32), 1f).apply { marginStart = dp(12) })
        })
        column.addView(
            button("Edit controls") { beginEditing() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
        )
        return column
    }

    private fun toolbar() = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun addToolbar(column: LinearLayout, strip: LinearLayout) {
        column.addView(strip, LinearLayout.LayoutParams(-1, dp(52)))
    }

    private fun canvasArea(column: LinearLayout, view: View): FrameLayout {
        val area = FrameLayout(this).apply {
            setPadding(0, dp(6), 0, 0)
            addView(view)
        }
        column.addView(area, LinearLayout.LayoutParams(-1, 0, 1f))
        return area
    }

    private fun buildPickPage(): View {
        val strip = toolbar()
        pickedCount = text("", 15f)
        strip.addView(pickedCount, LinearLayout.LayoutParams(0, -2, 1f))
        strip.addView(
            button("Cancel") { finishEditing(false) }.apply { tag = "cancel_setup" },
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) },
        )
        strip.addView(button("Next") { showPage(Page.EDIT) })
        picker = ControllerPicker(this) { target ->
            draft = if (draft.any { it.target == target }) draft.filterNot { it.target == target }
                else ControlLayout.add(draft, target)
            updatePicker()
        }
        return object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                measureChild(strip, widthMeasureSpec, heightMeasureSpec)
                val titleWidth = minOf(pickedCount.measuredWidth.toFloat(),
                    pickedCount.paint.measureText(pickedCount.text.toString()))
                val actionsWidth = strip.measuredWidth - pickedCount.measuredWidth
                picker.setToolbarClearance(strip.measuredHeight, maxOf(titleWidth, actionsWidth.toFloat()) + dp(8))
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            addView(picker, FrameLayout.LayoutParams(-1, -1))
            addView(strip, FrameLayout.LayoutParams(-1, dp(52)))
        }
    }

    private fun buildEditPage(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val strip = toolbar()
        val gap = dp(8)
        strip.addView(button("Back") { showPage(Page.PICK) })
        editorHint = text("", 12f, Theme.MUTED)
        strip.addView(editorHint, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = gap
            marginEnd = gap
        })
        strip.addView(button("Reset") { editor.show(ControlLayout.reset(draft)) })
        removeControl = button("Remove") { editor.removeSelected() }
        strip.addView(removeControl, LinearLayout.LayoutParams(-2, -2).apply { marginStart = gap })
        cancelLayout = button("Cancel") { finishEditing(false) }
        strip.addView(cancelLayout, LinearLayout.LayoutParams(-2, -2).apply { marginStart = gap })
        applyLayout = button("Save & continue") { finishEditing(true) }
        strip.addView(applyLayout, LinearLayout.LayoutParams(-2, -2).apply { marginStart = gap })
        addToolbar(column, strip)
        editor = LayoutEditor(this) { boxes, selected ->
            draft = boxes
            removeControl.isEnabled = selected != null
            editorHint.text = when {
                boxes.isEmpty() -> "No controls selected"
                selected != null -> "Pinch or drag the corner to resize"
                else -> "Drag a control to move it"
            }
        }.apply { setBackgroundColor(Theme.TROUGH) }
        canvasArea(column, editor)
        return column
    }

    private fun buildSteerPage(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val strip = toolbar()
        targetLabel = text("", 12f, Theme.MUTED)
        strip.addView(targetLabel)
        steerBar = Bar(this, Theme.STEER, centered = true).apply { contentDescription = "Steering" }
        strip.addView(steerBar, LinearLayout.LayoutParams(0, dp(18), 1f).apply {
            marginStart = dp(12); marginEnd = dp(12)
        })
        strip.addView(button("Calibrate centre") { sensor.calibrate() })
        strip.addView(button("Edit controls") { beginEditing() },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        strip.addView(button("Stop") { stopSending() },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        addToolbar(column, strip)

        buttonLayer = ButtonLayer(this) { mask, rt, lt ->
            sender.buttons = mask
            sender.rightTrigger = rt
            sender.leftTrigger = lt
        }
        val area = canvasArea(column, buttonLayer)
        emptyHint = text("Tap Edit controls to add touch buttons.", 13f, Theme.MUTED).apply {
            gravity = Gravity.CENTER
            isClickable = false // touches go through to the buttons
        }
        area.addView(emptyHint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT))
        return column
    }

    private fun showPage(page: Page) {
        current = page
        sender.muted = page != Page.DRIVE
        rumble.setActive(page == Page.DRIVE)
        pickPage.visibility = if (page == Page.PICK) View.VISIBLE else View.GONE
        editPage.visibility = if (page == Page.EDIT) View.VISIBLE else View.GONE
        connectPage.visibility = if (page == Page.CONNECT) View.VISIBLE else View.GONE
        steerPage.visibility = if (page == Page.DRIVE) View.VISIBLE else View.GONE
        when (page) {
            Page.PICK -> updatePicker()
            Page.EDIT -> {
                applyLayout.text = if (sender.isRunning) "Apply & return" else "Save & continue"
                cancelLayout.visibility = if (savedLayout != null || sender.isRunning) View.VISIBLE else View.GONE
                editor.show(draft)
            }
            Page.DRIVE -> {
                buttonLayer.boxes = savedLayout.orEmpty()
                emptyHint.visibility = if (buttonLayer.boxes.isEmpty()) View.VISIBLE else View.GONE
            }
            Page.CONNECT -> Unit
        }
        updateLocalNetworkAccess()
    }

    private fun updatePicker() {
        picker.show(draft.map { it.target }.toSet())
        pickedCount.text = "Choose buttons · ${draft.size} selected"
        pickPage.findViewWithTag<View>("cancel_setup").visibility =
            if (savedLayout != null || sender.isRunning) View.VISIBLE else View.GONE
    }

    private fun beginEditing() {
        sender.muted = true
        rumble.setActive(false)
        buttonLayer.release()
        draft = savedLayout.orEmpty()
        showPage(Page.PICK)
    }

    private fun storeLayout(boxes: List<Protocol.Box>) {
        savedLayout = boxes
        prefs.edit().putString("control_layout", ControlLayout.rows(boxes)).apply()
        sender.publishLayout(boxes)
        buttonLayer.boxes = boxes
        emptyHint.visibility = if (boxes.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun finishEditing(apply: Boolean) {
        if (apply) storeLayout(draft)
        showPage(if (sender.isRunning) Page.DRIVE else Page.CONNECT)
    }

    // --- behaviour ---

    private fun hasLocalNetworkAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN ||
            checkSelfPermission(ACCESS_LOCAL_NETWORK) == PERMISSION_GRANTED

    private fun updateLocalNetworkAccess() {
        val allowed = hasLocalNetworkAccess()
        localNetworkButton.visibility = if (allowed) View.GONE else View.VISIBLE
        pcList.visibility = if (allowed) View.VISIBLE else View.GONE
        if (allowed && resumed && current == Page.CONNECT) discovery.start()
    }

    private fun requestLocalNetworkAccess() {
        requestPermissions(arrayOf(ACCESS_LOCAL_NETWORK), LOCAL_NETWORK_REQUEST)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != LOCAL_NETWORK_REQUEST) return

        updateLocalNetworkAccess()
        if (grantResults.firstOrNull() == PERMISSION_DENIED &&
            !shouldShowRequestPermissionRationale(ACCESS_LOCAL_NETWORK)
        ) {
            AlertDialog.Builder(this)
                .setTitle(R.string.allow_local_network)
                .setMessage(R.string.local_network_settings_message)
                .setPositiveButton(R.string.open_app_settings) { _, _ ->
                    val appUri = Uri.fromParts("package", packageName, null)
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun startSending() {
        if (!hasLocalNetworkAccess()) {
            requestLocalNetworkAccess()
            return
        }
        val host = hostField.text.toString().trim()
        if (host.isEmpty()) {
            message("Pick your PC from the list, or type its IP.\n\n" +
                "If the list is empty, open the receiver app on Windows and press Start.")
            return
        }
        if (isPrivate(host) == false) {
            message("$host is not a local network address.\n\nUse the address shown at the " +
                "top of the Windows receiver app (192.168.x.x, 10.x.x.x or 172.16-31.x.x).")
            return
        }
        prefs.edit().putString("host", host).apply()
        hostField.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(hostField.windowToken, 0)
        sender.start(host, port, savedLayout.orEmpty())
        targetLabel.text = "Sending to $host"
        showPage(Page.DRIVE)
    }

    private fun stopSending() {
        buttonLayer.release()
        sender.stop()
        showPage(Page.CONNECT)
    }

    private fun message(value: String) {
        AlertDialog.Builder(this).setTitle("Tilt Wheel").setMessage(value)
            .setPositiveButton("OK", null).show()
    }

    private fun pickPc(pc: Discovery.Pc) {
        hostField.setText(pc.ip)
        port = pc.port
        showPcs(shownPcs.orEmpty())
    }

    private fun showPcs(pcs: List<Discovery.Pc>) {
        shownPcs = pcs
        pcList.removeAllViews()
        if (pcs.isEmpty()) {
            pcList.addView(text("Looking for the Windows receiver...", color = Theme.MUTED)
                .apply { setPadding(dp(6), dp(8), dp(6), dp(8)) })
            return
        }
        val selected = hostField.text.toString().trim()
        for (pc in pcs) {
            pcList.addView(text("${pc.name}  (${pc.ip})", 15f).apply {
                setPadding(dp(6), dp(8), dp(6), dp(8))
                if (pc.ip == selected) setBackgroundColor(Theme.STEER)
                setOnClickListener { pickPc(pc) }
            })
        }
    }

    private val refresh = object : Runnable {
        override fun run() {
            if (current == Page.CONNECT) {
                // Discovered PCs; fill in the only one automatically, like the Mac app.
                val pcs = discovery.recent()
                if (pcs.size == 1 && hostField.text.isBlank()) {
                    hostField.setText(pcs[0].ip)
                    port = pcs[0].port
                    shownPcs = null
                }
                if (pcs != shownPcs) showPcs(pcs)
            } else if (current == Page.DRIVE && !sender.isRunning) {
                // The address couldn't be used (e.g. no network): back to page 1.
                showPage(Page.CONNECT)
                message("Couldn't send to that address. Check the IP and that both are on the same WiFi.")
            }
            if (current == Page.DRIVE) {
                steerBar.value = sender.steer()
                sender.layoutProposal()?.let { storeLayout(it) }
            } else if (current == Page.EDIT && !sender.isRunning) {
                if (applyLayout.text != "Save & continue") applyLayout.text = "Save & continue"
            }
            ui.postDelayed(this, 50)
        }
    }

    companion object {
        private const val LOCAL_NETWORK_REQUEST = 1

        /** Port of mac_sender.is_private: true/false for an IPv4, null for a hostname. */
        fun isPrivate(host: String): Boolean? {
            val parts = host.split(".")
            if (parts.size != 4) return null
            val a = parts[0].toIntOrNull() ?: return null
            val b = parts[1].toIntOrNull() ?: return null
            return (a == 192 && b == 168) || a == 10 || (a == 172 && b in 16..31) || a == 127
        }
    }
}

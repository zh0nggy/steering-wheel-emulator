package com.tiltwheel.controller

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * The sender_app.SenderEngine loop for the phone: tilt steering, plus whichever
 * on-screen buttons are held (`buttons`, `rightTrigger`, `leftTrigger`, set by
 * the steering page). The packet is the full v3 frame, so the receiver needs
 * nothing new to drive the pad.
 *
 * The phone publishes the current layout. Windows can propose an edit
 * against that revision, which the activity accepts when it is not editing.
 * A separate acknowledged channel mirrors the Windows Android tuning profile.
 *
 * +-FULL_SCALE_DEGREES of virtual steering goes out as +-1. The receiver
 * applies Range (Android tuning), deadzone, gamma, smoothing and invert.
 *
 * Sends when anything changes, capped at MAX_RATE (a button press goes out at
 * once), and at least every KEEPALIVE so the receiver's failsafe never fires
 * while you hold still.
 */
class Sender(
    private val sensor: SteeringSensor,
    private val rumble: GameRumble,
    val tuning: TuningSync,
) {
    companion object {
        const val MAX_RATE = 250.0      // packets/sec ceiling
        const val KEEPALIVE = 0.01      // always send at least every 10 ms
        const val FULL_SCALE_DEGREES = 90.0  // must match PHONE_FULL_SCALE in receiver_app.pyw
        // Trigger ramp, the same timings as the Mac (sender_app.py uses Ramp(0.35, 0.18)).
        const val RISE_SECONDS = 0.35   // 0 -> 100% while held
        const val FALL_SECONDS = 0.18   // 100% -> 0 after letting go
        private val LAYOUT_CAPABILITY = "TWLCAP".toByteArray(Charsets.US_ASCII)
    }

    @Volatile var target: String? = null
        private set

    // Written by the screen, read by the send loop.
    @Volatile var buttons = 0
    @Volatile var rightTrigger = 0.0
    @Volatile var leftTrigger = 0.0

    @Volatile var muted = true
    @Volatile private var layout: ControlLayout.Snapshot? = null
    @Volatile private var proposal: ControlLayout.Snapshot? = null

    fun publishLayout(boxes: List<Protocol.Box>) {
        val previous = layout ?: return
        layout = previous.copy(revision = previous.revision + 1, boxes = boxes)
    }

    fun layoutProposal(): List<Protocol.Box>? {
        val current = layout ?: return null
        return proposal?.takeIf { it.session == current.session && it.revision == current.revision }?.boxes
    }

    @Volatile private var running = false
    private var thread: Thread? = null

    val isRunning get() = running

    /** -1 full left .. +1 full right. */
    fun steer() = Protocol.clamp(sensor.offset() / FULL_SCALE_DEGREES, -1.0, 1.0)

    fun start(host: String, port: Int, boxes: List<Protocol.Box>) {
        stop()
        sensor.resetAcceleration()
        layout = ControlLayout.Snapshot(ControlLayout.newSession(), 0, boxes)
        tuning.connect()
        target = host
        running = true
        thread = Thread({ run(host, port) }, "sender").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** Stops sending; the loop centres the car and releases everything on the way out. */
    fun stop() {
        running = false
        rumble.setActive(false)
        thread?.join(1000)
        thread = null
        target = null
        layout = null
        proposal = null
        tuning.disconnect()
    }

    private fun run(host: String, port: Int) {
        val socket: DatagramSocket
        val address: InetAddress
        try {
            address = InetAddress.getByName(host)
            socket = DatagramSocket().apply {
                trafficClass = 0xB8 // DSCP EF, same "voice" class as app_common.low_latency_socket
                soTimeout = 1       // receive() doubles as the loop's 1 ms sleep
                connect(address, port)
            }
        } catch (_: Exception) {
            running = false
            target = null // lets the screen retry on its next refresh
            return
        }

        fun send(steer: Double, rt: Double, lt: Double, mask: Int, seq: Int) {
            val data = Protocol.pack(steer, rt, lt, seq, mask, monotonicSeconds())
            try {
                socket.send(DatagramPacket(data, data.size, address, port))
            } catch (_: Exception) {
                // Network blip; the receiver's failsafe covers the gap.
            }
        }

        var seq = 0
        var lastSend = 0.0
        var lastFrame: List<Long>? = null
        var sentLayout: ControlLayout.Snapshot? = null
        var layoutBytes: ByteArray? = null
        var nextLayout = 0.0
        var nextTuning = 0.0
        var layoutSupported = false
        // Big enough for a layout of every button (about 40 bytes each).
        val buf = ByteArray(4096)
        // Same pedal feel as the Mac (key_input.Ramp): the touch buttons are on/off,
        // these ease the trigger values up and down.
        val rtRamp = Ramp(RISE_SECONDS, FALL_SECONDS)
        val ltRamp = Ramp(RISE_SECONDS, FALL_SECONDS)
        var prevLoop = monotonicSeconds()

        try {
            while (running) {
                val now = monotonicSeconds()
                val dt = now - prevLoop
                prevLoop = now
                val neutral = muted
                if (neutral) {
                    rtRamp.reset()
                    ltRamp.reset()
                }
                val steer = if (neutral) 0.0 else steer()
                val rt = if (neutral) 0.0 else rtRamp.update(rightTrigger, dt)
                val lt = if (neutral) 0.0 else ltRamp.update(leftTrigger, dt)
                val mask = if (neutral) 0 else buttons
                val frame = listOf(Math.round(steer * 1e4), Math.round(rt * 1e3), Math.round(lt * 1e3), mask.toLong())
                val since = now - lastSend
                val pressChanged = lastFrame == null || lastFrame[3] != frame[3]
                if (pressChanged || (frame != lastFrame && since >= 1.0 / MAX_RATE) || since >= KEEPALIVE) {
                    send(steer, rt, lt, mask, seq++)
                    lastSend = now
                    lastFrame = frame
                }
                val snapshot = layout
                if (layoutSupported && snapshot != null && (sentLayout !== snapshot || now >= nextLayout)) {
                    if (sentLayout !== snapshot) {
                        layoutBytes = ControlLayout.pack(ControlLayout.PHONE, snapshot)
                        sentLayout = snapshot
                    }
                    layoutBytes?.let {
                        try {
                            socket.send(DatagramPacket(it, it.size, address, port))
                        } catch (_: Exception) {}
                    }
                    nextLayout = now + 0.25
                }
                if (now >= nextTuning) {
                    tuning.packet()?.let {
                        try {
                            socket.send(DatagramPacket(it, it.size, address, port))
                        } catch (_: Exception) {}
                    }
                    nextTuning = now + 0.1
                }
                if (readReplies(socket, buf)) layoutSupported = true
                rumble.tick()
            }

            // Centre the car and release every button so nothing stays held.
            repeat(10) {
                send(0.0, 0.0, 0.0, 0, seq++)
                Thread.sleep(10)
            }
        } finally {
            rumble.reset()
            socket.close()
            running = false
        }
    }

    /**
     * Receive game rumble, bounce PINGs back and keep pending Windows layout edits.
     * Its echoes of our own timestamps are read and dropped:
     * the phone doesn't show a ping, but draining them keeps the buffer empty.
     */
    private fun readReplies(socket: DatagramSocket, buf: ByteArray): Boolean {
        var layoutSupported = false
        repeat(32) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                return layoutSupported
            } catch (_: Exception) {
                return layoutSupported
            }
            Protocol.unpackRumble(packet.data, packet.length)?.let {
                rumble.receive(it)
                return@repeat
            }
            if (tuning.receive(packet.data, packet.length)) return@repeat
            if (packet.length == LAYOUT_CAPABILITY.size &&
                LAYOUT_CAPABILITY.indices.all { buf[it] == LAYOUT_CAPABILITY[it] }
            ) {
                layoutSupported = true
                return@repeat
            }
            if (packet.length > Protocol.ECHO_SIZE) {
                val edit = ControlLayout.unpack(ControlLayout.WINDOWS, packet.data, packet.length)
                val current = layout
                if (edit != null && current != null && edit.session == current.session &&
                    edit.revision == current.revision
                ) proposal = edit
                return@repeat
            }
            val pong = Protocol.pingToPong(packet.data, packet.length) ?: return@repeat
            try {
                socket.send(DatagramPacket(pong, pong.size, packet.socketAddress))
            } catch (_: Exception) {}
        }
        return layoutSupported
    }
}

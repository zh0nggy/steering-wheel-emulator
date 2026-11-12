package com.tiltwheel.controller

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Continuous rumble, with feedback expiry enforced independently of the sender loop. */
class GameRumble internal constructor(
    private val output: RumbleOutput,
    private val watchdog: RumbleWatchdog,
    private val clock: () -> Double = ::monotonicSeconds,
) : AutoCloseable {
    constructor(context: Context) : this(AndroidRumbleOutput(context), ScheduledRumbleWatchdog())

    val isSupported = output.isSupported
    val amplitudeControl = output.amplitudeControl
    private val state = RumbleState()
    private val playback = RumblePlayback()
    private var enabled = true
    private var active = false
    private var playing = false
    private var previewUntil = Double.NEGATIVE_INFINITY
    private var closed = false
    private var boost = RumbleStrength.DEFAULT_BOOST
    private var cancelTimeout: (() -> Unit)? = null
    private var timeoutGeneration = 0L

    @Synchronized fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        reset()
    }

    @Synchronized fun setActive(value: Boolean) {
        active = value
        reset()
    }

    @Synchronized fun setBoost(percent: Int) {
        boost = percent.coerceIn(RumbleStrength.MIN_BOOST, RumbleStrength.MAX_BOOST)
        reset()
    }

    @Synchronized fun reset() {
        state.reset(clock())
        cancel()
    }

    @Synchronized fun receive(frame: Protocol.Rumble) {
        if (closed || !enabled || !active || !isSupported) return
        val now = clock()
        if (!state.accept(frame, now)) return
        // Settings travel with accepted feedback, so an old packet cannot
        // overwrite the Windows slider. Updating boost must not reset its lease.
        boost = frame.boost.coerceIn(RumbleStrength.MIN_BOOST, RumbleStrength.MAX_BOOST)
        if (state.motors(now).silent) {
            cancel() // Game stops bypass filtering, even if tick() is delayed.
        } else {
            // Delayed feedback keeps the expiry from its echoed phone timestamp.
            armTimeout(state.remainingMillis(now))
        }
    }

    @Synchronized fun tick() {
        if (closed) return
        val now = clock()
        // The connected sender still ticks while editing; let a local test finish.
        if (!active && enabled && isSupported && now < previewUntil) return
        val motors = if (enabled && active) state.motors(now) else RumbleMotors()
        if (motors.silent || !isSupported) {
            cancel()
            return
        }
        val amplitude = if (amplitudeControl) RumbleStrength.amplitude(motors, boost) else -1
        if (playback.needsUpdate(amplitude, now)) {
            output.start(amplitude)
            playing = true
            playback.started(amplitude, now)
        }
    }

    @Synchronized fun test() {
        if (!closed && enabled && isSupported && !active) {
            reset()
            val amplitude = if (amplitudeControl) RumbleStrength.amplitude(RumbleMotors(160, 100), boost) else -1
            output.preview(amplitude)
            playing = true
            previewUntil = clock() + 1.0
            armTimeout(1000)
        }
    }

    private fun armTimeout(delayMillis: Long) {
        cancelTimeout?.invoke()
        val generation = ++timeoutGeneration
        // This runs on a separate thread if sending/ticking stalls.
        cancelTimeout = watchdog.schedule(delayMillis) {
            synchronized(this) {
                // A cancelled callback may already have started. It must not
                // cancel an effect renewed by a more recent feedback packet.
                if (!closed && generation == timeoutGeneration) reset()
            }
        }
    }

    private fun cancel() {
        timeoutGeneration++
        cancelTimeout?.invoke()
        cancelTimeout = null
        if (playing) output.cancel()
        playing = false
        previewUntil = Double.NEGATIVE_INFINITY
        playback.reset()
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        active = false
        reset()
        watchdog.close()
    }
}

/** The Android adapter is kept separate so the full lifecycle can be tested off-device. */
internal interface RumbleOutput {
    val isSupported: Boolean
    val amplitudeControl: Boolean
    fun start(amplitude: Int)
    fun preview(amplitude: Int)
    fun cancel()
}

private class AndroidRumbleOutput(context: Context) : RumbleOutput {
    private val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }
    override val isSupported = vibrator.hasVibrator()
    override val amplitudeControl = vibrator.hasAmplitudeControl()
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    override fun start(amplitude: Int) {
        // A single non-zero segment repeated by Android keeps the actuator on.
        // No off segments, artificial pulse carrier, or heartbeat-driven replays.
        play(VibrationEffect.createWaveform(longArrayOf(1000), intArrayOf(amplitude), 0))
    }

    override fun preview(amplitude: Int) {
        play(VibrationEffect.createOneShot(1000, amplitude))
    }

    private fun play(effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, audioAttributes)
        }
    }

    override fun cancel() = vibrator.cancel()
}

internal interface RumbleWatchdog : AutoCloseable {
    /** Schedule expiry and return a cancellation function. */
    fun schedule(delayMillis: Long, timeout: () -> Unit): () -> Unit
}

private class ScheduledRumbleWatchdog : RumbleWatchdog {
    private val executor = ScheduledThreadPoolExecutor(1) { action ->
        Thread(action, "rumble-watchdog").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    override fun schedule(delayMillis: Long, timeout: () -> Unit): () -> Unit {
        val future = executor.schedule({ timeout() }, delayMillis, TimeUnit.MILLISECONDS)
        return { future.cancel(false) }
    }

    override fun close() {
        executor.shutdownNow()
    }
}

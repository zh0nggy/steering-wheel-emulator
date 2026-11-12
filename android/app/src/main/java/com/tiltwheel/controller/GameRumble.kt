package com.tiltwheel.controller

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Finite vibration effects ensure even a stalled sender cannot leave rumble on. */
class GameRumble(context: Context) {
    private val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }
    val isSupported = vibrator.hasVibrator()
    private val amplitudeControl = vibrator.hasAmplitudeControl()
    private val state = RumbleState()
    private var enabled = true
    private var active = false
    private var playingAmplitude = 0
    private var renewAt = 0.0
    private var playingUntil = 0.0
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    @Synchronized fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        reset()
    }

    @Synchronized fun setActive(value: Boolean) {
        active = value
        reset()
    }

    @Synchronized fun reset() {
        state.reset(monotonicSeconds())
        cancel()
    }

    @Synchronized fun receive(frame: Protocol.Rumble) {
        if (enabled && active && isSupported) state.accept(frame, monotonicSeconds())
    }

    @Synchronized fun tick() {
        val now = monotonicSeconds()
        val strength = if (enabled && active) state.amplitude(now) else 0
        if (strength == 0 || !isSupported) {
            cancel()
            return
        }
        val amplitude = if (amplitudeControl) strength else VibrationEffect.DEFAULT_AMPLITUDE
        if (amplitude != playingAmplitude || now >= renewAt || now >= playingUntil) {
            // Never extend an effect beyond its packet's deadline, even if
            // this loop stalls before it can explicitly call cancel().
            play(amplitude, state.remainingMillis(now))
            renewAt = now + 0.1
        }
    }

    @Synchronized fun test() {
        if (enabled && isSupported && !active) {
            play(if (amplitudeControl) 180 else VibrationEffect.DEFAULT_AMPLITUDE, 180)
        }
    }

    private fun play(amplitude: Int, duration: Long) {
        val effect = VibrationEffect.createOneShot(duration, amplitude)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, audioAttributes)
        }
        playingAmplitude = amplitude
        playingUntil = monotonicSeconds() + duration / 1000.0
    }

    private fun cancel() {
        if (playingAmplitude != 0) vibrator.cancel()
        playingAmplitude = 0
    }
}

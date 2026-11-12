package com.tiltwheel.controller

import kotlin.math.abs

/** Keep a continuous effect running through heartbeats and small strength changes. */
class RumblePlayback {
    private var playingAmplitude: Int? = null
    private var startedAt = 0.0

    fun reset() {
        playingAmplitude = null
        startedAt = 0.0
    }

    fun needsUpdate(amplitude: Int, now: Double): Boolean {
        if (amplitude == 0) return false
        val previous = playingAmplitude ?: return true
        // Compare what the phone actually plays, after mixing and boosting.
        // Different motor commands can produce the same output strength.
        val change = amplitude - previous
        if (abs(change) < 12) return false
        // Large impacts start at once; ordinary fluctuations are coalesced.
        // There is deliberately no periodic restart for an unchanged effect.
        return change >= 48 || now - startedAt >= 0.06
    }

    fun started(amplitude: Int, now: Double) {
        playingAmplitude = amplitude
        startedAt = now
    }
}

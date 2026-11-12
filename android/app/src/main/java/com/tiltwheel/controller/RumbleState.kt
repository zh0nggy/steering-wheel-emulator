package com.tiltwheel.controller

/** Ordering and expiry independent of Android hardware, using the phone's clock. */
class RumbleState {
    companion object {
        const val TIMEOUT_SECONDS = 0.25
    }

    private var latest: Protocol.Rumble? = null
    private var notBefore = Double.NEGATIVE_INFINITY

    fun reset(now: Double) {
        latest = null
        notBefore = now
    }

    fun accept(frame: Protocol.Rumble, now: Double): Boolean {
        if (!frame.timestamp.isFinite() || frame.timestamp < notBefore ||
            now < frame.timestamp || now - frame.timestamp >= TIMEOUT_SECONDS
        ) return false
        latest?.let {
            val advance = (frame.seq.toLong() - it.seq.toLong()) and 0xFFFFFFFFL
            if (advance == 0L || advance >= 0x80000000L || frame.timestamp < it.timestamp) return false
        }
        latest = frame
        return true
    }

    fun remainingMillis(now: Double): Long {
        val frame = latest ?: return 0
        return ((frame.timestamp + TIMEOUT_SECONDS - now) * 1000).toLong().coerceAtLeast(0)
    }

    fun amplitude(now: Double): Int {
        val frame = latest ?: return 0
        if (remainingMillis(now) == 0L) return 0
        // A phone usually has one actuator. Preserve either controller motor's
        // feedback without adding the strengths and clipping most effects.
        return maxOf(frame.largeMotor, frame.smallMotor).coerceIn(0, 255)
    }
}

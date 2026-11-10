package com.tiltwheel.controller

import kotlin.math.PI
import kotlin.math.abs

/**
 * Port of app_common.OneEuroFilter: heavy smoothing when the phone is still,
 * light smoothing when you turn fast, so you get neither jitter nor lag.
 */
class OneEuroFilter(
    @Volatile var minCutoff: Double = 1.0,
    @Volatile var beta: Double = 0.3,
    private val dCutoff: Double = 1.0,
) {
    var value: Double? = null
        private set
    private var speed = 0.0

    private fun alpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2.0 * PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }

    fun reset() {
        value = null
        speed = 0.0
    }

    fun update(x: Double, dt: Double): Double {
        val current = value
        if (current == null || dt <= 0.0) {
            value = x
            return x
        }
        val rawSpeed = (x - current) / dt
        speed += (rawSpeed - speed) * alpha(dCutoff, dt)
        val cutoff = minCutoff + beta * abs(speed)
        val next = current + (x - current) * alpha(cutoff, dt)
        value = next
        return next
    }
}

/** Adds more virtual rotation per degree when the phone moves quickly. */
class SteeringAcceleration(private val fullScale: Double) {
    private var lastOffset: Double? = null
    private var correction = 0.0

    fun update(offset: Double, dt: Double): Double {
        val previous = lastOffset
        lastOffset = offset
        val magnitude = abs(offset)
        val centreTolerance = 0.5
        if (previous == null || magnitude <= centreTolerance || offset * previous < 0.0 ||
            dt <= 0.0 || dt > 0.1) {
            correction = 0.0
            return offset
        }

        val delta = offset - previous
        val speed = abs(delta) / dt
        // Smoothly increase gain from 1x at 30 deg/s to 1.8x at 200 deg/s.
        val t = ((speed - 30.0) / 170.0).coerceIn(0.0, 1.0)
        val extraGain = 0.8 * t * t * (3.0 - 2.0 * t)
        val direction = if (offset > 0.0) 1.0 else -1.0

        // Bound the accumulated correction so repeated flicks can't wind it up.
        // On a fast return, allow less steering but keep it on the same side.
        val maxCorrection = (fullScale - magnitude).coerceIn(0.0, 20.0)
        correction = (correction + direction * delta * extraGain)
            .coerceIn(-0.5 * magnitude, maxCorrection)
        val centreBlend = ((magnitude - centreTolerance) / (12.0 - centreTolerance)).coerceAtMost(1.0)
        return offset + direction * correction * centreBlend
    }
}

/** Port of app_common.RollingStats: average and max of the last `window` seconds. */
class RollingStats(private val window: Double = 1.0) {
    private val samples = ArrayDeque<Pair<Double, Double>>()

    @Synchronized
    fun add(value: Double, now: Double) {
        samples.addLast(now to value)
        trim(now)
    }

    @Synchronized
    fun count(now: Double): Int {
        trim(now)
        return samples.size
    }

    /** (average, maximum), or null if nothing arrived in the window. */
    @Synchronized
    fun summary(now: Double): Pair<Double, Double>? {
        trim(now)
        if (samples.isEmpty()) return null
        return samples.sumOf { it.second } / samples.size to samples.maxOf { it.second }
    }

    private fun trim(now: Double) {
        while (samples.isNotEmpty() && samples.first().first < now - window) samples.removeFirst()
    }
}

/**
 * Port of key_input.Ramp: turns an on/off button into a smooth 0..1 pedal.
 * Holding it ramps up over `riseSeconds`, letting go drops over `fallSeconds`,
 * so the throttle eases in like the Mac's keys instead of slamming to 100%.
 */
class Ramp(riseSeconds: Double = 0.35, fallSeconds: Double = 0.18) {
    private val rise = 1.0 / maxOf(riseSeconds, 1e-3)
    private val fall = 1.0 / maxOf(fallSeconds, 1e-3)
    var value = 0.0
        private set

    fun reset() {
        value = 0.0
    }

    /** `target` is where the button says to go (0 or 1); `dt` in seconds. */
    fun update(target: Double, dt: Double): Double {
        value = if (target > value) minOf(target, value + rise * dt)
                else maxOf(target, value - fall * dt)
        return value
    }
}

fun wrapDegrees(deg: Double) = ((deg + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

/** Monotonic seconds, the same kind of clock as Python's time.monotonic(). */
fun monotonicSeconds() = System.nanoTime() / 1e9

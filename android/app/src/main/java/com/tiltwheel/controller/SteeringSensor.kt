package com.tiltwheel.controller

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Turns the phone into a wheel: hold it in landscape facing you and rotate it
 * like a steering wheel. The angle is gravity's direction in the screen plane,
 * so it still works with the screen tilted back like a dashboard.
 *
 * Prefers TYPE_GRAVITY (accelerometer fused with the gyro, so bumps and hand
 * shake don't move the wheel); falls back to the raw accelerometer.
 */
class SteeringSensor(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? =
        manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    val mode: String = when (sensor?.type) {
        Sensor.TYPE_GRAVITY -> "fused (gyro + accel)"
        Sensor.TYPE_ACCELEROMETER -> "accelerometer only"
        else -> "no motion sensor"
    }

    private val filter = OneEuroFilter()
    private val acceleration = SteeringAcceleration(Sender.FULL_SCALE_DEGREES)
    private var unwrapped: Double? = null
    private var lastRaw = 0.0
    private var lastSampleAt: Double? = null

    /** Smoothed wheel angle in degrees (continuous, can pass +-180). */
    private var angle: Double? = null

    /** Reading that becomes "straight ahead". Null until the first sample. */
    private var centre: Double? = null

    @Volatile private var steeringOffset = 0.0

    fun setSmoothing(minCutoff: Double, beta: Double) {
        filter.minCutoff = minCutoff
        filter.beta = beta
    }

    fun start(): Boolean {
        val s = sensor ?: return false
        // 5 ms = 200 Hz, the fastest Android allows without an extra permission.
        manager.registerListener(this, s, 5_000)
        return true
    }

    fun stop() {
        manager.unregisterListener(this)
        filter.reset()
        unwrapped = null
        lastSampleAt = null
        angle = null
        resetAcceleration()
    }

    fun calibrate() {
        centre = angle
        resetAcceleration()
    }

    fun resetAcceleration() {
        val a = angle
        val c = centre
        val offset = if (a != null && c != null) wrapDegrees(a - c) else 0.0
        steeringOffset = acceleration.update(offset, 0.0)
    }

    /** Virtual steering angle from the calibrated centre. */
    fun offset() = steeringOffset

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = Triple(event.values[0].toDouble(), event.values[1].toDouble(),
            event.values[2].toDouble())
        // Screen nearly flat: gravity has no direction in the screen plane, so
        // the angle would be noise. Hold the last value instead.
        val inPlane = sqrt(x * x + y * y)
        if (inPlane < 0.25 * sqrt(x * x + y * y + z * z)) return

        // In landscape, "up" is roughly +x. Turning the phone clockwise moves
        // gravity toward +y, so negate to make right turns positive.
        val raw = -Math.toDegrees(atan2(x, y))
        val prev = unwrapped
        val cont = if (prev == null) raw else prev + wrapDegrees(raw - lastRaw)
        unwrapped = cont
        lastRaw = raw

        val now = event.timestamp / 1e9
        val dt = lastSampleAt?.let { now - it } ?: 0.0
        lastSampleAt = now
        val filtered = filter.update(cont, dt)
        angle = filtered
        val c = centre ?: filtered.also { centre = it }
        steeringOffset = acceleration.update(wrapDegrees(filtered - c), dt)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

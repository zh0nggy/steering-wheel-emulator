package com.tiltwheel.controller

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class RumbleMotors(val large: Int = 0, val small: Int = 0) {
    val silent get() = large == 0 && small == 0
}

/** Mix both Xbox motor commands into a steady amplitude for the phone's actuator. */
object RumbleStrength {
    const val MIN_BOOST = 100
    const val MAX_BOOST = 250
    const val DEFAULT_BOOST = 150

    fun amplitude(motors: RumbleMotors, boost: Int): Int {
        val heavy = motors.large.coerceIn(0, 255) / 255.0
        val fine = motors.small.coerceIn(0, 255) / 255.0 * 0.8
        val mixed = sqrt(heavy * heavy + fine * fine).coerceIn(0.0, 1.0)
        // Lift quiet feedback without a minimum vibration floor. The hardware
        // supplies the vibration itself; adding a software carrier made it feel
        // like separate taps on amplitude-only phones such as the S25.
        val perceived = mixed.pow(0.65)
        val lifted = 1.0 - (1.0 - perceived).pow(boost.coerceIn(MIN_BOOST, MAX_BOOST) / 100.0)
        return (255.0 * lifted).roundToInt().coerceIn(0, 255)
    }
}

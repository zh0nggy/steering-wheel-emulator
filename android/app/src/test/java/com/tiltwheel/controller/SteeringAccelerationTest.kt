package com.tiltwheel.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SteeringAccelerationTest {
    @Test
    fun fastTurnsTravelFurtherAndHoldInEitherDirection() {
        for (direction in listOf(-1.0, 1.0)) {
            val slow = SteeringAcceleration(90.0)
            val fast = SteeringAcceleration(90.0)
            slow.update(0.0, 0.0)
            fast.update(0.0, 0.0)
            for (angle in 1..15) {
                slow.update(direction * angle, 0.05)
                fast.update(direction * angle, 0.005)
            }
            assertEquals(direction * 15.0, slow.update(direction * 15.0, 0.05), 1e-9)
            assertEquals(direction * 27.0, fast.update(direction * 15.0, 0.05), 1e-9)
        }
    }

    @Test
    fun slowUnwindTapersAndClearsTheBoostNearCentre() {
        val steering = SteeringAcceleration(90.0)
        steering.update(0.0, 0.0)
        steering.update(15.0, 0.075)
        assertEquals(26.0, steering.update(14.0, 0.05), 1e-9)
        for (angle in 13 downTo 6) steering.update(angle.toDouble(), 0.05)
        val nearCentre = steering.update(5.0, 0.05)
        assertTrue(nearCentre > 5.0 && nearCentre < 17.0)
        for (angle in 4 downTo 1) steering.update(angle.toDouble(), 0.05)
        assertEquals(0.4, steering.update(0.4, 0.05), 1e-9)
        assertEquals(1.4, steering.update(1.4, 0.05), 1e-9)
    }

    @Test
    fun fastUnwindMovesFurtherWithoutReversingPrematurely() {
        val steering = SteeringAcceleration(90.0)
        steering.update(15.0, 0.0)
        assertEquals(9.6, steering.update(12.0, 0.015), 1e-9)
        assertTrue(steering.update(2.0, 0.01) > 0.0)
        assertEquals(-2.0, steering.update(-2.0, 0.01), 1e-9)
    }

    @Test
    fun resetAndGapsClearTheBoost() {
        val steering = SteeringAcceleration(90.0)
        steering.update(0.0, 0.0)
        assertEquals(27.0, steering.update(15.0, 0.075), 1e-9)
        assertEquals(15.0, steering.update(15.0, 0.0), 1e-9)
        assertEquals(24.0, steering.update(20.0, 0.02), 1e-9)
        assertEquals(20.0, steering.update(20.0, 0.2), 1e-9)
    }
}

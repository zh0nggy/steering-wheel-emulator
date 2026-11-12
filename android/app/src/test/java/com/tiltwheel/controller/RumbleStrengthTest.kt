package com.tiltwheel.controller

import org.junit.Assert.*
import org.junit.Test

class RumbleStrengthTest {
    private fun amplitude(large: Int, small: Int = 0, boost: Int = RumbleStrength.DEFAULT_BOOST) =
        RumbleStrength.amplitude(RumbleMotors(large, small), boost)

    @Test fun bothMotorChannelsRemainAvailableUntilExpiry() {
        val state = RumbleState()
        state.accept(Protocol.Rumble(1, 1.0, 80, 200), 1.01)
        assertEquals(RumbleMotors(80, 200), state.motors(1.02))
        assertEquals(RumbleMotors(), state.motors(1.25))
    }

    @Test fun quieterMotorContributesWhenBothRun() {
        assertTrue(amplitude(80, 60) > amplitude(80, 0))
        assertTrue(amplitude(0, 80) > 0)
        assertTrue(amplitude(80, 0) > amplitude(0, 80))
    }

    @Test fun quietFeedbackIsLiftedAndRemainsProportional() {
        assertTrue(amplitude(24) > 24)
        assertTrue(amplitude(24) < amplitude(100))
        assertTrue(amplitude(100) < amplitude(255))
        assertTrue((0 until 255).all { amplitude(it) <= amplitude(it + 1) })
    }

    @Test fun boostRaisesQuieterFeedbackAndPreservesSilence() {
        assertTrue(amplitude(64, 32, 200) > amplitude(64, 32, 100))
        for (boost in 100..250) assertEquals(0, amplitude(0, 0, boost))
    }

    @Test fun outputStaysWithinHardwareRange() {
        for (large in listOf(-1, 0, 1, 100, 255, 300)) {
            for (small in listOf(-1, 0, 1, 100, 255, 300)) {
                assertTrue(amplitude(large, small, 250) in 0..255)
            }
        }
        assertEquals(255, amplitude(255, 255))
    }
}

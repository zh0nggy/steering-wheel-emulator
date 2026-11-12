package com.tiltwheel.controller

import org.junit.Assert.*
import org.junit.Test

class RumbleStateTest {
    private fun frame(seq: Int, at: Double, large: Int = 180, small: Int = 20) =
        Protocol.Rumble(seq, at, large, small)

    @Test fun combinesEitherMotorAndHonoursStop() {
        val state = RumbleState()
        assertTrue(state.accept(frame(1, 1.0, 0, 200), 1.01))
        assertEquals(200, state.amplitude(1.02))
        assertTrue(state.accept(frame(2, 1.03, 255, 0), 1.04))
        assertEquals(255, state.amplitude(1.05))
        assertTrue(state.accept(frame(3, 1.06, 0, 0), 1.07))
        assertEquals(0, state.amplitude(1.08))
    }

    @Test fun lostStopExpiresFromOriginalSendTime() {
        val state = RumbleState()
        assertTrue(state.accept(frame(1, 1.0), 1.20))
        assertTrue(state.remainingMillis(1.20) in 49L..50L)
        assertEquals(180, state.amplitude(1.24))
        assertEquals(0, state.amplitude(1.25))
        assertEquals(0L, state.remainingMillis(2.0))
        assertFalse(state.accept(frame(2, 1.1), 2.0))
    }

    @Test fun oldAndDuplicatePacketsCannotRestartRumble() {
        val state = RumbleState()
        assertTrue(state.accept(frame(100, 1.0, 0, 0), 1.01))
        assertFalse(state.accept(frame(99, 0.99), 1.02))
        assertFalse(state.accept(frame(100, 1.0), 1.02))
        assertFalse(state.accept(frame(101, 0.99), 1.02))
        assertEquals(0, state.amplitude(1.03))
    }

    @Test fun unsignedSequenceWrapAndReceiverRestart() {
        val state = RumbleState()
        assertTrue(state.accept(frame(-1, 1.0), 1.01))
        assertTrue(state.accept(frame(0, 1.02), 1.03))
        assertFalse(state.accept(frame(-2, 1.01), 1.04))
        // The PC echoes the phone's ongoing sequence after a receiver restart.
        assertTrue(state.accept(frame(5, 1.06), 1.07))
    }

    @Test fun resetRejectsFeedbackFromPreviousScreenOrSession() {
        val state = RumbleState()
        state.accept(frame(50, 1.0), 1.01)
        state.reset(1.05)
        assertEquals(0, state.amplitude(1.06))
        assertFalse(state.accept(frame(51, 1.04), 1.06))
        assertTrue(state.accept(frame(0, 1.07), 1.08))
    }

    @Test fun invalidTimestampsAreIgnored() {
        val state = RumbleState()
        for (timestamp in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 2.0)) {
            assertFalse(state.accept(frame(1, timestamp), 1.0))
        }
        assertEquals(0, state.amplitude(1.0))
    }
}

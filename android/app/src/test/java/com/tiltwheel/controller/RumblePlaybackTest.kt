package com.tiltwheel.controller

import org.junit.Assert.*
import org.junit.Test

class RumblePlaybackTest {
    @Test fun steadyFeedbackIsNotRestartedByHeartbeats() {
        val playback = RumblePlayback()
        assertTrue(playback.needsUpdate(180, 1.0))
        playback.started(180, 1.0)
        for (i in 1..600) assertFalse(playback.needsUpdate(180, 1.0 + i * 0.1))
    }

    @Test fun zeroDoesNotStartAnEffect() {
        val playback = RumblePlayback()
        assertFalse(playback.needsUpdate(0, 1.0))
    }

    @Test fun smallFluctuationsDoNotChopUpAnEffect() {
        val playback = RumblePlayback()
        playback.started(180, 1.0)
        assertFalse(playback.needsUpdate(182, 1.10))
        assertFalse(playback.needsUpdate(175, 1.15))
        assertTrue(playback.needsUpdate(195, 1.15))
    }

    @Test fun ordinaryChangesAreCoalescedButImpactsAreImmediate() {
        val playback = RumblePlayback()
        playback.started(100, 1.0)
        assertFalse(playback.needsUpdate(120, 1.01))
        assertTrue(playback.needsUpdate(180, 1.01))
        assertTrue(playback.needsUpdate(120, 1.07))
    }

    @Test fun resettingPlaybackAllowsANewOnset() {
        val playback = RumblePlayback()
        playback.started(180, 1.0)
        playback.reset()
        assertTrue(playback.needsUpdate(180, 1.02))
    }
}

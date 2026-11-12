package com.tiltwheel.controller

import org.junit.Assert.*
import org.junit.Test

/** Exercises the real controller through its platform boundary, with a virtual clock. */
class GameRumbleTest {
    private class Output(
        override val amplitudeControl: Boolean = true,
        override val isSupported: Boolean = true,
    ) : RumbleOutput {
        val starts = mutableListOf<Int>()
        val previews = mutableListOf<Int>()
        var cancellations = 0
        override fun start(amplitude: Int) { starts.add(amplitude) }
        override fun preview(amplitude: Int) { previews.add(amplitude) }
        override fun cancel() { cancellations++ }
    }

    private class Watchdog : RumbleWatchdog {
        data class Task(val at: Double, val timeout: () -> Unit, var cancelled: Boolean = false)
        var now = 1.0
        var closed = false
        val tasks = mutableListOf<Task>()
        override fun schedule(delayMillis: Long, timeout: () -> Unit): () -> Unit {
            check(!closed)
            val task = Task(now + delayMillis / 1000.0, timeout)
            tasks.add(task)
            return { task.cancelled = true }
        }
        fun advanceTo(time: Double) {
            while (true) {
                val task = tasks.filter { !it.cancelled && it.at <= time }.minByOrNull { it.at } ?: break
                now = task.at
                task.cancelled = true
                task.timeout()
            }
            now = time
        }
        override fun close() { closed = true }
    }

    private val output = Output()
    private val watchdog = Watchdog()
    private val rumble = GameRumble(output, watchdog) { watchdog.now }.apply { setActive(true) }
    private fun send(seq: Int, large: Int = 120, small: Int = 60, timestamp: Double = watchdog.now) {
        rumble.receive(Protocol.Rumble(seq, timestamp, large, small))
    }

    @Test fun steadyRumbleRunsForTenSecondsWithOneStart() {
        for (seq in 0..100) {
            watchdog.advanceTo(1.0 + seq * 0.1)
            send(seq)
            rumble.tick()
        }
        assertEquals(1, output.starts.size)
        assertEquals(0, output.cancellations)
        watchdog.advanceTo(11.251)
        assertEquals(1, output.cancellations)
    }

    @Test fun fluctuationsAndEquivalentMotorMixesDoNotRestartTheActuator() {
        for (seq in 0..20) {
            watchdog.advanceTo(1.0 + seq * 0.05)
            send(seq, large = 180 + seq % 3, small = 80)
            rumble.tick()
        }
        assertEquals(1, output.starts.size)
        rumble.reset()
        send(21, 255, 0)
        rumble.tick()
        watchdog.advanceTo(2.1)
        send(22, 255, 255)
        rumble.tick()
        assertEquals(2, output.starts.size)
    }

    @Test fun timeoutStopsRumbleEvenIfSenderNeverTicksAgain() {
        send(1)
        rumble.tick()
        watchdog.advanceTo(1.251)
        assertEquals(1, output.cancellations)
        rumble.tick()
        assertEquals(1, output.starts.size)
    }

    @Test fun delayedPacketKeepsItsOriginalExpiry() {
        watchdog.advanceTo(1.20)
        send(1, timestamp = 1.0)
        rumble.tick()
        watchdog.advanceTo(1.251)
        assertEquals(1, output.starts.size)
        assertEquals(1, output.cancellations)
    }

    @Test fun stopCommandCancelsImmediatelyWithoutWaitingForTick() {
        send(1)
        rumble.tick()
        watchdog.advanceTo(1.01)
        send(2, 0, 0)
        assertEquals(1, output.cancellations)
        assertTrue(watchdog.tasks.all { it.cancelled })
    }

    @Test fun duplicateOrOlderPacketsCannotExtendRumble() {
        send(10)
        rumble.tick()
        watchdog.advanceTo(1.20)
        send(10, timestamp = 1.0)
        send(9, timestamp = 1.0)
        watchdog.advanceTo(1.251)
        assertEquals(1, output.cancellations)
        send(11, timestamp = 1.0)
        rumble.tick()
        assertEquals(1, output.starts.size)
    }

    @Test fun alreadyRunningOldTimeoutCannotCancelNewFeedback() {
        send(1)
        rumble.tick()
        val oldTimeout = watchdog.tasks.last().timeout
        watchdog.advanceTo(1.1)
        send(2)
        oldTimeout() // Simulate cancellation racing with an already-running callback.
        assertEquals(0, output.cancellations)
        watchdog.advanceTo(1.351)
        assertEquals(1, output.cancellations)
    }

    @Test fun leavingDrivingStopsRumbleAndRejectsQueuedFeedback() {
        send(1)
        rumble.tick()
        watchdog.advanceTo(1.01)
        rumble.setActive(false)
        send(2)
        rumble.tick()
        assertEquals(1, output.starts.size)
        assertEquals(1, output.cancellations)
        watchdog.advanceTo(1.02)
        rumble.setActive(true)
        send(3, timestamp = 1.0)
        rumble.tick()
        assertEquals(1, output.starts.size)
        send(4)
        rumble.tick()
        assertEquals(2, output.starts.size)
    }

    @Test fun disablingOrClosingCancelsAndDisposesTheWatchdog() {
        send(1)
        rumble.tick()
        rumble.setEnabled(false)
        assertEquals(1, output.cancellations)
        send(2)
        rumble.tick()
        assertEquals(1, output.starts.size)
        rumble.setEnabled(true)
        send(3)
        rumble.tick()
        val oldTimeout = watchdog.tasks.last().timeout
        rumble.close()
        rumble.close()
        oldTimeout()
        send(4)
        rumble.tick()
        assertTrue(watchdog.closed)
        assertEquals(2, output.starts.size)
        assertEquals(2, output.cancellations)
    }

    @Test fun meaningfulStrengthChangesStillReachThePhone() {
        send(1, 20, 0)
        rumble.tick()
        watchdog.advanceTo(1.01)
        send(2, 220, 100)
        rumble.tick()
        assertEquals(2, output.starts.size)
        assertTrue(output.starts[1] > output.starts[0])
        assertEquals(0, output.cancellations)
    }

    @Test fun phoneWithoutAmplitudeControlRunsOneDefaultStrengthEffect() {
        val fixed = Output(amplitudeControl = false)
        val controller = GameRumble(fixed, watchdog) { watchdog.now }
        controller.setActive(true)
        for (seq in 1..10) {
            watchdog.advanceTo(1.0 + seq * 0.1)
            controller.receive(Protocol.Rumble(seq, watchdog.now, seq * 20, 0))
            controller.tick()
        }
        assertEquals(listOf(-1), fixed.starts)
        watchdog.advanceTo(2.251)
        assertEquals(1, fixed.cancellations)
    }

    @Test fun windowsBoostAppliesLiveAndOldPacketsCannotOverwriteIt() {
        rumble.receive(Protocol.Rumble(1, 1.0, 20, 0, 100))
        rumble.tick()
        watchdog.advanceTo(1.05)
        rumble.receive(Protocol.Rumble(2, 1.05, 20, 0, 250))
        rumble.tick()
        assertEquals(listOf(
            RumbleStrength.amplitude(RumbleMotors(20, 0), 100),
            RumbleStrength.amplitude(RumbleMotors(20, 0), 250),
        ), output.starts)
        // A reordered settings update must neither reset nor replace the new boost.
        rumble.receive(Protocol.Rumble(1, 1.0, 20, 0, 100))
        watchdog.advanceTo(1.10)
        rumble.tick()
        assertEquals(2, output.starts.size)
        assertEquals(0, output.cancellations)
        watchdog.advanceTo(1.301)
        assertEquals(1, output.cancellations)
    }

    @Test fun previewIsOneFiniteEffectAndStopsWhenLeavingTheScreen() {
        rumble.setActive(false)
        rumble.test()
        assertEquals(1, output.previews.size)
        assertEquals(0, output.starts.size)
        rumble.setActive(true)
        assertEquals(1, output.cancellations)
    }

    @Test fun previewSurvivesConnectedSenderTicksAndStopsAfterOneSecond() {
        rumble.setActive(false)
        rumble.test()
        for (step in 1..9) {
            watchdog.advanceTo(1.0 + step * 0.1)
            rumble.tick()
        }
        assertEquals(1, output.previews.size)
        assertTrue(output.starts.isEmpty())
        assertEquals(0, output.cancellations)
        watchdog.advanceTo(2.001)
        assertEquals(1, output.cancellations)
        rumble.tick()
        assertEquals(1, output.cancellations)
    }

    @Test fun previewUsesCurrentBoostAndRespectsGameRumbleToggle() {
        rumble.setActive(false)
        rumble.setBoost(100)
        rumble.test()
        assertEquals(listOf(RumbleStrength.amplitude(RumbleMotors(160, 100), 100)), output.previews)
        rumble.setBoost(250)
        assertEquals(1, output.cancellations)
        rumble.test()
        assertEquals(RumbleStrength.amplitude(RumbleMotors(160, 100), 250), output.previews.last())
        rumble.setEnabled(false)
        assertEquals(2, output.cancellations)
        rumble.test()
        assertEquals(2, output.previews.size)
        watchdog.advanceTo(3.0)
        assertEquals(2, output.cancellations)
    }
}

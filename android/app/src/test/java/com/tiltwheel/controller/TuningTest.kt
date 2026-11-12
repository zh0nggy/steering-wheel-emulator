package com.tiltwheel.controller

import java.nio.ByteBuffer
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class TuningTest {
    private val session = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")
    private val tuning = Tuning(rumbleBoost = 153)
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val windows = hex("5457545043310123456789abcdef0123456789abcdef000000000000000400000000000000092d050096009902")

    private fun session(sync: TuningSync): UUID {
        val buf = ByteBuffer.wrap(sync.packet()!!, 6, 16)
        return UUID(buf.long, buf.long)
    }

    private fun reply(sync: TuningSync, session: UUID, ack: Long, revision: Long, value: Tuning) {
        val buf = ByteBuffer.allocate(TuningProtocol.REPLY_SIZE)
            .put("TWTPC1".toByteArray()).putLong(session.mostSignificantBits).putLong(session.leastSignificantBits)
            .putLong(ack).putLong(revision)
        value.write(buf)
        assertTrue(sync.receive(buf.array(), buf.capacity()))
    }

    @Test fun wireMatchesPythonAndPreservesLegacyDrivingProtocol() {
        assertArrayEquals(hex("5457545048310123456789abcdef0123456789abcdef00000000000000042d050096009902"),
            TuningProtocol.request(session, 4, tuning))
        assertTrue(TuningProtocol.hello(session).size <= Protocol.PACKET_SIZE)
        assertEquals(TuningProtocol.Reply(session, 4, 9, tuning), TuningProtocol.reply(windows, windows.size))
        assertNull(TuningProtocol.reply(windows, windows.size - 1))
        assertNull(TuningProtocol.reply(windows.copyOf(10), windows.size))
        assertNull(TuningProtocol.reply(windows + byteArrayOf(0), windows.size + 1))
        for ((offset, value) in listOf(38 to 9, 38 to 91, 39 to 31, 40 to 255,
                42 to 91, 43 to 99, 43 to 251, 44 to 4)) {
            val invalid = windows.copyOf().apply { this[offset] = value.toByte() }
            assertNull(TuningProtocol.reply(invalid, invalid.size))
        }
    }

    @Test fun savedSettingsRoundTripAndRejectInvalidValues() {
        val value = tuning.copy(range = 90, deadzone = 30, gamma = 300, smooth = 90,
            rumbleBoost = 250, invert = true, gameRumble = false)
        assertEquals(value, Tuning.decode(value.encode()))
        for (invalid in listOf(null, "", "45,5,150,0,153", "45,5,150,0,153,4", "9,5,150,0,153,2")) {
            assertNull(Tuning.decode(invalid))
        }
    }

    @Test fun connectionLoadsExistingWindowsProfileAndTracksLaterWindowsEdits() {
        val sync = TuningSync()
        sync.connect()
        val current = session(sync)
        reply(sync, current, 0, 1, tuning)
        assertEquals(TuningSync.State(tuning, false), sync.state())
        val changed = tuning.copy(range = 70, invert = true)
        reply(sync, current, 0, 3, changed)
        reply(sync, current, 0, 2, tuning) // reordered PC snapshot
        assertEquals(changed, sync.state().tuning)
        assertArrayEquals(TuningProtocol.request(current, 0, changed), sync.packet())
    }

    @Test fun offlineEditsWaitForAcknowledgementAndRetryUntilAccepted() {
        val sync = TuningSync(tuning, pending = true)
        sync.connect()
        val current = session(sync)
        reply(sync, current, 0, 1, Tuning())
        assertEquals(TuningSync.State(tuning, true), sync.state())
        assertArrayEquals(TuningProtocol.request(current, 1, tuning), sync.packet())
        val changed = tuning.copy(gamma = 200, gameRumble = false)
        sync.edit(changed)
        reply(sync, current, 1, 2, tuning) // delayed acknowledgement of an earlier phone edit
        assertEquals(TuningSync.State(changed, true), sync.state())
        reply(sync, current, 2, 3, changed)
        assertEquals(TuningSync.State(changed, false), sync.state())
    }

    @Test fun pendingEditsSurviveReconnectionAndOldSessionRepliesAreIgnored() {
        val sync = TuningSync()
        sync.connect()
        val previous = session(sync)
        sync.edit(tuning)
        sync.disconnect()
        assertNull(sync.packet())
        sync.connect()
        val current = session(sync)
        reply(sync, previous, 1, 50, Tuning())
        assertEquals(TuningSync.State(tuning, true), sync.state())
        reply(sync, current, 0, 1, Tuning())
        assertArrayEquals(TuningProtocol.request(current, 1, tuning), sync.packet())
        reply(sync, current, 1, 2, tuning)
        assertFalse(sync.state().pending)
    }
}

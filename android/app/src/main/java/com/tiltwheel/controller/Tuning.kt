package com.tiltwheel.controller

import java.nio.ByteBuffer
import java.util.UUID

/** Slider units: degrees, hundredths for the three response controls, and percent. */
data class Tuning(
    val range: Int = 45,
    val deadzone: Int = 5,
    val gamma: Int = 150,
    val smooth: Int = 0,
    val rumbleBoost: Int = 150,
    val invert: Boolean = false,
    val gameRumble: Boolean = true,
) {
    private val flags get() = (if (invert) 1 else 0) or (if (gameRumble) 2 else 0)
    fun valid() = range in 10..90 && deadzone in 0..30 && gamma in 100..300 &&
        smooth in 0..90 && rumbleBoost in 100..250

    fun encode() = listOf(range, deadzone, gamma, smooth, rumbleBoost, flags).joinToString(",")

    internal fun write(buf: ByteBuffer) {
        buf.put(range.toByte()).put(deadzone.toByte()).putShort(gamma.toShort())
            .put(smooth.toByte()).put(rumbleBoost.toByte()).put(flags.toByte())
    }

    companion object {
        private fun from(values: List<Int>): Tuning? {
            if (values.size != 6 || values[5] !in 0..3) return null
            return Tuning(values[0], values[1], values[2], values[3], values[4],
                values[5] and 1 != 0, values[5] and 2 != 0).takeIf { it.valid() }
        }

        fun decode(value: String?): Tuning? {
            val values = value?.split(',')?.map { it.toIntOrNull() ?: return null } ?: return null
            return from(values)
        }

        internal fun read(buf: ByteBuffer): Tuning? = from(listOf(
            buf.get().toInt() and 0xFF, buf.get().toInt() and 0xFF,
            buf.short.toInt() and 0xFFFF, buf.get().toInt() and 0xFF,
            buf.get().toInt() and 0xFF, buf.get().toInt() and 0xFF,
        ))
    }
}

/** Binary twin of android_tuning.py; tuning never changes the driving-frame format. */
object TuningProtocol {
    private const val HELLO = "TWTHI1"
    private const val PHONE = "TWTPH1"
    private const val WINDOWS = "TWTPC1"
    private const val HEADER_SIZE = 6 + 16
    const val REPLY_SIZE = HEADER_SIZE + 8 + 8 + 7

    data class Reply(val session: UUID, val acknowledged: Long, val revision: Long, val tuning: Tuning)

    private fun header(magic: String, session: UUID, size: Int): ByteBuffer =
        ByteBuffer.allocate(size).apply {
            put(magic.toByteArray(Charsets.US_ASCII))
            putLong(session.mostSignificantBits).putLong(session.leastSignificantBits)
        }

    fun hello(session: UUID): ByteArray = header(HELLO, session, HEADER_SIZE).array()

    fun request(session: UUID, revision: Long, tuning: Tuning): ByteArray {
        require(revision >= 0 && tuning.valid())
        val buf = header(PHONE, session, HEADER_SIZE + 8 + 7).putLong(revision)
        tuning.write(buf)
        return buf.array()
    }

    fun reply(data: ByteArray, length: Int): Reply? {
        if (length != REPLY_SIZE || length > data.size ||
            String(data, 0, 6, Charsets.US_ASCII) != WINDOWS
        ) return null
        val buf = ByteBuffer.wrap(data, 6, length - 6)
        val session = UUID(buf.long, buf.long)
        val acknowledged = buf.long
        val revision = buf.long
        if (acknowledged < 0 || revision < 0) return null
        return Reply(session, acknowledged, revision, Tuning.read(buf) ?: return null)
    }
}

/** Owns pending offline edits and rejects replies superseded by newer phone/PC edits. */
class TuningSync(initial: Tuning = Tuning(), pending: Boolean = false) {
    data class State(val tuning: Tuning, val pending: Boolean)
    private var state = State(initial, pending)
    private var session: UUID? = null
    private var revision = 0L
    private var receivedRevision = -1L
    private var supported = false

    @Synchronized fun state(): State = state

    @Synchronized fun edit(tuning: Tuning) {
        require(tuning.valid())
        if (tuning == state.tuning) return
        state = State(tuning, true)
        revision++
    }

    @Synchronized fun connect() {
        session = UUID.randomUUID()
        revision = if (state.pending) 1 else 0
        receivedRevision = -1
        supported = false
    }

    @Synchronized fun disconnect() { session = null }

    @Synchronized fun packet(): ByteArray? {
        val current = session ?: return null
        return if (supported) TuningProtocol.request(current, revision, state.tuning)
            else TuningProtocol.hello(current)
    }

    @Synchronized fun receive(data: ByteArray, length: Int): Boolean {
        val reply = TuningProtocol.reply(data, length) ?: return false
        if (reply.session != session) return true
        supported = true
        if (reply.acknowledged == revision && reply.revision > receivedRevision) {
            receivedRevision = reply.revision
            state = State(reply.tuning, false)
        }
        return true
    }
}

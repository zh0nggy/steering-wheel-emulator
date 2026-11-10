package com.tiltwheel.controller

import java.util.UUID

/** Geometry and the separate layout channel; driving frames keep their v3 format. */
object ControlLayout {
    const val PHONE = "TWPHN2"
    const val WINDOWS = "TWPCV2"
    val targets = (Protocol.BUTTONS + listOf("LT", "RT")).toSet()
    private val sessionPattern = Regex("[a-f0-9]{32}")
    private val number = """-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"""
    private val row = Regex("""\[\s*"([A-Z_]+)"\s*,\s*($number)\s*,\s*($number)\s*,\s*($number)\s*,\s*($number)\s*\]""")

    data class Snapshot(val session: String, val revision: Long, val boxes: List<Protocol.Box>)

    fun newSession(): String = UUID.randomUUID().toString().replace("-", "")

    fun rows(boxes: List<Protocol.Box>): String = boxes.joinToString(",", "[", "]") {
        """["${it.target}",${it.x},${it.y},${it.w},${it.h}]"""
    }

    fun parseRows(value: String): List<Protocol.Box>? {
        val data = value.trim()
        if (data.length > 4096 || !data.startsWith("[") || !data.endsWith("]")) return null
        val body = data.substring(1, data.lastIndex).trim()
        if (body.isEmpty()) return emptyList()
        val boxes = ArrayList<Protocol.Box>()
        var end = 0
        for (match in row.findAll(body)) {
            val gap = body.substring(end, match.range.first).trim()
            if (gap != if (end == 0) "" else ",") return null
            val target = match.groupValues[1]
            if (target !in targets || boxes.any { it.target == target }) return null
            val (x, y, w, h) = (2..5).map { match.groupValues[it].toDoubleOrNull() ?: return null }
            if (!listOf(x, y, w, h).all { it.isFinite() } ||
                w !in 0.01..1.0 || h !in 0.01..1.0 || x < 0 || y < 0 ||
                x + w > 1.000001 || y + h > 1.000001
            ) return null
            boxes.add(Protocol.Box(target, x.coerceAtMost(1 - w), y.coerceAtMost(1 - h), w, h))
            end = match.range.last + 1
        }
        return boxes.takeIf { it.isNotEmpty() && body.substring(end).isBlank() }
    }

    fun pack(magic: String, snapshot: Snapshot): ByteArray =
        "$magic${snapshot.session}:${snapshot.revision}:${rows(snapshot.boxes)}".toByteArray()

    fun unpack(magic: String, bytes: ByteArray, size: Int): Snapshot? {
        if (size !in 1..4096) return null
        val data = String(bytes, 0, size, Charsets.UTF_8)
        if (!data.startsWith(magic)) return null
        val fields = data.substring(magic.length).split(':', limit = 3)
        if (fields.size != 3 || !sessionPattern.matches(fields[0])) return null
        val revision = fields[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        return Snapshot(fields[0], revision, parseRows(fields[2]) ?: return null)
    }

    /** Place additions in free slots, preserving the size and position of existing controls. */
    fun add(boxes: List<Protocol.Box>, target: String): List<Protocol.Box> {
        if (target !in targets || boxes.any { it.target == target }) return boxes
        val w = 0.14
        val h = 0.21
        val xSlots = listOf(0.02, 0.84, 0.18, 0.68, 0.34, 0.52)
        val ySlots = listOf(0.77, 0.51, 0.25, 0.02)
        val spots = ySlots.flatMap { y -> xSlots.map { x -> Protocol.Box(target, x, y, w, h) } }
        val free = spots.firstOrNull { spot ->
            boxes.none { b ->
                spot.x < b.x + b.w && b.x < spot.x + spot.w &&
                    spot.y < b.y + b.h && b.y < spot.y + spot.h
            }
        }
        return boxes + (free ?: spots[boxes.size % spots.size])
    }

    fun reset(boxes: List<Protocol.Box>): List<Protocol.Box> =
        boxes.fold(emptyList()) { placed, box -> add(placed, box.target) }
}

package com.tiltwheel.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ControlLayoutTest {
    private val session = "0123456789abcdef0123456789abcdef"

    @Test
    fun readsWindowsProposalAndPublishesMatchingSnapshot() {
        val rows = """[["RT",0.84,0.77,0.14,0.21],["DPAD_UP",0.02,0.77,0.14,0.21]]"""
        val wire = "TWPCV2$session:4:$rows".toByteArray()
        val snapshot = ControlLayout.unpack(ControlLayout.WINDOWS, wire, wire.size)!!
        assertEquals(4L, snapshot.revision)
        assertEquals(listOf("RT", "DPAD_UP"), snapshot.boxes.map { it.target })
        assertEquals("TWPHN2$session:5:$rows", ControlLayout.pack(
            ControlLayout.PHONE, snapshot.copy(revision = 5),
        ).decodeToString())
    }

    @Test
    fun invalidPayloadCannotBecomeAnEmptyOrPartialSavedLayout() {
        assertEquals(emptyList<Protocol.Box>(), ControlLayout.parseRows("[]"))
        assertNull(ControlLayout.parseRows("""[["A",0.1,0.1,0.2,0.2],["A",0,0,0.2,0.2]]"""))
        assertNull(ControlLayout.parseRows("""[["A",0.1,0.1,0.2,0.2],["RT",0.9,0,0.2,0.2]]"""))
        assertNull(ControlLayout.parseRows("""[["UNKNOWN",0,0,0.2,0.2]]"""))
        assertNull(ControlLayout.parseRows("""[["B",0,0,0.2,0.2],]"""))
    }

    @Test
    fun defaultSlotsCanPlaceEverySupportedControlWithoutOverlap() {
        val boxes = ControlLayout.targets.fold(emptyList<Protocol.Box>()) { placed, target ->
            ControlLayout.add(placed, target)
        }
        assertEquals(ControlLayout.targets, boxes.map { it.target }.toSet())
        assertEquals(boxes, ControlLayout.parseRows(ControlLayout.rows(boxes)))
        for ((i, a) in boxes.withIndex()) {
            assertFalse(boxes.drop(i + 1).any { b ->
                a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h
            })
        }
    }
}

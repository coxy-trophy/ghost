package com.coxy.ghost

import org.junit.Assert.*
import org.junit.Test

class ForegroundAccessTest {
    @Test fun `boot denial retains base foreground and no microphone access`() {
        val gate = ForegroundAccess(1)
        val calls = mutableListOf<Int>()
        gate.promote(listOf(2), { calls += it; if (it == 3) throw SecurityException("background") }, { _, _ -> })
        assertEquals(listOf(1, 3), calls)
        assertEquals(1, gate.accepted)
        assertFalse(gate.has(2))
    }

    @Test fun `later eligible interaction promotes without restarting base`() {
        val gate = ForegroundAccess(1)
        gate.promote(listOf(2), { if (it == 3) throw SecurityException() }, { _, _ -> })
        val calls = mutableListOf<Int>()
        gate.promote(listOf(2), { calls += it }, { _, _ -> fail() })
        assertEquals(listOf(3), calls)
        assertTrue(gate.has(2))
        gate.promote(listOf(2), { fail("already accepted") }, { _, _ -> fail() })
    }

    @Test fun `denied camera does not remove accepted microphone or prevent location`() {
        val gate = ForegroundAccess(1)
        gate.promote(listOf(2, 4, 8), { if (it and 4 != 0) throw SecurityException() }, { _, _ -> })
        assertTrue(gate.has(2))
        assertTrue(gate.has(8))
        assertFalse(gate.has(4))
        assertEquals(11, gate.accepted)
    }

    @Test fun `base failure propagates with no accepted access`() {
        val gate = ForegroundAccess(1)
        try {
            gate.promote(listOf(2), { throw SecurityException("base denied") }, { _, _ -> fail() })
            fail("base failure swallowed")
        } catch (_: SecurityException) { assertEquals(0, gate.accepted) }
    }
}

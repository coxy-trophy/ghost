package com.coxy.ghost.notifications

import org.junit.Assert.*
import org.junit.Test

class EphemeralSlotTest {
    @Test fun `handoff is single use`() {
        val slot = EphemeralSlot<String>()
        slot.put("one", "private", 10)
        assertEquals("private", slot.take("one", 9))
        assertNull(slot.take("one", 9))
    }
    @Test fun `wrong token does not consume another request`() {
        val slot = EphemeralSlot<String>()
        slot.put("one", "private", 10)
        assertNull(slot.take("other", 1))
        assertNull(slot.take(null, 1))
        assertEquals("private", slot.take("one", 2))
    }
    @Test fun `expired and cancelled handoffs reveal nothing`() {
        val slot = EphemeralSlot<String>()
        slot.put("one", "private", 10)
        assertNull(slot.take("one", 10))
        slot.put("two", "private", 20)
        slot.discard("two")
        assertNull(slot.take("two", 11))
    }
    @Test fun `replacement drops old content without stale cleanup removing new content`() {
        val slot = EphemeralSlot<String>()
        slot.put("old", "old private", 10)
        slot.put("new", "new private", 20)
        slot.discard("old")
        assertNull(slot.take("old", 2))
        assertEquals("new private", slot.take("new", 2))
    }
}

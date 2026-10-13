package com.coxy.ghost.action

import org.junit.Assert.*
import org.junit.Test

class PersonalToolPresentationTest {
    @Test fun `personal tool status is readable without altering memory source`() {
        val raw = "I'll check.\n[ACTION fail read_notifications: Enable local access.]"
        assertEquals("I'll check.\nEnable local access.", ActionRunner.forDisplay(raw))
        assertEquals("I'll check.", ActionRunner.withoutActionLines(raw))
    }
    @Test fun `contact status receives same presentation without rewriting other actions`() {
        val raw = "[ACTION ok lookup_contact: Opened local lookup.]\n[ACTION ok toggle_torch: on]"
        assertEquals("Opened local lookup.\n[ACTION ok toggle_torch: on]", ActionRunner.forDisplay(raw))
    }
}

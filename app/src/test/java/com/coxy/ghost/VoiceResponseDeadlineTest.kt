package com.coxy.ghost

import org.junit.Assert.*
import org.junit.Test

class VoiceResponseDeadlineTest {
    @Test fun `silent native work expires without waiting for its completion`() {
        val deadline = VoiceResponseDeadline(1000)
        assertFalse(deadline.expired(30_999))
        assertTrue(deadline.expired(31_000))
    }
    @Test fun `first response extends wait only to overall limit`() {
        val deadline = VoiceResponseDeadline(1000)
        deadline.response()
        assertFalse(deadline.expired(31_000))
        assertFalse(deadline.expired(90_999))
        assertTrue(deadline.expired(91_000))
    }
    @Test fun `continuous output cannot extend overall limit`() {
        val deadline = VoiceResponseDeadline(0)
        repeat(100) { deadline.response() }
        assertTrue(deadline.expired(90_000))
    }
    @Test fun `completed response cannot time out during speech playback`() {
        val deadline = VoiceResponseDeadline(0)
        deadline.finish()
        assertFalse(deadline.expired(1_000_000))
    }
}

package com.coxy.ghost.perception.wakeword

import org.junit.Assert.*
import org.junit.Test

class WakeDiagnosticsTest {
    @Test fun `silent input is distinguishable from gated speech`() {
        val d = WakeDiagnostics()
        d.read(shortArrayOf(0, 0), 2, null)
        val silent = d.snapshot()
        assertEquals(0, silent.rms)
        assertEquals(0L, silent.nonzero)
        assertEquals(2L, silent.fed)
        d.read(shortArrayOf(1000, -1000), 2, "pocket")
        val blocked = d.snapshot()
        assertEquals(1000, blocked.rms)
        assertEquals(0L, blocked.fed)
        assertEquals(mapOf("pocket" to 2L), blocked.suppressed)
    }
    @Test fun `full scale negative sample does not overflow and unread tail is ignored`() {
        val d = WakeDiagnostics()
        d.read(shortArrayOf(Short.MIN_VALUE, 1), 1, null)
        val s = d.snapshot()
        assertEquals(32768, s.peak)
        assertEquals(32768, s.rms)
        assertEquals(1L, s.samples)
    }
    @Test fun `aggregate windows reset and do not retain mutable audio`() {
        val d = WakeDiagnostics()
        val input = shortArrayOf(200, -200)
        d.read(input, 2, "warmup")
        input.fill(0)
        d.decoded(); d.detected()
        val first = d.snapshot()
        assertEquals(200, first.rms)
        assertEquals(1L, first.decoded)
        assertEquals(1L, first.hits)
        val next = d.snapshot()
        assertEquals(0L, next.samples)
        assertEquals(0L, next.hits)
        assertTrue(next.suppressed.isEmpty())
        assertEquals(mapOf("warmup" to 2L), first.suppressed)
    }
    @Test fun `all input is accounted for across suppression transitions`() {
        val d = WakeDiagnostics()
        d.read(shortArrayOf(1, 2), 2, "voice_busy")
        d.read(shortArrayOf(3, 4), 2, "warmup")
        d.read(shortArrayOf(5, 6), 2, null)
        val s = d.snapshot()
        assertEquals(s.samples, s.fed + s.suppressed.values.sum())
        assertEquals(3L, s.reads)
        assertEquals(6L, s.nonzero)
    }
}

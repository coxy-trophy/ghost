package com.coxy.ghost.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the summon decision.
 *
 * The overlay/window itself is device-only, but the choice of show-vs-reopen-vs-ignore is pure and
 * is exactly where a burst of triggers (the volume-key gesture and the wake word landing together)
 * would otherwise stack windows or thrash the mic. Pinned here so that decision is provable on the
 * Mac in milliseconds, the same split [com.coxy.ghost.perception.wakeword.WakeTrigger] already makes.
 */
class SummonControllerTest {

    private val t0 = 1_000_000L

    @Test
    fun `first summon shows and records the source`() {
        val c = SummonController(cooldownMs = 600L)
        assertEquals(SummonDecision.SHOW, c.onSummon(SummonSource.TILE, t0))
        assertTrue(c.showing)
        assertEquals(SummonSource.TILE, c.currentSource)
    }

    @Test
    fun `a second trigger in the same burst is ignored, not a second window`() {
        val c = SummonController(cooldownMs = 600L)
        assertEquals(SummonDecision.SHOW, c.onSummon(SummonSource.KEY, t0))
        // The wake word lands 100 ms later — inside the debounce window.
        assertEquals(SummonDecision.IGNORE, c.onSummon(SummonSource.WAKE, t0 + 100))
        // The source stays the one that actually raised the overlay.
        assertEquals(SummonSource.KEY, c.currentSource)
    }

    @Test
    fun `a summon while already showing re-opens the mic rather than restacking`() {
        val c = SummonController(cooldownMs = 600L)
        c.onSummon(SummonSource.ASSIST, t0)
        assertEquals(SummonDecision.REOPEN, c.onSummon(SummonSource.ASSIST, t0 + 5_000))
        assertTrue(c.showing)
    }

    @Test
    fun `after dismissal a fresh summon shows again`() {
        val c = SummonController(cooldownMs = 600L)
        c.onSummon(SummonSource.TILE, t0)
        c.onDismissed(t0 + 10_000)
        assertFalse(c.showing)
        assertNull(c.currentSource)
        // A re-summon after the cooldown from dismissal is a fresh SHOW.
        assertEquals(SummonDecision.SHOW, c.onSummon(SummonSource.WAKE, t0 + 11_000))
        assertEquals(SummonSource.WAKE, c.currentSource)
    }

    @Test
    fun `an echo trigger right after dismissal is swallowed`() {
        val c = SummonController(cooldownMs = 600L)
        c.onSummon(SummonSource.KEY, t0)
        c.onDismissed(t0 + 1_000)
        // A stray key-up or a duplicate assist delivery arriving 50 ms after dismissal.
        assertEquals(SummonDecision.IGNORE, c.onSummon(SummonSource.KEY, t0 + 1_050))
        assertFalse(c.showing)
    }

    @Test
    fun `unknown source strings fall back to shell`() {
        assertEquals(SummonSource.SHELL, SummonSource.fromString(null))
        assertEquals(SummonSource.SHELL, SummonSource.fromString("nonsense"))
        assertEquals(SummonSource.WAKE, SummonSource.fromString("wake"))
        assertEquals(SummonSource.NOTIFICATION, SummonSource.fromString("notif"))
    }
}

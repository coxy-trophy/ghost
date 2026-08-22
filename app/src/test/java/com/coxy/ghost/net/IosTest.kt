package com.coxy.ghost.net

import com.coxy.ghost.action.ActionCatalog
import com.coxy.ghost.net.ios.IosPhone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An iPhone can only do what Coxy built a Shortcut for, so the thing worth testing is that GHOST
 * never claims otherwise: an unregistered action must be refused here, not posted at a relay that
 * accepts it and silently does nothing.
 */
class IosTest {

    private val phone = IosPhone(
        id = 1,
        nickname = "my iPhone",
        relayUrl = "https://api.pushcut.io/SECRET/notifications/{action}",
        actions = "play\npause\nnext\nvolume up\nwhat's playing\nring\nbattery",
        addedMs = 0
    )

    @Test
    fun `a registered action resolves to its registered spelling`() {
        assertEquals("play", phone.resolveAction("play"))
        assertEquals("volume up", phone.resolveAction("Volume Up"))
        assertEquals("what's playing", phone.resolveAction("whats playing"))
    }

    @Test
    fun `case, spacing and punctuation are not part of an action name`() {
        // He types "Volume Up", the Shortcut is called "volume up", and neither should have to win.
        assertEquals("volume up", phone.resolveAction("VOLUMEUP"))
        assertEquals("what's playing", phone.resolveAction("WHATS-PLAYING"))
    }

    @Test
    fun `a prefix is enough once it is long enough to mean something`() {
        assertEquals("battery", phone.resolveAction("batt"))
        assertNull("two characters could be anything", phone.resolveAction("ba"))
    }

    @Test
    fun `an action that was never registered resolves to nothing`() {
        // The important one. A Shortcut that does not exist fails silently on the far phone, so
        // "I sent it" would be a lie with nothing to contradict it.
        assertNull(phone.resolveAction("send a text to mum"))
        assertNull(phone.resolveAction("reboot"))
        assertNull(phone.resolveAction(""))
    }

    @Test
    fun `a phone with no actions registered can do nothing`() {
        val fresh = phone.copy(actions = "")
        assertTrue(fresh.actionSet().isEmpty())
        assertNull(fresh.resolveAction("play"))
    }

    @Test
    fun `blank lines in the action list are not actions`() {
        val messy = phone.copy(actions = "play\n\n  \npause\n")
        assertEquals(listOf("play", "pause"), messy.actionSet())
    }

    @Test
    fun `asking is read-only and controlling is not`() {
        assertEquals(
            com.coxy.ghost.action.ActionTier.TIER_0_READ_ONLY,
            ActionCatalog.lookup("iphone_ask")?.tier
        )
        assertEquals(
            com.coxy.ghost.action.ActionTier.TIER_0_READ_ONLY,
            ActionCatalog.lookup("iphone_actions")?.tier
        )
        assertEquals(
            com.coxy.ghost.action.ActionTier.TIER_2_DISRUPTIVE,
            ActionCatalog.lookup("iphone_control")?.tier
        )
    }

    @Test
    fun `every iphone action names its phone, and the two that act name a command`() {
        for (id in listOf("iphone_control", "iphone_ask", "iphone_actions")) {
            assertTrue(
                "$id must require a device",
                ActionCatalog.validate(id, mapOf()).any { it.contains("device") }
            )
        }
        for (id in listOf("iphone_control", "iphone_ask")) {
            assertTrue(
                "$id must require a command",
                ActionCatalog.validate(id, mapOf()).any { it.contains("command") }
            )
        }
        // Listing what a phone can do needs no command, or he could not ask before knowing.
        assertTrue(
            ActionCatalog.validate("iphone_actions", mapOf("device" to "my iPhone")).isEmpty()
        )
    }

    @Test
    fun `the relay url carries the action where a provider wants it in the path`() {
        // Pushcut names the notification in the URL; Home Assistant puts everything in the body.
        // Supporting both is what keeps this from being a one-vendor integration.
        assertTrue(phone.relayUrl.contains("{action}"))
        assertEquals(
            "https://api.pushcut.io/SECRET/notifications/play",
            phone.relayUrl.replace("{action}", "play")
        )
    }
}

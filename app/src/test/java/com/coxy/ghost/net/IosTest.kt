package com.coxy.ghost.net

import com.coxy.ghost.action.ActionCatalog
import com.coxy.ghost.net.ios.IosPhone
import com.coxy.ghost.perception.toPromptBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/**
 * Her voice, and the rule that stopped her narrating her own senses.
 */
class VoiceAndSensesTest {

    @Test
    fun `instrumentation is never read aloud`() {
        // "[ACTION ok launch_app: opened WhatsApp]" spoken out loud is GHOST narrating her own
        // plumbing — the same reason those lines are kept out of her memory.
        val said = com.coxy.ghost.perception.GhostVoice.strip(
            "Opening it now.\n[ACTION ok launch_app: opened WhatsApp]"
        )
        assertEquals("Opening it now.", said)
    }

    @Test
    fun `context and memory blocks are never read aloud either`() {
        assertEquals(
            "It's about four.",
            com.coxy.ghost.perception.GhostVoice.strip("[CONTEXT activity=still] It's about four.")
        )
    }

    @Test
    fun `markdown emphasis is not spelled out`() {
        // A speech engine reads asterisks as characters.
        assertEquals(
            "Kotlin 2.4.10 is the stable one.",
            com.coxy.ghost.perception.GhostVoice.strip("**Kotlin 2.4.10** is the `stable` one.")
        )
    }

    @Test
    fun `a saved-device note is not read aloud`() {
        assertEquals(
            "Got it.",
            com.coxy.ghost.perception.GhostVoice.strip("Got it.\n(Saved: 192.168.100.24 is \"TV\".)")
        )
    }

    @Test
    fun `an ordinary reply is spoken as written`() {
        val reply = "Two point four ten, if you want the safe one."
        assertEquals(reply, com.coxy.ghost.perception.GhostVoice.strip(reply))
    }

    @Test
    fun `nothing to say produces nothing to speak`() {
        assertEquals("", com.coxy.ghost.perception.GhostVoice.strip("[ACTION ok a: b]"))
        assertEquals("", com.coxy.ghost.perception.GhostVoice.strip("   "))
    }

    @Test
    fun `the senses block carries its own rule when it is sent`() {
        // The instruction has to travel with the data. Forty paragraphs earlier in the system
        // prompt did not work — she opened replies with how long he had been sitting still.
        val state = com.coxy.ghost.perception.ContextState()
        val block = state.toPromptBlock(com.coxy.ghost.perception.GhostPrompts.CONTEXT_RULE)
        assertTrue(block.contains("do not mention unless asked"))
        assertTrue("the rule belongs inside the block", block.endsWith("]"))
    }

    @Test
    fun `the rule is not part of what the block is judged to say`() {
        // hasSignal() and the block are derived from one pass so that "worth sending" and "has
        // something to say" cannot drift. Text that is always present would break that.
        val state = com.coxy.ghost.perception.ContextState()
        assertFalse(state.toPromptBlock().contains("do not mention"))
    }
}

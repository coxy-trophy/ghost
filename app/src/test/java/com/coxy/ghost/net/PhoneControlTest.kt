package com.coxy.ghost.net

import com.coxy.ghost.action.ActionCatalog
import com.coxy.ghost.net.adb.PhoneControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneControlTest {

    @Test
    fun `everyday words reach the right control`() {
        assertEquals(PhoneControl.Command.PLAY_PAUSE, PhoneControl.Command.forWord("pause"))
        assertEquals(PhoneControl.Command.NEXT, PhoneControl.Command.forWord("skip"))
        assertEquals(PhoneControl.Command.VOLUME_UP, PhoneControl.Command.forWord("louder"))
        assertEquals(PhoneControl.Command.VOLUME_DOWN, PhoneControl.Command.forWord("turn it down"))
        assertEquals(PhoneControl.Command.RING, PhoneControl.Command.forWord("find"))
        assertEquals(PhoneControl.Command.LOCK, PhoneControl.Command.forWord("lock"))
    }

    @Test
    fun `case and spacing are not part of a command`() {
        assertEquals(PhoneControl.Command.PLAY_PAUSE, PhoneControl.Command.forWord("Play/Pause"))
        assertEquals(PhoneControl.Command.VOLUME_UP, PhoneControl.Command.forWord("VOLUME UP"))
    }

    @Test
    fun `an unknown word is not silently some other control`() {
        // Nothing here should ever guess: the effect lands on a phone in another room.
        assertNull(PhoneControl.Command.forWord("factory reset"))
        assertNull(PhoneControl.Command.forWord("banana"))
        assertNull(PhoneControl.Command.forWord(""))
    }

    @Test
    fun `reads are separated from things that change the phone`() {
        assertFalse(PhoneControl.Command.BATTERY.changesThings)
        assertFalse(PhoneControl.Command.WHATS_PLAYING.changesThings)
        assertFalse(PhoneControl.Command.STATUS.changesThings)
        assertTrue(PhoneControl.Command.LOCK.changesThings)
        assertTrue(PhoneControl.Command.RING.changesThings)
    }

    @Test
    fun `there is no command that runs arbitrary shell`() {
        // The whole safety model. A debug-bridge connection can run anything; the model picks from
        // this list and never composes a command line, because a model that can write shell is a
        // model that can be talked into writing shell by a page it just read.
        val names = PhoneControl.Command.entries.map { it.name.lowercase() }
        for (banned in listOf("shell", "exec", "run", "command", "su", "root")) {
            assertFalse("no '$banned' command may exist", names.contains(banned))
        }
    }

    @Test
    fun `every phone action names its device`() {
        for (id in listOf("phone_control", "phone_launch", "phone_status")) {
            assertTrue(
                "$id must require a device",
                ActionCatalog.validate(id, mapOf()).any { it.contains("device") }
            )
        }
    }

    @Test
    fun `reading a phone's state is tier 0 and changing it is not`() {
        assertEquals(
            com.coxy.ghost.action.ActionTier.TIER_0_READ_ONLY,
            ActionCatalog.lookup("phone_status")?.tier
        )
        assertEquals(
            com.coxy.ghost.action.ActionTier.TIER_2_DISRUPTIVE,
            ActionCatalog.lookup("phone_control")?.tier
        )
    }

    @Test
    fun `a debuggable device is recognisable as a phone`() {
        val phone = KnownDevice(
            id = 1, networkId = 1, stableKey = "mac:aa:bb:cc:dd:ee:ff", ip = "192.168.100.50",
            services = AdbProbe.SERVICE, firstSeenMs = 0, lastSeenMs = 0
        )
        assertTrue(phone.isPhone)
        assertFalse(phone.isAndroidTv)
        assertFalse(
            KnownDevice(
                id = 2, networkId = 1, stableKey = "ip:1", ip = "1", firstSeenMs = 0, lastSeenMs = 0
            ).isPhone
        )
    }
}

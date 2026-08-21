package com.coxy.ghost.net

import com.coxy.ghost.action.ActionRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of remembering a network that are pure: keys, capability inference, staleness, and the
 * instrumentation strip that stopped memory poisoning itself.
 */
class NetworkMemoryTest {

    private val tv = KnownDevice(
        id = 1, networkId = 1, stableKey = "mac:44:0f:b4:00:00:01",
        ip = "192.168.100.24", mac = "44:0f:b4:00:00:01",
        name = "Smart-TV-b2ad128c479835a5e8bd00857b54fc5b", nickname = "IT office TV", kind = "casts",
        services = "_googlecast._tcp _androidtvremote2._tcp urn:schemas-upnp-org:service:AVTransport:1",
        descriptionUrls = "http://192.168.100.24:8008/ssdp/device-desc.xml\nhttp://192.168.100.24:16246",
        firstSeenMs = 0, lastSeenMs = 0
    )
    private val anon = KnownDevice(
        id = 2, networkId = 1, stableKey = "ip:192.168.100.41",
        ip = "192.168.100.41", firstSeenMs = 0, lastSeenMs = 0
    )

    // --- identity ----------------------------------------------------------------------------------

    @Test
    fun `a device with a mac is keyed by it, not by an address that can be reassigned`() {
        assertEquals(
            "mac:44:0f:b4:00:00:01",
            NetworkMemory.stableKey(LanDevice("192.168.100.24", mac = "44:0f:b4:00:00:01"))
        )
    }

    @Test
    fun `a device with no mac falls back to its address`() {
        assertEquals("ip:192.168.100.41", NetworkMemory.stableKey(LanDevice("192.168.100.41")))
    }

    @Test
    fun `mac keys are case-insensitive so one device is not two rows`() {
        assertEquals(
            NetworkMemory.stableKey(LanDevice("1.2.3.4", mac = "AA:BB:CC:DD:EE:FF")),
            NetworkMemory.stableKey(LanDevice("1.2.3.4", mac = "aa:bb:cc:dd:ee:ff"))
        )
    }

    @Test
    fun `a network is labelled by his name, then its ssid, then the subnet`() {
        val base = KnownNetwork(
            id = 1, gatewayMac = "aa:bb:cc:11:22:33", subnet = "192.168.100.0/24",
            firstSeenMs = 0, lastSeenMs = 0
        )
        assertEquals("192.168.100.0/24", base.label)
        assertEquals("OfficeWiFi", base.copy(ssid = "OfficeWiFi").label)
        assertEquals("the office", base.copy(ssid = "OfficeWiFi", nickname = "the office").label)
    }

    // --- capabilities ------------------------------------------------------------------------------

    @Test
    fun `capabilities are read from what the device advertised`() {
        assertTrue(tv.isAndroidTv)
        assertTrue(tv.canControlMedia)
        assertTrue(tv.canWake)
        assertEquals("IT office TV", tv.bestName)
    }

    @Test
    fun `a device that advertised nothing claims nothing`() {
        assertFalse(anon.isAndroidTv)
        assertFalse(anon.canControlMedia)
        assertFalse(anon.canWake)
        assertFalse(anon.isIdentified)
        assertNull(anon.bestName)
    }

    @Test
    fun `both description urls survive the round trip through storage`() {
        // Keeping only one is what made a television refuse playback it plainly supported.
        assertEquals(2, tv.urlSet().size)
        assertTrue(tv.urlSet().contains("http://192.168.100.24:16246"))
    }

    // --- recollection vs sighting ------------------------------------------------------------------

    @Test
    fun `a remembered device is marked stale and a live one is not`() {
        assertTrue(NetworkMemory.asLanDevice(tv).isStale)
        assertFalse(LanDevice("192.168.100.24").isStale)
    }

    @Test
    fun `a live sighting beats a recollection when the two merge`() {
        val recalled = NetworkMemory.asLanDevice(tv)
        val live = LanDevice("192.168.100.24", mac = "44:0f:b4:00:00:01", kind = "casts")
        assertFalse("merging with a live sighting must clear staleness",
            LanDevice.merge(listOf(live, recalled)).first().isStale)
    }

    @Test
    fun `a recalled device keeps its name and its control urls`() {
        val d = NetworkMemory.asLanDevice(tv)
        assertEquals("IT office TV", d.nickname)
        assertEquals(2, d.descriptionUrls.size)
        assertTrue(d.services.contains("_androidtvremote2._tcp"))
    }

    // --- the memory poisoning fix --------------------------------------------------------------------

    @Test
    fun `action lines are not remembered as things she said`() {
        val reply = """Turning it off now.
            |[ACTION fail tv_key: the television never became ready for commands]""".trimMargin()
        val kept = ActionRunner.withoutActionLines(reply)
        assertEquals("Turning it off now.", kept)
    }

    @Test
    fun `a successful action line goes too, since the prose already says it`() {
        val reply = "Opening it.\n[ACTION ok launch_app: opened WhatsApp]"
        assertEquals("Opening it.", ActionRunner.withoutActionLines(reply))
    }

    @Test
    fun `several action lines all go, and the gap they leave closes`() {
        val reply = "Right.\n[ACTION ok a: x]\n[ACTION fail b: y]\n[ACTION pending c: z]"
        assertEquals("Right.", ActionRunner.withoutActionLines(reply))
    }

    @Test
    fun `an ordinary reply is untouched`() {
        val reply = "It's about four. You've been sitting in the dark for an hour."
        assertEquals(reply, ActionRunner.withoutActionLines(reply))
    }

    @Test
    fun `a reply that mentions an action in prose keeps it`() {
        // Only the executor's own bracketed lines go, not any sentence containing the word.
        val reply = "The action failed because the TV was asleep."
        assertEquals(reply, ActionRunner.withoutActionLines(reply))
    }
}

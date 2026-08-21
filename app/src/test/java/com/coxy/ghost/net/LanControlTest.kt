package com.coxy.ghost.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Targeting and protocol shaping, against the devices this network actually carries: three things
 * that answer to "TV", one printer, one router, one Windows renderer.
 */
class LanControlTest {

    private val tv24 = LanDevice(
        "192.168.100.24", name = "Smart-TV-b2ad128c479835a5e8bd00857b54fc5b", kind = "casts",
        services = setOf("_googlecast._tcp", "urn:schemas-upnp-org:device:MediaRenderer:1"),
        descriptionUrl = "http://192.168.100.24:8008/ssdp/device-desc.xml"
    )
    private val tv25 = LanDevice(
        "192.168.100.25", name = "Smart-TV-bfc60fb207f8fd3a70df58fb3a9aad44", kind = "casts",
        services = setOf("_googlecast._tcp", "_airplay._tcp")
    )
    private val tv98 = LanDevice(
        "192.168.100.98", name = "Smart-TV-4df02b09600928db702d41131980b458", kind = "casts",
        descriptionUrl = "http://192.168.100.98:8008/ssdp/device-desc.xml"
    )
    private val printer = LanDevice(
        "169.254.95.112", name = "HP Color LaserJet MFP M283fdw (5C3264)", kind = "printer"
    )
    private val router = LanDevice("192.168.100.22", name = "devices/1.6.19", kind = "router")
    private val pc = LanDevice(
        "192.168.100.26", name = "UPnP-Device-Host/1.0", kind = "media player",
        mac = "08:8e:90:00:00:01"
    )
    private val anon = LanDevice("192.168.100.41", mac = "1e:94:2b:00:00:01")

    private val network = listOf(tv24, tv25, tv98, printer, router, pc, anon)

    // --- targeting --------------------------------------------------------------------------------

    @Test
    fun `three televisions answering to 'the TV' is a question, never a pick`() {
        // The whole safety model: UPnP has no auth, so a guess reaches a real device silently.
        val r = DeviceMatcher.resolve("the TV", network)
        assertTrue("expected ambiguity, got $r", r is DeviceMatcher.Resolution.Ambiguous)
        assertEquals(3, (r as DeviceMatcher.Resolution.Ambiguous).candidates.size)
    }

    @Test
    fun `an address names exactly one device`() {
        val r = DeviceMatcher.resolve("192.168.100.98", network)
        assertEquals(tv98, (r as DeviceMatcher.Resolution.Match).device)
    }

    @Test
    fun `a distinctive piece of the name is enough`() {
        val r = DeviceMatcher.resolve("4df02b09", network)
        assertEquals(tv98, (r as DeviceMatcher.Resolution.Match).device)
    }

    @Test
    fun `everyday words reach things whose real names nobody would say`() {
        assertEquals(printer, (DeviceMatcher.resolve("the printer", network)
            as DeviceMatcher.Resolution.Match).device)
        assertEquals(router, (DeviceMatcher.resolve("router", network)
            as DeviceMatcher.Resolution.Match).device)
    }

    @Test
    fun `a name matching nothing offers what is actually there`() {
        val r = DeviceMatcher.resolve("the fridge", network)
        assertTrue(r is DeviceMatcher.Resolution.None)
        assertTrue("should offer identified devices", (r as DeviceMatcher.Resolution.None).near.isNotEmpty())
        assertTrue(r.near.none { !it.isIdentified })
    }

    @Test
    fun `an unidentified device is never offered as a suggestion`() {
        val r = DeviceMatcher.resolve("zzzz", network) as DeviceMatcher.Resolution.None
        assertTrue(r.near.none { it.ip == anon.ip })
    }

    // --- UPnP shaping ------------------------------------------------------------------------------

    @Test
    fun `a relative control url resolves against the description origin`() {
        assertEquals(
            "http://192.168.100.24:8008/AVTransport/control",
            UpnpControl.resolve(
                "/AVTransport/control", null, "http://192.168.100.24:8008/ssdp/device-desc.xml"
            )
        )
    }

    @Test
    fun `URLBase overrides the description origin when present`() {
        // Getting this wrong sends every command to the right device on the wrong path, which
        // fails as a 404 rather than as anything naming the cause.
        assertEquals(
            "http://192.168.100.26:2869/upnp/control",
            UpnpControl.resolve(
                "upnp/control", "http://192.168.100.26:2869/", "http://192.168.100.26:80/desc.xml"
            )
        )
    }

    @Test
    fun `an absolute control url is left alone`() {
        assertEquals(
            "http://10.0.0.5:1400/MediaRenderer/AVTransport/Control",
            UpnpControl.resolve(
                "http://10.0.0.5:1400/MediaRenderer/AVTransport/Control", "http://x/", "http://y/d.xml"
            )
        )
    }

    @Test
    fun `a value can be read back out of a SOAP response`() {
        val body = "<s:Envelope><s:Body><u:GetVolumeResponse><CurrentVolume>17</CurrentVolume>" +
            "</u:GetVolumeResponse></s:Body></s:Envelope>"
        assertEquals("17", UpnpControl.valueIn(body, "CurrentVolume"))
        assertNull(UpnpControl.valueIn(body, "CurrentMute"))
    }

    // --- DIAL ---------------------------------------------------------------------------------------

    @Test
    fun `spoken app names become the registered DIAL names`() {
        // The namespace is case-sensitive: a TV wants YouTube, not youtube.
        assertEquals("YouTube", DialControl.canonicalName("youtube"))
        assertEquals("YouTube", DialControl.canonicalName("YouTube"))
        assertEquals("PrimeVideo", DialControl.canonicalName("amazon prime"))
        assertEquals("Netflix", DialControl.canonicalName("netflix"))
    }

    @Test
    fun `an unknown app keeps its case rather than being mangled`() {
        assertEquals("Plex", DialControl.canonicalName("Plex"))
    }

    // --- Wake-on-LAN ---------------------------------------------------------------------------------

    @Test
    fun `a magic packet is six FFs then the mac sixteen times`() {
        val mac = WakeOnLan.parseMac("08:8e:90:00:00:01")!!
        val packet = WakeOnLan.magicPacket(mac)
        assertEquals(102, packet.size)
        assertTrue(packet.take(6).all { it == 0xFF.toByte() })
        for (i in 0 until 16) {
            assertEquals(mac.toList(), packet.slice(6 + i * 6 until 12 + i * 6))
        }
    }

    @Test
    fun `dashes are as good as colons and rubbish is rejected`() {
        assertTrue(WakeOnLan.parseMac("08-8e-90-fe-c1-3a") != null)
        assertNull(WakeOnLan.parseMac("08:8e:90:fe:c1"))
        assertNull(WakeOnLan.parseMac("not a mac"))
        assertNull(WakeOnLan.parseMac("08:8e:90:fe:c1:zz"))
    }

    // --- catalogue ---------------------------------------------------------------------------------

    @Test
    fun `every network control demands a named device`() {
        for (id in LanExecutor.ACTION_IDS) {
            val errors = com.coxy.ghost.action.ActionCatalog.validate(id, mapOf())
            assertTrue("$id must require a device", errors.any { it.contains("device") })
        }
    }
}

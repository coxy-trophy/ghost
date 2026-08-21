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
        descriptionUrls = setOf("http://192.168.100.24:8008/ssdp/device-desc.xml")
    )
    private val tv25 = LanDevice(
        "192.168.100.25", name = "Smart-TV-bfc60fb207f8fd3a70df58fb3a9aad44", kind = "casts",
        services = setOf("_googlecast._tcp", "_airplay._tcp")
    )
    private val tv98 = LanDevice(
        "192.168.100.98", name = "Smart-TV-4df02b09600928db702d41131980b458", kind = "casts",
        descriptionUrls = setOf("http://192.168.100.98:8008/ssdp/device-desc.xml")
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
    fun `every network control demands a named device, bar the one that cannot`() {
        // pair_code is the single exception and an intentional one: the code belongs to whichever
        // pairing is already open, and there is exactly one at a time. Asking Coxy to name the
        // television again while it is on screen showing him a code would be asking him to repeat
        // himself. It is listed here by name so that a *new* action slipping through without a
        // device requirement fails this test instead of quietly reaching the wrong device.
        val exempt = setOf("pair_code")
        for (id in LanExecutor.ACTION_IDS - exempt) {
            val errors = com.coxy.ghost.action.ActionCatalog.validate(id, mapOf())
            assertTrue("$id must require a device", errors.any { it.contains("device") })
        }
        assertTrue(
            "pair_code must still require the code itself",
            com.coxy.ghost.action.ActionCatalog.validate("pair_code", mapOf())
                .any { it.contains("code") }
        )
    }
}

/**
 * Naming. The televisions on this network advertise as `Smart-TV-<32 hex>` and nothing else, so
 * without this the control layer resolves nothing a person would actually say.
 */
class DeviceNamesTest {

    private val tv98 = LanDevice(
        "192.168.100.98", name = "Smart-TV-4df02b09600928db702d41131980b458", kind = "casts",
        mac = "44:0f:b4:00:00:02"
    )
    private val tv24 = LanDevice(
        "192.168.100.24", name = "Smart-TV-b2ad128c479835a5e8bd00857b54fc5b", kind = "casts",
        mac = "44:0f:b4:00:00:01"
    )
    private val noMac = LanDevice("192.168.100.55", name = "Odd-Thing")

    @Test
    fun `a device with a mac is keyed by it, not by its address`() {
        // A DHCP lease expiring would otherwise point "office TV" at whatever took the address.
        assertEquals("mac:44:0f:b4:00:00:02", DeviceNames.keyFor(tv98))
    }

    @Test
    fun `a device with no mac falls back to its address`() {
        assertEquals("ip:192.168.100.55", DeviceNames.keyFor(noMac))
    }

    @Test
    fun `a mac key is matched case-insensitively`() {
        assertEquals(
            DeviceNames.keyFor(tv98),
            DeviceNames.keyFor(tv98.copy(mac = "44:0F:B4:41:89:70"))
        )
    }

    @Test
    fun `a stored name survives the device changing address`() {
        val names = mapOf("mac:44:0f:b4:00:00:02" to "office TV")
        val moved = tv98.copy(ip = "192.168.100.140")
        assertEquals("office TV", DeviceNames.apply(listOf(moved), names).first().nickname)
    }

    @Test
    fun `a name given by address still applies once a mac is learned`() {
        val names = mapOf("ip:192.168.100.98" to "office TV")
        assertEquals("office TV", DeviceNames.apply(listOf(tv98), names).first().nickname)
    }

    @Test
    fun `only the named device gets the name`() {
        val applied = DeviceNames.apply(
            listOf(tv24, tv98), mapOf("mac:44:0f:b4:00:00:02" to "office TV")
        )
        assertEquals(null, applied.first { it.ip == tv24.ip }.nickname)
        assertEquals("office TV", applied.first { it.ip == tv98.ip }.nickname)
    }

    @Test
    fun `his name beats the advertised one everywhere it shows`() {
        val named = tv98.copy(nickname = "office TV")
        assertEquals("office TV", named.bestName)
        assertEquals("office TV at 192.168.100.98", DeviceMatcher.label(named))
    }

    @Test
    fun `naming one television makes 'the TV' unambiguous no longer`() {
        // Before: three candidates. After: "office TV" reaches exactly one.
        val network = listOf(tv24, tv98.copy(nickname = "office TV"))
        val r = DeviceMatcher.resolve("office TV", network)
        assertEquals("192.168.100.98", (r as DeviceMatcher.Resolution.Match).device.ip)
    }

    @Test
    fun `a nickname beats another device's advertised name`() {
        val decoy = LanDevice("192.168.100.7", name = "office-printer", kind = "printer")
        val network = listOf(decoy, tv98.copy(nickname = "office"))
        val r = DeviceMatcher.resolve("office", network)
        assertEquals("192.168.100.98", (r as DeviceMatcher.Resolution.Match).device.ip)
    }

    @Test
    fun `names round-trip through storage`() {
        val names = mapOf("mac:44:0f:b4:00:00:02" to "office TV", "ip:192.168.100.55" to "odd one")
        assertEquals(names, DeviceNames.parse(DeviceNames.render(names)))
    }

    @Test
    fun `unreadable stored names start empty rather than crashing`() {
        assertTrue(DeviceNames.parse("{not json").isEmpty())
        assertTrue(DeviceNames.parse(null).isEmpty())
        assertTrue(DeviceNames.parse("").isEmpty())
    }

    @Test
    fun `naming requires both the device and the name`() {
        val errors = com.coxy.ghost.action.ActionCatalog.validate("name_device", mapOf())
        assertTrue(errors.any { it.contains("device") })
        assertTrue(errors.any { it.contains("name") })
    }
}

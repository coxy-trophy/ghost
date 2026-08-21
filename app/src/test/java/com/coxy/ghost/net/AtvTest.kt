package com.coxy.ghost.net

import com.coxy.ghost.net.atv.AtvKeys
import com.coxy.ghost.net.atv.Protobuf
import com.coxy.ghost.net.atv.Protobuf.field
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire format, which is the part that either works exactly or produces a television that
 * accepts the connection and then ignores everything.
 */
class AtvTest {

    // --- protobuf ---------------------------------------------------------------------------------

    @Test
    fun `a varint field round-trips`() {
        val body = Protobuf.Writer().varint(2, 200).build()
        assertEquals(200L, Protobuf.parse(body).field(2)?.varint)
    }

    @Test
    fun `proto3 defaults are omitted rather than sent as zero`() {
        // A zero on the wire is not the same as absent to some firmwares.
        assertEquals(0, Protobuf.Writer().varint(1, 0).build().size)
    }

    @Test
    fun `a nested message round-trips`() {
        val body = Protobuf.Writer()
            .varint(1, 2)
            .message(10) {
                string(1, "com.coxy.ghost/ghost")
                string(2, "GHOST")
            }
            .build()
        val outer = Protobuf.parse(body)
        assertEquals(2L, outer.field(1)?.varint)
        val inner = Protobuf.parse(outer.field(10)!!.bytes!!)
        assertEquals("com.coxy.ghost/ghost", String(inner.field(1)!!.bytes!!))
        assertEquals("GHOST", String(inner.field(2)!!.bytes!!))
    }

    @Test
    fun `an unknown field is stepped over, not treated as an error`() {
        // A firmware is free to add fields this code has never heard of.
        val body = Protobuf.Writer().varint(2, 200).varint(99, 7).string(98, "x").build()
        val fields = Protobuf.parse(body)
        assertEquals(200L, fields.field(2)?.varint)
        assertEquals(7L, fields.field(99)?.varint)
    }

    @Test
    fun `framing is a varint length then the body`() {
        val framed = Protobuf.frame(ByteArray(5) { 1 })
        assertEquals(6, framed.size)
        assertEquals(5, framed[0].toInt())
    }

    @Test
    fun `a body over 127 bytes gets a two-byte length prefix`() {
        // A single-byte prefix would truncate here and corrupt every message after it.
        val framed = Protobuf.frame(ByteArray(200) { 1 })
        assertEquals(202, framed.size)
        assertEquals(0xC8.toByte(), framed[0])
        assertEquals(0x01.toByte(), framed[1])
    }

    @Test
    fun `a truncated message yields what it can rather than throwing`() {
        val body = Protobuf.Writer().string(1, "hello").build()
        assertTrue(Protobuf.parse(body.copyOfRange(0, 3)).size <= 1)
    }

    // --- key codes ---------------------------------------------------------------------------------

    @Test
    fun `power is the android keycode, which is the whole point of this protocol`() {
        assertEquals(26, AtvKeys.POWER)
        assertEquals(26, AtvKeys.forWord("power"))
        assertEquals(26, AtvKeys.forWord("Power"))
    }

    @Test
    fun `on and off are both the power key, because that is what the key does`() {
        assertEquals(AtvKeys.POWER, AtvKeys.forWord("off"))
        assertEquals(AtvKeys.POWER, AtvKeys.forWord("on"))
        assertTrue("a reply must not claim a direction", AtvKeys.isToggle(AtvKeys.POWER))
    }

    @Test
    fun `everyday words reach the keys people mean`() {
        assertEquals(AtvKeys.VOLUME_UP, AtvKeys.forWord("louder"))
        assertEquals(AtvKeys.VOLUME_DOWN, AtvKeys.forWord("quieter"))
        assertEquals(AtvKeys.DPAD_CENTER, AtvKeys.forWord("ok"))
        assertEquals(AtvKeys.VOLUME_MUTE, AtvKeys.forWord("mute"))
    }

    @Test
    fun `mute toggles too, so unmute is the same key`() {
        assertEquals(AtvKeys.forWord("mute"), AtvKeys.forWord("unmute"))
        assertTrue(AtvKeys.isToggle(AtvKeys.VOLUME_MUTE))
    }

    @Test
    fun `an unknown word is not silently some other key`() {
        assertNull(AtvKeys.forWord("banana"))
        assertNull(AtvKeys.forWord(""))
    }

    @Test
    fun `directional keys are not toggles`() {
        assertTrue(!AtvKeys.isToggle(AtvKeys.DPAD_UP))
        assertTrue(!AtvKeys.isToggle(AtvKeys.VOLUME_UP))
    }
}

/**
 * Reading a code off a television screen.
 *
 * Every case here is one a real pairing attempt hit or would have hit: the first live code was
 * F9EFD0 and was read out as F9EFDO, and the validator threw it away before the television ever
 * saw it.
 */
class AtvCodeTest {

    private val pairing = com.coxy.ghost.net.atv.AtvPairing("192.168.100.24")

    @Test
    fun `a letter O is a zero, because a zero is not a letter O`() {
        // The exact code that failed live.
        assertEquals("F9EFD0", pairing.normalise("F9EFDO"))
    }

    @Test
    fun `I and L are ones for the same reason`() {
        assertEquals("1A2B3C", pairing.normalise("IA2B3C"))
        assertEquals("1A2B3C", pairing.normalise("LA2B3C"))
    }

    @Test
    fun `the substitutions are only safe one way, and only these`() {
        // B, C, D, E, F are hexadecimal and must never be rewritten as something else.
        assertEquals("BCDEF0", pairing.normalise("bcdefo"))
    }

    @Test
    fun `case, spaces and dashes are not part of a code`() {
        assertEquals("F9EFD0", pairing.normalise(" f9 ef-d0 "))
    }

    @Test
    fun `a code already correct is left alone`() {
        assertEquals("A1B2C3", pairing.normalise("A1B2C3"))
    }
}

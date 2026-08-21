package com.coxy.ghost.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Mostly asserting what must **not** fire.
 *
 * A missed naming gets repeated by someone who can see it did not take. A false positive silently
 * relabels a device, and the next "turn off the office TV" lands somewhere else.
 */
class DeviceNameSignalTest {

    @Test
    fun `the sentence that failed twice in a row is caught`() {
        val n = DeviceNameSignal.detect("the TV at 192.168.100.24 is the IT office TV")
        assertEquals("192.168.100.24", n?.target)
        assertEquals("IT office TV", n?.name)
    }

    @Test
    fun `a bare address and a name works`() {
        val n = DeviceNameSignal.detect("192.168.100.98 is the office TV")
        assertEquals("192.168.100.98", n?.target)
        assertEquals("office TV", n?.name)
    }

    @Test
    fun `the last octet is how people say it out loud`() {
        val n = DeviceNameSignal.detect("the one at .24 is the boardroom screen")
        assertEquals(".24", n?.target)
        assertEquals("boardroom screen", n?.name)
    }

    @Test
    fun `the imperative order works too`() {
        val n = DeviceNameSignal.detect("call 192.168.100.26 the workshop PC")
        assertEquals("192.168.100.26", n?.target)
        assertEquals("workshop PC", n?.name)
    }

    @Test
    fun `articles are not part of the name`() {
        assertEquals("kitchen TV", DeviceNameSignal.detect("192.168.100.7 is my kitchen TV")?.name)
    }

    // --- what must not fire ------------------------------------------------------------------------

    @Test
    fun `a question about a device is not a naming`() {
        assertNull(DeviceNameSignal.detect("is 192.168.100.24 on?"))
        assertNull(DeviceNameSignal.detect("what is 192.168.100.24?"))
        assertNull(DeviceNameSignal.detect("is the TV at .24 muted?"))
    }

    @Test
    fun `an observation about state is not a name`() {
        // Without this, "the TV at .24 is off" stores a device called "off".
        assertNull(DeviceNameSignal.detect("the TV at 192.168.100.24 is off"))
        assertNull(DeviceNameSignal.detect("192.168.100.24 is on"))
        assertNull(DeviceNameSignal.detect("the thing at .26 is offline"))
        assertNull(DeviceNameSignal.detect("192.168.100.98 is muted"))
    }

    @Test
    fun `a message with no address names nothing`() {
        // Three things answer to "TV" on this network; that is not enough to act on.
        assertNull(DeviceNameSignal.detect("the TV is the office TV"))
        assertNull(DeviceNameSignal.detect("call it the office TV"))
    }

    @Test
    fun `an address restated is not a name`() {
        assertNull(DeviceNameSignal.detect("192.168.100.24 is 192.168.100.24"))
    }

    @Test
    fun `a whole clause about a device is not a name for it`() {
        assertNull(
            DeviceNameSignal.detect(
                "192.168.100.24 is the one I could never get to work properly since we moved it upstairs"
            )
        )
    }

    @Test
    fun `a name has to contain letters`() {
        assertNull(DeviceNameSignal.detect("192.168.100.24 is 42"))
    }

    @Test
    fun `ordinary conversation is left alone`() {
        assertNull(DeviceNameSignal.detect("what's on my wifi"))
        assertNull(DeviceNameSignal.detect("turn off the office TV"))
        assertNull(DeviceNameSignal.detect("it's about four in the afternoon"))
        assertNull(DeviceNameSignal.detect(""))
        assertNull(DeviceNameSignal.detect(null))
    }

    @Test
    fun `a long message is not scanned for namings`() {
        val essay = "192.168.100.24 is the office TV. " + "and then ".repeat(40)
        assertNull(DeviceNameSignal.detect(essay))
    }
}

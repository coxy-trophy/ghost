package com.coxy.ghost.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeLifetimeTest {
    @Test fun `terminal result releases detector exactly once`() {
        var closes = 0
        val lifetime = ProbeLifetime()
        lifetime.attach(AutoCloseable { closes++ })
        lifetime.close()
        lifetime.close()
        assertEquals(1, closes)
        assertTrue(lifetime.finished)
    }

    @Test fun `synchronous completion releases handle when creation returns`() {
        var closes = 0
        val lifetime = ProbeLifetime()
        lifetime.close()
        lifetime.attach(AutoCloseable { closes++ })
        assertEquals(1, closes)
    }

    @Test fun `destroy failure cannot reopen completed probe`() {
        var closes = 0
        val lifetime = ProbeLifetime()
        lifetime.attach(AutoCloseable { closes++; error("already gone") })
        lifetime.close()
        lifetime.close()
        assertTrue(lifetime.finished)
        assertEquals(1, closes)
    }
}

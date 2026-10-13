package com.coxy.ghost.ipc

import org.junit.Assert.*
import org.junit.Test

class InteractionSurfaceTest {
    @Test fun `lease expiry transfers the current active conversation exactly once`() {
        val lease = IslandLease()
        val surface = InteractionSurface()
        lease.advertise(true, 1000)
        surface.begin("conversation", true)
        assertFalse(surface.recover("conversation", true, lease.available(15999)))
        assertTrue(surface.recover("conversation", true, lease.available(16000)))
        assertEquals("conversation", surface.session)
        assertEquals("overlay", surface.owner)
        assertFalse(surface.recover("conversation", true, false))
    }

    @Test fun `peer returning after fallback never duplicates the surface`() {
        val surface = InteractionSurface()
        surface.begin("one", true)
        assertTrue(surface.recover("one", true, false))
        assertFalse(surface.recover("one", true, true))
        assertEquals("overlay", surface.owner)
        surface.begin("two", true)
        assertEquals("island", surface.owner)
    }

    @Test fun `stale callbacks cannot transfer a newer interaction`() {
        val surface = InteractionSurface()
        surface.begin("old", true)
        surface.begin("new", true)
        assertFalse(surface.recover("old", true, false))
        assertEquals("island", surface.owner)
        assertEquals("new", surface.session)
    }

    @Test fun `idle and already fallback sessions do not recover`() {
        val surface = InteractionSurface()
        assertFalse(surface.recover("", true, false))
        surface.begin("one", true)
        assertFalse(surface.recover("one", false, false))
        surface.begin("two", false)
        assertFalse(surface.recover("two", true, false))
    }

    @Test fun `revocation transfers without waiting for expiry`() {
        val lease = IslandLease()
        val surface = InteractionSurface()
        surface.begin("one", true)
        lease.advertise(true, 1000)
        lease.advertise(false, 1001)
        assertTrue(surface.recover("one", true, lease.available(1001)))
    }
}

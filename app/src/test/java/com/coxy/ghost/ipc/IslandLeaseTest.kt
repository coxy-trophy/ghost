package com.coxy.ghost.ipc
import org.junit.Assert.*
import org.junit.Test

class IslandLeaseTest {
    @Test fun `no advertisement means use fallback`() {
        assertFalse(IslandLease().available(0))
    }
    @Test fun `lease expires when peer stops answering and recovers after renewal`() {
        val lease = IslandLease()
        lease.advertise(true, 1000)
        assertTrue(lease.available(15999))
        assertFalse(lease.available(16000))
        lease.advertise(true, 17000)
        assertTrue(lease.available(18000))
    }
    @Test fun `disabled surface revokes immediately`() {
        val lease = IslandLease()
        lease.advertise(true, 1000)
        lease.advertise(false, 1001)
        assertFalse(lease.available(1001))
    }
    @Test fun `service restart clears old advertisement`() {
        val lease = IslandLease()
        lease.advertise(true, 1000)
        lease.clear()
        assertFalse(lease.available(1001))
    }
    @Test fun `backward clock cannot keep old lease alive`() {
        val lease = IslandLease()
        lease.advertise(true, 1000)
        assertFalse(lease.available(999))
    }
}

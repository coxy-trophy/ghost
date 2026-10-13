package com.coxy.ghost.execution

import org.junit.Assert.*
import org.junit.Test

class ApprovalLedgerTest {
    private class Store : ApprovalLedger.Store {
        val saved = mutableMapOf<String, ApprovalLedger.Entry>()
        var failRemove = false
        override fun entries() = saved.toMap()
        override fun put(id: String, entry: ApprovalLedger.Entry): Boolean { saved[id] = entry; return true }
        override fun remove(id: String): Boolean { if (failRemove) return false; saved.remove(id); return true }
    }
    @Test fun approvalsSurviveRecreationButAreSingleUse() {
        val store = Store()
        val id = ApprovalLedger(store).create("exact recipient and message", 10, 2)!!
        val recreated = ApprovalLedger(store)
        assertEquals("exact recipient and message", recreated.consume(id, 20, 2))
        assertNull(recreated.consume(id, 21, 2))
    }
    @Test fun requestsDoNotOverwriteEachOther() {
        val ledger = ApprovalLedger(Store())
        val a = ledger.create("first", 0, 2)!!
        val b = ledger.create("second", 1, 2)!!
        assertNotEquals(a, b)
        assertEquals("first", ledger.consume(a, 10, 2))
        assertEquals("second", ledger.consume(b, 11, 2))
    }
    @Test fun expiryAndRebootInvalidateApproval() {
        val ledger = ApprovalLedger(Store())
        assertNull(ledger.consume(ledger.create("expires", 0, 2)!!, 300_000, 2))
        assertNull(ledger.consume(ledger.create("reboot", 0, 2)!!, 10, 3))
    }
    @Test fun failedDurableRemovalCannotAuthorizeExecution() {
        val store = Store(); val ledger = ApprovalLedger(store)
        val id = ledger.create("payload", 0, 2)!!
        store.failRemove = true
        assertNull(ledger.consume(id, 1, 2))
    }
    @Test fun capacityAndPayloadAreBounded() {
        val ledger = ApprovalLedger(Store())
        repeat(16) { assertNotNull(ledger.create("request$it", 0, 2)) }
        assertNull(ledger.create("overflow", 1, 2))
        assertNotNull(ledger.create("expired entries pruned", 300_001, 2))
        assertNull(ledger.create("x".repeat(16_385), 300_002, 2))
    }
}

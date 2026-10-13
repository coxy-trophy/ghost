package com.coxy.ghost.notifications

import org.junit.Assert.*
import org.junit.Test

class NotificationProtocolTest {
    private fun row(title: String = "Message", body: String = "Local content", time: String = "123") =
        """{"package":"com.example.app","title":"$title","text":"$body","postTime":$time}"""
    private fun snapshot(rows: String, status: String = "OK") = """{"status":"$status","rows":[$rows]}"""
    private fun rejected(raw: String) { assertTrue(runCatching { NotificationProtocol.parse(raw) }.isFailure) }

    @Test fun `package name without matching Android supplied UID is untrusted`() {
        assertTrue(NotificationProtocol.authenticated(NotificationProtocol.PEER, 100, 100))
        assertFalse(NotificationProtocol.authenticated(NotificationProtocol.PEER, 101, 100))
        assertFalse(NotificationProtocol.authenticated("com.fake", 100, 100))
        assertFalse(NotificationProtocol.authenticated(null, 100, 100))
    }
    @Test fun `valid bounded snapshot preserves local display fields`() {
        val parsed = NotificationProtocol.parse(snapshot(row()))
        assertEquals("OK", parsed.status)
        assertEquals("Local content", parsed.rows.single().text)
        assertEquals(123L, parsed.rows.single().postTime)
    }
    @Test fun `failure statuses cannot carry hidden content`() {
        rejected(snapshot(row(), "LOCKED"))
        rejected(snapshot("", "BOGUS"))
        assertTrue(NotificationProtocol.parse(snapshot("", "DISABLED")).rows.isEmpty())
    }
    @Test fun `row and text limits fail closed`() {
        rejected(snapshot(List(21) { row() }.joinToString(",")))
        rejected(snapshot(row(title = "x".repeat(201))))
        rejected(snapshot(row(body = "x".repeat(501))))
        rejected(snapshot(row(time = "1.5")))
        rejected(snapshot(row(time = "-1")))
    }
    @Test fun `payload limit is bytes not characters`() {
        rejected(snapshot(row()) + " ".repeat(32_000))
        rejected(snapshot(List(20) { row(title = "界".repeat(200), body = "界".repeat(500)) }.joinToString(",")))
    }
    @Test fun `malformed fields do not get coerced into display content`() {
        rejected("""{"status":"OK","rows":[{"package":"x","title":123,"text":"y","postTime":1}]}""")
        rejected("""{"status":"OK","rows":[{"package":"x/y","title":"x","text":"y","postTime":1}]}""")
    }
}

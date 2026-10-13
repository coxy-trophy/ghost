package com.coxy.ghost.action

import org.junit.Assert.assertTrue
import org.junit.Test

class ActionConfirmationTest {
    @Test
    fun `sms confirmation includes exact recipient and complete message`() {
        val body = "First line\nSecond line"
        val details = ActionCatalog.confirmationDetails(Action("send_sms",
            ActionTier.TIER_3_DESTRUCTIVE, mapOf("number" to "+233123456789", "message" to body)))
        assertTrue(details.contains("Recipient: +233123456789"))
        assertTrue(details.contains("Message: $body"))
    }

    @Test
    fun `uninstall confirmation identifies the actual package`() {
        val details = ActionCatalog.confirmationDetails(Action("uninstall_app",
            ActionTier.TIER_3_DESTRUCTIVE, mapOf("package" to "com.example.target")))
        assertTrue(details.contains("App package: com.example.target"))
    }
}

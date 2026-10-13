package com.coxy.ghost.contacts

import org.junit.Assert.*
import org.junit.Test

class ContactQueryTest {
    @Test fun `empty queries cannot trigger bulk contact access`() {
        assertNull(ContactQuery.normalize("")); assertNull(ContactQuery.normalize("   "))
    }
    @Test fun `names preserve accents and punctuation`() {
        assertEquals("Élodie O'Neil", ContactQuery.normalize(" Élodie O'Neil "))
    }
    @Test fun `phone fragments are accepted without shell interpretation`() {
        assertEquals("+233 123", ContactQuery.normalize("+233 123"))
    }
    @Test fun `control and unbounded queries are rejected`() {
        assertNull(ContactQuery.normalize("name\nother"))
        assertNull(ContactQuery.normalize("x".repeat(101)))
        assertEquals(100, ContactQuery.normalize("x".repeat(100))?.length)
    }
}

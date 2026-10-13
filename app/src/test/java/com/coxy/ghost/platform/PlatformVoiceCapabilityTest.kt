package com.coxy.ghost.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class PlatformVoiceCapabilityTest {
    @Test fun `module presence never claims enrolled or working recognition`() {
        assertEquals("MODULES_PRESENT_ENROLLMENT_UNVERIFIED", PlatformVoiceCapability.assessment(true, 1, null))
    }
    @Test fun `only a successful empty list proves no exposed DSP modules`() {
        assertEquals("NO_DSP_MODULES", PlatformVoiceCapability.assessment(true, 0, null))
        assertEquals("UNKNOWN", PlatformVoiceCapability.assessment(true, null, null))
    }
    @Test fun `permission failure is not reported as hardware absence`() {
        assertEquals("PROBE_BLOCKED", PlatformVoiceCapability.assessment(true, null, "SecurityException"))
    }
    @Test fun `unselected service cannot claim active capability`() {
        assertEquals("NOT_SELECTED", PlatformVoiceCapability.assessment(false, 2, null))
    }
}

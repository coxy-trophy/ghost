package com.coxy.ghost

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppProcessPolicyTest {
    private val app = "com.coxy.ghost"

    @Test fun `main process initializes appearance`() {
        assertTrue(AppProcessPolicy.initializeUi(app, app))
    }

    @Test fun `platform processes never initialize appearance`() {
        listOf("$app:voice", "$app:hotword",
            "$app:hotword:com.coxy.ghost.platform.GhostHotwordDetectionService:hotword_detector_1",
            "$app:hotword:com.coxy.ghost.platform.GhostHotwordDetectionService:hotword_detector_2"
        ).forEach { assertFalse(it, AppProcessPolicy.initializeUi(it, app)) }
    }

    @Test fun `unknown secondary process has no UI initialization`() {
        assertFalse(AppProcessPolicy.initializeUi("$app:future", app))
    }
}

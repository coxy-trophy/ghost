package com.coxy.ghost.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `toPromptBlock` is the function that turns GHOST's senses into words, and it had no test at any
 * tier — every prompt on every path goes through it. It is pure Kotlin, so it belongs here rather
 * than behind a 20-minute device install.
 */
class ContextStateTest {

    @Test
    fun toPromptBlock_alwaysReportsTheUnconditionalFields() {
        val block = ContextState().toPromptBlock()
        assertTrue(block.startsWith("[CONTEXT "))
        assertTrue(block.endsWith("]"))
        // These four are emitted whether or not anything has been sensed — which is exactly why
        // hasSignal() exists to suppress the whole block.
        for (field in listOf("activity=unknown", "location=unknown", "light=unknown", "proximity=unknown")) {
            assertTrue("expected $field in: $block", block.contains(field))
        }
    }

    @Test
    fun toPromptBlock_omitsZeroAndUnknownOptionalFields() {
        val block = ContextState().toPromptBlock()
        assertFalse("speed is omitted at 0", block.contains("speed="))
        assertFalse("steps are omitted at 0", block.contains("steps="))
        assertFalse("no transcript means no heard=", block.contains("heard="))
        assertFalse("no frame means no camera=captured", block.contains("camera=captured"))
        assertFalse("mic is omitted while UNKNOWN", block.contains("mic="))
    }

    @Test
    fun toPromptBlock_includesActiveSensorReadings() {
        val block = ContextState(
            activity = Activity.WALKING,
            activityConfidence = 85,
            locationBucket = LocationBucket.HOME,
            speedKmh = 4.2f,
            lightBucket = LightBucket.BRIGHT,
            lightLux = 1200f,
            proximity = Proximity.NEAR,
            stepRatePerMin = 98,
            micState = MicState.VOICE,
            audioLevelDb = -22f
        ).toPromptBlock()

        assertTrue(block.contains("activity=walking(85%)"))
        assertTrue(block.contains("location=home"))
        assertTrue(block.contains("speed=4.2km/h"))
        assertTrue(block.contains("light=bright(1200lx)"))
        assertTrue(block.contains("proximity=near"))
        assertTrue(block.contains("steps=98/min"))
        assertTrue(block.contains("mic=voice(-22dB)"))
    }

    @Test
    fun toPromptBlock_truncatesHeardTranscriptToFifteenWords() {
        val long = (1..40).joinToString(" ") { "word$it" }
        val block = ContextState(transcriptSnippet = long).toPromptBlock()
        assertTrue("transcript should be present", block.contains("heard=\""))
        assertTrue("should be elided", block.contains("…"))
        assertTrue("15th word kept", block.contains("word15"))
        assertFalse("16th word dropped", block.contains("word16"))
    }

    @Test
    fun toPromptBlock_reportsCameraAsMetadataNotContent() {
        val block = ContextState(lastFrameMs = 1L, lastFrameFacing = "back", sceneBrightness = 130f)
            .toPromptBlock()
        // The model cannot see pixels; the block must only ever claim a capture happened.
        assertTrue(block.contains("camera=captured(back,br 130)"))
    }

    @Test
    fun toPromptBlock_flagsDisabledSubsystems() {
        val block = ContextState(
            micState = MicState.OFF,
            cameraAvailable = false,
            gmsAvailable = false
        ).toPromptBlock()
        assertTrue(block.contains("mic:off"))
        assertTrue(block.contains("camera:off"))
        assertTrue(block.contains("gms:off"))
    }

    @Test
    fun hasSignal_isFalseOnlyWhenNothingHasBeenSensed() {
        assertFalse("a default state carries no information", ContextState().hasSignal())

        assertTrue(ContextState(timestampMs = 1L).hasSignal())
        assertTrue(ContextState(lastTranscriptMs = 1L).hasSignal())
        assertTrue(ContextState(lastFrameMs = 1L).hasSignal())
        assertTrue(ContextState(micState = MicState.SILENT).hasSignal())
        assertTrue("an unavailable mic is itself information", ContextState(micAvailable = false).hasSignal())
        assertTrue("an unavailable camera is itself information", ContextState(cameraAvailable = false).hasSignal())
    }

    @Test
    fun toStatusLine_readsIdleUntilSomethingIsSensed() {
        assertEquals("Perception: idle", ContextState().toStatusLine())
        assertTrue(ContextState(timestampMs = 1L, activity = Activity.STILL).toStatusLine().contains("still"))
    }
}

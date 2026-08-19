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
    fun hasSignal_isFalseWheneverTheBlockWouldSayNothing() {
        assertFalse("a default state carries no information", ContextState().hasSignal())

        // A bare timestamp is not a reading. `timestampMs` and `lastTranscriptMs` render no clause
        // at all, so a state carrying only one of them would send four unknowns and nothing else.
        // In practice PerceptionManager stamps them alongside a real reading, which is why this
        // never showed up as a bare block on the device.
        assertFalse("a stamp is not a sense", ContextState(timestampMs = 1L).hasSignal())
        assertFalse("a stamp is not a transcript", ContextState(lastTranscriptMs = 1L).hasSignal())

        assertTrue(ContextState(timestampMs = 1L, activity = Activity.STILL).hasSignal())
        assertTrue(ContextState(transcriptSnippet = "put the kettle on").hasSignal())
        assertTrue(ContextState(lastFrameMs = 1L).hasSignal())
        assertTrue(ContextState(micState = MicState.SILENT).hasSignal())
        assertTrue("an unavailable mic is itself information", ContextState(micAvailable = false).hasSignal())
        assertTrue("an unavailable camera is itself information", ContextState(cameraAvailable = false).hasSignal())
        assertTrue("a missing GMS is itself information", ContextState(gmsAvailable = false).hasSignal())
    }

    @Test
    fun toStatusLine_readsIdleUntilSomethingIsSensed() {
        assertEquals("Perception: idle", ContextState().toStatusLine())
        assertTrue(ContextState(timestampMs = 1L, activity = Activity.STILL).toStatusLine().contains("still"))
    }

    // --- Phase 4.5: posture, nearby devices, radios ---
    // The block rides on every prompt into a 1.5B model, so the governing rule for all of these is
    // "silent unless it carries information". These tests are that rule.

    @Test
    fun phase45Fields_costNothingWhenNothingIsKnown() {
        val before = ContextState().toPromptBlock()
        for (field in listOf("posture=", "pocket=", "heading=", "motion=", "just_picked_up",
                             "nearby=", "known=", "call=", "signal=", "nfc:")) {
            assertFalse("$field leaked into an idle block: $before", before.contains(field))
        }
    }

    @Test
    fun postureAndHandlingAreReportedWhenKnown() {
        val block = ContextState(
            posture = Posture.FACE_UP,
            inPocket = false,
            heading = "NE",
            motion = MotionState.STATIONARY
        ).toPromptBlock()
        assertTrue(block.contains("posture=face_up"))
        assertTrue(block.contains("heading=NE"))
        assertTrue(block.contains("motion=stationary"))
        assertFalse("pocket=no is noise; only 'yes' is worth saying", block.contains("pocket="))
    }

    @Test
    fun pocketIsReportedOnlyWhenTrue() {
        assertTrue(ContextState(inPocket = true).toPromptBlock().contains("pocket=yes"))
        assertFalse(ContextState(inPocket = null).toPromptBlock().contains("pocket="))
    }

    @Test
    fun pickUpIsReportedOnlyWhileStillRecent() {
        val now = System.currentTimeMillis()
        assertTrue(ContextState(lastPickUpMs = now).toPromptBlock().contains("just_picked_up"))
        assertFalse(
            "a pick-up from an hour ago is not news",
            ContextState(lastPickUpMs = now - 3_600_000L).toPromptBlock().contains("just_picked_up")
        )
    }

    @Test
    fun nearbyReportsCountAndOnlyPairedNames() {
        val block = ContextState(nearbyCount = 4, nearbyKnown = "Car Audio, Buds").toPromptBlock()
        assertTrue(block.contains("nearby=4"))
        assertTrue(block.contains("known=\"Car Audio, Buds\""))
        // A scan that saw nothing, and a never-scanned state, both stay silent.
        assertFalse(ContextState(nearbyCount = 0).toPromptBlock().contains("nearby="))
        assertFalse(ContextState(nearbyCount = -1).toPromptBlock().contains("nearby="))
    }

    @Test
    fun callStateIsReportedOnlyWhenItMeansDoNotSpeak() {
        assertTrue(ContextState(callState = CallState.RINGING).toPromptBlock().contains("call=ringing"))
        assertTrue(ContextState(callState = CallState.IN_CALL).toPromptBlock().contains("call=in_call"))
        assertFalse("idle is the boring default", ContextState(callState = CallState.IDLE).toPromptBlock().contains("call="))
        assertFalse(ContextState(callState = CallState.UNKNOWN).toPromptBlock().contains("call="))
    }

    @Test
    fun signalIsReportedOnlyWhenWeakOrOnACall() {
        assertTrue("a weak signal explains a slow cloud reply",
            ContextState(signalLevel = 1, networkType = "LTE").toPromptBlock().contains("signal=1/4(LTE)"))
        assertFalse("a good signal is not worth a token",
            ContextState(signalLevel = 4, networkType = "LTE").toPromptBlock().contains("signal="))
        assertTrue(ContextState(signalLevel = 3, callState = CallState.IN_CALL).toPromptBlock().contains("signal=3/4"))
    }

    @Test
    fun hasSignal_isTrueForSourcesThatDoNotStampATimestamp() {
        // BLE and telephony are not sensor-clocked, so a phone that only knows those things must
        // still send its block — but only when the thing it knows is one the block actually prints.
        assertTrue(ContextState(posture = Posture.FACE_DOWN).hasSignal())
        assertTrue(ContextState(inPocket = true).hasSignal())
        assertTrue(ContextState(heading = "S").hasSignal())
        assertTrue(ContextState(motion = MotionState.MOVING).hasSignal())
        assertTrue(ContextState(nearbyCount = 4).hasSignal())
        assertTrue(ContextState(callState = CallState.RINGING).hasSignal())
        assertTrue(ContextState(nfcEnabled = true).hasSignal())
        assertTrue(ContextState(lastPickUpMs = System.currentTimeMillis()).hasSignal())
        assertTrue(ContextState(speedKmh = 4.2f).hasSignal())
        assertTrue(ContextState(stepRatePerMin = 98).hasSignal())
        assertTrue(ContextState(signalLevel = 1).hasSignal())
    }

    @Test
    fun hasSignal_isFalseForStatesTheBlockDeliberatelyDoesNotPrint() {
        // These are the four that drifted. RadioWatcher sets call state to IDLE and NFC to
        // true/false the moment it starts, so before this was fixed every prompt on a running
        // device claimed signal and then printed nothing but the unconditional unknowns.
        assertFalse("IDLE is the boring default", ContextState(callState = CallState.IDLE).hasSignal())
        assertFalse("nfc:off is never printed", ContextState(nfcEnabled = false).hasSignal())
        assertFalse("pocket=no is never printed", ContextState(inPocket = false).hasSignal())
        assertFalse("nearby=0 is never printed", ContextState(nearbyCount = 0).hasSignal())
        assertFalse("a full signal bar is not news", ContextState(signalLevel = 4).hasSignal())
        assertFalse("a stale pick-up is noise", ContextState(lastPickUpMs = 1L).hasSignal())
    }

    @Test
    fun hasSignalAgreesWithWhatTheBlockActuallySays() {
        // The invariant, and the test this file was missing. Both halves were pinned field by
        // field above; the relationship between them was not, and that is where Phase 4.5 drifted.
        // hasSignal() must be true exactly when the block carries a clause beyond the four
        // unconditional unknowns.
        val states = mapOf(
            "default" to ContextState(),
            "bare timestamp" to ContextState(timestampMs = 1L),
            "radios read, nothing else" to
                ContextState(callState = CallState.IDLE, nfcEnabled = false, signalLevel = 4),
            "scanned, saw nobody" to ContextState(nearbyCount = 0, inPocket = false),
            "one real reading" to ContextState(timestampMs = 1L, lightBucket = LightBucket.DARK),
            "walking" to ContextState(activity = Activity.WALKING, activityConfidence = 85, speedKmh = 4.2f),
            "on a call" to ContextState(callState = CallState.IN_CALL, signalLevel = 2, networkType = "LTE"),
            "heard something" to ContextState(transcriptSnippet = "put the kettle on"),
            "mic broken" to ContextState(micAvailable = false),
            "camera broken" to ContextState(cameraAvailable = false),
            "no GMS" to ContextState(gmsAvailable = false),
            "just picked up" to ContextState(lastPickUpMs = System.currentTimeMillis()),
            "stale pick-up" to ContextState(lastPickUpMs = 1L),
            "face down in a pocket" to
                ContextState(posture = Posture.FACE_DOWN, inPocket = true, motion = MotionState.STATIONARY),
            "still for a while" to ContextState(
                activity = Activity.STILL,
                activitySinceMs = System.currentTimeMillis() - 42 * 60_000L,
            ),
            "confidence with no activity" to ContextState(activityConfidence = 85),
            "dwell with no activity" to
                ContextState(activitySinceMs = System.currentTimeMillis() - 42 * 60_000L),
            "at home" to ContextState(locationBucket = LocationBucket.HOME),
            "face down in a pocket, collapsed" to ContextState(
                activity = Activity.STILL,
                motion = MotionState.STATIONARY,
                inPocket = true,
                proximity = Proximity.NEAR,
            ),
            "motion echoing activity only" to
                ContextState(activity = Activity.STILL, motion = MotionState.STATIONARY),
            "pocket covering proximity only" to
                ContextState(inPocket = true, proximity = Proximity.NEAR),
            "away" to ContextState(locationBucket = LocationBucket.AWAY),
        )
        for ((name, state) in states) {
            assertEquals(
                "hasSignal() disagrees with toPromptBlock() for '$name': ${state.toPromptBlock()}",
                state.blockSaysSomething(),
                state.hasSignal(),
            )
        }
    }

    // --- activity dwell ---
    // "still" and "still for forty minutes" are different facts, and only the first was reachable.

    @Test
    fun dwellIsOmittedUntilItIsWorthSaying() {
        val now = System.currentTimeMillis()
        // Never known.
        assertFalse(ContextState(activity = Activity.STILL).toPromptBlock().contains("m)"))
        // Known, but only just — every state has just started at some point, and "0m" is noise.
        assertEquals(
            "[CONTEXT activity=still location=unknown light=unknown proximity=unknown]",
            ContextState(activity = Activity.STILL, activitySinceMs = now - 30_000L).toPromptBlock(),
        )
    }

    @Test
    fun dwellReadsInMinutesThenHours() {
        val now = System.currentTimeMillis()
        fun label(heldMs: Long) =
            ContextState(activity = Activity.STILL, activitySinceMs = now - heldMs).toPromptBlock()

        assertTrue(label(60_000L).contains("activity=still(1m)"))
        assertTrue(label(42 * 60_000L).contains("activity=still(42m)"))
        assertTrue("a round hour drops the minutes", label(60 * 60_000L).contains("activity=still(1h)"))
        assertTrue(label(135 * 60_000L).contains("activity=still(2h15m)"))
    }

    @Test
    fun dwellSitsAlongsideConfidence() {
        val block = ContextState(
            activity = Activity.STILL,
            activityConfidence = 100,
            activitySinceMs = System.currentTimeMillis() - 42 * 60_000L,
        ).toPromptBlock()
        assertTrue(block, block.contains("activity=still(100%,42m)"))
    }

    @Test
    fun anUnknownActivityTakesNoQualifiers() {
        // Both would be nonsense, and a qualifier here would also break the hasSignal invariant:
        // the clause would read as informative while renderClauses correctly says it is not.
        val block = ContextState(
            activityConfidence = 85,
            activitySinceMs = System.currentTimeMillis() - 42 * 60_000L,
        ).toPromptBlock()
        assertTrue(block, block.contains("activity=unknown "))
        assertFalse(block, block.contains("85%"))
        assertFalse(block, block.contains("42m"))
    }

    @Test
    fun dwellClockRestartsOnlyWhenTheActivityActuallyChanges() {
        // The stamp lives in ContextStore.update so no writer can forget it; this is the pure half.
        val walking = ContextState(activity = Activity.WALKING, activitySinceMs = 1_000L)

        val sameActivityNewReading = walking.copy(activityConfidence = 90).stampDwell(walking)
        assertEquals(
            "a fresh reading of the same activity must not restart the clock",
            1_000L,
            sameActivityNewReading.activitySinceMs,
        )

        val changed = walking.copy(activity = Activity.STILL).stampDwell(walking)
        assertTrue("a real change restarts the clock", changed.activitySinceMs > 1_000L)

        val wentUnknown = walking.copy(activity = Activity.UNKNOWN).stampDwell(walking)
        assertEquals(
            "losing the activity clears the clock rather than dating it",
            0L,
            wentUnknown.activitySinceMs,
        )
    }

    // --- one fact, one clause ---
    // A phone face-down in a pocket used to emit activity, motion, posture, pocket and proximity
    // all describing the same situation. The rule is: say it when it adds or contradicts, never
    // when it echoes.

    @Test
    fun motionIsSilentWhenItMerelyAgreesWithActivity() {
        val still = ContextState(activity = Activity.STILL, motion = MotionState.STATIONARY).toPromptBlock()
        assertTrue(still, still.contains("activity=still"))
        assertFalse("stationary adds nothing to still: $still", still.contains("motion="))

        val walking = ContextState(activity = Activity.WALKING, motion = MotionState.MOVING).toPromptBlock()
        assertFalse("moving adds nothing to walking: $walking", walking.contains("motion="))
    }

    @Test
    fun motionSpeaksWhenItDisagreesOrStandsAlone() {
        // Two sensors disagreeing is worth the tokens — it tells the model its senses are unsure.
        val conflict = ContextState(activity = Activity.STILL, motion = MotionState.MOVING).toPromptBlock()
        assertTrue(conflict, conflict.contains("motion=moving"))

        // With no activity reading it is the only movement signal there is.
        val alone = ContextState(motion = MotionState.STATIONARY).toPromptBlock()
        assertTrue(alone, alone.contains("motion=stationary"))
    }

    @Test
    fun proximityIsSilentWhenThePocketSensorAlreadySaidIt() {
        val pocketed = ContextState(inPocket = true, proximity = Proximity.NEAR).toPromptBlock()
        assertTrue(pocketed, pocketed.contains("pocket=yes"))
        assertFalse("near is what being in a pocket means: $pocketed", pocketed.contains("proximity="))
    }

    @Test
    fun proximitySpeaksWhenItContradictsThePocketSensor() {
        val odd = ContextState(inPocket = true, proximity = Proximity.FAR).toPromptBlock()
        assertTrue(odd, odd.contains("pocket=yes"))
        assertTrue("a pocket reading FAR is a disagreement worth stating: $odd", odd.contains("proximity=far"))
    }

    @Test
    fun proximityIsUnaffectedWhenThereIsNoPocketReading() {
        assertTrue(ContextState(proximity = Proximity.NEAR).toPromptBlock().contains("proximity=near"))
        assertTrue(ContextState(inPocket = false, proximity = Proximity.NEAR).toPromptBlock().contains("proximity=near"))
        assertTrue(ContextState().toPromptBlock().contains("proximity=unknown"))
    }

    @Test
    fun theFaceDownInAPocketCaseSaysEachThingOnce() {
        // The exact state that motivated this: five clauses for one situation, now three.
        val block = ContextState(
            activity = Activity.STILL,
            motion = MotionState.STATIONARY,
            posture = Posture.FACE_DOWN,
            inPocket = true,
            proximity = Proximity.NEAR,
        ).toPromptBlock()
        assertTrue(block, block.contains("activity=still"))
        assertTrue(block, block.contains("posture=face_down"))
        assertTrue(block, block.contains("pocket=yes"))
        assertFalse(block, block.contains("motion="))
        assertFalse(block, block.contains("proximity="))
    }

    /**
     * Whether the rendered block contains anything beyond the four unconditional clauses in their
     * unknown form. Deliberately re-derived from the rendered string rather than from the same code
     * [hasSignal] uses, so this asserts the contract instead of restating the implementation.
     */
    private fun ContextState.blockSaysSomething(): Boolean {
        val noise = setOf("activity=unknown", "location=unknown", "light=unknown", "proximity=unknown")
        return toPromptBlock()
            .removePrefix("[CONTEXT ")
            .removeSuffix("]")
            .split(" ")
            .any { it.isNotBlank() && it !in noise }
    }
}

package com.coxy.ghost.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What counts as an event. An over-eager rule here does not produce a wrong log line — it produces a
 * phone that talks to you about nothing, so the guards matter more than the detections.
 */
class TransitionDetectorTest {

    private val t0 = 1_800_000_000_000L
    private fun min(n: Long) = n * 60_000L

    private fun detect(prev: ContextState, next: ContextState, now: Long = t0, last: Long = t0 - min(5)) =
        TransitionDetector.detect(prev, next, now, last).map { it.kind }

    @Test
    fun arrivingAndLeavingHomeAreBothEvents() {
        assertEquals(
            listOf(TransitionKind.ARRIVED_HOME),
            detect(
                ContextState(locationBucket = LocationBucket.AWAY),
                ContextState(locationBucket = LocationBucket.HOME),
            ),
        )
        assertEquals(
            listOf(TransitionKind.LEFT_HOME),
            detect(
                ContextState(locationBucket = LocationBucket.HOME),
                ContextState(locationBucket = LocationBucket.AWAY),
            ),
        )
    }

    @Test
    fun theFirstFixAfterBootIsNotAnArrival() {
        // UNKNOWN -> HOME happens on every reboot, and "welcome home" every time the phone restarts
        // is exactly what makes an ambient assistant feel broken.
        assertTrue(
            detect(
                ContextState(locationBucket = LocationBucket.UNKNOWN),
                ContextState(locationBucket = LocationBucket.HOME),
            ).isEmpty(),
        )
        assertTrue(
            detect(
                ContextState(locationBucket = LocationBucket.HOME),
                ContextState(locationBucket = LocationBucket.UNKNOWN),
            ).isEmpty(),
        )
    }

    @Test
    fun movingAgainCountsOnlyAfterRealStillness() {
        val longStill = ContextState(activity = Activity.STILL, activitySinceMs = t0 - min(45))
        val briefStill = ContextState(activity = Activity.STILL, activitySinceMs = t0 - min(3))
        val walking = ContextState(activity = Activity.WALKING, activitySinceMs = t0)

        assertEquals(listOf(TransitionKind.STARTED_MOVING), detect(longStill, walking))
        assertTrue("three minutes at a desk is not an event", detect(briefStill, walking).isEmpty())
    }

    @Test
    fun theDurationInTheDescriptionIsTheStillnessThatEnded() {
        // Not the new activity's age, which is zero at this moment — the old duration only survives
        // on the previous sample, which is the subtle part of reading it correctly.
        val prev = ContextState(activity = Activity.STILL, activitySinceMs = t0 - min(45))
        val next = ContextState(activity = Activity.WALKING, activitySinceMs = t0)
        val d = TransitionDetector.detect(prev, next, t0, t0 - min(5)).single()
        assertTrue(d.description, d.description.contains("45 minutes"))
    }

    @Test
    fun stoppingAfterAVehicleIsAnArrival() {
        assertEquals(
            listOf(TransitionKind.ARRIVED_AFTER_TRAVEL),
            detect(
                ContextState(activity = Activity.IN_VEHICLE),
                ContextState(activity = Activity.STILL),
            ),
        )
    }

    @Test
    fun longStillnessFiresOnceOnTheCrossing() {
        val since = t0 - min(95)
        val state = ContextState(activity = Activity.STILL, activitySinceMs = since)

        // Last checked at 85 minutes, now at 95 — the 90 minute mark was crossed in between.
        assertEquals(
            listOf(TransitionKind.LONG_STILLNESS),
            TransitionDetector.detect(state, state, now = t0, lastEvaluatedMs = since + min(85))
                .map { it.kind },
        )

        // Checked again ten minutes later: already past the mark at the previous check, so silence.
        assertTrue(
            TransitionDetector.detect(state, state, now = t0 + min(10), lastEvaluatedMs = t0).isEmpty(),
        )
    }

    @Test
    fun aNewStretchOfStillnessDoesNotInheritTheOldCrossing() {
        // If activitySinceMs moved, this is a different stretch and any crossing belongs to it.
        val prev = ContextState(activity = Activity.STILL, activitySinceMs = t0 - min(200))
        val next = ContextState(activity = Activity.STILL, activitySinceMs = t0 - min(95))
        assertTrue(
            TransitionDetector.detect(prev, next, t0, t0 - min(5)).isEmpty(),
        )
    }

    @Test
    fun nothingHappeningProducesNothing() {
        val still = ContextState(activity = Activity.STILL, activitySinceMs = t0 - min(10))
        assertTrue(detect(still, still).isEmpty())
        assertTrue(detect(ContextState(), ContextState()).isEmpty())
    }

    @Test
    fun durationsReadAsSpeechNotAsFieldValues() {
        assertEquals("40 minutes", TransitionDetector.minutesPhrase(min(40)))
        assertEquals("an hour", TransitionDetector.minutesPhrase(min(60)))
        assertEquals("2 hours", TransitionDetector.minutesPhrase(min(120)))
        assertEquals("2 hours and 15 minutes", TransitionDetector.minutesPhrase(min(135)))
    }
}

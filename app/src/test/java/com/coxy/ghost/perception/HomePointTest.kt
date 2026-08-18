package com.coxy.ghost.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `location=` rendered on every prompt from Phase 4 until now and was hardcoded to `unknown` — one
 * of the four unconditional clauses, permanently dead. These tests are the bucketing that replaced
 * the hardcode; they are pure Kotlin on purpose (see [HomePoint.distanceMeters]) so the whole
 * home/away decision is checkable in the fast tier.
 */
class HomePointTest {

    // A home point and some real distances from it, on the equator-ish scale this device lives at.
    private val home = HomePoint(lat = 6.5244, lon = 3.3792, radiusM = 120f)

    @Test
    fun noHomePointMeansUnknownRatherThanAway() {
        // The distinction matters: "away" is a claim, "unknown" is the absence of one. Before a
        // home point exists GHOST knows nothing about where it is, and must say so.
        assertEquals(
            LocationBucket.UNKNOWN,
            HomePoint.bucketFor(null, 6.5244, 3.3792, 10f),
        )
    }

    @Test
    fun standingOnTheHomePointIsHome() {
        assertEquals(LocationBucket.HOME, HomePoint.bucketFor(home, home.lat, home.lon, 10f))
    }

    @Test
    fun justInsideTheRadiusIsHomeAndJustOutsideIsAway() {
        // ~0.0005 deg of latitude is ~55 m; ~0.0018 deg is ~200 m.
        val nearby = home.lat + 0.0005
        val downTheRoad = home.lat + 0.0018

        assertTrue(
            "sanity: the near point should be inside 120 m",
            HomePoint.distanceMeters(home.lat, home.lon, nearby, home.lon) < 120.0,
        )
        assertTrue(
            "sanity: the far point should be outside 120 m",
            HomePoint.distanceMeters(home.lat, home.lon, downTheRoad, home.lon) > 120.0,
        )

        assertEquals(LocationBucket.HOME, HomePoint.bucketFor(home, nearby, home.lon, 10f))
        assertEquals(LocationBucket.AWAY, HomePoint.bucketFor(home, downTheRoad, home.lon, 10f))
    }

    @Test
    fun aFixVaguerThanTheGeofenceAnswersNothing() {
        // A ±500 m fix cannot resolve a 120 m question in either direction. The system prompt's
        // bedrock says GHOST does not dress a guess up as knowledge, so this reports unknown even
        // standing on the exact spot.
        assertEquals(LocationBucket.UNKNOWN, HomePoint.bucketFor(home, home.lat, home.lon, 500f))
        assertEquals(LocationBucket.UNKNOWN, HomePoint.bucketFor(home, home.lat + 0.05, home.lon, 500f))
    }

    @Test
    fun anAccuracyOfMinusOneMeansUnreportedNotVague() {
        // Location.hasAccuracy() is false on some providers; handleLocation passes -1f for that.
        // Treating it as "infinitely vague" would make the bucket permanently unknown on exactly
        // the fallback provider path that needs it most.
        assertEquals(LocationBucket.HOME, HomePoint.bucketFor(home, home.lat, home.lon, -1f))
    }

    @Test
    fun distanceIsSymmetricAndZeroAtThePoint() {
        assertEquals(0.0, HomePoint.distanceMeters(home.lat, home.lon, home.lat, home.lon), 0.001)
        val a = HomePoint.distanceMeters(home.lat, home.lon, home.lat + 0.01, home.lon + 0.01)
        val b = HomePoint.distanceMeters(home.lat + 0.01, home.lon + 0.01, home.lat, home.lon)
        assertEquals(a, b, 0.001)
    }

    @Test
    fun aKilometreNorthMeasuresAsAKilometre() {
        // 0.008993 deg of latitude is ~1 km anywhere. This pins the haversine constant itself —
        // a wrong earth radius would still pass every relative test above.
        val km = HomePoint.distanceMeters(home.lat, home.lon, home.lat + 0.0089932, home.lon)
        assertEquals(1000.0, km, 5.0)
    }
}

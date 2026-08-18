package com.coxy.ghost.perception

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The one place GHOST is allowed to know by name.
 *
 * `location=` had rendered on every prompt since Phase 4 and could never say anything but `unknown`:
 * [PerceptionManager.handleLocation] took a real GPS fix, pulled `speed` off it for the activity
 * backstop, and hardcoded the bucket to [LocationBucket.UNKNOWN] behind a "home/away learning is
 * deferred" comment. One of the four unconditional clauses was therefore permanently dead weight.
 *
 * The bucketing is what makes this safe to send anywhere. Coordinates live in [PerceptionManager]
 * and in this store and go nowhere else — they are never written into [ContextState], so they cannot
 * reach the prompt, the cloud, the distillation log or the memory DB. What leaves is one word:
 * `home` or `away`.
 */
data class HomePoint(
    val lat: Double,
    val lon: Double,
    val radiusM: Float = DEFAULT_RADIUS_M,
) {
    companion object {
        /**
         * Comfortably larger than this device's GPS jitter, so standing in the garden does not
         * flap the bucket, and small enough that the next street is `away`.
         */
        const val DEFAULT_RADIUS_M = 120f

        /** Guard rails for a radius arriving from a shell broadcast. */
        const val MIN_RADIUS_M = 20f
        const val MAX_RADIUS_M = 2000f

        /**
         * Which bucket a fix falls in.
         *
         * Returns [LocationBucket.UNKNOWN] when there is no home point *and* when the fix is vaguer
         * than the geofence it is being tested against — a reading accurate to ±500 m cannot answer
         * a 120 m question in either direction, and the bedrock in the system prompt says GHOST does
         * not dress a guess up as knowledge. Saying nothing is the honest answer there.
         */
        fun bucketFor(
            home: HomePoint?,
            lat: Double,
            lon: Double,
            accuracyM: Float,
        ): LocationBucket {
            if (home == null) return LocationBucket.UNKNOWN
            if (accuracyM > 0f && accuracyM > home.radiusM) return LocationBucket.UNKNOWN
            val distance = distanceMeters(home.lat, home.lon, lat, lon)
            return if (distance <= home.radiusM) LocationBucket.HOME else LocationBucket.AWAY
        }

        /**
         * Great-circle distance in metres.
         *
         * Deliberately not `android.location.Location.distanceBetween`: keeping it pure Kotlin keeps
         * the whole home/away decision in the fast JVM test tier rather than behind a 20-minute
         * device install. Haversine is accurate to well under a metre at these distances.
         */
        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val earthRadiusM = 6_371_008.8
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * earthRadiusM * asin(min(1.0, sqrt(a)))
        }
    }
}

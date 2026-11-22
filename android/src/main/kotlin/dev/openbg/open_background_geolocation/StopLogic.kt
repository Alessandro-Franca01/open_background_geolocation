package dev.openbg.open_background_geolocation

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Battery-related decisions with no Android types, so they run in JVM unit tests.
 *
 * [StopDetector] is the distance-based safety net: while MOVING, if no accepted fix lands farther
 * than the stationary radius from an anchor for `stopTimeout`, the device is treated as stopped,
 * even when activity recognition never reports STILL (permission denied, or an idling vehicle that
 * keeps vibrating as `in_vehicle`).
 */
internal class StopDetector {
    private var anchorLat = 0.0
    private var anchorLon = 0.0
    private var anchorAt = -1L

    val hasAnchor: Boolean get() = anchorAt >= 0

    fun reset() {
        anchorAt = -1L
    }

    /** Starts the count from a known position (e.g. the last fix when leaving STATIONARY). */
    fun seed(lat: Double, lon: Double, at: Long) {
        anchorLat = lat
        anchorLon = lon
        anchorAt = at
    }

    /**
     * Feeds an accepted fix. The anchor moves only for a fix outside [radius] whose accuracy is
     * within [radius], so GPS noise does not restart the count. Returns true if the anchor moved.
     */
    fun onFix(lat: Double, lon: Double, accuracy: Double, at: Long, radius: Double): Boolean {
        if (!hasAnchor) {
            seed(lat, lon, at)
            return true
        }
        if (accuracy > radius) return false
        if (distanceMeters(anchorLat, anchorLon, lat, lon) <= radius) return false
        seed(lat, lon, at)
        return true
    }

    /** True when there is an anchor and it has not moved for [timeoutMillis]. */
    fun shouldStop(now: Long, timeoutMillis: Long): Boolean =
        hasAnchor && timeoutMillis > 0 && now - anchorAt >= timeoutMillis

    companion object {
        private const val EARTH_RADIUS_M = 6_371_000.0

        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
            return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
        }
    }
}

/**
 * Elasticity with hysteresis: the distance filter widens by 1x every 20 km/h (max 10x), but the
 * location request is only re-issued when the new factor differs by 2 or more, or the same new
 * factor holds for [STREAK] fixes in a row. City traffic swinging across a 20 km/h boundary no
 * longer restarts the request several times a minute.
 */
internal class ElasticityGate {
    var current = 1.0
        private set
    private var candidate = 1.0
    private var streak = 0

    fun reset() {
        current = 1.0
        candidate = 1.0
        streak = 0
    }

    /** Returns true if [current] changed and the location request should be re-issued. */
    fun offer(speedMps: Double): Boolean {
        val factor = factorFor(speedMps * 3.6)
        if (factor == current) {
            streak = 0
            return false
        }
        if (abs(factor - current) >= 2.0) return apply(factor)
        if (factor == candidate) streak++ else {
            candidate = factor
            streak = 1
        }
        return if (streak >= STREAK) apply(factor) else false
    }

    private fun apply(factor: Double): Boolean {
        current = factor
        candidate = factor
        streak = 0
        return true
    }

    companion object {
        const val STREAK = 3
        fun factorFor(kmh: Double): Double = min(10.0, 1.0 + (kmh / 20.0).toInt())
    }
}

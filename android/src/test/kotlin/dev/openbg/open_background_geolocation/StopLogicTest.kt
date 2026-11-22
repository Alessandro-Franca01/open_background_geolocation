package dev.openbg.open_background_geolocation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopLogicTest {
    private val min = 60_000L
    private val lat = -6.97 // Cabedelo-PB
    private val lon = -34.84

    /** Degrees of latitude for [m] meters. */
    private fun dLat(m: Double) = m / 111_195.0

    @Test
    fun distanceIsRoughlyCorrect() {
        val d = StopDetector.distanceMeters(lat, lon, lat + dLat(100.0), lon)
        assertEquals(100.0, d, 0.5)
    }

    @Test
    fun noAnchorNeverStops() {
        val s = StopDetector()
        assertFalse(s.shouldStop(10 * min, 5 * min))
    }

    @Test
    fun stopsWhenNoFixArrivesAfterSeed() {
        val s = StopDetector()
        s.seed(lat, lon, 0)
        assertFalse(s.shouldStop(4 * min, 5 * min))
        assertTrue(s.shouldStop(5 * min, 5 * min))
    }

    @Test
    fun fixesInsideRadiusDoNotResetTheCount() {
        val s = StopDetector()
        s.onFix(lat, lon, 5.0, 0, 100.0)
        for (i in 1..9) {
            assertFalse(s.onFix(lat + dLat(60.0), lon, 5.0, i * 30_000L, 100.0))
        }
        assertTrue(s.shouldStop(5 * min, 5 * min))
    }

    @Test
    fun fixOutsideRadiusRestartsTheCount() {
        val s = StopDetector()
        s.onFix(lat, lon, 5.0, 0, 100.0)
        assertTrue(s.onFix(lat + dLat(150.0), lon, 5.0, 4 * min, 100.0))
        assertFalse(s.shouldStop(5 * min, 5 * min))
        assertTrue(s.shouldStop(9 * min, 5 * min))
    }

    @Test
    fun inaccurateFixDoesNotMoveTheAnchor() {
        val s = StopDetector()
        s.onFix(lat, lon, 5.0, 0, 100.0)
        assertFalse(s.onFix(lat + dLat(300.0), lon, 150.0, 2 * min, 100.0))
        assertTrue(s.shouldStop(5 * min, 5 * min))
    }

    @Test
    fun zeroTimeoutDisablesStop() {
        val s = StopDetector()
        s.seed(lat, lon, 0)
        assertFalse(s.shouldStop(60 * min, 0))
    }

    private fun kmh(v: Double) = v / 3.6

    @Test
    fun elasticityIgnoresSwingsAcrossOneBoundary() {
        val g = ElasticityGate()
        var reissues = 0
        repeat(10) {
            if (g.offer(kmh(15.0))) reissues++
            if (g.offer(kmh(25.0))) reissues++
        }
        assertEquals(0, reissues)
        assertEquals(1.0, g.current, 0.0)
    }

    @Test
    fun elasticityAppliesAfterAStreak() {
        val g = ElasticityGate()
        assertFalse(g.offer(kmh(25.0)))
        assertFalse(g.offer(kmh(25.0)))
        assertTrue(g.offer(kmh(25.0)))
        assertEquals(2.0, g.current, 0.0)
    }

    @Test
    fun elasticityJumpsImmediatelyOnLargeChange() {
        val g = ElasticityGate()
        assertTrue(g.offer(kmh(60.0)))
        assertEquals(4.0, g.current, 0.0)
        assertTrue(g.offer(kmh(0.0)))
        assertEquals(1.0, g.current, 0.0)
    }

    @Test
    fun elasticityCapsAtTen() {
        assertEquals(10.0, ElasticityGate.factorFor(400.0), 0.0)
    }
}

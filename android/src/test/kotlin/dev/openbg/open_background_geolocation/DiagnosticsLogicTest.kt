package dev.openbg.open_background_geolocation

import dev.openbg.open_background_geolocation.DiagnosticsLogic.SERVICE_PERMISSION
import dev.openbg.open_background_geolocation.DiagnosticsLogic.locationServiceBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsLogicTest {
    @Test
    fun noLocationPermissionAlwaysBlocks() {
        assertEquals(SERVICE_PERMISSION, locationServiceBlock(30, false, false, true))
        assertEquals(SERVICE_PERMISSION, locationServiceBlock(34, false, true, true))
    }

    @Test
    fun android14WhileInUseInBackgroundBlocks() {
        assertEquals(SERVICE_PERMISSION, locationServiceBlock(34, true, false, false))
        assertEquals(SERVICE_PERMISSION, locationServiceBlock(35, true, false, false))
    }

    @Test
    fun android14WhileInUseInForegroundAllows() {
        assertNull(locationServiceBlock(34, true, false, true))
    }

    @Test
    fun android14WithBackgroundPermissionAllows() {
        assertNull(locationServiceBlock(34, true, true, false))
    }

    @Test
    fun olderAndroidWhileInUseInBackgroundAllows() {
        assertNull(locationServiceBlock(33, true, false, false))
    }

    @Test
    fun silenceReopensOncePerEpisode() {
        val g = SilenceGate()
        assertTrue(g.onSilent())
        assertFalse(g.onSilent())
        assertFalse(g.onSilent())
        g.onFix()
        assertTrue(g.onSilent())
    }
}

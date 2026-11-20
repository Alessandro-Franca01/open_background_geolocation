package dev.openbg.open_background_geolocation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogLevelsTest {
    @Test
    fun offRecordsNothing() {
        for (l in listOf("error", "warning", "info", "debug", "verbose")) {
            assertFalse(LogLevels.enabled(l, "off"))
        }
    }

    @Test
    fun thresholdIncludesMoreSevereLevels() {
        assertTrue(LogLevels.enabled("error", "info"))
        assertTrue(LogLevels.enabled("info", "info"))
        assertFalse(LogLevels.enabled("debug", "info"))
        assertFalse(LogLevels.enabled("verbose", "info"))
    }

    @Test
    fun verboseRecordsEverythingButOff() {
        assertTrue(LogLevels.enabled("verbose", "verbose"))
        assertTrue(LogLevels.enabled("error", "VERBOSE"))
    }

    @Test
    fun unknownConfiguredLevelBehavesAsVerbose() {
        assertTrue(LogLevels.enabled("debug", "nonsense"))
    }

    @Test
    fun formatsLineAsIsoLevelTagMessage() {
        val line = LogLevels.format(LogRow(0L, "info", "engine", "hello"))
        assertEquals("1970-01-01T00:00:00.000Z INFO [engine] hello", line)
    }
}

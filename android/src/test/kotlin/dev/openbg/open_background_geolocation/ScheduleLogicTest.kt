package dev.openbg.open_background_geolocation

import dev.openbg.open_background_geolocation.ScheduleLogic.GEOFENCE
import dev.openbg.open_background_geolocation.ScheduleLogic.LOCATION
import dev.openbg.open_background_geolocation.ScheduleLogic.OFF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleLogicTest {
    private fun w(vararg entries: String) = ScheduleLogic.parse(entries.toList())
    private fun hm(h: Int, m: Int) = h * 60 + m

    // 1 = Sunday ... 7 = Saturday
    private val SUN = 1
    private val MON = 2
    private val FRI = 6
    private val SAT = 7

    @Test
    fun parsesDayRangesListsAndMixes() {
        assertEquals(setOf(2, 3, 4, 5, 6), ScheduleLogic.parseDays("2-6"))
        assertEquals(setOf(1, 7), ScheduleLogic.parseDays("1,7"))
        assertEquals(setOf(1, 3, 4, 5), ScheduleLogic.parseDays("1,3-5"))
        assertEquals((1..7).toSet(), ScheduleLogic.parseDays("1-7"))
    }

    @Test
    fun aDescendingDayRangeWrapsThroughTheWeekend() {
        assertEquals(setOf(6, 7, 1, 2), ScheduleLogic.parseDays("6-2"))
    }

    @Test
    fun rejectsMalformedDays() {
        for (bad in listOf("0", "8", "a", "1-", "-3", "1-2-3", "", "1,,2")) {
            assertNull("'$bad' should be invalid", ScheduleLogic.parseDays(bad))
        }
    }

    @Test
    fun parsesAWindowWithAndWithoutTheGeofenceSuffix() {
        val a = ScheduleLogic.parseEntry("2-6 08:00-18:30")!!
        assertEquals(hm(8, 0), a.startMin)
        assertEquals(hm(18, 30), a.endMin)
        assertEquals(false, a.geofenceOnly)
        assertEquals(true, ScheduleLogic.parseEntry("1,7 09:00-12:00 geofence")!!.geofenceOnly)
    }

    @Test
    fun rejectsMalformedEntriesAndParseSkipsThem() {
        for (bad in listOf("", "2-6", "2-6 8:00", "2-6 25:00-26:00", "2-6 08:60-09:00",
            "2-6 08:00-08:00", "2-6 08:00-09:00 nope", "2-6 08:00-09:00 geofence extra")) {
            assertNull("'$bad' should be invalid", ScheduleLogic.parseEntry(bad))
        }
        assertEquals(1, w("2-6 08:00-09:00", "garbage").size)
    }

    @Test
    fun endIsExclusiveAndTwentyFourIsAllowedAsAnEnd() {
        val windows = w("1-7 08:00-24:00")
        assertEquals(LOCATION, ScheduleLogic.modeAt(windows, MON, hm(8, 0)))
        assertEquals(LOCATION, ScheduleLogic.modeAt(windows, MON, hm(23, 59)))
        assertEquals(OFF, ScheduleLogic.modeAt(windows, MON, hm(7, 59)))
        val day = w("2 08:00-18:00")
        assertEquals(OFF, ScheduleLogic.modeAt(day, MON, hm(18, 0)))
    }

    @Test
    fun windowsCrossingMidnightSpillIntoTheNextDay() {
        val night = w("6 22:00-06:00") // Friday night into Saturday morning
        assertEquals(OFF, ScheduleLogic.modeAt(night, FRI, hm(21, 59)))
        assertEquals(LOCATION, ScheduleLogic.modeAt(night, FRI, hm(22, 0)))
        assertEquals(LOCATION, ScheduleLogic.modeAt(night, FRI, hm(23, 59)))
        assertEquals(LOCATION, ScheduleLogic.modeAt(night, SAT, hm(0, 0)))
        assertEquals(LOCATION, ScheduleLogic.modeAt(night, SAT, hm(5, 59)))
        assertEquals(OFF, ScheduleLogic.modeAt(night, SAT, hm(6, 0)))
        assertEquals(OFF, ScheduleLogic.modeAt(night, SAT, hm(22, 30))) // Saturday is not listed
    }

    @Test
    fun aSundayNightWindowSpillsIntoMondayAcrossTheWeekBoundary() {
        val night = w("7 22:00-06:00") // Saturday night into Sunday
        assertEquals(LOCATION, ScheduleLogic.modeAt(night, SUN, hm(3, 0)))
        assertEquals(OFF, ScheduleLogic.modeAt(night, MON, hm(3, 0)))
    }

    @Test
    fun locationWinsOverGeofenceWhenWindowsOverlap() {
        val windows = w("2 08:00-18:00 geofence", "2 10:00-12:00")
        assertEquals(GEOFENCE, ScheduleLogic.modeAt(windows, MON, hm(9, 0)))
        assertEquals(LOCATION, ScheduleLogic.modeAt(windows, MON, hm(11, 0)))
        assertEquals(GEOFENCE, ScheduleLogic.modeAt(windows, MON, hm(13, 0)))
    }

    @Test
    fun minutesUntilChangeFindsTheNextBoundary() {
        val windows = w("2-6 08:00-18:00")
        assertEquals(60, ScheduleLogic.minutesUntilChange(windows, MON, hm(7, 0)))   // starts at 08:00
        assertEquals(30, ScheduleLogic.minutesUntilChange(windows, MON, hm(17, 30))) // ends at 18:00
        assertEquals(1, ScheduleLogic.minutesUntilChange(windows, MON, hm(7, 59)))
    }

    @Test
    fun minutesUntilChangeCrossesMidnightAndTheWeekend() {
        val windows = w("2-6 08:00-18:00")
        // Friday 18:00 ends the window; the next start is Monday 08:00.
        assertEquals(hm(14, 0) + 2 * 1440, // Fri 18:00 -> Mon 08:00 = 2 days + 14 h
            ScheduleLogic.minutesUntilChange(windows, FRI, hm(18, 0)))
        // From Saturday 12:00 the next change is Monday 08:00.
        assertEquals(hm(20, 0) + 1440, ScheduleLogic.minutesUntilChange(windows, SAT, hm(12, 0)))
    }

    @Test
    fun noChangeWhenEmptyOrAlwaysOn() {
        assertNull(ScheduleLogic.minutesUntilChange(emptyList(), MON, 0))
        // Covers every minute of the week, so the mode never changes.
        val always = listOf(
            ScheduleLogic.Window((1..7).toSet(), 0, 1440, false),
        )
        assertNull(ScheduleLogic.minutesUntilChange(always, MON, hm(12, 0)))
    }
}

package dev.openbg.open_background_geolocation

/**
 * Parsing and evaluation of `AppConfig.schedule` entries. No Android dependencies, so it can be
 * unit-tested on the JVM.
 *
 * Entry format: `"<days> <HH:mm>-<HH:mm> [geofence]"`.
 *  - days: numbers 1..7 where **1 = Sunday**, as a range (`2-6`), a list (`1,7`) or a mix (`1,3-5`).
 *  - times: 24-hour, start inclusive and end exclusive. `24:00` is allowed as an end. When the end
 *    is earlier than the start the window crosses midnight into the next day.
 *  - `geofence` suffix: monitor geofences only instead of tracking continuously.
 */
object ScheduleLogic {
    const val OFF = 0
    const val LOCATION = 1
    const val GEOFENCE = 2

    private const val DAY = 1440
    private const val WEEK = 7 * DAY

    data class Window(val days: Set<Int>, val startMin: Int, val endMin: Int, val geofenceOnly: Boolean) {
        val crossesMidnight: Boolean get() = endMin < startMin
    }

    /** Valid entries only; malformed ones are skipped (callers can compare sizes to detect that). */
    fun parse(entries: List<String>): List<Window> = entries.mapNotNull { parseEntry(it) }

    fun parseEntry(entry: String): Window? {
        val parts = entry.trim().split(Regex("\\s+"))
        if (parts.size !in 2..3) return null
        val days = parseDays(parts[0]) ?: return null
        val times = parts[1].split("-")
        if (times.size != 2) return null
        val start = parseTime(times[0], allowTwentyFour = false) ?: return null
        val end = parseTime(times[1], allowTwentyFour = true) ?: return null
        if (start == end) return null
        val geofence = when {
            parts.size == 2 -> false
            parts[2].equals("geofence", ignoreCase = true) -> true
            else -> return null
        }
        return Window(days, start, end, geofence)
    }

    fun parseDays(text: String): Set<Int>? {
        val out = sortedSetOf<Int>()
        for (token in text.split(",")) {
            val range = token.split("-")
            val from = range[0].toIntOrNull() ?: return null
            val to = if (range.size == 1) from else (range.getOrNull(1)?.toIntOrNull() ?: return null)
            if (range.size > 2 || from !in 1..7 || to !in 1..7) return null
            if (from <= to) out.addAll(from..to) else { out.addAll(from..7); out.addAll(1..to) }
        }
        return out
    }

    private fun parseTime(text: String, allowTwentyFour: Boolean): Int? {
        val hm = text.split(":")
        if (hm.size != 2) return null
        val h = hm[0].toIntOrNull() ?: return null
        val m = hm[1].toIntOrNull() ?: return null
        if (m !in 0..59) return null
        if (h == 24 && m == 0 && allowTwentyFour) return DAY
        if (h !in 0..23) return null
        return h * 60 + m
    }

    private fun previousDay(dow: Int) = if (dow == 1) 7 else dow - 1

    private fun active(w: Window, dow: Int, minute: Int): Boolean =
        if (!w.crossesMidnight) {
            dow in w.days && minute >= w.startMin && minute < w.endMin
        } else {
            (dow in w.days && minute >= w.startMin) || (previousDay(dow) in w.days && minute < w.endMin)
        }

    /** What the schedule wants at [dow] (1 = Sunday) and [minuteOfDay]: [OFF], [LOCATION] or [GEOFENCE]. */
    fun modeAt(windows: List<Window>, dow: Int, minuteOfDay: Int): Int {
        var mode = OFF
        for (w in windows) {
            if (!active(w, dow, minuteOfDay)) continue
            if (!w.geofenceOnly) return LOCATION
            mode = GEOFENCE
        }
        return mode
    }

    /**
     * Minutes from now until the desired mode next changes, or null if it never does within a
     * week. Scans minute by minute; the result is always >= 1.
     */
    fun minutesUntilChange(windows: List<Window>, dow: Int, minuteOfDay: Int): Int? {
        if (windows.isEmpty()) return null
        val current = modeAt(windows, dow, minuteOfDay)
        var d = dow
        var m = minuteOfDay
        for (step in 1..WEEK) {
            m++
            if (m == DAY) { m = 0; d = if (d == 7) 1 else d + 1 }
            if (modeAt(windows, d, m) != current) return step
        }
        return null
    }
}

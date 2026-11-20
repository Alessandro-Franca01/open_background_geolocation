package dev.openbg.open_background_geolocation

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One persisted log line. */
data class LogRow(val ts: Long, val level: String, val tag: String, val message: String)

/** Pure helpers for log filtering and formatting (no Android dependencies, JVM-testable). */
object LogLevels {
    private val order = listOf("off", "error", "warning", "info", "debug", "verbose")

    fun rank(level: String): Int = order.indexOf(level.lowercase()).let { if (it < 0) order.size - 1 else it }

    /** True when a message at [level] should be recorded under the [configured] threshold. */
    fun enabled(level: String, configured: String): Boolean {
        val threshold = rank(configured)
        if (threshold == 0) return false
        return rank(level) in 1..threshold
    }

    fun format(row: LogRow): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return "${f.format(Date(row.ts))} ${row.level.uppercase()} [${row.tag}] ${row.message}"
    }
}

package dev.openbg.open_background_geolocation

import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Persistent diagnostic log, stored in the plugin database and filtered by `logger.logLevel`.
 * Writes happen on a single background thread so callers never block on SQLite.
 */
object Logger {
    private const val PURGE_EVERY = 500
    private val worker = Executors.newSingleThreadExecutor()
    private val sinceLastPurge = AtomicInteger(0)

    fun e(tag: String, msg: String) = log("error", tag, msg)
    fun w(tag: String, msg: String) = log("warning", tag, msg)
    fun i(tag: String, msg: String) = log("info", tag, msg)
    fun d(tag: String, msg: String) = log("debug", tag, msg)
    fun v(tag: String, msg: String) = log("verbose", tag, msg)

    fun log(level: String, tag: String, msg: String) {
        val configured = runCatching { Settings.string("logger", "logLevel", "verbose") }.getOrDefault("off")
        if (!LogLevels.enabled(level, configured)) return
        if (runCatching { Settings.bool("logger", "debug") }.getOrDefault(false)) {
            when (level.lowercase()) {
                "error" -> Log.e("OBG/$tag", msg)
                "warning" -> Log.w("OBG/$tag", msg)
                else -> Log.d("OBG/$tag", msg)
            }
        }
        val ts = System.currentTimeMillis()
        worker.execute {
            runCatching {
                Db.logInsert(ts, level.lowercase(), tag, msg)
                if (sinceLastPurge.incrementAndGet() >= PURGE_EVERY) {
                    sinceLastPurge.set(0)
                    purge()
                }
            }
        }
    }

    /** Drops lines older than `logger.logMaxDays`. */
    fun purge() {
        runCatching { Db.logPurge(Settings.int("logger", "logMaxDays", 3)) }
    }

    fun get(order: String, limit: Int?, start: Long?, end: Long?): String =
        Db.logQuery(order, limit, start, end).joinToString("\n") { LogLevels.format(it) }

    fun clear(): Boolean = Db.logDeleteAll()
}

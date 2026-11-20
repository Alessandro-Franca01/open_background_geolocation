package dev.openbg.open_background_geolocation

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/** Local store for recorded locations (JSON documents indexed by insertion order). */
object Db {
    private lateinit var helper: SQLiteOpenHelper

    fun init(ctx: Context) {
        if (::helper.isInitialized) return
        helper = object : SQLiteOpenHelper(ctx.applicationContext, "obg.db", null, 3) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE locations (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "uuid TEXT UNIQUE NOT NULL, ts INTEGER NOT NULL, json TEXT NOT NULL)"
                )
                createGeofences(db)
                createLogs(db)
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                if (oldVersion < 2) createGeofences(db)
                if (oldVersion < 3) createLogs(db)
            }

            private fun createLogs(db: SQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS logs (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "ts INTEGER NOT NULL, level TEXT NOT NULL, tag TEXT NOT NULL, " +
                        "message TEXT NOT NULL)"
                )
            }

            private fun createGeofences(db: SQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS geofences " +
                        "(identifier TEXT PRIMARY KEY NOT NULL, json TEXT NOT NULL)"
                )
            }
        }
    }

    @Synchronized
    fun insert(uuid: String, tsMillis: Long, json: JSONObject) {
        val values = ContentValues().apply {
            put("uuid", uuid)
            put("ts", tsMillis)
            put("json", json.toString())
        }
        helper.writableDatabase.insertWithOnConflict(
            "locations", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    @Synchronized
    fun query(order: String, limit: Int?, offset: Int?): List<JSONObject> {
        val dir = if (order.equals("DESC", ignoreCase = true)) "DESC" else "ASC"
        val lim = when {
            limit != null && limit > 0 -> "${offset ?: 0}, $limit"
            else -> null
        }
        helper.readableDatabase.query("locations", arrayOf("json"), null, null, null, null, "id $dir", lim)
            .use { c ->
                val out = ArrayList<JSONObject>()
                while (c.moveToNext()) out.add(JSONObject(c.getString(0)))
                return out
            }
    }

    @Synchronized
    fun count(): Int =
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM locations", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    @Synchronized
    fun delete(uuid: String): Boolean =
        helper.writableDatabase.delete("locations", "uuid = ?", arrayOf(uuid)) > 0

    @Synchronized
    fun deleteAll(): Boolean {
        helper.writableDatabase.delete("locations", null, null)
        return true
    }

    /** Applies `maxDaysToPersist` / `maxRecordsToPersist` retention. */
    @Synchronized
    fun purge(maxDays: Int, maxRecords: Int) {
        val db = helper.writableDatabase
        if (maxDays > 0) {
            val cutoff = System.currentTimeMillis() - maxDays * 86_400_000L
            db.delete("locations", "ts < ?", arrayOf(cutoff.toString()))
        }
        if (maxRecords > 0) {
            db.execSQL(
                "DELETE FROM locations WHERE id NOT IN " +
                    "(SELECT id FROM locations ORDER BY id DESC LIMIT $maxRecords)"
            )
        }
    }

    // ------------------------------------------------------------------ logs

    @Synchronized
    fun logInsert(ts: Long, level: String, tag: String, message: String) {
        val values = ContentValues().apply {
            put("ts", ts)
            put("level", level)
            put("tag", tag)
            put("message", message)
        }
        helper.writableDatabase.insert("logs", null, values)
    }

    /** Rows as (ts, level, tag, message), filtered by an optional [start, end] range in millis. */
    @Synchronized
    fun logQuery(order: String, limit: Int?, start: Long?, end: Long?): List<LogRow> {
        val dir = if (order.equals("DESC", ignoreCase = true)) "DESC" else "ASC"
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (start != null) { where += "ts >= ?"; args += start.toString() }
        if (end != null) { where += "ts <= ?"; args += end.toString() }
        helper.readableDatabase.query(
            "logs", arrayOf("ts", "level", "tag", "message"),
            where.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
            args.takeIf { it.isNotEmpty() }?.toTypedArray(),
            null, null, "id $dir", limit?.takeIf { it > 0 }?.toString()
        ).use { c ->
            val out = ArrayList<LogRow>()
            while (c.moveToNext()) out.add(LogRow(c.getLong(0), c.getString(1), c.getString(2), c.getString(3)))
            return out
        }
    }

    @Synchronized
    fun logPurge(maxDays: Int) {
        if (maxDays <= 0) return
        val cutoff = System.currentTimeMillis() - maxDays * 86_400_000L
        helper.writableDatabase.delete("logs", "ts < ?", arrayOf(cutoff.toString()))
    }

    @Synchronized
    fun logDeleteAll(): Boolean {
        helper.writableDatabase.delete("logs", null, null)
        return true
    }

    // ------------------------------------------------------------------ geofences

    @Synchronized
    fun geofencePut(identifier: String, json: JSONObject) {
        val values = ContentValues().apply {
            put("identifier", identifier)
            put("json", json.toString())
        }
        helper.writableDatabase.insertWithOnConflict(
            "geofences", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    @Synchronized
    fun geofenceGet(identifier: String): JSONObject? =
        helper.readableDatabase.query(
            "geofences", arrayOf("json"), "identifier = ?", arrayOf(identifier), null, null, null
        ).use { c -> if (c.moveToFirst()) JSONObject(c.getString(0)) else null }

    @Synchronized
    fun geofenceAll(): List<JSONObject> =
        helper.readableDatabase.query("geofences", arrayOf("json"), null, null, null, null, null)
            .use { c ->
                val out = ArrayList<JSONObject>()
                while (c.moveToNext()) out.add(JSONObject(c.getString(0)))
                out
            }

    @Synchronized
    fun geofenceDelete(identifier: String): Boolean =
        helper.writableDatabase.delete("geofences", "identifier = ?", arrayOf(identifier)) > 0

    @Synchronized
    fun geofenceDeleteAll() {
        helper.writableDatabase.delete("geofences", null, null)
    }
}

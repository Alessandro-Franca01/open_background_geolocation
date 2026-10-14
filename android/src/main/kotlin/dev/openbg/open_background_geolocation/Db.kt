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
        helper = object : SQLiteOpenHelper(ctx.applicationContext, "obg.db", null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE locations (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "uuid TEXT UNIQUE NOT NULL, ts INTEGER NOT NULL, json TEXT NOT NULL)"
                )
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
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
}

package dev.openbg.open_background_geolocation

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import org.json.JSONObject

/**
 * Stores user geofences and keeps the OS geofence monitor in sync with them.
 *
 * Play Services allows at most 100 geofences per app, one of which the tracking engine reserves
 * for stationary detection. When more are stored, only the nearest [MAX_ACTIVE] to the last known
 * position are monitored.
 */
object GeofenceManager {
    private const val MAX_ACTIVE = 95

    private lateinit var app: Context
    private val active = linkedSetOf<String>()

    fun init(ctx: Context) {
        if (!::app.isInitialized) app = ctx.applicationContext
        Db.init(app)
    }

    // ------------------------------------------------------------------ CRUD

    /** Validates and stores geofences, replacing any with the same identifier. */
    fun add(ctx: Context, list: List<Map<*, *>>, cb: (Boolean, String?) -> Unit) {
        init(ctx)
        for (g in list) {
            val id = g["identifier"] as? String
            if (id.isNullOrEmpty()) return cb(false, "Geofence identifier is required")
            if (g["latitude"] !is Number || g["longitude"] !is Number || g["radius"] !is Number) {
                return cb(false, "Geofence '$id' requires latitude, longitude and radius")
            }
        }
        for (g in list) {
            val json = g.toJson()
            val existing = Db.geofenceGet(json.getString("identifier"))
            // Replacing a geofence resets its runtime state unless it is re-added unchanged.
            if (existing != null) {
                json.put("hits", 0)
            }
            Db.geofencePut(json.getString("identifier"), json)
        }
        refresh(force = true, cb)
    }

    fun remove(ctx: Context, ids: List<String>?, cb: (Boolean, String?) -> Unit) {
        init(ctx)
        if (ids == null) Db.geofenceDeleteAll() else ids.forEach { Db.geofenceDelete(it) }
        refresh(force = true, cb)
    }

    fun get(ctx: Context, id: String): Map<String, Any?>? {
        init(ctx)
        return Db.geofenceGet(id)?.toMap()
    }

    fun all(ctx: Context): List<Map<String, Any?>> {
        init(ctx)
        return Db.geofenceAll().map { it.toMap() }
    }

    // ------------------------------------------------------------------ OS registration

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        app, 3, Intent(app, GeofenceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
    )

    /** Registers stored geofences with the OS when tracking is enabled; no-op otherwise. */
    @SuppressLint("MissingPermission")
    fun refresh(force: Boolean = false, cb: ((Boolean, String?) -> Unit)? = null) {
        if (!::app.isInitialized) return cb?.invoke(true, null) ?: Unit
        val client = LocationServices.getGeofencingClient(app)
        if (!Settings.enabled || !TrackingEngine.hasLocationPermission(app)) {
            return cb?.invoke(true, null) ?: Unit
        }

        val all = Db.geofenceAll()
        val chosen = choose(all)
        val chosenIds = chosen.map { it.getString("identifier") }.toSet()
        if (!force && chosenIds == active) return cb?.invoke(true, null) ?: Unit

        val off = active.filter { it !in chosenIds }
        val on = chosen.filter { it.getString("identifier") !in active }

        client.removeGeofences(pendingIntent()).addOnCompleteListener {
            active.clear()
            if (chosen.isEmpty()) {
                emitChange(emptyList(), off)
                return@addOnCompleteListener cb?.invoke(true, null) ?: Unit
            }
            val request = GeofencingRequest.Builder()
                .setInitialTrigger(
                    if (Settings.bool("geolocation", "geofenceInitialTriggerEntry", true))
                        GeofencingRequest.INITIAL_TRIGGER_ENTER else 0
                )
                .addGeofences(chosen.map { toOsGeofence(it) })
                .build()
            client.addGeofences(request, pendingIntent())
                .addOnSuccessListener {
                    active.addAll(chosenIds)
                    emitChange(on, off)
                    cb?.invoke(true, null)
                }
                .addOnFailureListener { cb?.invoke(false, it.message) }
        }
    }

    fun unregisterAll() {
        if (!::app.isInitialized) return
        LocationServices.getGeofencingClient(app).removeGeofences(pendingIntent())
        val off = active.toList()
        active.clear()
        if (off.isNotEmpty()) emitChange(emptyList(), off)
    }

    private fun choose(all: List<JSONObject>): List<JSONObject> {
        if (all.size <= MAX_ACTIVE) return all
        val ref = TrackingEngine.lastKnown() ?: return all.take(MAX_ACTIVE)
        return all.sortedBy {
            val r = FloatArray(1)
            android.location.Location.distanceBetween(
                ref.latitude, ref.longitude, it.getDouble("latitude"), it.getDouble("longitude"), r
            )
            r[0]
        }.take(MAX_ACTIVE)
    }

    private fun toOsGeofence(g: JSONObject): Geofence {
        // Entry and exit notifications are on unless explicitly disabled; dwell is opt-in.
        val entry = g.optBoolean("notifyOnEntry", true)
        val exit = g.optBoolean("notifyOnExit", true)
        val dwell = g.optBoolean("notifyOnDwell", false)
        var mask = 0
        if (entry) mask = mask or Geofence.GEOFENCE_TRANSITION_ENTER
        if (exit) mask = mask or Geofence.GEOFENCE_TRANSITION_EXIT
        if (dwell) mask = mask or Geofence.GEOFENCE_TRANSITION_DWELL
        val b = Geofence.Builder()
            .setRequestId(g.getString("identifier"))
            .setCircularRegion(g.getDouble("latitude"), g.getDouble("longitude"), g.getDouble("radius").toFloat())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(mask)
        if (dwell) b.setLoiteringDelay(g.optInt("loiteringDelay", 0))
        return b.build()
    }

    private fun emitChange(on: List<JSONObject>, off: List<String>) {
        EventBus.emit("geofenceschange", mapOf("on" to on.map { it.toMap() }, "off" to off))
    }

    // ------------------------------------------------------------------ transitions

    fun onTransition(ctx: Context, event: GeofencingEvent) {
        init(ctx)
        TrackingEngine.init(ctx)
        val action = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "ENTER"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "EXIT"
            Geofence.GEOFENCE_TRANSITION_DWELL -> "DWELL"
            else -> return
        }
        for (g in event.triggeringGeofences.orEmpty()) {
            val stored = Db.geofenceGet(g.requestId) ?: continue
            // The OS may omit the triggering fix; fall back to the last known one, then the center.
            val loc = event.triggeringLocation ?: TrackingEngine.lastKnown()
                ?: android.location.Location("geofence").apply {
                    latitude = stored.getDouble("latitude")
                    longitude = stored.getDouble("longitude")
                    time = System.currentTimeMillis()
                }
            stored.put("hits", stored.optInt("hits", 0) + 1)
            stored.put("entryState", if (action == "EXIT") 0 else 1)
            stored.put("stateUpdatedAt", System.currentTimeMillis() / 1000.0)
            Db.geofencePut(g.requestId, stored)
            TrackingEngine.recordGeofence(loc, g.requestId, action, stored)
        }
    }
}

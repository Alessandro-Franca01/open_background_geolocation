package dev.openbg.open_background_geolocation

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONObject
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Core tracking logic. Lives in the app process; [TrackingService] keeps that process alive in the
 * foreground while tracking is enabled.
 *
 * State machine:
 *  - MOVING: continuous fused-location updates filtered by distance. Activity recognition reporting
 *    STILL arms a timer ([stopTimeout] minutes); when it fires the engine goes STATIONARY.
 *  - STATIONARY: location updates are off. A geofence around the last fix (and activity recognition)
 *    wakes the engine back to MOVING.
 */
object TrackingEngine {
    private const val STATIONARY_FENCE_ID = "obg_stationary"

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())

    private var fused: FusedLocationProviderClient? = null
    private var running = false
    private var moving = false
    private var pendingMotionChange = false
    private var lastLocation: android.location.Location? = null
    private var lastActivity: Pair<String, Int> = "unknown" to 0
    private var elasticityFactor = 1.0
    private var providerReceiver: BroadcastReceiver? = null
    private var locationCallback: LocationCallback? = null

    private var stopTimerArmed = false
    private val stopTimer = Runnable { stopTimerArmed = false; onStopTimeout() }
    private val heartbeat = object : Runnable {
        override fun run() {
            if (!running) return
            val interval = Settings.int("app", "heartbeatInterval", -1)
            if (interval <= 0) return
            lastLocation?.let {
                val json = buildLocation(it, "heartbeat", moving)
                EventBus.emit("heartbeat", mapOf("location" to json.toMap()))
            }
            main.postDelayed(this, interval * 1000L)
        }
    }

    fun init(ctx: Context) {
        app = ctx.applicationContext
        Settings.init(app)
        Db.init(app)
    }

    // ------------------------------------------------------------------ public control

    fun start(ctx: Context) {
        init(ctx)
        Settings.enabled = true
        Settings.trackingMode = 1
        Settings.markBooted()
        TrackingService.start(app)
        EventBus.emit("enabledchange", true)
    }

    fun stop(ctx: Context) {
        init(ctx)
        val wasEnabled = Settings.enabled
        Settings.enabled = false
        end()
        TrackingService.stop(app)
        if (wasEnabled) EventBus.emit("enabledchange", false)
    }

    fun changePace(isMoving: Boolean) {
        if (!running) return
        if (isMoving) enterMoving() else enterStationary()
    }

    /** Called by [TrackingService] once it is in the foreground. */
    @SuppressLint("MissingPermission")
    fun begin() {
        if (running) return
        running = true
        fused = LocationServices.getFusedLocationProviderClient(app)
        registerProviderReceiver()
        startActivityUpdates()
        enterMoving()
        scheduleHeartbeat()
        val stopAfter = Settings.int("geolocation", "stopAfterElapsedMinutes", 0)
        if (stopAfter > 0) main.postDelayed({ stop(app) }, stopAfter * 60_000L)
    }

    fun end() {
        if (!running) return
        running = false
        cancelStopTimer()
        main.removeCallbacks(heartbeat)
        removeLocationUpdates()
        removeStationaryGeofence()
        stopActivityUpdates()
        providerReceiver?.let { runCatching { app.unregisterReceiver(it) } }
        providerReceiver = null
        moving = false
        Settings.isMoving = false
        lastLocation = null
    }

    // ------------------------------------------------------------------ state transitions

    private fun enterMoving() {
        cancelStopTimer()
        removeStationaryGeofence()
        moving = true
        Settings.isMoving = true
        pendingMotionChange = true
        elasticityFactor = 1.0
        requestLocationUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun enterStationary() {
        cancelStopTimer()
        removeLocationUpdates()
        moving = false
        Settings.isMoving = false
        pendingMotionChange = false
        val client = fused ?: return
        val emit = { loc: android.location.Location? ->
            if (loc != null) {
                lastLocation = loc
                registerStationaryGeofence(loc)
                record(loc, "motionchange", sample = false)
            }
        }
        val known = lastLocation
        if (known != null) emit(known) else client.lastLocation.addOnSuccessListener { emit(it) }
    }

    private fun onStopTimeout() {
        if (!running || !moving) return
        if (Settings.bool("activity", "stopOnStationary")) {
            stop(app)
        } else {
            enterStationary()
        }
    }

    // ------------------------------------------------------------------ location updates

    private fun priorityFor(accuracy: String): Int = when (accuracy.lowercase()) {
        "navigation", "high" -> Priority.PRIORITY_HIGH_ACCURACY
        "medium" -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        "low", "verylow" -> Priority.PRIORITY_LOW_POWER
        else -> Priority.PRIORITY_PASSIVE
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        if (!hasLocationPermission(app)) return
        val client = fused ?: return
        removeLocationUpdates()

        val significant = Settings.bool("geolocation", "useSignificantChangesOnly")
        val baseFilter = Settings.double("geolocation", "distanceFilter", 10.0)
        val multiplier = Settings.double("geolocation", "elasticityMultiplier", 1.0)
        val elastic = !Settings.bool("geolocation", "disableElasticity") && multiplier > 0
        val distance = when {
            significant -> 500.0
            elastic -> baseFilter * elasticityFactor * multiplier
            else -> baseFilter
        }
        val interval = if (significant) 600_000L
        else Settings.int("geolocation", "locationUpdateInterval", 1000).toLong()
        val priority = if (significant) Priority.PRIORITY_BALANCED_POWER_ACCURACY
        else priorityFor(Settings.string("geolocation", "desiredAccuracy", "high"))

        val builder = LocationRequest.Builder(priority, max(interval, 1000L))
            .setMinUpdateDistanceMeters(distance.toFloat())
        val fastest = Settings.int("geolocation", "fastestLocationUpdateInterval", -1)
        if (fastest >= 0) builder.setMinUpdateIntervalMillis(fastest.toLong())
        val defer = Settings.int("geolocation", "deferTime", 0)
        if (defer > 0) builder.setMaxUpdateDelayMillis(defer.toLong())

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) onLocation(loc)
            }
        }
        locationCallback = cb
        client.requestLocationUpdates(builder.build(), cb, Looper.getMainLooper())
    }

    private fun removeLocationUpdates() {
        locationCallback?.let { fused?.removeLocationUpdates(it) }
        locationCallback = null
    }

    private fun onLocation(loc: android.location.Location) {
        if (!running || !moving) return
        val forced = pendingMotionChange
        val threshold = Settings.obj("geolocation", "filter")
            ?.optDouble("trackingAccuracyThreshold", 100.0) ?: 100.0
        if (!forced && loc.hasAccuracy() && loc.accuracy > threshold) return

        val prev = lastLocation
        if (!forced && prev != null && !Settings.bool("geolocation", "allowIdenticalLocations") &&
            prev.latitude == loc.latitude && prev.longitude == loc.longitude
        ) return

        val event = if (forced) "motionchange" else null
        pendingMotionChange = false
        record(loc, event, sample = false)
        adjustElasticity(loc)
    }

    /** Elasticity: widen the distance filter as speed grows, then re-issue the request. */
    private fun adjustElasticity(loc: android.location.Location) {
        if (Settings.bool("geolocation", "disableElasticity")) return
        val kmh = loc.speed * 3.6
        val factor = min(10.0, 1.0 + (kmh / 20.0).toInt())
        if (factor != elasticityFactor) {
            elasticityFactor = factor
            requestLocationUpdates()
        }
    }

    // ------------------------------------------------------------------ recording

    private fun record(loc: android.location.Location, event: String?, sample: Boolean) {
        val prev = lastLocation
        if (prev != null && prev !== loc) {
            val threshold = Settings.obj("geolocation", "filter")
                ?.optDouble("odometerAccuracyThreshold", 100.0) ?: 100.0
            if (!loc.hasAccuracy() || loc.accuracy <= threshold) {
                Settings.odometer = Settings.odometer + prev.distanceTo(loc)
            }
        }
        lastLocation = loc

        val json = buildLocation(loc, event, moving, sample)
        persist(json, loc.time)
        val data = json.toMap()
        EventBus.emit("location", data)
        if (event == "motionchange") EventBus.emit("motionchange", data)
    }

    private fun persist(json: JSONObject, millis: Long) {
        val mode = Settings.string("persistence", "persistMode", "all").lowercase()
        if (mode == "none" || mode == "geofence") return
        Db.insert(json.getString("uuid"), millis, json)
        Db.purge(
            Settings.int("persistence", "maxDaysToPersist", 1),
            Settings.int("persistence", "maxRecordsToPersist", -1),
        )
    }

    @SuppressLint("NewApi")
    fun buildLocation(
        loc: android.location.Location,
        event: String?,
        isMoving: Boolean,
        sample: Boolean = false,
    ): JSONObject {
        val epoch = Settings.string("persistence", "timestampFormat", "iso") == "epoch"
        val now = System.currentTimeMillis()
        val ts: Any = if (epoch) loc.time else isoTimestamp(loc.time)
        val recorded: Any = if (epoch) now else isoTimestamp(now)
        val ageSec = max(0.0, (android.os.SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1e9)
        val mock = if (Build.VERSION.SDK_INT >= 31) loc.isMock else @Suppress("DEPRECATION") loc.isFromMockProvider

        val json = JSONObject()
            .put("timestamp", ts)
            .put("uuid", UUID.randomUUID().toString())
            .put(
                "coords", JSONObject()
                    .put("latitude", loc.latitude)
                    .put("longitude", loc.longitude)
                    .put("accuracy", if (loc.hasAccuracy()) loc.accuracy.toDouble() else -1.0)
                    .put("speed", if (loc.hasSpeed()) loc.speed.toDouble() else -1.0)
                    .put("heading", if (loc.hasBearing()) loc.bearing.toDouble() else -1.0)
                    .put("altitude", if (loc.hasAltitude()) loc.altitude else 0.0)
            )
            .put("activity", JSONObject().put("type", lastActivity.first).put("confidence", lastActivity.second))
            .put("battery", battery())
            .put("odometer", Settings.odometer)
            .put("is_moving", isMoving)
            .put("sample", sample)
            .put("mock", mock)
            .put("age", ageSec)
            .put("recorded_at", recorded)
        if (event != null) json.put("event", event)
        Settings.obj("persistence", "extras")?.let { json.put("extras", it) }
        return json
    }

    private fun battery(): JSONObject {
        val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return JSONObject()
            .put("level", if (level >= 0 && scale > 0) level.toDouble() / scale else -1.0)
            .put("is_charging", charging)
    }

    // ------------------------------------------------------------------ one-shot / manual

    /** One-shot high-accuracy fix. [cb] receives (location map, error message). */
    @SuppressLint("MissingPermission")
    fun currentPosition(ctx: Context, args: Map<*, *>, cb: (Map<String, Any?>?, String?) -> Unit) {
        init(ctx)
        if (!hasLocationPermission(app)) return cb(null, "Location permission not granted")
        val client = fused ?: LocationServices.getFusedLocationProviderClient(app).also { fused = it }
        val timeoutSec = (args["timeout"] as? Number)?.toInt()
            ?: Settings.int("geolocation", "locationTimeout", 60)
        val maxAge = (args["maximumAge"] as? Number)?.toLong() ?: 0L
        val persist = args["persist"] as? Boolean ?: true
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(timeoutSec * 1000L)
            .setMaxUpdateAgeMillis(maxAge)
            .build()
        client.getCurrentLocation(request, null)
            .addOnSuccessListener { loc ->
                if (loc == null) return@addOnSuccessListener cb(null, "Location unavailable")
                val json = buildLocation(loc, null, moving)
                (args["extras"] as? Map<*, *>)?.let { json.put("extras", it.toJson()) }
                if (persist) persist(json, loc.time)
                cb(json.toMap(), null)
            }
            .addOnFailureListener { cb(null, it.message ?: "Location error") }
    }

    /** Inserts a caller-supplied record, bypassing persist mode. Returns its uuid. */
    fun insertLocation(ctx: Context, params: Map<*, *>): String {
        init(ctx)
        val json = params.toJson()
        val uuid = json.optString("uuid").ifEmpty { UUID.randomUUID().toString() }
        json.put("uuid", uuid)
        if (!json.has("timestamp")) json.put("timestamp", isoTimestamp(System.currentTimeMillis()))
        Db.insert(uuid, System.currentTimeMillis(), json)
        return uuid
    }

    // ------------------------------------------------------------------ activity recognition

    private fun activityIntent(): PendingIntent = PendingIntent.getBroadcast(
        app, 1, Intent(app, ActivityReceiver::class.java), mutableFlag()
    )

    @SuppressLint("MissingPermission")
    private fun startActivityUpdates() {
        if (Settings.bool("activity", "disableMotionActivityUpdates")) return
        if (Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.ACTIVITY_RECOGNITION)) return
        ActivityRecognition.getClient(app).requestActivityUpdates(
            Settings.int("activity", "activityRecognitionInterval", 10000).toLong(),
            activityIntent()
        )
    }

    @SuppressLint("MissingPermission")
    private fun stopActivityUpdates() {
        if (Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.ACTIVITY_RECOGNITION)) return
        ActivityRecognition.getClient(app).removeActivityUpdates(activityIntent())
    }

    fun onActivity(type: Int, confidence: Int) {
        if (!running) return
        val minConf = Settings.int("activity", "minimumActivityRecognitionConfidence", 75)
        if (confidence < minConf) return
        val name = activityName(type)
        val changed = name != lastActivity.first
        lastActivity = name to confidence
        if (changed) {
            EventBus.emit("activitychange", mapOf("activity" to name, "confidence" to confidence))
        }
        if (Settings.bool("activity", "disableStopDetection")) return

        if (moving) {
            if (name == "still") {
                if (!stopTimerArmed) {
                    stopTimerArmed = true
                    val minutes = Settings.int("geolocation", "stopTimeout", 5)
                    main.postDelayed(stopTimer, minutes * 60_000L)
                }
            } else if (name != "unknown") {
                cancelStopTimer()
            }
        } else if (name != "still" && name != "unknown" && isTrigger(name)) {
            val delay = Settings.int("activity", "motionTriggerDelay", 0).toLong()
            main.postDelayed({ if (running && !moving && lastActivity.first != "still") enterMoving() }, delay)
        }
    }

    private fun cancelStopTimer() {
        stopTimerArmed = false
        main.removeCallbacks(stopTimer)
    }

    /** Empty / unset `triggerActivities` means every moving activity can wake the engine. */
    private fun isTrigger(name: String): Boolean {
        val list = Settings.array("activity", "triggerActivities")
        if (list == null || list.length() == 0) return true
        return (0 until list.length()).any { list.optString(it).equals(name, ignoreCase = true) }
    }

    private fun activityName(type: Int) = when (type) {
        DetectedActivity.STILL -> "still"
        DetectedActivity.WALKING -> "walking"
        DetectedActivity.ON_FOOT -> "on_foot"
        DetectedActivity.RUNNING -> "running"
        DetectedActivity.IN_VEHICLE -> "in_vehicle"
        DetectedActivity.ON_BICYCLE -> "on_bicycle"
        else -> "unknown"
    }

    // ------------------------------------------------------------------ stationary geofence

    private fun geofenceIntent(): PendingIntent = PendingIntent.getBroadcast(
        app, 2, Intent(app, StationaryReceiver::class.java), mutableFlag()
    )

    @SuppressLint("MissingPermission")
    private fun registerStationaryGeofence(loc: android.location.Location) {
        if (!hasLocationPermission(app)) return
        val radius = max(Settings.int("geolocation", "stationaryRadius", 150), 100).toFloat()
        val fence = Geofence.Builder()
            .setRequestId(STATIONARY_FENCE_ID)
            .setCircularRegion(loc.latitude, loc.longitude, radius)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()
        val request = GeofencingRequest.Builder().addGeofence(fence).build()
        LocationServices.getGeofencingClient(app).addGeofences(request, geofenceIntent())
    }

    private fun removeStationaryGeofence() {
        if (!::app.isInitialized) return
        LocationServices.getGeofencingClient(app).removeGeofences(geofenceIntent())
    }

    fun onStationaryExit() {
        if (running && !moving) enterMoving()
    }

    // ------------------------------------------------------------------ provider state

    private fun registerProviderReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                EventBus.emit("providerchange", providerState(context))
            }
        }
        providerReceiver = r
        ContextCompat.registerReceiver(
            app, r, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun providerState(ctx: Context): Map<String, Any?> {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gps = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        val net = runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION, ctx)
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION, ctx)
        val bg = Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION, ctx)
        // Status codes follow CLAuthorizationStatus: 2 denied, 3 always, 4 whenInUse.
        val status = when {
            fine && bg -> 3
            fine || coarse -> 4
            else -> 2
        }
        return mapOf(
            "enabled" to (gps || net),
            "gps" to gps,
            "network" to net,
            "status" to status,
            "accuracyAuthorization" to if (fine) 1 else 0,
        )
    }

    // ------------------------------------------------------------------ heartbeat

    private fun scheduleHeartbeat() {
        val interval = Settings.int("app", "heartbeatInterval", -1)
        if (interval > 0) main.postDelayed(heartbeat, interval * 1000L)
    }

    // ------------------------------------------------------------------ helpers

    private fun mutableFlag(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)

    private fun granted(permission: String, ctx: Context = app): Boolean =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    fun hasLocationPermission(ctx: Context): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION, ctx) ||
            granted(Manifest.permission.ACCESS_COARSE_LOCATION, ctx)
}

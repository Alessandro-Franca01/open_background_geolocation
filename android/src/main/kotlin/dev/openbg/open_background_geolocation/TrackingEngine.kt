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
import android.net.ConnectivityManager
import android.net.Network
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
    private const val DISTANCE_CHECK_MS = 60_000L
    private const val TAG = "engine"

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())

    private var fused: FusedLocationProviderClient? = null
    private var running = false
    private var moving = false
    private var pendingMotionChange = false
    private var lastLocation: android.location.Location? = null
    private var lastActivity: Pair<String, Int> = "unknown" to 0
    private val elasticity = ElasticityGate()
    private val elasticityFactor: Double get() = elasticity.current
    private val stopDetector = StopDetector()

    /**
     * Set when the distance detector (not activity recognition) put the engine in STATIONARY: an
     * idling vehicle keeps reporting `in_vehicle`, which would turn GPS back on at once. Cleared by
     * leaving the stationary geofence, by `changePace(true)` or by activity recognition reporting STILL.
     */
    private var suppressActivityWake = false

    // Battery stats for the current session (reset in begin()).
    private var sessionStart = 0L
    private var movingSince = 0L
    private var stationarySince = 0L
    private var gpsOnMillis = 0L
    private var stationaryMillis = 0L
    private var fixCount = 0
    private var lastFixAt = 0L
    private var stationaryTransitions = 0
    private var providerReceiver: BroadcastReceiver? = null
    private var powerReceiver: BroadcastReceiver? = null
    private var locationCallback: LocationCallback? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var stopTimerArmed = false
    private val stopTimer = Runnable { stopTimerArmed = false; onStopTimeout() }
    private val distanceCheck = object : Runnable {
        override fun run() {
            if (!running || !moving) return
            if (distanceStopEnabled()) {
                val minutes = Settings.int("geolocation", "stopTimeout", 5)
                if (stopDetector.shouldStop(System.currentTimeMillis(), minutes * 60_000L)) {
                    Logger.i(
                        TAG,
                        "no displacement > ${stationaryRadius()}m for $minutes min; going STATIONARY " +
                            "(stopOnStationary=${Settings.bool("activity", "stopOnStationary")})"
                    )
                    if (Settings.bool("activity", "stopOnStationary")) stop(app) else enterStationary(byDistance = true)
                    return
                }
            }
            main.postDelayed(this, DISTANCE_CHECK_MS)
        }
    }
    private val heartbeat = object : Runnable {
        override fun run() {
            if (!running) return
            val interval = Settings.int("app", "heartbeatInterval", -1)
            if (interval <= 0) return
            Logger.d(TAG, "heartbeat moving=$moving lastFix=${lastLocation != null}")
            lastLocation?.let {
                val json = buildLocation(it, "heartbeat", moving)
                EventBus.emit("heartbeat", mapOf("location" to json.toMap()))
            }
            HttpSync.autoSync(app) // heartbeat retries queued uploads
            main.postDelayed(this, interval * 1000L)
        }
    }

    fun init(ctx: Context) {
        app = ctx.applicationContext
        EventBus.context = app
        Settings.init(app)
        Db.init(app)
    }

    // ------------------------------------------------------------------ public control

    fun start(ctx: Context) {
        init(ctx)
        val wasEnabled = Settings.enabled
        Settings.enabled = true
        Settings.trackingMode = 1
        Settings.markBooted()
        Logger.i(TAG, "start() requested (wasEnabled=$wasEnabled)")
        TrackingService.start(app)
        GeofenceManager.init(app)
        GeofenceManager.refresh(force = true)
        if (!wasEnabled) EventBus.emit("enabledchange", true)
    }

    /** Geofence-only mode: no continuous location, no foreground service. */
    fun startGeofences(ctx: Context) {
        init(ctx)
        val wasEnabled = Settings.enabled
        Settings.enabled = true
        Settings.trackingMode = 0
        Settings.markBooted()
        Logger.i(TAG, "startGeofences() requested (wasEnabled=$wasEnabled)")
        end()
        TrackingService.stop(app)
        GeofenceManager.init(app)
        GeofenceManager.refresh(force = true)
        if (!wasEnabled) EventBus.emit("enabledchange", true)
    }

    fun stop(ctx: Context) {
        init(ctx)
        val wasEnabled = Settings.enabled
        Settings.enabled = false
        Logger.i(TAG, "stop() requested (wasEnabled=$wasEnabled)")
        stopAllWatchers()
        end()
        TrackingService.stop(app)
        GeofenceManager.init(app)
        GeofenceManager.unregisterAll()
        if (wasEnabled) EventBus.emit("enabledchange", false)
        // Let the final `enabledchange` reach the headless task before the engine goes away.
        main.postDelayed({ if (!Settings.enabled) HeadlessTask.shutdown() }, 2000)
    }

    fun changePace(isMoving: Boolean) {
        if (!running) return
        Logger.i(TAG, "changePace(isMoving=$isMoving) requested by the app")
        if (isMoving) enterMoving() else enterStationary()
    }

    /** Battery counters for the current session; exposed in the state map as `stats`. */
    fun stats(): Map<String, Any?> {
        val now = System.currentTimeMillis()
        val gps = gpsOnMillis + if (running && moving && movingSince > 0) now - movingSince else 0
        val still = stationaryMillis + if (running && !moving && stationarySince > 0) now - stationarySince else 0
        return mapOf(
            "running" to running,
            "sessionMillis" to if (running && sessionStart > 0) now - sessionStart else 0L,
            "gpsOnMillis" to gps,
            "stationaryMillis" to still,
            "fixCount" to fixCount,
            "httpRequestCount" to HttpSync.requestCount(),
            "stationaryTransitions" to stationaryTransitions,
            "lastFixAt" to if (lastFixAt > 0) isoTimestamp(lastFixAt) else null,
        )
    }

    private fun logStats(reason: String) {
        val s = stats()
        Logger.i(
            TAG,
            "stats ($reason): session=${(s["sessionMillis"] as Long) / 1000}s " +
                "gpsOn=${(s["gpsOnMillis"] as Long) / 1000}s stationary=${(s["stationaryMillis"] as Long) / 1000}s " +
                "fixes=${s["fixCount"]} httpRequests=${s["httpRequestCount"]} stops=${s["stationaryTransitions"]}"
        )
    }

    /** Called by [TrackingService] once it is in the foreground. */
    @SuppressLint("MissingPermission")
    fun begin() {
        if (running) return
        running = true
        sessionStart = System.currentTimeMillis()
        movingSince = 0L
        stationarySince = 0L
        gpsOnMillis = 0L
        stationaryMillis = 0L
        fixCount = 0
        lastFixAt = 0L
        stationaryTransitions = 0
        HttpSync.resetRequestCount()
        Logger.purge()
        Logger.i(
            TAG,
            "begin(): sdk=${Build.VERSION.SDK_INT} model=${Build.MANUFACTURER} ${Build.MODEL} " +
                "locationPermission=${hasLocationPermission(app)} " +
                "backgroundPermission=${Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)} " +
                "activityPermission=${Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACTIVITY_RECOGNITION)} " +
                "ignoringBatteryOptimizations=${isIgnoringBatteryOptimizations()}"
        )
        fused = LocationServices.getFusedLocationProviderClient(app)
        registerProviderReceiver()
        registerPowerReceiver()
        registerNetworkCallback()
        startActivityUpdates()
        enterMoving()
        scheduleHeartbeat()
        val stopAfter = Settings.int("geolocation", "stopAfterElapsedMinutes", 0)
        if (stopAfter > 0) main.postDelayed({ stop(app) }, stopAfter * 60_000L)
    }

    fun end() {
        if (!running) return
        accumulateStateTime()
        logStats("end")
        running = false
        Logger.i(TAG, "end(): engine stopped")
        cancelStopTimer()
        main.removeCallbacks(distanceCheck)
        suppressActivityWake = false
        main.removeCallbacks(heartbeat)
        removeLocationUpdates()
        removeStationaryGeofence()
        stopActivityUpdates()
        providerReceiver?.let { runCatching { app.unregisterReceiver(it) } }
        providerReceiver = null
        powerReceiver?.let { runCatching { app.unregisterReceiver(it) } }
        powerReceiver = null
        unregisterNetworkCallback()
        moving = false
        Settings.isMoving = false
        lastLocation = null
    }

    // ------------------------------------------------------------------ state transitions

    private fun enterMoving() {
        Logger.i(TAG, "-> MOVING (activity=${lastActivity.first}/${lastActivity.second})")
        cancelStopTimer()
        removeStationaryGeofence()
        accumulateStateTime()
        moving = true
        movingSince = System.currentTimeMillis()
        Settings.isMoving = true
        pendingMotionChange = true
        suppressActivityWake = false
        elasticity.reset()
        // A false wake (no real displacement) goes back to STATIONARY after stopTimeout.
        stopDetector.reset()
        lastLocation?.let { stopDetector.seed(it.latitude, it.longitude, movingSince) }
        main.removeCallbacks(distanceCheck)
        main.postDelayed(distanceCheck, DISTANCE_CHECK_MS)
        requestLocationUpdates()
    }

    /** Adds the time spent in the current state to the session counters. */
    private fun accumulateStateTime() {
        val now = System.currentTimeMillis()
        if (moving && movingSince > 0) gpsOnMillis += now - movingSince
        if (!moving && stationarySince > 0) stationaryMillis += now - stationarySince
        movingSince = 0L
        stationarySince = 0L
    }

    @SuppressLint("MissingPermission")
    private fun enterStationary(byDistance: Boolean = false) {
        Logger.i(
            TAG,
            "-> STATIONARY (activity=${lastActivity.first}/${lastActivity.second} byDistance=$byDistance); GPS updates off"
        )
        cancelStopTimer()
        main.removeCallbacks(distanceCheck)
        removeLocationUpdates()
        accumulateStateTime()
        moving = false
        stationarySince = System.currentTimeMillis()
        stationaryTransitions++
        suppressActivityWake = byDistance
        Settings.isMoving = false
        pendingMotionChange = false
        logStats("stationary")
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
        Logger.i(TAG, "stopTimeout elapsed with the device still; stopOnStationary=${Settings.bool("activity", "stopOnStationary")}")
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
        if (!hasLocationPermission(app)) {
            Logger.w(TAG, "requestLocationUpdates skipped: location permission not granted")
            return
        }
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

        Logger.d(
            TAG,
            "LocationRequest priority=$priority interval=${max(interval, 1000L)}ms " +
                "distanceFilter=${distance}m elasticityFactor=$elasticityFactor significant=$significant"
        )
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
        if (!forced && loc.hasAccuracy() && loc.accuracy > threshold) {
            Logger.v(TAG, "fix dropped: accuracy ${loc.accuracy}m > threshold ${threshold}m")
            return
        }

        val prev = lastLocation
        if (!forced && prev != null && !Settings.bool("geolocation", "allowIdenticalLocations") &&
            prev.latitude == loc.latitude && prev.longitude == loc.longitude
        ) {
            Logger.v(TAG, "fix dropped: identical to the previous location")
            return
        }
        Logger.v(TAG, "fix accepted: acc=${loc.accuracy}m speed=${loc.speed} forced=$forced")

        val event = if (forced) "motionchange" else null
        pendingMotionChange = false
        record(loc, event, sample = false)
        fixCount++
        lastFixAt = System.currentTimeMillis()
        stopDetector.onFix(
            loc.latitude, loc.longitude,
            if (loc.hasAccuracy()) loc.accuracy.toDouble() else 0.0,
            lastFixAt, stationaryRadius().toDouble(),
        )
        adjustElasticity(loc)
    }

    /** Elasticity: widen the distance filter as speed grows, then re-issue the request (with hysteresis). */
    private fun adjustElasticity(loc: android.location.Location) {
        if (Settings.bool("geolocation", "disableElasticity")) return
        if (elasticity.offer(loc.speed.toDouble())) requestLocationUpdates()
    }

    private fun stationaryRadius(): Int = max(Settings.int("geolocation", "stationaryRadius", 150), 100)

    private fun distanceStopEnabled(): Boolean =
        !Settings.bool("activity", "disableStopDetection") &&
            !Settings.bool("activity", "disableDistanceStopDetection")

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
        HttpSync.autoSync(app)
    }

    /** Last fix seen by the engine, or null if tracking has not produced one yet. */
    fun lastKnown(): android.location.Location? = lastLocation

    /** Records and publishes a geofence transition. [fence] is the stored geofence document. */
    fun recordGeofence(loc: android.location.Location, id: String, action: String, fence: JSONObject) {
        val now = isoTimestamp(System.currentTimeMillis())
        val json = buildLocation(loc, "geofence", Settings.isMoving)
        json.put("geofence", JSONObject().put("identifier", id).put("action", action).put("timestamp", now))
        fence.optJSONObject("extras")?.let { json.put("extras", it) }
        persist(json, loc.time, geofence = true)
        EventBus.emit(
            "geofence",
            mapOf(
                "identifier" to id,
                "action" to action,
                "location" to json.toMap(),
                "geofence" to fence.toMap(),
                "extras" to fence.optJSONObject("extras")?.toMap(),
                "timestamp" to now,
            )
        )
        HttpSync.autoSync(app)
    }

    private fun persist(json: JSONObject, millis: Long, geofence: Boolean = false) {
        val mode = Settings.string("persistence", "persistMode", "all").lowercase()
        if (mode == "none") return
        if (geofence && mode == "location") return
        if (!geofence && mode == "geofence") return
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

    // ------------------------------------------------------------------ watchPosition

    private const val WATCH_TIMEOUT_CODE = 408
    private var nextWatchId = 1
    private val watchers = mutableMapOf<Int, LocationCallback>()
    private val watchTimeouts = mutableMapOf<Int, Runnable>()

    /** Maps a `desiredAccuracy` argument (name or meters) to a fused-location priority. */
    internal fun watchPriority(arg: Any?): Int = when (arg) {
        is String -> priorityFor(arg)
        is Number -> when {
            arg.toDouble() <= 10.0 -> Priority.PRIORITY_HIGH_ACCURACY
            arg.toDouble() <= 100.0 -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
            else -> Priority.PRIORITY_LOW_POWER
        }
        else -> priorityFor(Settings.string("geolocation", "desiredAccuracy", "high"))
    }

    /**
     * Starts an independent position stream that does not touch the main tracking state. Each fix
     * is emitted as `watchposition` `{id, location}`; a missing first fix within `timeout` seconds
     * emits `{id, error: 408}`. Returns the watch id.
     */
    @SuppressLint("MissingPermission")
    fun watchPosition(ctx: Context, args: Map<*, *>): Int {
        init(ctx)
        if (!hasLocationPermission(app)) throw IllegalStateException("Location permission not granted")
        val client = fused ?: LocationServices.getFusedLocationProviderClient(app).also { fused = it }

        val id = nextWatchId++
        val interval = max((args["interval"] as? Number)?.toLong() ?: 1000L, 1000L)
        val persist = args["persist"] as? Boolean ?: false
        val extras = (args["extras"] as? Map<*, *>)?.toJson()
        val timeoutSec = (args["timeout"] as? Number)?.toInt() ?: 0

        val request = LocationRequest.Builder(watchPriority(args["desiredAccuracy"]), interval).build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                watchTimeouts.remove(id)?.let { main.removeCallbacks(it) }
                for (loc in result.locations) {
                    val json = buildLocation(loc, null, moving)
                    extras?.let { json.put("extras", it) }
                    if (persist) persist(json, loc.time)
                    EventBus.emit("watchposition", mapOf("id" to id, "location" to json.toMap()))
                }
            }
        }
        watchers[id] = cb
        client.requestLocationUpdates(request, cb, Looper.getMainLooper())
        Logger.i(TAG, "watchPosition started id=$id interval=${interval}ms persist=$persist timeout=${timeoutSec}s")

        if (timeoutSec > 0) {
            val r = Runnable {
                watchTimeouts.remove(id)
                Logger.w(TAG, "watchPosition id=$id: no fix within ${timeoutSec}s")
                EventBus.emit("watchposition", mapOf("id" to id, "error" to WATCH_TIMEOUT_CODE))
            }
            watchTimeouts[id] = r
            main.postDelayed(r, timeoutSec * 1000L)
        }
        return id
    }

    fun stopWatchPosition(id: Int): Boolean {
        watchTimeouts.remove(id)?.let { main.removeCallbacks(it) }
        val cb = watchers.remove(id) ?: return false
        fused?.removeLocationUpdates(cb)
        Logger.i(TAG, "watchPosition stopped id=$id")
        return true
    }

    fun stopAllWatchers() {
        for (id in watchers.keys.toList()) stopWatchPosition(id)
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
            Logger.d(TAG, "activity changed: $name ($confidence%)")
            EventBus.emit("activitychange", mapOf("activity" to name, "confidence" to confidence))
        }
        if (Settings.bool("activity", "disableStopDetection")) return

        if (moving) {
            if (name == "still") {
                if (!stopTimerArmed) {
                    stopTimerArmed = true
                    val minutes = Settings.int("geolocation", "stopTimeout", 5)
                    Logger.i(TAG, "device still; stop timer armed for $minutes min")
                    main.postDelayed(stopTimer, minutes * 60_000L)
                }
            } else if (name != "unknown") {
                cancelStopTimer()
            }
        } else if (name == "still") {
            if (suppressActivityWake) Logger.d(TAG, "activity STILL while stationary; activity wake re-enabled")
            suppressActivityWake = false
        } else if (name != "unknown" && isTrigger(name)) {
            if (suppressActivityWake) {
                Logger.v(TAG, "activity $name ignored: stopped by distance, waiting for the stationary geofence exit")
                return
            }
            val delay = Settings.int("activity", "motionTriggerDelay", 0).toLong()
            main.postDelayed(
                { if (running && !moving && !suppressActivityWake && lastActivity.first != "still") enterMoving() },
                delay
            )
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
        val radius = stationaryRadius().toFloat()
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
        Logger.i(TAG, "left the stationary geofence (running=$running moving=$moving)")
        if (running && !moving) enterMoving()
    }

    // ------------------------------------------------------------------ provider state

    private fun registerProviderReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = providerState(context)
                Logger.i(TAG, "provider change: gps=${state["gps"]} network=${state["network"]} status=${state["status"]}")
                EventBus.emit("providerchange", state)
            }
        }
        providerReceiver = r
        ContextCompat.registerReceiver(
            app, r, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    // ------------------------------------------------------------------ power state

    private fun isIgnoringBatteryOptimizations(): Boolean = runCatching {
        val pm = app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(app.packageName)
    }.getOrDefault(false)

    /** Power-save and Doze transitions: the usual suspects for silent gaps in tracking. */
    private fun registerPowerReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                when (intent.action) {
                    android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                        val on = pm.isPowerSaveMode
                        Logger.i(TAG, "power save mode changed: enabled=$on")
                        EventBus.emit("powersavechange", mapOf("enabled" to on))
                    }
                    android.os.PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                        val idle = Build.VERSION.SDK_INT >= 23 && pm.isDeviceIdleMode
                        Logger.i(TAG, "Doze (device idle) mode changed: idle=$idle")
                    }
                }
            }
        }
        powerReceiver = r
        val filter = IntentFilter(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        if (Build.VERSION.SDK_INT >= 23) filter.addAction(android.os.PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        ContextCompat.registerReceiver(app, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
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

    // ------------------------------------------------------------------ connectivity

    private fun registerNetworkCallback() {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Logger.i(TAG, "network available")
                EventBus.emit("connectivitychange", mapOf("connected" to true))
                HttpSync.autoSync(app) // flush records queued while offline
            }

            override fun onLost(network: Network) {
                Logger.i(TAG, "network lost")
                EventBus.emit("connectivitychange", mapOf("connected" to false))
            }
        }
        networkCallback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        runCatching { cm.unregisterNetworkCallback(cb) }
        networkCallback = null
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

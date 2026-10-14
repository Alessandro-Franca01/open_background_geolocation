package dev.openbg.open_background_geolocation

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Effective configuration (defaults + user overrides) and persisted tracking state. */
object Settings {
    private lateinit var prefs: SharedPreferences
    private var cfg = JSONObject()

    /** Keys whose value is replaced wholesale instead of deep-merged. */
    private val replaceKeys = setOf("headers", "params", "extras", "schedule", "triggerActivities")

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("obg_settings", Context.MODE_PRIVATE)
        cfg = defaults()
        prefs.getString("config", null)?.let { merge(cfg, JSONObject(it)) }
    }

    private fun defaults(): JSONObject = JSONObject(
        mapOf(
            "geolocation" to mapOf(
                "desiredAccuracy" to "high",
                "distanceFilter" to 10.0,
                "stationaryRadius" to 150,
                "stopTimeout" to 5,
                "locationUpdateInterval" to 1000,
                "fastestLocationUpdateInterval" to -1,
                "deferTime" to 0,
                "locationTimeout" to 60,
                "stopAfterElapsedMinutes" to 0,
                "disableElasticity" to false,
                "elasticityMultiplier" to 1.0,
                "useSignificantChangesOnly" to false,
                "allowIdenticalLocations" to false,
                "geofenceProximityRadius" to 1000,
                "geofenceInitialTriggerEntry" to true,
                "geofenceModeHighAccuracy" to false,
                "locationAuthorizationRequest" to "Always",
                "filter" to mapOf(
                    "trackingAccuracyThreshold" to 100.0,
                    "odometerAccuracyThreshold" to 100.0,
                ),
            ),
            "activity" to mapOf(
                "activityRecognitionInterval" to 10000,
                "minimumActivityRecognitionConfidence" to 75,
                "motionTriggerDelay" to 0,
                "disableStopDetection" to false,
                "disableMotionActivityUpdates" to false,
                "stopOnStationary" to false,
            ),
            "http" to mapOf(
                "autoSync" to true,
                "autoSyncThreshold" to 0,
                "batchSync" to false,
                "disableAutoSyncOnCellular" to false,
                "maxBatchSize" to -1,
                "method" to "POST",
                "rootProperty" to "location",
                "timeout" to 60000,
            ),
            "persistence" to mapOf(
                "persistMode" to "all",
                "maxDaysToPersist" to 1,
                "maxRecordsToPersist" to -1,
                "locationsOrderDirection" to "ASC",
                "timestampFormat" to "iso",
            ),
            "app" to mapOf(
                "stopOnTerminate" to true,
                "startOnBoot" to false,
                "enableHeadless" to false,
                "heartbeatInterval" to -1,
            ),
            "logger" to mapOf(
                "debug" to false,
                "logLevel" to "verbose",
                "logMaxDays" to 3,
            ),
        )
    )

    private fun merge(dst: JSONObject, src: JSONObject) {
        for (key in src.keys()) {
            val v = src.get(key)
            val existing = dst.opt(key)
            if (v is JSONObject && existing is JSONObject && key !in replaceKeys) {
                merge(existing, v)
            } else {
                dst.put(key, v)
            }
        }
    }

    private fun save() = prefs.edit().putString("config", cfg.toString()).apply()

    /** `reset == true` starts from defaults; otherwise the stored config is kept and merged. */
    fun ready(overrides: Map<*, *>, reset: Boolean) {
        if (reset) cfg = defaults()
        merge(cfg, overrides.toJson())
        cfg.remove("reset")
        save()
    }

    fun update(overrides: Map<*, *>) {
        merge(cfg, overrides.toJson())
        save()
    }

    fun reset(overrides: Map<*, *>?) {
        cfg = defaults()
        if (overrides != null) merge(cfg, overrides.toJson())
        cfg.remove("reset")
        save()
    }

    // ------------------------------------------------------------------ typed reads

    private fun group(g: String): JSONObject = cfg.optJSONObject(g) ?: JSONObject()
    fun obj(g: String, k: String): JSONObject? = group(g).optJSONObject(k)
    fun array(g: String, k: String): org.json.JSONArray? = group(g).optJSONArray(k)
    fun int(g: String, k: String, def: Int = 0): Int = group(g).optInt(k, def)
    fun double(g: String, k: String, def: Double = 0.0): Double = group(g).optDouble(k, def)
    fun bool(g: String, k: String, def: Boolean = false): Boolean = group(g).optBoolean(k, def)
    fun string(g: String, k: String, def: String = ""): String = group(g).optString(k, def)

    // ------------------------------------------------------------------ tracking state

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    var isMoving: Boolean
        get() = prefs.getBoolean("isMoving", false)
        set(v) = prefs.edit().putBoolean("isMoving", v).apply()

    /** 0 = geofences only, 1 = location + geofences. */
    var trackingMode: Int
        get() = prefs.getInt("trackingMode", 1)
        set(v) = prefs.edit().putInt("trackingMode", v).apply()

    var odometer: Double
        get() = java.lang.Double.longBitsToDouble(prefs.getLong("odometer", 0L))
        set(v) = prefs.edit().putLong("odometer", java.lang.Double.doubleToRawLongBits(v)).apply()

    var didDeviceReboot: Boolean
        get() = prefs.getBoolean("didReboot", false)
        set(v) = prefs.edit().putBoolean("didReboot", v).apply()

    /** True until the first call to [markBooted] after installation. */
    val isFirstBoot: Boolean get() = !prefs.getBoolean("booted", false)
    fun markBooted() = prefs.edit().putBoolean("booted", true).apply()

    fun stateMap(): Map<String, Any?> {
        val m = cfg.toMap().toMutableMap()
        m["enabled"] = enabled
        m["isMoving"] = isMoving
        m["trackingMode"] = trackingMode
        m["odometer"] = odometer
        m["schedulerEnabled"] = false
        m["didDeviceReboot"] = didDeviceReboot
        m["didLaunchInBackground"] = false
        m["isFirstBoot"] = isFirstBoot
        return m
    }
}

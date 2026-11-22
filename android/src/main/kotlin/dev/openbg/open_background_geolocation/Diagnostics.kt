package dev.openbg.open_background_geolocation

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject

/**
 * Tracking health in one place, so the app can show why it is not tracking. The last error is
 * persisted: a failure at boot or from the schedule happens with no UI, and the app reads it later.
 */
object Diagnostics {
    private const val TAG = "diagnostics"

    const val STOPPED = "stopped"
    const val STARTING = "starting"
    const val RUNNING = "running"
    const val FAILED = "failed"

    private lateinit var prefs: SharedPreferences

    @Volatile
    var serviceState: String = STOPPED
        private set
    private var serviceStateAt = 0L

    /** Last value reported by the fused provider's `onLocationAvailability`, null if unknown. */
    @Volatile
    var locationAvailable: Boolean? = null

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("obg_diagnostics", Context.MODE_PRIVATE)
    }

    fun setServiceState(state: String) {
        if (state == serviceState) return
        serviceState = state
        serviceStateAt = System.currentTimeMillis()
        Logger.i(TAG, "service state -> $state")
    }

    /** Records an error: persisted, logged and emitted as `trackingerror`. */
    fun record(code: String, message: String) {
        val now = System.currentTimeMillis()
        val ts = isoTimestamp(now)
        if (::prefs.isInitialized) {
            prefs.edit().putString(
                "lastError",
                JSONObject().put("code", code).put("message", message).put("timestamp", ts).toString()
            ).apply()
        }
        Logger.e(TAG, "$code: $message")
        EventBus.emit("trackingerror", mapOf("code" to code, "message" to message, "timestamp" to ts))
    }

    /** Records a failed service start and marks the service as failed. */
    fun serviceFailed(code: String, message: String) {
        setServiceState(FAILED)
        record(code, message)
    }

    fun lastError(): Map<String, Any?>? =
        if (!::prefs.isInitialized) null
        else prefs.getString("lastError", null)?.let { runCatching { JSONObject(it).toMap() }.getOrNull() }

    fun appInForeground(): Boolean = runCatching {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(false)

    fun snapshot(ctx: Context): Map<String, Any?> {
        val provider = TrackingEngine.providerState(ctx)
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return mapOf(
            "serviceState" to serviceState,
            "serviceStateAt" to if (serviceStateAt > 0) isoTimestamp(serviceStateAt) else null,
            "lastError" to lastError(),
            "enabled" to Settings.enabled,
            "isMoving" to Settings.isMoving,
            "locationAvailable" to locationAvailable,
            "provider" to provider,
            "backgroundPermission" to TrackingEngine.hasBackgroundPermission(ctx),
            "activityPermission" to TrackingEngine.hasActivityPermission(ctx),
            "notificationsEnabled" to NotificationManagerCompat.from(ctx).areNotificationsEnabled(),
            "ignoringBatteryOptimizations" to TrackingEngine.isIgnoringBatteryOptimizations(ctx),
            "powerSaveMode" to pm.isPowerSaveMode,
            "appInForeground" to appInForeground(),
            "sdk" to Build.VERSION.SDK_INT,
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "stats" to TrackingEngine.stats(),
        )
    }
}

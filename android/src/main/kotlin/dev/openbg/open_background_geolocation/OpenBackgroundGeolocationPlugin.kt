package dev.openbg.open_background_geolocation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry

class OpenBackgroundGeolocationPlugin :
    FlutterPlugin,
    ActivityAware,
    MethodChannel.MethodCallHandler,
    EventChannel.StreamHandler,
    PluginRegistry.RequestPermissionsResultListener {

    private lateinit var context: Context
    private lateinit var methods: MethodChannel
    private lateinit var events: EventChannel
    private var activity: Activity? = null
    private var activityBinding: ActivityPluginBinding? = null

    /** Completion for an in-flight permission flow. */
    private var pendingPermission: ((Int) -> Unit)? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        TrackingEngine.init(context)
        methods = MethodChannel(binding.binaryMessenger, "dev.openbg/methods")
        methods.setMethodCallHandler(this)
        events = EventChannel(binding.binaryMessenger, "dev.openbg/events")
        events.setStreamHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methods.setMethodCallHandler(null)
        events.setStreamHandler(null)
        EventBus.sink = null
    }

    override fun onListen(arguments: Any?, sink: EventChannel.EventSink?) {
        EventBus.sink = sink
    }

    override fun onCancel(arguments: Any?) {
        EventBus.sink = null
    }

    // ----------------------------------------------------------------- activity

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        activityBinding = binding
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() = onDetachedFromActivity()

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) =
        onAttachedToActivity(binding)

    override fun onDetachedFromActivity() {
        activityBinding?.removeRequestPermissionsResultListener(this)
        activityBinding = null
        activity = null
    }

    // ----------------------------------------------------------------- methods

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            handle(call, result)
        } catch (e: Exception) {
            result.error("OBG_ERROR", e.message, null)
        }
    }

    private fun handle(call: MethodCall, result: MethodChannel.Result) {
        val args = call.arguments
        when (call.method) {
            "ready" -> {
                val config = (args as? Map<*, *>) ?: emptyMap<String, Any?>()
                val reset = config["reset"] as? Boolean ?: true
                Settings.ready(config, reset)
                result.success(Settings.stateMap())
            }
            "getState" -> result.success(Settings.stateMap())
            "setConfig" -> {
                Settings.update(args as Map<*, *>)
                result.success(Settings.stateMap())
            }
            "reset" -> {
                Settings.reset(args as? Map<*, *>)
                result.success(Settings.stateMap())
            }
            "start" -> start(result)
            "startGeofences" -> {
                TrackingEngine.startGeofences(context)
                result.success(Settings.stateMap())
            }
            "stop" -> {
                TrackingEngine.stop(context)
                result.success(Settings.stateMap())
            }
            "changePace" -> {
                TrackingEngine.changePace(args as Boolean)
                result.success(Settings.stateMap())
            }
            "getCurrentPosition" -> TrackingEngine.currentPosition(
                context, (args as? Map<*, *>) ?: emptyMap<String, Any?>()
            ) { loc, err ->
                if (loc != null) result.success(loc) else result.error("LOCATION_ERROR", err, null)
            }
            "getOdometer" -> result.success(Settings.odometer)
            "setOdometer" -> {
                Settings.odometer = (args as Number).toDouble()
                TrackingEngine.currentPosition(context, mapOf("persist" to false)) { loc, err ->
                    if (loc != null) result.success(loc) else result.error("LOCATION_ERROR", err, null)
                }
            }
            "getLocations" -> {
                val q = (args as? Map<*, *>) ?: emptyMap<String, Any?>()
                val order = q["order"] as? String
                    ?: Settings.string("persistence", "locationsOrderDirection", "ASC")
                val rows = Db.query(order, (q["limit"] as? Number)?.toInt(), (q["start"] as? Number)?.toInt())
                result.success(rows.map { it.toMap() })
            }
            "getCount" -> result.success(Db.count())
            "insertLocation" -> result.success(TrackingEngine.insertLocation(context, args as Map<*, *>))
            "destroyLocation" -> result.success(Db.delete(args as String))
            "destroyLocations" -> result.success(Db.deleteAll())
            "addGeofence" -> GeofenceManager.add(context, listOf(args as Map<*, *>)) { ok, err ->
                if (ok) result.success(true) else result.error("GEOFENCE_ERROR", err, null)
            }
            "addGeofences" -> GeofenceManager.add(context, (args as List<*>).map { it as Map<*, *> }) { ok, err ->
                if (ok) result.success(true) else result.error("GEOFENCE_ERROR", err, null)
            }
            "geofenceExists" -> result.success(GeofenceManager.get(context, args as String) != null)
            "getGeofence" -> result.success(GeofenceManager.get(context, args as String))
            "getGeofences" -> result.success(GeofenceManager.all(context))
            "removeGeofence" -> GeofenceManager.remove(context, listOf(args as String)) { ok, err ->
                if (ok) result.success(true) else result.error("GEOFENCE_ERROR", err, null)
            }
            "removeGeofences" -> GeofenceManager.remove(
                context, (args as? List<*>)?.map { it as String }
            ) { ok, err ->
                if (ok) result.success(true) else result.error("GEOFENCE_ERROR", err, null)
            }
            "sync" -> HttpSync.sync(context) { records, err ->
                // Called from the sync worker; Flutter replies must happen on the main thread.
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (err == null) result.success(records) else result.error("HTTP_ERROR", err, null)
                }
            }
            "getProviderState" -> result.success(TrackingEngine.providerState(context))
            "requestPermission" -> requestPermission { result.success(it) }
            // iOS-only / no-op on Android.
            "requestTemporaryFullAccuracy" -> result.success(1)
            "startBackgroundTask" -> result.success(1)
            "stopBackgroundTask" -> result.success(0)
            else -> result.notImplemented()
        }
    }

    private fun start(result: MethodChannel.Result) {
        if (TrackingEngine.hasLocationPermission(context)) {
            TrackingEngine.start(context)
            result.success(Settings.stateMap())
            return
        }
        requestPermission {
            if (TrackingEngine.hasLocationPermission(context)) TrackingEngine.start(context)
            result.success(Settings.stateMap())
        }
    }

    // ----------------------------------------------------------------- permissions

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    /** Foreground permissions first, then background location if `locationAuthorizationRequest` is Always. */
    private fun requestPermission(done: (Int) -> Unit) {
        val act = activity
        if (act == null) {
            done(statusCode())
            return
        }
        val needed = mutableListOf<String>()
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) needed += Manifest.permission.ACCESS_FINE_LOCATION
        if (!granted(Manifest.permission.ACCESS_COARSE_LOCATION)) needed += Manifest.permission.ACCESS_COARSE_LOCATION
        if (Build.VERSION.SDK_INT >= 29 && !Settings.bool("activity", "disableMotionActivityUpdates") &&
            !granted(Manifest.permission.ACTIVITY_RECOGNITION)
        ) needed += Manifest.permission.ACTIVITY_RECOGNITION
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        pendingPermission = done
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(act, needed.toTypedArray(), REQ_FOREGROUND)
        } else {
            requestBackgroundOrFinish(act)
        }
    }

    private fun requestBackgroundOrFinish(act: Activity) {
        val wantsAlways = Settings.string("geolocation", "locationAuthorizationRequest", "Always") == "Always"
        if (wantsAlways && Build.VERSION.SDK_INT >= 29 &&
            TrackingEngine.hasLocationPermission(context) &&
            !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ) {
            ActivityCompat.requestPermissions(act, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BACKGROUND)
        } else {
            finishPermission()
        }
    }

    private fun finishPermission() {
        val cb = pendingPermission
        pendingPermission = null
        cb?.invoke(statusCode())
    }

    private fun statusCode(): Int = (TrackingEngine.providerState(context)["status"] as Int)

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ): Boolean {
        when (requestCode) {
            REQ_FOREGROUND -> {
                val act = activity
                if (act != null) requestBackgroundOrFinish(act) else finishPermission()
            }
            REQ_BACKGROUND -> finishPermission()
            else -> return false
        }
        return true
    }

    private companion object {
        const val REQ_FOREGROUND = 7101
        const val REQ_BACKGROUND = 7102
    }
}

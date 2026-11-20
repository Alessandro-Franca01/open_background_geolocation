package dev.openbg.open_background_geolocation

import android.content.Context
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.FlutterCallbackInformation

/**
 * Delivers events to a Dart callback while the app has no UI.
 *
 * When an event is emitted and no Dart listener is attached ([EventBus.sink] is null), a
 * background [FlutterEngine] is started on demand. It runs the dispatcher registered through
 * `registerHeadlessTask`, which forwards every event to the user's callback. Events produced
 * before the dispatcher reports `initialized` are queued.
 */
object HeadlessTask {
    private const val TAG = "headless"
    private const val PREFS = "obg_headless"
    private const val MAX_QUEUE = 100

    /** Events worth waking Dart for. `watchposition` is a foreground stream and is excluded. */
    private val EVENTS = setOf(
        "location", "motionchange", "activitychange", "providerchange", "geofence",
        "geofenceschange", "heartbeat", "http", "connectivitychange", "enabledchange",
        "powersavechange", "schedule", "authorization",
    )

    private var engine: FlutterEngine? = null
    private var channel: MethodChannel? = null
    private var ready = false
    private val queue = ArrayDeque<Pair<String, Any?>>()

    /** True while the background engine is being built, so its plugin instance can tell itself apart. */
    @Volatile
    var creating = false
        private set

    fun register(ctx: Context, dispatcher: Long, callback: Long) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("dispatcher", dispatcher)
            .putLong("callback", callback)
            .apply()
        Logger.i(TAG, "headless task registered")
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Must be called on the main thread (the [EventBus] already guarantees it). */
    fun dispatch(ctx: Context, name: String, data: Any?) {
        if (name !in EVENTS) return
        if (!Settings.bool("app", "enableHeadless")) return
        val p = prefs(ctx)
        val callback = p.getLong("callback", 0L)
        if (callback == 0L || p.getLong("dispatcher", 0L) == 0L) {
            Logger.w(TAG, "event '$name' dropped: enableHeadless is on but no task is registered")
            return
        }
        if (!ensureEngine(ctx.applicationContext, p.getLong("dispatcher", 0L))) return
        if (ready) send(callback, name, data)
        else {
            if (queue.size >= MAX_QUEUE) queue.removeFirst()
            queue.addLast(name to data)
        }
    }

    private fun ensureEngine(app: Context, dispatcher: Long): Boolean {
        if (engine != null) return true
        val info = FlutterCallbackInformation.lookupCallbackInformation(dispatcher)
        if (info == null) {
            Logger.e(TAG, "dispatcher handle is stale; call registerHeadlessTask again from the app")
            return false
        }
        return try {
            Logger.i(TAG, "starting background Flutter engine")
            val loader = FlutterInjector.instance().flutterLoader()
            if (!loader.initialized()) loader.startInitialization(app)
            loader.ensureInitializationComplete(app, null)

            creating = true
            val created = try {
                FlutterEngine(app)
            } finally {
                creating = false
            }
            val ch = MethodChannel(created.dartExecutor.binaryMessenger, "dev.openbg/headless")
            ch.setMethodCallHandler { call, result ->
                if (call.method == "initialized") {
                    ready = true
                    Logger.i(TAG, "dispatcher ready; flushing ${queue.size} queued event(s)")
                    flush(app)
                    result.success(true)
                } else {
                    result.notImplemented()
                }
            }
            created.dartExecutor.executeDartCallback(
                DartExecutor.DartCallback(app.assets, loader.findAppBundlePath(), info)
            )
            engine = created
            channel = ch
            true
        } catch (e: Exception) {
            Logger.e(TAG, "could not start background engine: ${e.message}")
            false
        }
    }

    private fun flush(app: Context) {
        val callback = prefs(app).getLong("callback", 0L)
        while (queue.isNotEmpty()) {
            val (name, data) = queue.removeFirst()
            send(callback, name, data)
        }
    }

    private fun send(callback: Long, name: String, data: Any?) {
        channel?.invokeMethod("event", mapOf("handle" to callback, "name" to name, "data" to data))
    }

    /** Tears the background engine down (tracking stopped). */
    fun shutdown() {
        if (engine == null) return
        Logger.i(TAG, "stopping background Flutter engine")
        ready = false
        queue.clear()
        channel?.setMethodCallHandler(null)
        channel = null
        engine?.destroy()
        engine = null
    }
}

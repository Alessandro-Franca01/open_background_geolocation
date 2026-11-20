package dev.openbg.open_background_geolocation

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel

/**
 * Bridge from native components to Dart. While a Dart listener is attached events go through
 * [sink]; otherwise they are handed to [HeadlessTask], which delivers them to the registered
 * background callback (and drops them if headless mode is off).
 */
object EventBus {
    @Volatile
    var sink: EventChannel.EventSink? = null

    /** Application context, set by the engine; needed to start the headless engine on demand. */
    @Volatile
    var context: Context? = null

    private val main = Handler(Looper.getMainLooper())

    fun emit(name: String, data: Any?) {
        main.post {
            val s = sink
            if (s != null) {
                s.success(mapOf("event" to name, "data" to data))
            } else {
                context?.let { HeadlessTask.dispatch(it, name, data) }
            }
        }
    }
}

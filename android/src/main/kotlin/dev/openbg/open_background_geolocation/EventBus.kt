package dev.openbg.open_background_geolocation

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel

/**
 * Bridge from native components to Dart. Events emitted while no Dart listener is attached are
 * dropped (headless delivery is a later phase).
 */
object EventBus {
    @Volatile
    var sink: EventChannel.EventSink? = null
    private val main = Handler(Looper.getMainLooper())

    fun emit(name: String, data: Any?) {
        main.post { sink?.success(mapOf("event" to name, "data" to data)) }
    }
}

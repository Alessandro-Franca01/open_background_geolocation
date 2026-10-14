package dev.openbg.open_background_geolocation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

/** Receives activity-recognition updates and forwards the most probable activity. */
class ActivityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityRecognitionResult.hasResult(intent)) return
        val best = ActivityRecognitionResult.extractResult(intent)?.mostProbableActivity ?: return
        TrackingEngine.init(context)
        TrackingEngine.onActivity(best.type, best.confidence)
    }
}

/** Fires when the device leaves the stationary geofence. */
class StationaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        if (event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT) {
            TrackingEngine.init(context)
            TrackingEngine.onStationaryExit()
        }
    }
}

/** Restarts tracking after reboot or app update when `startOnBoot` is set. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TrackingEngine.init(context)
        if (Settings.enabled && Settings.bool("app", "startOnBoot")) {
            Settings.didDeviceReboot = intent.action == Intent.ACTION_BOOT_COMPLETED
            TrackingEngine.start(context)
        }
    }
}

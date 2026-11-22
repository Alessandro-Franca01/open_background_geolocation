package dev.openbg.open_background_geolocation

/** Health decisions with no Android types, so they run in JVM unit tests. */
internal object DiagnosticsLogic {
    const val SERVICE_PERMISSION = "service_permission"
    const val SERVICE_START_NOT_ALLOWED = "service_start_not_allowed"
    const val LOCATION_SILENCE = "location_silence"
    const val LOCATION_UNAVAILABLE = "location_unavailable"

    /**
     * Returns an error code when starting the `location` foreground service would fail, or null
     * when it may start. Android 14 (SDK 34) requires a location permission usable at start time:
     * with only "while in use", the app must be visible.
     */
    fun locationServiceBlock(
        sdk: Int,
        hasLocation: Boolean,
        hasBackground: Boolean,
        appInForeground: Boolean,
    ): String? = when {
        !hasLocation -> SERVICE_PERMISSION
        sdk >= 34 && !hasBackground && !appInForeground -> SERVICE_PERMISSION
        else -> null
    }
}

/**
 * Silent-stream recovery: the location request is re-issued once per silence episode. Later
 * silent windows still report, but do not re-issue again until a fix arrives (no loop).
 */
internal class SilenceGate {
    private var reopened = false

    fun reset() {
        reopened = false
    }

    /** A fix arrived: the stream is alive, so a future silence may re-issue again. */
    fun onFix() = reset()

    /** Returns true if the request should be re-issued for this silent window. */
    fun onSilent(): Boolean {
        if (reopened) return false
        reopened = true
        return true
    }
}

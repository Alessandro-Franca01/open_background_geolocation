package dev.openbg.open_background_geolocation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * Turns tracking on and off following `AppConfig.schedule`. Each evaluation applies the mode the
 * schedule wants right now and arms an alarm for the next change.
 */
object Scheduler {
    private const val TAG = "schedule"
    private const val REQUEST_CODE = 4

    private fun windows(): List<ScheduleLogic.Window> {
        val arr = Settings.array("app", "schedule") ?: return emptyList()
        val raw = (0 until arr.length()).map { arr.optString(it) }
        val parsed = ScheduleLogic.parse(raw)
        if (parsed.size != raw.size) {
            Logger.w(TAG, "${raw.size - parsed.size} malformed schedule entr(ies) ignored")
        }
        return parsed
    }

    /** Enables the scheduler. Returns false (and stays off) when no valid schedule is configured. */
    fun start(ctx: Context): Boolean {
        val valid = windows()
        if (valid.isEmpty()) {
            Logger.w(TAG, "startSchedule ignored: app.schedule has no valid entries")
            return false
        }
        Settings.schedulerEnabled = true
        Logger.i(TAG, "scheduler started with ${valid.size} window(s)")
        evaluate(ctx)
        return true
    }

    /** Stops scheduling. Tracking that is already running is left alone. */
    fun stop(ctx: Context) {
        Settings.schedulerEnabled = false
        cancel(ctx)
        Logger.i(TAG, "scheduler stopped")
    }

    /** Applies the current window's mode and schedules the next alarm. */
    fun evaluate(ctx: Context) {
        val app = ctx.applicationContext
        TrackingEngine.init(app)
        if (!Settings.schedulerEnabled) return

        val list = windows()
        val cal = Calendar.getInstance()
        val dow = cal.get(Calendar.DAY_OF_WEEK) // 1 = Sunday, like the schedule format
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val mode = ScheduleLogic.modeAt(list, dow, minute)
        Logger.i(TAG, "evaluate: day=$dow minute=$minute mode=$mode (enabled=${Settings.enabled})")

        try {
            when (mode) {
                ScheduleLogic.LOCATION ->
                    if (!Settings.enabled || Settings.trackingMode != 1) TrackingEngine.start(app)
                ScheduleLogic.GEOFENCE ->
                    if (!Settings.enabled || Settings.trackingMode != 0) TrackingEngine.startGeofences(app)
                else -> if (Settings.enabled) TrackingEngine.stop(app)
            }
        } catch (e: Exception) {
            // Typically a foreground-service start refused while the app is in the background.
            Logger.e(TAG, "could not apply mode $mode: ${e.message}")
        }
        EventBus.emit("schedule", Settings.stateMap())
        scheduleNext(app, list, dow, minute)
    }

    private fun alarmIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQUEST_CODE, Intent(ctx, ScheduleReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun cancel(ctx: Context) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmIntent(ctx))
    }

    private fun scheduleNext(ctx: Context, list: List<ScheduleLogic.Window>, dow: Int, minute: Int) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val minutes = ScheduleLogic.minutesUntilChange(list, dow, minute)
        if (minutes == null) {
            cancel(ctx)
            return
        }
        // Boundaries sit on whole minutes: aim for the start of the target minute.
        val trigger = (System.currentTimeMillis() / 60_000L + minutes) * 60_000L
        val pi = alarmIntent(ctx)
        val exactAllowed = Settings.bool("app", "scheduleUseAlarmManager") &&
            (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
        try {
            if (exactAllowed) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            Logger.d(TAG, "next change in $minutes min (exact=$exactAllowed)")
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            Logger.w(TAG, "exact alarm refused; using an inexact one")
        }
    }
}

/** Fires at each schedule boundary, and when the clock or time zone changes. */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TrackingEngine.init(context)
        Logger.d("schedule", "received ${intent.action ?: "alarm"}")
        Scheduler.evaluate(context)
    }
}

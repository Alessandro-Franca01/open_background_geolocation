package dev.openbg.open_background_geolocation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Foreground service that keeps the process alive while tracking is enabled. */
class TrackingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TrackingEngine.init(this)
        Logger.i("service", "onStartCommand action=${intent?.action} enabled=${Settings.enabled} restart=${intent == null}")
        if (intent?.action == ACTION_STOP || !Settings.enabled) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification(this)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            // Android 14+: the `location` type needs a location permission usable right now.
            return failStart(DiagnosticsLogic.SERVICE_PERMISSION, e)
        } catch (e: IllegalStateException) {
            // Android 12+: ForegroundServiceStartNotAllowedException (started from the background).
            return failStart(DiagnosticsLogic.SERVICE_START_NOT_ALLOWED, e)
        }
        Diagnostics.setServiceState(Diagnostics.RUNNING)
        TrackingEngine.begin()
        return START_STICKY
    }

    private fun failStart(code: String, e: Exception): Int {
        Diagnostics.serviceFailed(
            code,
            "startForeground failed (${e.javaClass.simpleName}: ${e.message}); tracking resumes when the app " +
                "is opened, or grant \"Allow all the time\" to start from the background"
        )
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        TrackingEngine.init(this)
        val stop = Settings.bool("app", "stopOnTerminate", true)
        Logger.i("service", "onTaskRemoved (app closed from recents); stopOnTerminate=$stop")
        if (stop) {
            TrackingEngine.stop(this)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Logger.i("service", "onDestroy")
        if (Diagnostics.serviceState != Diagnostics.FAILED) Diagnostics.setServiceState(Diagnostics.STOPPED)
        TrackingEngine.end()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "dev.openbg.STOP"
        private const val CHANNEL_ID = "obg_tracking"
        private const val NOTIFICATION_ID = 7345

        /**
         * Starts the foreground service unless Android would refuse it. A refusal is recorded in
         * [Diagnostics] (and emitted as `trackingerror`) instead of crashing the app. Returns
         * whether the start was requested.
         */
        fun start(ctx: Context): Boolean {
            val block = DiagnosticsLogic.locationServiceBlock(
                Build.VERSION.SDK_INT,
                TrackingEngine.hasLocationPermission(ctx),
                TrackingEngine.hasBackgroundPermission(ctx),
                Diagnostics.appInForeground(),
            )
            if (block != null) {
                Diagnostics.serviceFailed(
                    block,
                    "foreground service not started: location permission is not usable from the background " +
                        "(sdk=${Build.VERSION.SDK_INT}); tracking resumes when the app is opened, or grant " +
                        "\"Allow all the time\""
                )
                return false
            }
            Diagnostics.setServiceState(Diagnostics.STARTING)
            return try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, TrackingService::class.java))
                true
            } catch (e: IllegalStateException) {
                Diagnostics.serviceFailed(
                    DiagnosticsLogic.SERVICE_START_NOT_ALLOWED,
                    "startForegroundService refused (${e.javaClass.simpleName}: ${e.message})"
                )
                false
            } catch (e: SecurityException) {
                Diagnostics.serviceFailed(DiagnosticsLogic.SERVICE_PERMISSION, "startForegroundService: ${e.message}")
                false
            }
        }

        fun stop(ctx: Context) {
            // startService throws from the background when the service is not running (e.g. after
            // a failed start); there is nothing to stop in that case.
            runCatching {
                ctx.startService(Intent(ctx, TrackingService::class.java).setAction(ACTION_STOP))
            }.onFailure { Logger.d("service", "stop: service not running (${it.javaClass.simpleName})") }
        }

        private fun buildNotification(ctx: Context): Notification {
            val cfg = Settings.obj("app", "notification")
            val title = cfg?.optString("title").takeUnless { it.isNullOrEmpty() }
                ?: ctx.applicationInfo.loadLabel(ctx.packageManager).toString()
            val text = cfg?.optString("text").takeUnless { it.isNullOrEmpty() }
                ?: "Tracking location in background"
            val channelName = cfg?.optString("channelName").takeUnless { it.isNullOrEmpty() }
                ?: "Location tracking"

            if (Build.VERSION.SDK_INT >= 26) {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW)
                )
            }

            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            val contentIntent = launch?.let {
                PendingIntent.getActivity(ctx, 0, it, PendingIntent.FLAG_IMMUTABLE)
            }

            val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(smallIcon(ctx, cfg?.optString("smallIcon")))
                .setOngoing(cfg?.optBoolean("sticky", true) ?: true)
                .setPriority(cfg?.optInt("priority", NotificationCompat.PRIORITY_LOW) ?: NotificationCompat.PRIORITY_LOW)
                .setContentIntent(contentIntent)
            cfg?.optString("color")?.takeIf { it.isNotEmpty() }?.let {
                runCatching { builder.setColor(Color.parseColor(it)) }
            }
            return builder.build()
        }

        private fun smallIcon(ctx: Context, name: String?): Int {
            if (!name.isNullOrEmpty()) {
                for (type in arrayOf("drawable", "mipmap")) {
                    val id = ctx.resources.getIdentifier(name, type, ctx.packageName)
                    if (id != 0) return id
                }
            }
            return ctx.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_menu_mylocation
        }
    }
}

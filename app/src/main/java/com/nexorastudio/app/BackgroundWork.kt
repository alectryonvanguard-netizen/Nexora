package com.nexorastudio.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** True while the app is on screen. Replies only become notifications when it is not. */
object AppState {
    @Volatile var visible = false
}

/**
 * Keeps the chat state alive for the whole life of the app process, not just the screen.
 * So closing the screen with Back does not cancel a request that is still running.
 */
class NexoraApplication : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

/** Shows the actual reply as a notification when you are away from the app. */
object Notifier {
    private const val CHANNEL = "nexora_replies"

    fun reply(ctx: Context, text: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Replies", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val body = text.trim().take(900)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("NEXORA")
            .setContentText(body.take(120))
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n) }
    }
}

/** Foreground service that keeps the process and network alive while a request is running. */
class WorkService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Working in the background", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("NEXORA is working")
            .setContentText("Your request keeps running in the background.")
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nexora:work").apply { acquire(20 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "nexora_work"
        private const val NOTIFICATION_ID = 4108

        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, WorkService::class.java)) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, WorkService::class.java)) }
        }
    }
}

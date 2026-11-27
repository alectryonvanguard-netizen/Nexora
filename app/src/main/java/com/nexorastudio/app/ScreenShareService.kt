package com.nexorastudio.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock

/** Foreground service that captures the screen so the AI can see it. Required by Android for screen capture. */
class ScreenShareService : Service() {

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var lastTs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = readData(intent)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        try {
            begin(code, data)
        } catch (e: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun readData(intent: Intent?): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else intent?.getParcelableExtra(EXTRA_DATA)

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Screen sharing", NotificationManager.IMPORTANCE_LOW))
        val notification: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("NEXORA can see your screen")
            .setContentText("Screen sharing is on. Stop it from the Live screen.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun begin(code: Int, data: Intent) {
        val t = HandlerThread("nexora-screen").also { it.start() }
        thread = t
        val handler = Handler(t.looper)

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mgr.getMediaProjection(code, data)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)
        projection = p

        val dm = resources.displayMetrics
        val scale = 960f / maxOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(1)
        val w = (dm.widthPixels * scale).toInt().coerceAtLeast(2)
        val h = (dm.heightPixels * scale).toInt().coerceAtLeast(2)

        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        display = p.createVirtualDisplay(
            "nexora-screen", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
        )

        r.setOnImageAvailableListener({ rd ->
            val image = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.uptimeMillis()
                if (now - lastTs < 800) return@setOnImageAvailableListener
                lastTs = now
                val plane = image.planes[0]
                val rowPadding = plane.rowStride - plane.pixelStride * image.width
                val padded = Bitmap.createBitmap(
                    image.width + rowPadding / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888
                )
                padded.copyPixelsFromBuffer(plane.buffer)
                FrameHub.screen = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                padded.recycle()
            } catch (_: Exception) {
                // skip this frame
            } finally {
                image.close()
            }
        }, handler)
    }

    override fun onDestroy() {
        display?.release()
        reader?.close()
        projection?.stop()
        thread?.quitSafely()
        FrameHub.screen = null
        FrameHub.onScreenStopped?.invoke()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "nexora_screen"
        private const val NOTIFICATION_ID = 4107
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, ScreenShareService::class.java)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ScreenShareService::class.java))
        }
    }
}

package com.nexorastudio.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

/** Latest frames the AI can "see". Camera and screen are kept only in memory. */
object FrameHub {
    @Volatile var camera: Bitmap? = null
    @Volatile var screen: Bitmap? = null
    @Volatile var onScreenStopped: (() -> Unit)? = null
}

/**
 * Uses the camera in the background: no preview is shown on screen.
 * It only keeps the newest small frame in [FrameHub.camera] (about one per second).
 */
class CameraFrames(private val ctx: Context) {
    private var provider: ProcessCameraProvider? = null
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var lastTs = 0L

    fun start(owner: LifecycleOwner, front: Boolean, onResult: (Boolean, String?) -> Unit) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                p.unbindAll()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy -> handle(proxy) }
                val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                p.bindToLifecycle(owner, selector, analysis)
                onResult(true, null)
            } catch (e: Exception) {
                onResult(false, "Could not open the camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    private fun handle(proxy: ImageProxy) {
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastTs < 800) return
            lastTs = now
            val bmp = proxy.toBitmap()
            val m = Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            FrameHub.camera = MediaUtils.scaleDown(rotated, 768)
        } catch (_: Exception) {
            // skip this frame
        } finally {
            proxy.close()
        }
    }

    fun stop() {
        provider?.unbindAll()
        FrameHub.camera = null
    }
}

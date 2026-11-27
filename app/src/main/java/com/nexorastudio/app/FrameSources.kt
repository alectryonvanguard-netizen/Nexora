package com.nexorastudio.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
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
 * Uses the camera and keeps only the newest small frame in [FrameHub.camera] (about one per second).
 * Without a preview provider nothing is shown on screen. With one, the camera is also displayed.
 */
class CameraFrames(private val ctx: Context) {
    private var provider: ProcessCameraProvider? = null
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var lastTs = 0L

    private val analysis: ImageAnalysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
        .also { it.setAnalyzer(executor) { proxy -> handle(proxy) } }

    fun start(
        owner: LifecycleOwner,
        front: Boolean,
        previewProvider: Preview.SurfaceProvider?,
        onResult: (Boolean, String?) -> Unit
    ) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                p.unbindAll()
                val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                val useCases = mutableListOf<UseCase>(analysis)
                if (previewProvider != null) {
                    useCases.add(Preview.Builder().build().also { it.setSurfaceProvider(previewProvider) })
                }
                p.bindToLifecycle(owner, selector, *useCases.toTypedArray())
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

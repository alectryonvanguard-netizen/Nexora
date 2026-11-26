@file:OptIn(UnstableApi::class)

package com.nexorastudio.app

import android.content.Context
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class VideoPlan(
    val reply: String,
    val startSec: Double?,
    val endSec: Double?,
    val speed: Float,
    val mute: Boolean,
    val filter: String,
    val brightness: Float,
    val contrast: Float,
    val rotate: Int,
    val height: Int?,
    val text: String?,
    val textPos: String
)

object VideoPlanParser {
    fun parse(raw: String): VideoPlan? {
        val s = raw.indexOf('{')
        val e = raw.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        val o = runCatching { JSONObject(raw.substring(s, e + 1)) }.getOrNull() ?: return null
        fun num(k: String): Double? = if (o.has(k) && !o.isNull(k)) o.optDouble(k).takeIf { !it.isNaN() } else null
        return VideoPlan(
            reply = o.optString("reply", "Applying your edit."),
            startSec = num("startSec"),
            endSec = num("endSec"),
            speed = (num("speed") ?: 1.0).toFloat().coerceIn(0.25f, 4f),
            mute = o.optBoolean("mute", false),
            filter = o.optString("filter", "none").lowercase(),
            brightness = (num("brightness") ?: 0.0).toFloat().coerceIn(-0.5f, 0.5f),
            contrast = (num("contrast") ?: 0.0).toFloat().coerceIn(-0.5f, 0.5f),
            rotate = (num("rotate") ?: 0.0).toInt().let { if (it in listOf(90, 180, 270)) it else 0 },
            height = num("height")?.toInt()?.takeIf { it in 240..2160 },
            text = o.optString("text", "").takeIf { it.isNotBlank() },
            textPos = o.optString("textPosition", "bottom").lowercase()
        )
    }
}

object FrameSampler {
    /** Returns (durationMs, frames as JPEG data URIs). Blocking: call on IO. */
    fun sample(ctx: Context, uri: Uri, count: Int = 6): Pair<Long, List<String>> {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val frames = mutableListOf<String>()
            for (i in 0 until count) {
                val tUs = if (durMs > 0) durMs * 1000L * (2L * i + 1) / (2L * count) else 0L
                val bmp = r.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                val scaled = MediaUtils.scaleDown(bmp, 512)
                val bos = ByteArrayOutputStream()
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, bos)
                frames.add("data:image/jpeg;base64," + android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP))
            }
            return durMs to frames
        } finally {
            r.release()
        }
    }
}

/** Executes a [VideoPlan] on-device with Media3 Transformer. */
class VideoEngine(private val ctx: Context) {

    suspend fun export(uri: Uri, plan: VideoPlan, durationMs: Long, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.Main) {
            val outDir = File(ctx.filesDir, "videos").apply { mkdirs() }
            val out = File(outDir, "nexora_${System.currentTimeMillis()}.mp4")

            suspendCancellableCoroutine<File> { cont ->
                val clipping = MediaItem.ClippingConfiguration.Builder().apply {
                    val startMs = ((plan.startSec ?: 0.0) * 1000).toLong().coerceAtLeast(0L)
                    var endMs = plan.endSec?.let { (it * 1000).toLong() } ?: -1L
                    if (durationMs > 0 && endMs > durationMs) endMs = durationMs
                    if (startMs > 0) setStartPositionMs(startMs)
                    if (endMs > startMs) setEndPositionMs(endMs)
                }.build()

                val mediaItem = MediaItem.Builder().setUri(uri).setClippingConfiguration(clipping).build()

                val audio = mutableListOf<AudioProcessor>()
                if (plan.speed != 1f) audio.add(SonicAudioProcessor().apply { setSpeed(plan.speed) })

                val edited = EditedMediaItem.Builder(mediaItem)
                    .setRemoveAudio(plan.mute)
                    .setEffects(Effects(audio, videoEffects(plan)))
                    .build()

                val transformer = Transformer.Builder(ctx)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(out)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()

                transformer.start(edited, out.absolutePath)

                val handler = Handler(Looper.getMainLooper())
                val holder = ProgressHolder()
                val poll = object : Runnable {
                    override fun run() {
                        if (!cont.isActive) return
                        if (transformer.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) onProgress(holder.progress)
                        handler.postDelayed(this, 300)
                    }
                }
                handler.post(poll)
                cont.invokeOnCancellation {
                    handler.removeCallbacks(poll)
                    handler.post { transformer.cancel() }
                }
            }
        }

    private fun videoEffects(plan: VideoPlan): List<Effect> {
        val fx = mutableListOf<Effect>()
        if (plan.speed != 1f) fx.add(SpeedChangeEffect(plan.speed))
        when (plan.filter) {
            "grayscale" -> fx.add(RgbFilter.createGrayscaleFilter())
            "noir" -> { fx.add(RgbFilter.createGrayscaleFilter()); fx.add(Contrast(0.3f)) }
            "warm" -> fx.add(RgbAdjustment.Builder().setRedScale(1.12f).setBlueScale(0.9f).build())
            "cool" -> fx.add(RgbAdjustment.Builder().setRedScale(0.9f).setBlueScale(1.12f).build())
            "vivid" -> fx.add(Contrast(0.25f))
        }
        if (plan.brightness != 0f) fx.add(Brightness(plan.brightness))
        if (plan.contrast != 0f) fx.add(Contrast(plan.contrast))
        if (plan.rotate != 0) {
            fx.add(ScaleAndRotateTransformation.Builder().setRotationDegrees(plan.rotate.toFloat()).build())
        }
        plan.height?.let { fx.add(Presentation.createForHeight(it)) }
        plan.text?.let { fx.add(OverlayEffect(ImmutableList.of<TextureOverlay>(textOverlay(it, plan.textPos)))) }
        return fx
    }

    private fun textOverlay(t: String, pos: String): TextureOverlay {
        val sp = SpannableString(t)
        sp.setSpan(ForegroundColorSpan(Color.WHITE), 0, t.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.setSpan(AbsoluteSizeSpan(64), 0, t.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        val y = when (pos) { "top" -> 0.75f; "center" -> 0f; else -> -0.75f }
        val settings = OverlaySettings.Builder().setBackgroundFrameAnchor(0f, y).build()
        return TextOverlay.createStaticTextOverlay(sp, settings)
    }
}

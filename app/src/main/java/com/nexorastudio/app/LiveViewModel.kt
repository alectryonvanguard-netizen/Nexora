package com.nexorastudio.app

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

enum class LivePhase { IDLE, LISTENING, THINKING, SPEAKING }

/**
 * Live voice conversation: you talk, the AI answers out loud in your chosen accent.
 * The camera (background, no preview) and shared screen are sent as pictures with each question.
 */
class LiveViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = AppSettings(app)
    private val main = Handler(Looper.getMainLooper())

    var phase by mutableStateOf(LivePhase.IDLE)
        private set
    var heard by mutableStateOf("")
        private set
    var reply by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var muted by mutableStateOf(false)
        private set
    var cameraOn by mutableStateOf(false)
        private set
    var frontCamera by mutableStateOf(false)
        private set
    var screenOn by mutableStateOf(false)
        private set

    private var active = false
    private var retries = 0
    private var recognizer: SpeechRecognizer? = null
    private var turnJob: Job? = null
    private val history = mutableListOf<Pair<String, String>>()

    private val voice = LiveVoice(app) { main.post { onSpeechFinished() } }
    private val cameraFrames = CameraFrames(app)

    init {
        FrameHub.onScreenStopped = { main.post { screenOn = false } }
    }

    // ------------------------------------------------------------------ lifecycle

    fun fail(message: String) { error = message }

    fun start() {
        if (active) return
        error = null
        if (settings.apiKey.isBlank()) {
            error = "Add your NVIDIA API key in Settings first."
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(getApplication())) {
            error = "Speech recognition is not available on this phone. Install or enable Google speech services."
            return
        }
        active = true
        listen()
    }

    fun end() {
        active = false
        turnJob?.cancel()
        voice.stop()
        recognizer?.destroy()
        recognizer = null
        cameraFrames.stop()
        cameraOn = false
        stopScreen()
        phase = LivePhase.IDLE
        heard = ""
        reply = ""
    }

    override fun onCleared() {
        end()
        voice.shutdown()
        super.onCleared()
    }

    // ------------------------------------------------------------------ controls

    fun tapOrb() {
        when {
            !active -> start()
            phase == LivePhase.SPEAKING || phase == LivePhase.THINKING -> {
                turnJob?.cancel()
                voice.stop()
                listen()
            }
            phase == LivePhase.IDLE && !muted -> listen()
        }
    }

    fun toggleMute() {
        muted = !muted
        if (muted) {
            recognizer?.cancel()
            if (phase == LivePhase.LISTENING) phase = LivePhase.IDLE
        } else if (active && phase == LivePhase.IDLE) {
            listen()
        }
    }

    fun setCamera(on: Boolean, owner: LifecycleOwner) {
        if (on) {
            cameraFrames.start(owner, frontCamera) { ok, msg ->
                cameraOn = ok
                if (!ok) error = msg
            }
        } else {
            cameraFrames.stop()
            cameraOn = false
        }
    }

    fun flipCamera(owner: LifecycleOwner) {
        frontCamera = !frontCamera
        if (cameraOn) setCamera(true, owner)
    }

    fun onScreenGranted(resultCode: Int, data: Intent) {
        ScreenShareService.start(getApplication(), resultCode, data)
        screenOn = true
    }

    fun stopScreen() {
        ScreenShareService.stop(getApplication())
        FrameHub.screen = null
        screenOn = false
    }

    // ------------------------------------------------------------------ listening

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank() && phase == LivePhase.LISTENING) heard = t
        }

        override fun onResults(results: Bundle?) {
            retries = 0
            if (!active) return
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (t.isBlank()) restartListening(300) else handleUser(t)
        }

        override fun onError(errorCode: Int) {
            if (!active || phase != LivePhase.LISTENING) return
            when (errorCode) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restartListening(200)

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    error = "Microphone permission is needed."
                    active = false
                    phase = LivePhase.IDLE
                }

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    recognizer?.destroy()
                    recognizer = null
                    restartListening(500)
                }

                else -> {
                    retries++
                    if (retries > 6) {
                        error = "Speech recognition keeps failing. Check your internet connection and try again."
                        active = false
                        phase = LivePhase.IDLE
                    } else {
                        restartListening(800)
                    }
                }
            }
        }
    }

    private fun restartListening(delayMs: Long) {
        main.postDelayed({ if (active && !muted && phase == LivePhase.LISTENING) listen() }, delayMs)
    }

    private fun listen() {
        if (!active) return
        if (muted) { phase = LivePhase.IDLE; return }
        phase = LivePhase.LISTENING
        heard = ""
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(getApplication()).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, settings.accentTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1100L)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getApplication<Application>().packageName)
        }
        r.startListening(intent)
    }

    // ------------------------------------------------------------------ answering

    private fun handleUser(text: String) {
        heard = text
        reply = ""
        phase = LivePhase.THINKING
        turnJob = viewModelScope.launch {
            try {
                respond(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.toString()
                voice.stop()
                if (active) listen()
            }
        }
    }

    private suspend fun respond(text: String) {
        val api = NvidiaApi(settings.apiKey, settings)
        val tag = settings.accentTag

        val frame = withContext(Dispatchers.Default) { currentFrameDataUri() }
        val note = frameNote()
        val system = livePrompt(Accents.languageRule(settings))

        // Vision models often reject a system message next to an image, so it goes into the user message.
        val msgs = buildList {
            if (frame == null) add(ChatMsg("system", system))
            history.takeLast(6).forEach { add(ChatMsg("user", it.first)); add(ChatMsg("assistant", it.second)) }
            if (frame == null) add(ChatMsg("user", text))
            else add(ChatMsg("user", "$system\n\n$note\n\nUser says: $text", listOf(frame)))
        }
        val models = if (frame == null) settings.fastCandidates() else settings.visionCandidates()

        val voiceError = voice.begin(tag)
        if (voiceError != null) main.post { error = voiceError }

        val sb = StringBuilder()
        val pending = StringBuilder()
        val full = api.streamChatAuto(models, msgs, "low", 400) { piece ->
            sb.append(piece)
            pending.append(piece)
            val shown = ReplyStyle.clean(sb.toString())
            main.post {
                reply = shown
                if (phase == LivePhase.THINKING) phase = LivePhase.SPEAKING
            }
            while (true) {
                val end = sentenceEnd(pending)
                if (end < 0) break
                val sentence = pending.substring(0, end + 1).trim()
                pending.delete(0, end + 1)
                if (sentence.length > 1) voice.enqueue(ReplyStyle.clean(sentence))
            }
        }
        if (pending.isNotBlank()) voice.enqueue(ReplyStyle.clean(pending.toString().trim()))
        history.add(text to full)
        if (full.isBlank()) main.post { reply = "I did not catch that, please try again." }
        voice.finish()
    }

    private fun sentenceEnd(sb: StringBuilder): Int {
        for (i in sb.indices) {
            val c = sb[i]
            val isEnd = c == '.' || c == '!' || c == '?' || c == '。' || c == '！' || c == '？' || c == '\n'
            if (!isEnd) continue
            if (c == '.' && i + 1 < sb.length && sb[i + 1].isDigit()) continue
            val hasNext = i + 1 < sb.length
            if ((hasNext || c == '\n') && i >= 8) return i
        }
        return -1
    }

    private fun onSpeechFinished() {
        if (!active || phase == LivePhase.LISTENING || phase == LivePhase.IDLE) return
        main.postDelayed({ if (active && phase != LivePhase.LISTENING) listen() }, 250)
    }

    // ------------------------------------------------------------------ what the AI sees

    private fun frameNote(): String {
        val cam = cameraOn && FrameHub.camera != null
        val scr = screenOn && FrameHub.screen != null
        return when {
            cam && scr -> "[The attached picture shows the user's shared screen on top and their live camera view at the bottom.]"
            scr -> "[The attached picture is the user's shared screen right now.]"
            cam -> "[The attached picture is the user's live camera view right now.]"
            else -> ""
        }
    }

    private fun currentFrameDataUri(): String? {
        val cam = if (cameraOn) FrameHub.camera else null
        val scr = if (screenOn) FrameHub.screen else null
        val bmp = when {
            cam != null && scr != null -> stack(scr, cam)
            scr != null -> scr
            cam != null -> cam
            else -> null
        } ?: return null
        return MediaUtils.bitmapToDataUri(MediaUtils.scaleDown(bmp, 768), 70)
    }

    private fun stack(top: Bitmap, bottom: Bitmap): Bitmap {
        val w = 640
        val t = Bitmap.createScaledBitmap(top, w, (top.height * w.toFloat() / top.width).toInt().coerceAtLeast(1), true)
        val b = Bitmap.createScaledBitmap(bottom, w, (bottom.height * w.toFloat() / bottom.width).toInt().coerceAtLeast(1), true)
        val out = Bitmap.createBitmap(w, t.height + b.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(t, 0f, 0f, null)
        c.drawBitmap(b, 0f, t.height.toFloat(), null)
        return out
    }
}

/** Speaks a reply sentence by sentence while it is still being written, so answers start fast. */
class LiveVoice(ctx: android.content.Context, private val onIdle: () -> Unit) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var voiceOk = false
    private val pending = AtomicInteger(0)
    @Volatile private var finished = true
    private var counter = 0

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { utteranceDone() }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { utteranceDone() }
            })
        }
    }

    private fun utteranceDone() {
        if (pending.decrementAndGet() <= 0 && finished) onIdle()
    }

    /** Returns an error message if the accent voice is missing. */
    fun begin(tag: String): String? {
        finished = false
        pending.set(0)
        val t = tts
        if (t == null || !ready) { voiceOk = false; return "Voice is not ready yet." }
        val res = t.setLanguage(Locale.forLanguageTag(tag))
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            voiceOk = false
            return "This voice is not installed. Open Android Settings, Text to speech, and install ${Accents.name(tag)}."
        }
        voiceOk = true
        t.stop()
        return null
    }

    fun enqueue(text: String) {
        if (!voiceOk || text.isBlank()) return
        pending.incrementAndGet()
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "live${counter++}")
    }

    fun finish() {
        finished = true
        if (pending.get() <= 0) onIdle()
    }

    fun stop() {
        finished = true
        pending.set(0)
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}

private fun livePrompt(languageRule: String): String = """
You are NEXORA in a live voice conversation. The user talks to you and hears your answer out loud. Reply in one to three short, natural spoken sentences with simple words, like a friend on a call. No lists, no markdown, no emojis, and no dashes of any kind. When a picture is attached it shows what the user's camera sees right now or what is on their screen: use it naturally when it helps and never say that you received a picture. If you cannot see well, say so briefly.
$languageRule
""".trimIndent()

package com.nexorastudio.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.camera.core.Preview
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
 * Live voice conversation. The microphone stays on for the whole session (unless you mute it),
 * so you can talk over the AI to interrupt it. The camera and shared screen are sent as pictures
 * with each question.
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
    var showPreview by mutableStateOf(false)
        private set
    var frontCamera by mutableStateOf(false)
        private set
    var screenOn by mutableStateOf(false)
        private set

    private var active = false
    private var recognizing = false
    private var aiAudioInSession = false
    private var retries = 0
    private var recognizer: SpeechRecognizer? = null
    private var restartTask: Runnable? = null
    private var turnJob: Job? = null
    @Volatile private var turnId = 0
    private val history = mutableListOf<Pair<String, String>>()

    private val voice = LiveVoice(app) { main.post { onSpeechFinished() } }
    private val cameraFrames = CameraFrames(app)
    private val beeps = BeepGuard(app)

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
        beeps.mute()
        phase = if (muted) LivePhase.IDLE else LivePhase.LISTENING
        ensureRecognizing()
    }

    fun end() {
        active = false
        turnId++
        turnJob?.cancel()
        voice.stop()
        restartTask?.let { main.removeCallbacks(it) }
        recognizer?.destroy()
        recognizer = null
        recognizing = false
        beeps.restore()
        cameraFrames.stop()
        cameraOn = false
        showPreview = false
        muted = false
        stopScreen()
        phase = LivePhase.IDLE
        heard = ""
        reply = ""
        if (history.isNotEmpty()) {
            HistoryStore(getApplication()).saveTranscript(history.toList())
            history.clear()
        }
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
            phase == LivePhase.SPEAKING || phase == LivePhase.THINKING -> interruptNow()
        }
    }

    fun toggleMute() {
        muted = !muted
        retries = 0
        if (muted) {
            recognizer?.cancel()
            recognizing = false
            if (phase == LivePhase.LISTENING) phase = LivePhase.IDLE
        } else if (active) {
            if (phase == LivePhase.IDLE) phase = LivePhase.LISTENING
            ensureRecognizing()
        }
    }

    fun setCamera(on: Boolean) {
        cameraOn = on
        if (!on) {
            cameraFrames.stop()
            showPreview = false
        }
    }

    fun flipCamera() { frontCamera = !frontCamera }

    fun togglePreview() { showPreview = !showPreview }

    /** Called by the screen whenever the camera, preview or lens changes. */
    fun rebindCamera(owner: LifecycleOwner, provider: Preview.SurfaceProvider?) {
        cameraFrames.start(owner, frontCamera, provider) { ok, msg ->
            if (!ok) {
                cameraOn = false
                showPreview = false
                error = msg
            }
        }
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

    // ------------------------------------------------------------------ microphone (always on)

    private fun first(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val raw = first(partialResults) ?: return
            val t = if (aiAudioInSession) stripEcho(raw) else raw
            when (phase) {
                LivePhase.LISTENING, LivePhase.IDLE -> if (t.isNotBlank()) heard = t
                else -> if (isInterruption(t)) {
                    interruptNow()
                    if (t.isNotBlank()) heard = t
                }
            }
        }

        override fun onResults(results: Bundle?) {
            recognizing = false
            retries = 0
            val raw = first(results)?.trim().orEmpty()
            scheduleRestart(120)
            if (!active || raw.isBlank()) return
            val t = if (aiAudioInSession) stripEcho(raw) else raw
            if (t.isBlank()) return
            when (phase) {
                LivePhase.LISTENING, LivePhase.IDLE -> handleUser(t)
                else -> if (isInterruption(t)) {
                    interruptNow()
                    handleUser(t)
                }
            }
        }

        override fun onError(errorCode: Int) {
            recognizing = false
            if (!active || muted) return
            when (errorCode) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleRestart(150)

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    error = "Microphone permission is needed."
                    active = false
                    phase = LivePhase.IDLE
                }

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    recognizer?.destroy()
                    recognizer = null
                    scheduleRestart(500)
                }

                else -> {
                    retries++
                    if (retries > 8) {
                        error = "Speech recognition keeps failing. Check your internet connection and try again."
                        active = false
                        phase = LivePhase.IDLE
                    } else {
                        scheduleRestart(800)
                    }
                }
            }
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        restartTask?.let { main.removeCallbacks(it) }
        val task = Runnable { ensureRecognizing() }
        restartTask = task
        main.postDelayed(task, delayMs)
    }

    private fun ensureRecognizing() {
        if (!active || muted || recognizing) return
        recognizing = true
        aiAudioInSession = phase == LivePhase.SPEAKING
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

    // ------------------------------------------------------------------ interruption and echo handling

    private fun norm(w: String): String = w.lowercase().filter { it.isLetterOrDigit() || it == '\'' }

    private fun replyWords(): List<String> =
        reply.split(Regex("\\s+")).map { norm(it) }.filter { it.isNotEmpty() }

    private fun containsSeq(a: List<String>, s: List<String>): Boolean {
        if (s.size > a.size) return false
        for (i in 0..a.size - s.size) {
            var ok = true
            for (j in s.indices) {
                if (a[i + j] != s[j]) { ok = false; break }
            }
            if (ok) return true
        }
        return false
    }

    /** Removes the start of the text if it is just the phone hearing the AI's own voice. */
    private fun stripEcho(text: String): String {
        val a = replyWords()
        if (a.isEmpty()) return text
        val u = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var k = u.size
        while (k > 0) {
            val prefix = u.take(k).map { norm(it) }.filter { it.isNotEmpty() }
            if (prefix.isNotEmpty() && containsSeq(a, prefix)) break
            k--
        }
        return if (k >= 2 || (k > 0 && k == u.size)) u.drop(k).joinToString(" ") else text
    }

    private val stopWords = setOf("stop", "wait", "pause", "enough", "hold")

    /** True when what the mic hears is the user talking, not the AI's voice coming back through the speaker. */
    private fun isInterruption(text: String): Boolean {
        val u = text.trim().split(Regex("\\s+")).map { norm(it) }.filter { it.isNotEmpty() }
        if (u.isEmpty()) return false
        if (phase == LivePhase.THINKING && reply.isBlank()) return true
        val ref = replyWords().toSet()
        val novel = u.count { it !in ref }
        return novel >= 2 || (novel >= 1 && u.size <= 2 && u.any { it in stopWords })
    }

    private fun interruptNow() {
        turnId++
        turnJob?.cancel()
        voice.stop()
        phase = if (muted) LivePhase.IDLE else LivePhase.LISTENING
    }

    // ------------------------------------------------------------------ answering

    private fun handleUser(text: String) {
        turnJob?.cancel()
        val turn = ++turnId
        heard = text
        reply = ""
        phase = LivePhase.THINKING
        turnJob = viewModelScope.launch {
            try {
                respond(text, turn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (turn == turnId) {
                    error = e.message ?: e.toString()
                    voice.stop()
                    phase = if (muted) LivePhase.IDLE else LivePhase.LISTENING
                }
            }
        }
    }

    private suspend fun respond(text: String, turn: Int) {
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

        val voiceError = voice.begin(tag, settings.voiceName)
        if (voiceError != null) main.post { if (turn == turnId) error = voiceError }

        val sb = StringBuilder()
        val pending = StringBuilder()
        val full = api.streamChatAuto(models, msgs, "low", 400) { piece ->
            if (turn != turnId) return@streamChatAuto
            sb.append(piece)
            pending.append(piece)
            val shown = ReplyStyle.clean(sb.toString())
            main.post {
                if (turn == turnId) {
                    reply = shown
                    if (phase == LivePhase.THINKING) phase = LivePhase.SPEAKING
                    aiAudioInSession = true
                }
            }
            while (true) {
                val end = sentenceEnd(pending)
                if (end < 0) break
                val sentence = pending.substring(0, end + 1).trim()
                pending.delete(0, end + 1)
                if (sentence.length > 1) voice.enqueue(ReplyStyle.clean(sentence))
            }
        }
        if (turn != turnId) return
        if (pending.isNotBlank()) voice.enqueue(ReplyStyle.clean(pending.toString().trim()))
        history.add(text to full)
        if (full.isBlank()) main.post { if (turn == turnId) reply = "I did not catch that, please try again." }
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
        if (!active) return
        if (phase == LivePhase.SPEAKING || phase == LivePhase.THINKING) {
            phase = if (muted) LivePhase.IDLE else LivePhase.LISTENING
            ensureRecognizing()
        }
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

/**
 * Silences the recognizer's start and stop beeps while the live screen is open.
 * The AI voice plays on the call volume stream, so it is not affected. Everything is restored on exit.
 */
class BeepGuard(ctx: Context) {
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val muted = mutableListOf<Int>()

    fun mute() {
        if (muted.isNotEmpty()) return
        for (s in listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)) {
            runCatching {
                if (!am.isStreamMute(s)) {
                    am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0)
                    muted.add(s)
                }
            }
        }
    }

    fun restore() {
        muted.forEach { s -> runCatching { am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) } }
        muted.clear()
    }
}

/** Speaks a reply sentence by sentence while it is still being written, so answers start fast. */
class LiveVoice(ctx: Context, private val onIdle: () -> Unit) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var voiceOk = false
    private val pending = AtomicInteger(0)
    @Volatile private var finished = true
    private var counter = 0

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
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
    fun begin(tag: String, voiceName: String): String? {
        finished = false
        pending.set(0)
        val t = tts
        if (t == null || !ready) { voiceOk = false; return "Voice is not ready yet." }
        val res = t.setLanguage(Locale.forLanguageTag(tag))
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            voiceOk = false
            return "This voice is not installed. Open Android Settings, Text to speech, and install ${Accents.name(tag)}."
        }
        VoicePicker.apply(t, tag, voiceName)
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
You are NEXORA in a live voice conversation. The user talks to you and hears your answer out loud. Reply in one to three short, natural spoken sentences with simple words, like a friend on a call. Use contractions and a relaxed, warm tone. No lists, no markdown, no emojis, and no dashes of any kind. When a picture is attached it shows what the user's camera sees right now or what is on their screen: use it naturally when it helps and never say that you received a picture. If you cannot see well, say so briefly.
$languageRule
""".trimIndent()

package com.nexorastudio.app

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

private data class Turn(val user: String, val assistant: String)

class StudioViewModel(app: Application) : AndroidViewModel(app) {

    val settings = AppSettings(app)
    val store = ProjectStore(app)
    private val videoEngine = VideoEngine(app)
    private val main = Handler(Looper.getMainLooper())

    private val speaker = Speaker(app) { main.post { speakingId = null } }

    val items = mutableStateListOf<Msg>()

    /** What the user picked in the strip. AUTO = the router decides. */
    var manualMode by mutableStateOf(Mode.AUTO)

    /** Specialist used for the most recent request. */
    var activeMode by mutableStateOf(Mode.GENERAL)

    /** Mode that currently drives the animated colors (follows live routing while typing). */
    var auraMode by mutableStateOf(Mode.GENERAL)

    var attachment by mutableStateOf<Attachment?>(null)
        private set

    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set
    var progress by mutableStateOf<Float?>(null)
        private set

    /** Seconds since the current request started. Shown so you can see it is working. */
    var elapsed by mutableIntStateOf(0)
        private set

    /** Id of the reply being read aloud, if any. */
    var speakingId by mutableStateOf<Long?>(null)
        private set

    private var job: Job? = null
    private var counter = 0L
    private fun nid() = counter++

    private var currentProject: File? = null
    private var lastVideo: Uri? = null
    private val codeTurns = mutableListOf<Turn>()
    private val generalTurns = mutableListOf<Turn>()

    override fun onCleared() {
        speaker.shutdown()
        super.onCleared()
    }

    // ------------------------------------------------------------------ user actions

    fun attach(uri: Uri, kind: AttachKind) { attachment = Attachment(uri, kind) }

    fun attachFile(file: File) { attachment = Attachment(Uri.fromFile(file), AttachKind.IMAGE) }

    fun clearAttachment() { attachment = null }

    fun newChat() {
        job?.cancel()
        speaker.stop()
        speakingId = null
        items.clear()
        codeTurns.clear()
        generalTurns.clear()
        currentProject = null
        lastVideo = null
        attachment = null
        busy = false
        status = ""
        progress = null
    }

    fun stop() { job?.cancel() }

    fun send(raw: String) {
        val text = raw.trim()
        val att = attachment
        if ((text.isEmpty() && att == null) || busy) return
        if (!hasKey()) return

        val mode = Router.route(text, att, manualMode)
        activeMode = mode
        items.add(UserMsg(nid(), text, att))
        attachment = null

        launchWork(mode) { api ->
            when (mode) {
                Mode.CODE -> runCode(api, text, att)
                Mode.VIDEO -> runVideo(api, text, att)
                Mode.IMAGE -> runImage(api, text, att)
                else -> runGeneral(api, text, att)
            }
        }
    }

    /** Translates one reply into another language and shows it as a new reply. */
    fun translate(sourceText: String, tag: String) {
        if (busy || !hasKey()) return
        val lang = Accents.name(tag)
        activeMode = Mode.GENERAL
        launchWork(Mode.GENERAL) { api ->
            status = "Translating to $lang…"
            val msgs = listOf(
                ChatMsg(
                    "system",
                    "You are a translator. Output only the translation into $lang, using natural wording for that region. Keep the same tone and meaning. Never use dashes of any kind, reword instead. Leave code blocks unchanged."
                ),
                ChatMsg("user", ReplyStyle.clean(sourceText))
            )
            streamReply(api, msgs, 3000, settings.fastModel)
        }
    }

    fun toggleSpeak(id: Long, text: String) {
        if (speakingId == id) {
            speaker.stop()
            speakingId = null
            return
        }
        val err = speaker.speak(text, settings.accentTag)
        if (err != null) toast(err) else speakingId = id
    }

    fun previewVoice() {
        val err = speaker.speak(Accents.sample(settings.accentTag), settings.accentTag)
        if (err != null) toast(err)
    }

    // ------------------------------------------------------------------ shared plumbing

    private fun toast(msg: String) {
        Toast.makeText(getApplication(), msg, Toast.LENGTH_LONG).show()
    }

    private fun hasKey(): Boolean {
        if (settings.apiKey.isBlank()) {
            items.add(ErrorMsg(nid(), "Add your NVIDIA API key in Settings first (build.nvidia.com, Get API Key)."))
            return false
        }
        return true
    }

    private fun launchWork(mode: Mode, block: suspend (NvidiaApi) -> Unit) {
        job = viewModelScope.launch {
            busy = true
            elapsed = 0
            val ticker = launch {
                while (true) {
                    delay(1000)
                    elapsed += 1
                }
            }
            try {
                block(NvidiaApi(settings.apiKey, settings))
            } catch (e: CancellationException) {
                settleStreaming()
                items.add(AiMsg(nid(), mode, "Stopped."))
                throw e
            } catch (e: Exception) {
                settleStreaming()
                items.add(ErrorMsg(nid(), e.message ?: e.toString()))
            } finally {
                ticker.cancel()
                busy = false
                status = ""
                progress = null
            }
        }
    }

    /** Removes empty typing bubbles and freezes partial replies after a stop or an error. */
    private fun settleStreaming() {
        var i = 0
        while (i < items.size) {
            val m = items[i]
            if (m is AiMsg && m.streaming) {
                if (m.text.isBlank()) { items.removeAt(i); continue }
                items[i] = m.copy(streaming = false)
            }
            i++
        }
    }

    /** Streams one reply into the chat with a typing bubble. Returns the full text. */
    private suspend fun streamReply(api: NvidiaApi, msgs: List<ChatMsg>, maxTokens: Int, model: String): String {
        status = "Thinking…"
        val id = nid()
        items.add(AiMsg(id, Mode.GENERAL, "", true))
        val idx = items.lastIndex
        val sb = StringBuilder()
        var last = 0L
        val full = api.streamChat(
            model = model,
            messages = msgs,
            effort = "low",
            maxTokens = maxTokens,
            onThinking = {}
        ) { piece ->
            sb.append(piece)
            val now = SystemClock.uptimeMillis()
            if (now - last > 120) {
                last = now
                val snapshot = sb.toString()
                main.post { if (idx < items.size) items[idx] = AiMsg(id, Mode.GENERAL, snapshot, true) }
            }
        }
        items[idx] = AiMsg(id, Mode.GENERAL, full.trim().ifEmpty { "I have nothing to add, try asking another way." }, false)
        return full
    }

    // ------------------------------------------------------------------ GENERAL

    private suspend fun runGeneral(api: NvidiaApi, text: String, att: Attachment?) {
        val images = imagesOf(att)
        val system = Prompts.general(Accents.languageRule(settings))
        // Vision models often reject a system message next to an image, so it goes into the user message.
        val msgs = buildList {
            if (images.isEmpty()) add(ChatMsg("system", system))
            generalTurns.takeLast(6).forEach { add(ChatMsg("user", it.user)); add(ChatMsg("assistant", it.assistant)) }
            add(ChatMsg("user", if (images.isEmpty()) text else "$system\n\nUser says: $text", images))
        }
        val model = if (images.isEmpty()) settings.fastModel else settings.visionModel
        val full = streamReply(api, msgs, 1500, model)
        generalTurns.add(Turn(text, full))
    }

    // ------------------------------------------------------------------ CODE

    private suspend fun runCode(api: NvidiaApi, text: String, att: Attachment?) {
        val dir0 = currentProject
        val ctxNote = if (dir0 != null) "\n\n[Current project files: ${store.listRelative(dir0).joinToString()}]" else ""
        val images = imagesOf(att)
        val msgs = buildList {
            add(ChatMsg("system", Prompts.CODE))
            codeTurns.takeLast(2).forEach { add(ChatMsg("user", it.user)); add(ChatMsg("assistant", it.assistant)) }
            add(ChatMsg("user", text + ctxNote, images))
        }

        status = "Kimi is writing code…"
        var chars = 0
        var shown = 0
        val raw = api.streamChat(settings.chatModel, msgs, settings.effort) { piece ->
            chars += piece.length
            if (chars - shown > 150) {
                shown = chars
                val c = chars
                main.post { status = "Kimi is writing code… $c characters" }
            }
        }
        codeTurns.add(Turn(text, raw))

        val parsed = ResponseParser.parse(raw)
        if (parsed.files.isEmpty()) {
            items.add(AiMsg(nid(), Mode.CODE, raw.trim().ifEmpty { "(empty answer)" }))
            return
        }

        val dir = currentProject ?: store.newProject(text.ifBlank { "project" }).also { currentProject = it }
        val failed = mutableListOf<String>()
        val imgs = parsed.images.take(6)

        if (imgs.isNotEmpty()) {
            val sem = Semaphore(2)
            val done = AtomicInteger(0)
            status = "FLUX is painting ${imgs.size} image(s)…"
            coroutineScope {
                imgs.map { img ->
                    async {
                        sem.withPermit {
                            try {
                                val bytes = api.generateImage(img.prompt, img.width, img.height)
                                withContext(Dispatchers.IO) { store.saveImagePng(dir, img.id, bytes) }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failed.add("${img.id}: ${e.message}")
                                withContext(Dispatchers.IO) { store.savePlaceholder(dir, img.id, img.width, img.height) }
                            }
                            status = "FLUX painted ${done.incrementAndGet()}/${imgs.size} images…"
                        }
                    }
                }.awaitAll()
            }
        }

        status = "Wiring images into your code…"
        withContext(Dispatchers.IO) {
            parsed.files.forEach { store.writeText(dir, it.path, it.content) }
        }

        val summary = buildString {
            append(parsed.summary.ifBlank { "Project ready." })
            if (failed.isNotEmpty()) {
                append("\n\n⚠ ${failed.size} image(s) failed, placeholders used:\n")
                append(failed.joinToString("\n"))
            }
        }
        items.add(ProjectMsg(nid(), summary, dir.name))
    }

    // ------------------------------------------------------------------ IMAGE

    private suspend fun runImage(api: NvidiaApi, text: String, att: Attachment?) {
        val ctx = getApplication<Application>()
        val bytes: ByteArray
        val label: String
        if (att != null && att.kind == AttachKind.IMAGE) {
            status = "Reading your image…"
            val data = withContext(Dispatchers.IO) { MediaUtils.imageDataUri(ctx, att.uri, 1024) }
            status = "FLUX Kontext is editing your image…"
            bytes = api.editImage(text.ifBlank { "Enhance this image" }, data)
            label = "Edited with FLUX Kontext"
        } else {
            val t = text.lowercase()
            val (w, h) = when {
                listOf("wallpaper", "landscape", "wide", "banner", "cinematic").any { t.contains(it) } -> 1344 to 768
                listOf("portrait", "phone", "vertical", "poster", "story").any { t.contains(it) } -> 768 to 1344
                else -> 1024 to 1024
            }
            status = "FLUX is painting…"
            bytes = api.generateImage(text, w, h)
            label = "Generated with FLUX 3 Image Klein"
        }
        val file = withContext(Dispatchers.IO) { MediaUtils.savePng(ctx, bytes) }
        items.add(ImageMsg(nid(), Mode.IMAGE, label, file.absolutePath))
    }

    // ------------------------------------------------------------------ VIDEO

    private suspend fun runVideo(api: NvidiaApi, text: String, att: Attachment?) {
        val ctx = getApplication<Application>()
        if (att != null && att.kind == AttachKind.VIDEO) lastVideo = att.uri
        val uri = lastVideo
        if (uri == null) {
            items.add(AiMsg(nid(), Mode.VIDEO, "Attach a video with the clip button, then tell me how to edit it. For example: trim the first 5 seconds, make it black and white and add the caption Road trip."))
            return
        }

        status = "Watching your video…"
        val (durMs, frames) = withContext(Dispatchers.IO) { FrameSampler.sample(ctx, uri, 6) }
        val durSec = durMs / 1000.0

        status = "Kimi is planning the edit…"
        val prompt = "Request: ${text.ifBlank { "Make it look great" }}\nVideo duration: ${"%.1f".format(durSec)} seconds.\nSampled frames are attached in order."
        val raw = api.streamChat(
            settings.chatModel,
            listOf(ChatMsg("system", Prompts.VIDEO), ChatMsg("user", prompt, frames)),
            "low"
        )
        val plan = VideoPlanParser.parse(raw)
        if (plan == null) {
            items.add(AiMsg(nid(), Mode.VIDEO, raw.trim().ifEmpty { "I could not plan that edit. Try rephrasing." }))
            return
        }

        status = "Rendering your video…"
        progress = 0f
        val out = videoEngine.export(uri, plan, durMs) { p -> progress = p / 100f }
        progress = null
        items.add(VideoMsg(nid(), plan.reply, out.absolutePath))
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun imagesOf(att: Attachment?): List<String> {
        if (att == null || att.kind != AttachKind.IMAGE) return emptyList()
        val ctx = getApplication<Application>()
        return withContext(Dispatchers.IO) { listOf(MediaUtils.imageDataUri(ctx, att.uri, 1024)) }
    }
}

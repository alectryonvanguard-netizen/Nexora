package com.nexorastudio.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/** Languages and accents. The tag decides both the reply language and the voice accent. */
object Accents {
    val tags = listOf(
        "en-US", "en-GB", "en-AU", "en-IN", "en-NG", "en-ZA", "en-CA", "en-IE",
        "es-ES", "es-MX", "es-US", "fr-FR", "fr-CA", "pt-BR", "pt-PT", "de-DE",
        "it-IT", "nl-NL", "sv-SE", "pl-PL", "ru-RU", "tr-TR", "ar-SA", "hi-IN",
        "ja-JP", "ko-KR", "zh-CN", "zh-TW", "id-ID", "vi-VN", "th-TH", "sw-KE"
    )

    fun name(tag: String): String = Locale.forLanguageTag(tag).getDisplayName(Locale.ENGLISH)

    fun sample(tag: String): String = when (tag.substringBefore('-')) {
        "fr" -> "Bonjour, voici ma voix."
        "es" -> "Hola, así es como sueno."
        "pt" -> "Olá, é assim que eu falo."
        "de" -> "Hallo, so klinge ich."
        "it" -> "Ciao, ecco come suono."
        else -> "Hello, this is how I sound."
    }

    /** Rule added to the chat system prompt. */
    fun languageRule(s: AppSettings): String {
        val name = name(s.accentTag)
        return if (s.autoLanguage) {
            val variant = if (s.accentTag.startsWith("en")) name else "English (United States)"
            "Reply in the language the user writes in. When you reply in English, use the spelling and wording of $variant."
        } else {
            "Always reply in $name, with natural wording and spelling for that region, even if the user writes in another language."
        }
    }
}

/** Keeps replies simple and free of dashes. Code blocks are never touched. */
object ReplyStyle {
    private val bullet = Regex("(?m)^\\s*[-*]\\s+")
    private val heading = Regex("(?m)^#{1,6}\\s*")
    private val spacedDash = Regex("\\s*[—–]\\s*|\\s+-\\s+")
    private val doubleComma = Regex(",\\s*,")

    fun prose(s: String): String = s
        .replace(bullet, "• ")
        .replace(heading, "")
        .replace("**", "")
        .replace("__", "")
        .replace(spacedDash, ", ")
        .replace(doubleComma, ",")

    fun clean(text: String): String =
        text.split("```").mapIndexed { i, p -> if (i % 2 == 0) prose(p) else p }.joinToString("```")
}

/** Text to speech with the selected accent. */
class Speaker(ctx: Context, private val onFinished: () -> Unit) {
    private var tts: TextToSpeech? = null
    private var ready = false

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (utteranceId == LAST) onFinished() }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { onFinished() }
            })
        }
    }

    /** Returns an error message, or null when speaking started. */
    fun speak(text: String, tag: String, voiceName: String = ""): String? {
        val t = tts
        if (t == null || !ready) return "Voice is not ready yet, try again in a moment."
        val res = t.setLanguage(Locale.forLanguageTag(tag))
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            return "This voice is not installed. Open Android Settings, Text to speech, and install ${Accents.name(tag)}."
        }
        VoicePicker.apply(t, tag, voiceName)
        val chunks = chunk(plain(text), 3500)
        if (chunks.isEmpty()) return null
        t.stop()
        chunks.forEachIndexed { i, c ->
            t.speak(
                c,
                if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                if (i == chunks.lastIndex) LAST else "part$i"
            )
        }
        return null
    }

    /** Installed voices for this accent, best sounding first. */
    fun voices(tag: String): List<VoiceOption> {
        val t = tts
        return if (t != null && ready) VoicePicker.options(t, tag) else emptyList()
    }

    fun stop() { tts?.stop() }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }

    private fun plain(s: String): String = s
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("[*#_`]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun chunk(s: String, max: Int): List<String> {
        val out = mutableListOf<String>()
        var cur = StringBuilder()
        for (sentence in s.split(Regex("(?<=[.!?。！？])\\s+"))) {
            for (piece in sentence.chunked(max)) {
                if (cur.isNotEmpty() && cur.length + piece.length + 1 > max) {
                    out.add(cur.toString().trim())
                    cur = StringBuilder()
                }
                cur.append(piece).append(' ')
            }
        }
        if (cur.isNotBlank()) out.add(cur.toString().trim())
        return out
    }

    private companion object { const val LAST = "nexora_last" }
}

data class VoiceOption(val name: String, val label: String)

/** Picks the most natural installed voice for an accent: network and high quality voices sound the most human. */
object VoicePicker {
    private fun installed(v: Voice): Boolean =
        v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true

    private fun score(v: Voice): Int {
        var s = v.quality
        if (v.isNetworkConnectionRequired) s += 150
        if (v.name.contains("neural", ignoreCase = true) || v.name.contains("network", ignoreCase = true)) s += 100
        if (v.latency <= Voice.LATENCY_NORMAL) s += 5
        return s
    }

    fun forLocale(t: TextToSpeech, tag: String): List<Voice> {
        val loc = Locale.forLanguageTag(tag)
        val all = runCatching { t.voices }.getOrNull().orEmpty().filter { installed(it) }
        val exact = all.filter { it.locale.language == loc.language && it.locale.country.equals(loc.country, ignoreCase = true) }
        val pool = exact.ifEmpty { all.filter { it.locale.language == loc.language } }
        return pool.sortedByDescending { score(it) }
    }

    fun options(t: TextToSpeech, tag: String): List<VoiceOption> =
        forLocale(t, tag).mapIndexed { i, v ->
            val natural = v.isNetworkConnectionRequired || v.quality >= Voice.QUALITY_HIGH
            VoiceOption(v.name, "Voice ${i + 1}" + if (natural) " · natural" else "")
        }

    fun apply(t: TextToSpeech, tag: String, preferred: String) {
        val list = forLocale(t, tag)
        val chosen = list.firstOrNull { it.name == preferred } ?: list.firstOrNull()
        if (chosen != null) runCatching { t.voice = chosen }
    }
}

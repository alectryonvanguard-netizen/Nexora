
package com.nexorastudio.app

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

object Accents {
    val tags = listOf(
        "en-US", "en-GB", "en-KE", "sw-KE",
        "fr-FR", "es-ES", "de-DE", "pt-BR",
        "ar", "hi-IN", "ja-JP", "zh-CN"
    )

    fun name(tag: String): String = when (tag) {
        "en-US" -> "English (US)"
        "en-GB" -> "English (UK)"
        "en-KE" -> "English (Kenya)"
        "sw-KE" -> "Kiswahili"
        "fr-FR" -> "French"
        "es-ES" -> "Spanish"
        "de-DE" -> "German"
        "pt-BR" -> "Portuguese"
        "ar" -> "Arabic"
        "hi-IN" -> "Hindi"
        "ja-JP" -> "Japanese"
        "zh-CN" -> "Chinese"
        else -> Locale.forLanguageTag(tag).displayName
    }

    fun sample(tag: String): String = when (tag) {
        "sw-KE" -> "Habari! Karibu Nexora."
        "fr-FR" -> "Bonjour ! Bienvenue sur Nexora."
        "es-ES" -> "¡Hola! Bienvenido a Nexora."
        "de-DE" -> "Hallo! Willkommen bei Nexora."
        "pt-BR" -> "Olá! Bem-vindo ao Nexora."
        "ar" -> "مرحباً بك في نيكسورا"
        "hi-IN" -> "नमस्ते! नेक्सोरा में आपका स्वागत है।"
        "ja-JP" -> "こんにちは。Nexoraへようこそ。"
        "zh-CN" -> "你好，欢迎使用 Nexora。"
        else -> "Hello! Welcome to Nexora."
    }

    fun languageRule(settings: AppSettings): String {
        return if (settings.autoLanguage) {
            "Automatically detect the language used by the user and respond in that language."
        } else {
            "Respond in ${name(settings.accentTag)} unless the user explicitly requests another language."
        }
    }
}

object ReplyStyle {
    fun clean(text: String): String {
        return text
            .replace(Regex("(?m)^#{1,6}\\s*"), "")
            .replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")
            .replace(Regex("(?<!\\*)\\*([^*]+)\\*"), "$1")
            .replace(Regex("`([^`]+)`"), "$1")
            .replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    fun prose(text: String): String {
        return text
            .replace(Regex("(?m)^#{1,6}\\s*"), "")
            .replace(Regex("(?m)^\\s*[-*+]\\s+"), "• ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }
}

class Speaker(
    context: Context,
    private val onFinished: () -> Unit
) {
    private val appContext = context.applicationContext
    private lateinit var tts: TextToSpeech
    private var ready = false

    init {
        tts = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
        }

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                onFinished()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onFinished()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                onFinished()
            }
        })
    }

    fun speak(text: String, tag: String): String? {
        if (!ready) return "Text-to-speech is not ready yet."

        val locale = Locale.forLanguageTag(tag)
        val languageResult = tts.setLanguage(locale)

        if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            return "The selected voice language is not available on this device."
        }

        val id = UUID.randomUUID().toString()
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
        }

        val result = tts.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            params,
            id
        )

        return if (result == TextToSpeech.ERROR) {
            "Could not start speech playback."
        } else {
            null
        }
    }

    fun stop() {
        if (::tts.isInitialized) tts.stop()
    }

    fun shutdown() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }
}

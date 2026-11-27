package com.nexorastudio.app

import android.content.Context

class AppSettings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("nexora_settings", Context.MODE_PRIVATE)

    private fun str(key: String, def: String) = sp.getString(key, def)?.takeIf { it.isNotBlank() } ?: def

    var apiKey: String
        get() = sp.getString("key", "") ?: ""
        set(v) { sp.edit().putString("key", v.trim()).apply() }

    var chatModel: String
        get() = str("chat", "moonshotai/kimi-k3.1-pro")
        set(v) { sp.edit().putString("chat", v.trim()).apply() }

    /** Quick text model for normal chat and translations. */
    var fastModel: String
        get() = str("fast", "nvidia/nemotron-3.5-bolt-e4b")
        set(v) { sp.edit().putString("fast", v.trim()).apply() }

    /** Quick vision model for live camera, screen and image questions. */
    var visionModel: String
        get() = str("vision", "nvidia/nemotron-3.5-super-vl-120b-a12b")
        set(v) { sp.edit().putString("vision", v.trim()).apply() }

    /** Fast text models in order of preference. The main model is the last resort. */
    fun fastCandidates(): List<String> = listOf(
        fastModel,
        "nvidia/nemotron-3.5-bolt-e4b",
        "nvidia/nemotron-3.5-lightning-30b-a3b",
        "nvidia/nemotron-3-nano-30b-a3b",
        chatModel
    )

    /** Vision models in order of preference. The main model (which can see images) is the last resort. */
    fun visionCandidates(): List<String> = listOf(
        visionModel,
        "nvidia/nemotron-3.5-super-vl-120b-a12b",
        "minimaxai/minimax-m3.1-flash",
        chatModel
    )

    /** Main model for code, with fallbacks if NVIDIA retires it. */
    fun mainCandidates(): List<String> = listOf(
        chatModel,
        "nvidia/nemotron-3-super-120b-a12b",
        "nvidia/nemotron-3.5-lightning-30b-a3b"
    )

    /** Video planning needs a model that can see pictures. */
    fun videoCandidates(): List<String> = (listOf(chatModel) + visionCandidates()).distinct()

    var genModel: String
        get() = str("gen", "black-forest-labs/flux-3-image-klein")
        set(v) { sp.edit().putString("gen", v.trim()).apply() }

    var editModel: String
        get() = str("edit", "black-forest-labs/flux.1-kontext-dev")
        set(v) { sp.edit().putString("edit", v.trim()).apply() }

    var imageBase: String
        get() = str("imgbase", "https://ai.api.nvidia.com/v1/genai/")
        set(v) { sp.edit().putString("imgbase", v.trim()).apply() }

    var effort: String
        get() = str("effort", "low")
        set(v) { sp.edit().putString("effort", v).apply() }

    var accentTag: String
        get() = str("accent", "en-US")
        set(v) { sp.edit().putString("accent", v).apply() }

    /** Name of the chosen voice. Empty means pick the best one automatically. */
    var voiceName: String
        get() = sp.getString("voice", "") ?: ""
        set(v) { sp.edit().putString("voice", v).apply() }

    var autoLanguage: Boolean
        get() = sp.getBoolean("auto_lang", true)
        set(v) { sp.edit().putBoolean("auto_lang", v).apply() }

    var steps: Int
        get() = sp.getInt("steps", 30)
        set(v) { sp.edit().putInt("steps", v).apply() }
}

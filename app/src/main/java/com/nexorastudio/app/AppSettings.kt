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
        get() = str("fast", "meta/llama-3.3-70b-instruct")
        set(v) { sp.edit().putString("fast", v.trim()).apply() }

    /** Quick vision model for live camera, screen and image questions. */
    var visionModel: String
        get() = str("vision", "meta/llama-3.2-11b-vision-instruct")
        set(v) { sp.edit().putString("vision", v.trim()).apply() }

    var genModel: String
        get() = str("gen", "black-forest-labs/flux.3-image-klein")
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

    var autoLanguage: Boolean
        get() = sp.getBoolean("auto_lang", true)
        set(v) { sp.edit().putBoolean("auto_lang", v).apply() }

    var steps: Int
        get() = sp.getInt("steps", 30)
        set(v) { sp.edit().putInt("steps", v).apply() }
}

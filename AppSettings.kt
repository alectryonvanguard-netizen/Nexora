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
        get() = str("effort", "medium")
        set(v) { sp.edit().putString("effort", v).apply() }

    var steps: Int
        get() = sp.getInt("steps", 30)
        set(v) { sp.edit().putInt("steps", v).apply() }
}

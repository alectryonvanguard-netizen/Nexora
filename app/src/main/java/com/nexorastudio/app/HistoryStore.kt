package com.nexorastudio.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryEntry(val id: String, val title: String, val updated: Long, val count: Int)

data class SavedChat(
    val id: String,
    val title: String,
    val updated: Long,
    val project: String?,
    val messages: List<Msg>
)

/** Saves every conversation as a small JSON file so it can be reopened later. */
class HistoryStore(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "history").apply { mkdirs() }

    fun save(chat: SavedChat) {
        val arr = JSONArray()
        chat.messages.forEach { m -> toJson(m)?.let { arr.put(it) } }
        val o = JSONObject()
            .put("id", chat.id)
            .put("title", chat.title)
            .put("updated", chat.updated)
            .put("project", chat.project ?: "")
            .put("messages", arr)
        File(dir, "${chat.id}.json").writeText(o.toString())
    }

    fun list(): List<HistoryEntry> =
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { f ->
                runCatching {
                    val o = JSONObject(f.readText())
                    HistoryEntry(o.getString("id"), o.getString("title"), o.getLong("updated"), o.getJSONArray("messages").length())
                }.getOrNull()
            }
            .sortedByDescending { it.updated }

    fun load(id: String): SavedChat? = runCatching {
        val o = JSONObject(File(dir, "$id.json").readText())
        val arr = o.getJSONArray("messages")
        val msgs = mutableListOf<Msg>()
        for (i in 0 until arr.length()) fromJson(arr.getJSONObject(i))?.let { msgs.add(it) }
        SavedChat(
            id = o.getString("id"),
            title = o.getString("title"),
            updated = o.getLong("updated"),
            project = o.optString("project", "").ifEmpty { null },
            messages = msgs
        )
    }.getOrNull()

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }

    /** Stores a finished live conversation as a normal chat. */
    fun saveTranscript(pairs: List<Pair<String, String>>) {
        if (pairs.isEmpty()) return
        val msgs = mutableListOf<Msg>()
        var n = 0L
        pairs.forEach { (u, a) ->
            msgs.add(UserMsg(n++, u, null))
            msgs.add(AiMsg(n++, Mode.GENERAL, a, false))
        }
        val now = System.currentTimeMillis()
        save(SavedChat(now.toString(), "Live: " + pairs.first().first.take(34), now, null, msgs))
    }

    private fun toJson(m: Msg): JSONObject? = when (m) {
        is UserMsg -> JSONObject().put("t", "user").put("id", m.id).put("text", m.text)
            .put("auri", m.attachment?.uri?.toString() ?: "").put("akind", m.attachment?.kind?.name ?: "")
        is AiMsg -> JSONObject().put("t", "ai").put("id", m.id).put("mode", m.mode.name).put("text", m.text)
        is ProjectMsg -> JSONObject().put("t", "project").put("id", m.id).put("text", m.text).put("project", m.project)
        is ImageMsg -> JSONObject().put("t", "image").put("id", m.id).put("mode", m.mode.name).put("text", m.text).put("path", m.path)
        is VideoMsg -> JSONObject().put("t", "video").put("id", m.id).put("text", m.text).put("path", m.path)
        is ErrorMsg -> JSONObject().put("t", "error").put("id", m.id).put("text", m.text)
    }

    private fun mode(o: JSONObject): Mode =
        runCatching { Mode.valueOf(o.optString("mode", "GENERAL")) }.getOrDefault(Mode.GENERAL)

    private fun fromJson(o: JSONObject): Msg? {
        val id = o.optLong("id", 0L)
        return when (o.optString("t")) {
            "user" -> {
                val uri = o.optString("auri", "")
                val kind = runCatching { AttachKind.valueOf(o.optString("akind", "")) }.getOrNull()
                val att = if (uri.isNotEmpty() && kind != null) Attachment(Uri.parse(uri), kind) else null
                UserMsg(id, o.optString("text", ""), att)
            }
            "ai" -> AiMsg(id, mode(o), o.optString("text", ""), false)
            "project" -> ProjectMsg(id, o.optString("text", ""), o.optString("project", ""))
            "image" -> ImageMsg(id, mode(o), o.optString("text", ""), o.optString("path", ""))
            "video" -> VideoMsg(id, o.optString("text", ""), o.optString("path", ""))
            "error" -> ErrorMsg(id, o.optString("text", ""))
            else -> null
        }
    }
}

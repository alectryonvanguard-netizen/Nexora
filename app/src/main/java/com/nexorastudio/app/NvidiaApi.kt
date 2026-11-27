package com.nexorastudio.app

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, detail: String) : Exception("HTTP $code: $detail")

class NvidiaApi(private val apiKey: String, private val settings: AppSettings) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .build()

    private val json = "application/json".toMediaType()

    companion object {
        const val CHAT_URL = "https://integrate.api.nvidia.com/v1/chat/completions"
        const val STATUS_URL = "https://api.nvcf.nvidia.com/v2/nvcf/pexec/status/"
    }

    // ---------------------------------------------------------------- chat (Kimi, streaming)

    suspend fun streamChat(
        model: String,
        messages: List<ChatMsg>,
        effort: String,
        maxTokens: Int = 32000,
        onThinking: () -> Unit = {},
        onDelta: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply { messages.forEach { put(toJson(it)) } })
            put("max_tokens", maxTokens)
            put("temperature", 0.6)
            put("stream", true)
            if (model.contains("kimi", ignoreCase = true)) put("reasoning_effort", effort)
        }
        val req = Request.Builder()
            .url(CHAT_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(json))
            .build()

        val first = exec<Pair<String, String?>>(req) { resp ->
            if (resp.code == 202) return@exec Pair<String, String?>("", resp.header("NVCF-REQID"))
            if (!resp.isSuccessful) throw ApiException(resp.code, resp.body?.string().orEmpty().take(400))
            val ct = resp.header("Content-Type").orEmpty()
            if (!ct.contains("event-stream")) {
                val txt = resp.body?.string().orEmpty()
                val msg = JSONObject(txt).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                val c = if (msg.isNull("content")) "" else msg.getString("content")
                onDelta(c)
                return@exec Pair<String, String?>(c, null)
            }
            val source = resp.body?.source() ?: throw ApiException(resp.code, "Empty response")
            val sb = StringBuilder()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty()) continue
                if (data == "[DONE]") break
                val delta = runCatching {
                    JSONObject(data).getJSONArray("choices").getJSONObject(0).optJSONObject("delta")
                }.getOrNull() ?: continue
                val thinking = (delta.has("reasoning_content") && !delta.isNull("reasoning_content")) ||
                    (delta.has("reasoning") && !delta.isNull("reasoning"))
                if (thinking) onThinking()
                if (delta.isNull("content")) continue
                val piece = delta.getString("content")
                if (piece.isNotEmpty()) {
                    sb.append(piece)
                    onDelta(piece)
                }
            }
            Pair<String, String?>(sb.toString(), null)
        }

        val reqId = first.second ?: return@withContext first.first

        // The server queued the request (HTTP 202): poll until it is ready.
        var tries = 0
        while (tries < 150) {
            delay(2000)
            tries++
            val poll = Request.Builder()
                .url(STATUS_URL + reqId)
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .get()
                .build()
            val r = exec(poll) { Pair(it.code, it.body?.string().orEmpty()) }
            if (r.first == 202) { onThinking(); continue }
            if (r.first !in 200..299) throw ApiException(r.first, r.second.take(400))
            val msg = JSONObject(r.second).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val c = if (msg.isNull("content")) "" else msg.getString("content")
            onDelta(c)
            return@withContext c
        }
        throw ApiException(408, "The model took too long to answer. Please try again.")
    }

    private fun toJson(m: ChatMsg): JSONObject {
        val o = JSONObject().put("role", m.role)
        if (m.images.isEmpty()) {
            o.put("content", m.text)
        } else {
            val arr = JSONArray()
            arr.put(JSONObject().put("type", "text").put("text", m.text.ifBlank { "Look at the attached image(s)." }))
            m.images.forEach { uri ->
                arr.put(
                    JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", uri))
                )
            }
            o.put("content", arr)
        }
        return o
    }

    // ---------------------------------------------------------------- images (FLUX)

    /** Text -> image with FLUX 3 Image Klein. Falls back to 1024x1024 if the size is rejected. */
    suspend fun generateImage(prompt: String, width: Int, height: Int): ByteArray = withContext(Dispatchers.IO) {
        try {
            requestGenerate(prompt, width, height)
        } catch (e: ApiException) {
            if ((e.code == 400 || e.code == 422) && (width != 1024 || height != 1024)) {
                requestGenerate(prompt, 1024, 1024)
            } else throw e
        }
    }

    private suspend fun requestGenerate(prompt: String, w: Int, h: Int): ByteArray {
        val payload = JSONObject().apply {
            put("mode", "Image Generation")
            put("prompt", prompt.take(2000))
            put("width", w)
            put("height", h)
            put("cfg_scale", 3.5)
            put("samples", 1)
            put("seed", 0)
            put("steps", settings.steps.coerceIn(1, 50))
        }
        return postImage(settings.genModel, payload)
    }

    /** Image + instruction -> edited image (FLUX Kontext). [imageDataUri] is a data:image/...;base64 URI. */
    suspend fun editImage(prompt: String, imageDataUri: String): ByteArray = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("prompt", prompt.take(2000))
            put("image", imageDataUri)
            put("aspect_ratio", "match_input_image")
            put("cfg_scale", 3.5)
            put("seed", 0)
            put("steps", settings.steps.coerceIn(1, 50))
        }
        postImage(settings.editModel, payload)
    }

    private suspend fun postImage(model: String, payload: JSONObject): ByteArray {
        val name = model.trim().replace("flux-3-image-klein", "flux.3-image-klein")
        val base = settings.imageBase.trim().let { if (it.endsWith("/")) it else "$it/" }
        val req = Request.Builder()
            .url(base + name)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(json))
            .build()

        val first = exec(req) { Triple(it.code, it.header("NVCF-REQID"), it.body?.string().orEmpty()) }
        var code = first.first
        val reqId = first.second
        var body = first.third

        if (code == 202 && reqId != null) {
            var tries = 0
            while (code == 202 && tries < 150) {
                delay(2000)
                tries++
                val poll = Request.Builder()
                    .url(STATUS_URL + reqId)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Accept", "application/json")
                    .get()
                    .build()
                val r = exec(poll) { Triple(it.code, it.header("NVCF-REQID"), it.body?.string().orEmpty()) }
                code = r.first
                body = r.third
            }
        }
        if (code !in 200..299) throw ApiException(code, body.take(400))
        return extractImage(body)
    }

    private fun extractImage(text: String): ByteArray {
        val o = JSONObject(text)
        var b64 = ""
        if (o.has("artifacts")) {
            val a = o.getJSONArray("artifacts").getJSONObject(0)
            val reason = a.optString("finishReason", "")
            if (reason.contains("FILTER", ignoreCase = true)) throw ApiException(200, "Image blocked by the safety filter. Try a different prompt.")
            b64 = a.optString("base64", "").ifEmpty { a.optString("b64_json", "") }
        } else if (o.has("data")) {
            b64 = o.getJSONArray("data").getJSONObject(0).optString("b64_json", "")
        } else if (o.has("image")) {
            b64 = o.optString("image", "")
        } else if (o.has("b64_json")) {
            b64 = o.optString("b64_json", "")
        }
        if (b64.isEmpty()) throw ApiException(200, "No image in response: ${text.take(160)}")
        return Base64.decode(b64.substringAfter("base64,"), Base64.DEFAULT)
    }

    // ---------------------------------------------------------------- plumbing

    /** Runs a request; cancels the socket when the calling coroutine is cancelled. */
    private suspend fun <T> exec(req: Request, block: (Response) -> T): T {
        val call = http.newCall(req)
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { if (it != null) call.cancel() }
        try {
            return call.execute().use(block)
        } finally {
            handle?.dispose()
        }
    }
}

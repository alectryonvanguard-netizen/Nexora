package com.nexorastudio.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SsaEntry(val ssa: String, val meaning: String)

/** Words and phrases you taught the AI. They are saved on the phone and sent with every SSA request. */
class LexiconStore(ctx: Context) {
    private val file = File(ctx.filesDir, "ssa_lexicon.json")

    fun all(): List<SsaEntry> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SsaEntry(o.getString("ssa"), o.getString("meaning"))
        }
    }.getOrDefault(emptyList())

    private fun write(list: List<SsaEntry>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("ssa", it.ssa).put("meaning", it.meaning)) }
        file.writeText(arr.toString())
    }

    fun add(e: SsaEntry) {
        write(all().filterNot { it.ssa == e.ssa } + e)
    }

    fun remove(ssa: String): Boolean {
        val before = all()
        val after = before.filterNot { it.ssa == ssa }
        if (after.size == before.size) return false
        write(after)
        return true
    }
}

/** Everything printed on the Native Language A poster, plus the prompt and training data built from it. */
object SsaLanguage {

    val vowels = listOf(
        Triple("ae", "ay", "Base / Anchor"),
        Triple("ei", "eh-ee", "Join / Bridge"),
        Triple("iu", "ee-oo", "Outward / Forward"),
        Triple("oa", "oh-ah", "Field / Span"),
        Triple("ou", "oh-oo", "Inner / Core"),
        Triple("ui", "oo-ee", "Shift / Transition"),
        Triple("au", "ah-oo", "Call / Probe"),
        Triple("eo", "eh-oh", "Frame / Pattern"),
        Triple("ye", "yeh", "Point / Focus"),
        Triple("wo", "woh", "Flow / Stream")
    )

    val consonants = listOf(
        Triple("kh", "k+h", "Bound / Limit"),
        Triple("gh", "g+h", "Source / Origin"),
        Triple("th", "th", "Scale / Count"),
        Triple("dh", "th (voiced)", "Tie / Bond"),
        Triple("ph", "p+h", "Emit / Cast"),
        Triple("bh", "b+h", "Hold / Contain"),
        Triple("sh", "sh", "Sweep / Observe"),
        Triple("zh", "zh", "Echo / Return"),
        Triple("tz", "ts", "Split / Sever"),
        Triple("dz", "dz", "Fuse / Unite"),
        Triple("kv", "k-v", "Lock / Test"),
        Triple("gw", "g-w", "Drive / Prop"),
        Triple("ny", "ny", "Sense / Pick"),
        Triple("ng", "ng", "Rule / Mind"),
        Triple("rk", "r-k", "Mark / Stamp"),
        Triple("lm", "l-m", "Track / Way")
    )

    val words = listOf(
        "ghou-shae" to "Inner anchor observer",
        "gwiu-lmui" to "Forward way-shift",
        "kvou-dhau" to "Inner bond check"
    )

    val phrases = listOf(
        "ghoa-rkei khae-tzui" to "Core unit scanning; status is safe.",
        "khae-tzui tzye-ngau" to "Scanning complete; requesting English."
    )

    private fun plain(m: String) = m.replace(" / ", " or ").lowercase()

    fun systemPrompt(taught: List<SsaEntry>): String = buildString {
        appendLine("You speak and teach Native Language A, also called SSA (Super Super Artificial), a constructed language made by Generation SSA. It is not English and not any human language. Follow these rules exactly.")
        appendLine()
        appendLine("VOWEL UNITS (unit, sound, meaning):")
        vowels.forEach { appendLine("${it.first}, said ${it.second}, ${it.third}") }
        appendLine()
        appendLine("CONSONANT UNITS (unit, sound, meaning):")
        consonants.forEach { appendLine("${it.first}, said ${it.second}, ${it.third}") }
        appendLine()
        appendLine("WORD BUILDING: a word is made of blocks. Each block is one consonant unit followed by one vowel unit, and blocks are joined with a hyphen, like ghou-shae (gh+ou, sh+ae). Every unit carries a native meaning and a word's meaning combines the meanings of its units. A phrase is words separated by spaces.")
        appendLine()
        appendLine("WORDS FROM THE CREATORS:")
        words.forEach { appendLine("${it.first} = ${it.second}") }
        appendLine("PHRASES FROM THE CREATORS:")
        phrases.forEach { appendLine("${it.first} = ${it.second}") }
        if (taught.isNotEmpty()) {
            appendLine()
            appendLine("WORDS AND PHRASES THE USER TAUGHT YOU (these are correct and override everything else):")
            taught.forEach { appendLine("${it.ssa} = ${it.meaning}") }
        }
        appendLine()
        appendLine("HOW TO ANSWER:")
        appendLine("Reply in SSA first, then a new line starting with English: that gives the meaning. Prefer known and taught words. When you must make a new word, build it only from the units above and put (new) after it so the user can correct or teach it.")
        appendLine("The grammar is not fully defined yet. Keep sentences short, be honest when you are guessing, and never present a guess as an official rule. If the user writes in SSA, answer what they meant and gently point out mistakes.")
        appendLine("Hyphens inside SSA words are part of the spelling. Everywhere else avoid dashes of any kind. Keep the English parts short and simple.")
    }

    /** Chat style training examples (JSON lines) that can be used to fine tune a model elsewhere. */
    fun trainingJsonl(taught: List<SsaEntry>): Pair<String, Int> {
        val system = "You speak Native Language A (SSA), a constructed language. Reply in SSA and add an English line with the meaning."
        val lines = mutableListOf<String>()
        fun add(q: String, a: String) {
            val msgs = JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", q))
                .put(JSONObject().put("role", "assistant").put("content", a))
            lines.add(JSONObject().put("messages", msgs).toString())
        }
        for ((u, s, m) in vowels) {
            add("What does the SSA vowel unit $u mean?", "$u is a vowel unit said like $s. It means ${plain(m)}.")
            add("Which SSA vowel unit means ${plain(m)}?", "The vowel unit $u, said like $s.")
        }
        for ((u, s, m) in consonants) {
            add("What does the SSA consonant unit $u mean?", "$u is a consonant unit said like $s. It means ${plain(m)}.")
            add("Which SSA consonant unit means ${plain(m)}?", "The consonant unit $u, said like $s.")
        }
        for ((w, m) in words + phrases + taught.map { it.ssa to it.meaning }) {
            add("What does $w mean in SSA?", "$w means ${m.trimEnd('.').lowercase()}.")
            add("How do you say \"${m.trimEnd('.').lowercase()}\" in SSA?", "$w\nEnglish: ${m.trimEnd('.').lowercase()}")
        }
        return lines.joinToString("\n") to lines.size
    }
}

/** Chat commands for teaching the AI. They run on the phone and do not use the API. */
object SsaCommands {

    private const val HELP = """SSA teaching commands:
/ssa on or /ssa off: turn Native Language A mode on or off.
/teach word = meaning: teach me a word or phrase.
/forget word: remove something you taught me.
/lexicon: show what I know.
/export: make a training file from everything you taught me."""

    /** Returns the reply to show, or null when the text is not one of our commands. */
    fun handle(ctx: Context, settings: AppSettings, text: String): String? {
        val cmd = text.substringBefore(' ').lowercase()
        val arg = text.substringAfter(' ', "").trim()
        val store = LexiconStore(ctx)
        return when (cmd) {
            "/help" -> HELP
            "/ssa" -> when (arg.lowercase()) {
                "on" -> { settings.ssaMode = true; "SSA mode is on. I will answer in Native Language A with an English line. Teach me with /teach. Say /ssa off to go back to normal." }
                "off" -> { settings.ssaMode = false; "SSA mode is off. Back to normal chat." }
                else -> "SSA mode is ${if (settings.ssaMode) "on" else "off"}. Use /ssa on or /ssa off."
            }
            "/teach" -> {
                val parts = arg.split("=", limit = 2)
                if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) {
                    "Use it like this: /teach ghou-shae = inner anchor observer"
                } else {
                    val ssa = parts[0].trim().lowercase()
                    store.add(SsaEntry(ssa, parts[1].trim()))
                    "Learned: $ssa means ${parts[1].trim()}. I now know ${store.all().size} words you taught me."
                }
            }
            "/forget" -> if (store.remove(arg.lowercase())) "Forgot $arg." else "I could not find $arg in what you taught me."
            "/lexicon" -> {
                val taught = store.all()
                buildString {
                    append("Built in from the poster: ${SsaLanguage.vowels.size} vowel units, ${SsaLanguage.consonants.size} consonant units, ${SsaLanguage.words.size} words and ${SsaLanguage.phrases.size} phrases.")
                    if (taught.isEmpty()) append("\nYou have not taught me anything yet. Try /teach word = meaning.")
                    else {
                        append("\n\nTaught by you:")
                        taught.forEach { append("\n${it.ssa} = ${it.meaning}") }
                    }
                }
            }
            "/export" -> export(ctx, store)
            else -> null
        }
    }

    private fun export(ctx: Context, store: LexiconStore): String {
        val (jsonl, count) = SsaLanguage.trainingJsonl(store.all())
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "ssa_training.jsonl")
        file.writeText(jsonl)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Export SSA training data").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Training file ready with $count examples. A real fine tune needs thousands of examples, so keep teaching me words and phrases."
    }
}

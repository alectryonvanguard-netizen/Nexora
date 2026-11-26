package com.nexorastudio.app

/** Picks the right specialist for a request. Runs instantly on-device while the user types. */
object Router {

    private val code = mapOf(
        "code" to 3, "function" to 3, "bug" to 3, "debug" to 3, "compile" to 3, "error" to 1,
        "kotlin" to 4, "java" to 3, "python" to 4, "html" to 4, "css" to 4, "react" to 3,
        "script" to 2, "website" to 4, "web page" to 4, "webpage" to 4, "landing page" to 4,
        " app " to 2, " api " to 2, "sql" to 3, "regex" to 3, "gradle" to 4, "android" to 3,
        "algorithm" to 3, "refactor" to 4, "json" to 2, "swift" to 2, "flutter" to 4,
        "typescript" to 4, "component" to 2, "build me" to 2, "program" to 3, "github" to 2,
        "unit test" to 3, "dashboard" to 2, "game" to 2, "calculator" to 3
    )

    private val video = mapOf(
        "video" to 4, "clip" to 3, "trim" to 4, "speed up" to 4, "slow motion" to 4, "slow-mo" to 4,
        "slow down" to 3, "mute" to 3, "reel" to 3, "footage" to 4, "timelapse" to 3, "fade" to 2,
        "subtitle" to 2, "tiktok" to 2, "shorts" to 2, "cinematic" to 2, "rotate" to 1
    )

    private val image = mapOf(
        "image" to 3, "photo" to 3, "picture" to 3, "draw" to 4, "illustration" to 4, "logo" to 3,
        "wallpaper" to 4, "poster" to 3, "portrait" to 2, "remove background" to 5, "retouch" to 4,
        "enhance" to 2, "painting" to 3, "artwork" to 3, "sticker" to 2, "avatar" to 3,
        "edit this" to 3, "make it look" to 2, "turn it into" to 2, "change the background" to 4,
        "generate an image" to 5, "make an image" to 5
    )

    private val askWords = listOf("what is", "what's", "describe", "explain", "read", "ocr", "translate", "who is", "identify", "summarize")

    private fun score(t: String, words: Map<String, Int>) =
        words.entries.sumOf { if (t.contains(it.key)) it.value else 0 }

    fun route(text: String, attachment: Attachment?, manual: Mode): Mode {
        if (manual != Mode.AUTO) return manual
        val t = " ${text.lowercase().trim()} "
        val c = score(t, code)
        val v = score(t, video)
        val i = score(t, image)

        if (attachment?.kind == AttachKind.VIDEO) return Mode.VIDEO

        if (attachment?.kind == AttachKind.IMAGE) {
            return when {
                c >= 4 && c > i -> Mode.CODE                       // screenshot -> code
                askWords.any { t.contains(it) } && i < 4 -> Mode.GENERAL   // vision Q&A
                else -> Mode.IMAGE                                 // edit the image
            }
        }

        return when {
            v >= 4 && v > c && v >= i -> Mode.VIDEO
            c >= 3 && c >= i -> Mode.CODE
            i >= 3 -> Mode.IMAGE
            else -> Mode.GENERAL
        }
    }
}

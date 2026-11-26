package com.nexorastudio.app

import android.net.Uri

enum class Mode(val label: String, val hue: Float) {
    AUTO("Auto", 200f),
    CODE("Code", 160f),
    VIDEO("Video", 22f),
    IMAGE("Image", 320f),
    GENERAL("General", 215f)
}

enum class AttachKind { VIDEO, IMAGE }

data class Attachment(val uri: Uri, val kind: AttachKind)

sealed interface Msg { val id: Long }
data class UserMsg(override val id: Long, val text: String, val attachment: Attachment?) : Msg
data class AiMsg(override val id: Long, val mode: Mode, val text: String, val streaming: Boolean = false) : Msg
data class ProjectMsg(override val id: Long, val text: String, val project: String) : Msg
data class ImageMsg(override val id: Long, val mode: Mode, val text: String, val path: String) : Msg
data class VideoMsg(override val id: Long, val text: String, val path: String) : Msg
data class ErrorMsg(override val id: Long, val text: String) : Msg

/** One chat message sent to the model. [images] are data URIs (vision input). */
data class ChatMsg(val role: String, val text: String, val images: List<String> = emptyList())

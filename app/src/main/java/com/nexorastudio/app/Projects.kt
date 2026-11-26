package com.nexorastudio.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ImageSpec(val id: String, val width: Int, val height: Int, val prompt: String)
data class FileSpec(val path: String, val content: String)
data class ParsedAnswer(val summary: String, val images: List<ImageSpec>, val files: List<FileSpec>)

/** Parses the @@@IMAGE / @@@FILE / @@@END block format produced by the Code specialist. */
object ResponseParser {
    private val sizes = (768..1344 step 64).toList()
    private fun snap(v: Int): Int = sizes.minByOrNull { kotlin.math.abs(it - v) } ?: 1024

    fun parse(raw: String): ParsedAnswer {
        val summary = StringBuilder()
        val images = mutableListOf<ImageSpec>()
        val files = mutableListOf<FileSpec>()
        var kind = 0 // 0 = outside, 1 = image, 2 = file
        var header = ""
        val body = StringBuilder()

        fun finish() {
            when (kind) {
                1 -> {
                    val p = header.trim().split(Regex("\\s+"))
                    val id = p.getOrNull(0).orEmpty()
                        .replace(Regex("[^A-Za-z0-9_-]"), "-")
                        .ifEmpty { "image${images.size + 1}" }
                    val size = p.getOrNull(1)?.lowercase()?.split("x")
                    val w = snap(size?.getOrNull(0)?.toIntOrNull() ?: 1024)
                    val h = snap(size?.getOrNull(1)?.toIntOrNull() ?: 1024)
                    val prompt = body.toString().trim()
                    if (prompt.isNotEmpty() && images.none { it.id == id }) images.add(ImageSpec(id, w, h, prompt))
                }
                2 -> {
                    val path = header.trim().trim('"', '\'').trimStart('/')
                    if (path.isNotEmpty()) files.add(FileSpec(path, stripFences(body.toString())))
                }
            }
            kind = 0
            header = ""
            body.clear()
        }

        for (line in raw.lines()) {
            val t = line.trim()
            when {
                t.startsWith("@@@IMAGE") -> { if (kind != 0) finish(); kind = 1; header = t.removePrefix("@@@IMAGE") }
                t.startsWith("@@@FILE") -> { if (kind != 0) finish(); kind = 2; header = t.removePrefix("@@@FILE") }
                t == "@@@END" -> if (kind != 0) finish()
                kind != 0 -> body.appendLine(line)
                else -> summary.appendLine(line)
            }
        }
        if (kind != 0) finish()
        return ParsedAnswer(summary.toString().trim(), images, files)
    }

    private fun stripFences(s: String): String {
        val lines = s.trim('\n').lines().toMutableList()
        if (lines.size >= 2 && lines.first().trim().startsWith("```") && lines.last().trim() == "```") {
            lines.removeAt(0)
            lines.removeAt(lines.lastIndex)
        }
        return lines.joinToString("\n").trimEnd() + "\n"
    }
}

class ProjectStore(private val ctx: Context) {
    val root = File(ctx.filesDir, "projects").apply { mkdirs() }

    fun newProject(title: String): File {
        val slug = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32).ifEmpty { "project" }
        return File(root, "${System.currentTimeMillis()}-$slug").apply { mkdirs() }
    }

    fun displayName(dir: File): String = dir.name.substringAfter('-').replace('-', ' ').ifBlank { "Project" }

    fun listRelative(dir: File): List<String> =
        dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path.replace('\\', '/') }.sorted().toList()

    private fun resolve(dir: File, rel: String): File? {
        val base = dir.canonicalFile
        val f = File(base, rel.trimStart('/', '\\')).canonicalFile
        return if (f.path.startsWith(base.path + File.separator)) f else null
    }

    fun writeText(dir: File, rel: String, text: String): Boolean {
        val f = resolve(dir, rel) ?: return false
        f.parentFile?.mkdirs()
        f.writeText(text)
        return true
    }

    fun saveImagePng(dir: File, id: String, bytes: ByteArray): File {
        val out = File(dir, "assets/$id.png").apply { parentFile?.mkdirs() }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bmp != null) {
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
        } else {
            out.writeBytes(bytes)
        }
        return out
    }

    /** Dark placeholder so the page never shows a broken image if generation fails. */
    fun savePlaceholder(dir: File, id: String, w: Int, h: Int) {
        val out = File(dir, "assets/$id.png").apply { parentFile?.mkdirs() }
        val bmp = Bitmap.createBitmap(w / 2, h / 2, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.parseColor("#1B2030"))
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    fun zip(dir: File): File {
        val outDir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val out = File(outDir, "${dir.name}.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                zos.putNextEntry(ZipEntry(f.relativeTo(dir).path.replace('\\', '/')))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return out
    }

    fun delete(dir: File) { dir.deleteRecursively() }
}

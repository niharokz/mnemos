package com.nihars.mnemos.core.data

import com.nihars.mnemos.core.vaultfs.VaultFs
import com.nihars.mnemos.core.vaultfs.VaultPaths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Images added to a note. Same rules as the web app (internal/vault/attach.go), so a note
 * and its pictures look the same everywhere:
 *  - saved in `attachments/` next to the note, never overwriting ("photo 2.jpg");
 *  - only real PNG, JPEG, GIF, WebP or AVIF bytes (SVG can carry scripts), named by what they are;
 *  - a safe name: "My photo (1).JPEG" → "My-photo-1.jpg", a pasted "image.png" → "image-<time>.png";
 *  - linked with plain markdown relative to the note, spaces escaped.
 */
object Attachments {
    const val DIR = "attachments"

    private val UNSAFE = Regex("""[^\p{L}\p{M}\p{N}._-]+""")
    private val GENERIC = setOf("", "image", "blob", "pasted", "clipboard")
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** The file extension for the image these bytes really are, or null when they are not one. */
    fun imageExt(b: ByteArray): String? {
        fun at(i: Int, s: String) = b.size >= i + s.length && s.indices.all { b[i + it] == s[it].code.toByte() }
        return when {
            b.size >= 8 && b[0] == 0x89.toByte() && at(1, "PNG\r\n\u001a\n") -> "png"
            b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "jpg"
            at(0, "GIF87a") || at(0, "GIF89a") -> "gif"
            at(0, "RIFF") && at(8, "WEBP") -> "webp"
            at(4, "ftypavif") || at(4, "ftypavis") -> "avif"
            else -> null
        }
    }

    /** A safe file name with [ext] (".jpg"), built from what the picker or camera suggested. */
    fun safeName(suggested: String, ext: String, now: LocalDateTime): String {
        val file = suggested.replace('\\', '/').substringAfterLast('/')
        val stem = if ('.' in file) file.substringBeforeLast('.') else file
        var base = UNSAFE.replace(stem, "-").trim('-', '.')
        if (base.lowercase() in GENERIC) base = "image-" + now.format(STAMP)
        val points = base.codePoints().limit(80).toArray()
        return String(points, 0, points.size) + ext
    }

    /**
     * Saves [bytes] for the note at [notePath] and returns the new file's vault path.
     * Throws [IllegalArgumentException] when the bytes are not a supported image.
     */
    fun saveImage(fs: VaultFs, notePath: String, suggested: String, bytes: ByteArray, now: LocalDateTime): String {
        val ext = imageExt(bytes) ?: throw IllegalArgumentException("only PNG, JPEG, GIF, WebP and AVIF images can be added")
        val note = VaultPaths.checkWritable(notePath)
        val dir = VaultPaths.join(VaultPaths.parent(note), DIR)
        if (!fs.exists(dir)) fs.mkdirs(dir)
        val name = VaultPaths.uniqueName(dir, safeName(suggested, ".$ext", now)) { fs.exists(it) }
        val path = VaultPaths.join(dir, name)
        fs.createNew(path, bytes)
        return path
    }

    /** `![name](attachments/name.jpg)`: the markdown that shows [target] in the note at [notePath]. */
    fun markdown(notePath: String, target: String): String =
        "![${VaultPaths.stem(VaultPaths.name(target))}](${relLink(notePath, target)})"

    /** The link from the note's folder to [target], with characters markdown would trip over escaped. */
    fun relLink(notePath: String, target: String): String {
        val from = VaultPaths.parent(VaultPaths.normalize(notePath))
        val rel = when {
            from.isEmpty() -> target
            target.startsWith("$from/") -> target.removePrefix("$from/")
            else -> "../".repeat(from.count { it == '/' } + 1) + target
        }
        return rel.split('/').joinToString("/") { part ->
            part.replace(" ", "%20").replace("(", "%28").replace(")", "%29").replace("<", "%3C").replace(">", "%3E")
        }
    }
}

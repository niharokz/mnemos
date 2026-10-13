package com.nihars.mnemos.core.data

import com.nihars.mnemos.core.text.TextCodec
import com.nihars.mnemos.core.text.TextFormat
import com.nihars.mnemos.core.text.Versions
import com.nihars.mnemos.core.vaultfs.SaveCoordinator
import com.nihars.mnemos.core.vaultfs.SaveResult
import com.nihars.mnemos.core.vaultfs.VaultFs
import com.nihars.mnemos.core.vaultfs.VaultPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Quick capture: append one entry to the capture note. Used by the share target, tile and widget. */
object Capture {
    /** Formats the entry; extra lines are indented so they stay part of the same list item. */
    fun entry(format: String, text: String, now: LocalDateTime): String {
        val lines = text.trim().lines()
        val first = format
            .replace("{date}", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
            .replace("{time}", now.format(DateTimeFormatter.ofPattern("HH:mm")))
            .replace("{text}", lines.first())
        return (listOf(first) + lines.drop(1).map { "  $it" }).joinToString("\n")
    }

    /** Quick capture: appends to the capture note. */
    suspend fun append(fs: VaultFs, saver: SaveCoordinator, settings: AppSettings, text: String, now: LocalDateTime): String =
        appendTo(fs, saver, settings.captureNote, entry(settings.captureFormat, text, now))

    /**
     * Appends [entry] to [notePath] with the usual version check. Appending is safe to retry, so
     * if another app edits the note at the same moment we simply read again and append again.
     */
    suspend fun appendTo(fs: VaultFs, saver: SaveCoordinator, notePath: String, entry: String): String =
        withContext(Dispatchers.IO) {
            val path = VaultPaths.checkWritable(notePath)
            val parent = VaultPaths.parent(path)
            if (parent.isNotEmpty() && !fs.exists(parent)) fs.mkdirs(parent)
            repeat(3) {
                val existing = if (fs.exists(path)) fs.readBytes(path) else null
                val decoded = existing?.let { TextCodec.decode(it) }
                val old = decoded?.text ?: ""
                val joined = old + (if (old.isEmpty() || old.endsWith("\n")) "" else "\n") + entry + "\n"
                val bytes = TextCodec.encode(joined, decoded?.format ?: TextFormat.NEW_FILE)
                when (val r = saver.save(path, bytes, existing?.let { b -> Versions.of(b) })) {
                    is SaveResult.Saved, is SaveResult.Unchanged -> return@withContext path
                    is SaveResult.Failed -> throw IllegalStateException(r.message)
                    else -> Unit
                }
            }
            throw IllegalStateException("“$path” kept changing while saving. Try again.")
        }

    /**
     * Copies a shared file into `attachments/` next to [notePath] (never overwriting) and returns
     * its path relative to the note, ready for a markdown link.
     */
    suspend fun saveAttachment(fs: VaultFs, notePath: String, name: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val dir = VaultPaths.join(VaultPaths.parent(VaultPaths.normalize(notePath)), ATTACHMENTS)
        if (!fs.exists(dir)) fs.mkdirs(dir)
        val safe = name.replace('/', '_').replace('\\', '_').trim().ifEmpty { "shared" }
        val unique = VaultPaths.uniqueName(dir, safe) { fs.exists(it) }
        fs.createNew(VaultPaths.join(dir, unique), bytes)
        "$ATTACHMENTS/$unique"
    }

    /** One line for a shared file: an image embed for pictures, a link for anything else. */
    fun attachmentEntry(format: String, relative: String, name: String, isImage: Boolean, now: LocalDateTime): String {
        val link = if (isImage) "![$name](${relative.replace(" ", "%20")})" else "[$name](${relative.replace(" ", "%20")})"
        return entry(format, link, now)
    }

    const val ATTACHMENTS = Attachments.DIR
}

package com.nihars.mnemos.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import com.nihars.mnemos.core.data.AppGraph
import com.nihars.mnemos.core.data.Capture
import com.nihars.mnemos.core.data.UserKind
import com.nihars.mnemos.core.security.LockState
import com.nihars.mnemos.core.vaultfs.SaveCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/**
 * Anything shared to Mnemos (a link from Chrome, selected text, a photo, a PDF) is written to the
 * share file from Settings → Vault. Text and links become a timestamped line; files are copied into
 * `attachments/` next to the share file and linked from it.
 */
object ShareReceiver {
    data class Result(val message: String, val needsUnlock: Boolean = false)

    private const val MAX_FILE_BYTES = 50L * 1024 * 1024

    suspend fun save(context: Context, graph: AppGraph, intent: Intent): Result {
        val security = graph.lockStore.current()
        if (security.pin == null) return Result("Set up Mnemos first.", needsUnlock = true)
        val locked = graph.lock.state.value != LockState.UNLOCKED
        if (locked && !security.captureWhileLocked) return Result("Unlock Mnemos to save shared items.", needsUnlock = true)
        val session = graph.session.value?.takeIf { it.kind == UserKind.OWNER }
        val fs = session?.fs ?: graph.ownerFs().getOrElse { return Result(it.message ?: "Can't reach the vault.") }
        val saver = session?.saver ?: SaveCoordinator(fs)
        val settings = session?.settings?.value ?: graph.ownerSettings.settings.first()
        val target = settings.shareNote
        val now = LocalDateTime.now()
        return try {
            val lines = ArrayList<String>()
            text(intent)?.let { lines += Capture.entry(settings.captureFormat, it, now) }
            for (uri in streams(intent)) {
                val (name, bytes) = withContext(Dispatchers.IO) { read(context, uri) } ?: continue
                val rel = Capture.saveAttachment(fs, target, name, bytes)
                val isImage = (context.contentResolver.getType(uri) ?: intent.type.orEmpty()).startsWith("image/")
                lines += Capture.attachmentEntry(settings.captureFormat, rel, name, isImage, now)
            }
            if (lines.isEmpty()) return Result("Nothing to save in what was shared.")
            Capture.appendTo(fs, saver, target, lines.joinToString("\n"))
            session?.notifyChanged(target)
            graph.onVaultChanged()
            Result("Saved to $target")
        } catch (e: Exception) {
            Result("Couldn't save: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // Subject + text, so a shared article keeps its title above the link.
    private fun text(intent: Intent): String? {
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        val body = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val combined = when {
            subject.isEmpty() -> body
            body.isEmpty() || body.contains(subject) -> body.ifEmpty { subject }
            else -> "$subject — $body"
        }
        return combined.ifBlank { null }
    }

    private fun streams(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }

    // Name from the provider; files over 50 MB are skipped rather than filling the vault by surprise.
    private fun read(context: Context, uri: Uri): Pair<String, ByteArray>? {
        val resolver = context.contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "shared"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size > MAX_FILE_BYTES) return null
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        return name to bytes
    }
}

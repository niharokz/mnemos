package com.nihars.mnemos.core.data

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import com.nihars.mnemos.core.vaultfs.VaultPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** "Open with…" and friends. Grants are read-only and temporary (spec §10). */
object ExternalOpen {
    /** Returns a user-facing problem, or null when another app was started. */
    suspend fun openWith(context: Context, session: VaultSession, path: String): String? {
        val uri = withContext(Dispatchers.IO) { uriFor(context, session, path) } ?: return "Can't share this file from here."
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, FileKinds.mimeType(VaultPaths.name(path)))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(Intent.createChooser(intent, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        } catch (_: ActivityNotFoundException) {
            "No app on this phone can open “${VaultPaths.name(path)}”."
        }
    }

    /** Best effort: shows a folder in the system Files app. */
    fun openFolder(context: Context, absolute: String): String? {
        val storageRoot = android.os.Environment.getExternalStorageDirectory().absolutePath
        if (!absolute.startsWith(storageRoot)) return "This folder can't be opened in another app."
        val docId = "primary:" + absolute.removePrefix(storageRoot).trimStart('/')
        val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            null
        } catch (_: ActivityNotFoundException) {
            "No file manager could open “$absolute”."
        }
    }

    fun copy(context: Context, label: String, text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun uriFor(context: Context, session: VaultSession, path: String): Uri? {
        (session.fs as? SafVaultFs)?.let { return runCatching { it.documentUri(path) }.getOrNull() }
        val abs = session.fs.absolutePath(path) ?: return null
        return runCatching { FileProvider.getUriForFile(context, context.packageName + ".files", File(abs)) }.getOrNull()
    }
}

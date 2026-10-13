package com.nihars.mnemos.core.data

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.nihars.mnemos.core.vaultfs.VaultEntry
import com.nihars.mnemos.core.vaultfs.VaultException
import com.nihars.mnemos.core.vaultfs.VaultFs
import com.nihars.mnemos.core.vaultfs.VaultPaths
import com.nihars.mnemos.core.vaultfs.WriteOutcome
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * "Slower mode": the vault through a Storage Access Framework tree, used only when All files
 * access is refused. SAF cannot rename over an existing file, so saves overwrite in place
 * (truncate + write + fsync) and report [WriteOutcome.IN_PLACE].
 */
class SafVaultFs(private val resolver: ContentResolver, private val tree: Uri) : VaultFs {
    private val rootId: String = DocumentsContract.getTreeDocumentId(tree)
    // path → documentId; cleared on moves since ids can change.
    private val ids = ConcurrentHashMap<String, String>()

    override val rootLabel: String = tree.lastPathSegment ?: tree.toString()
    override val isFast: Boolean = false

    override fun list(dir: String): List<VaultEntry> {
        val rel = VaultPaths.normalize(dir)
        val id = idOf(rel) ?: throw VaultException.NotFound(rel)
        return children(id).map { c ->
            val path = VaultPaths.join(rel, c.name)
            ids[path] = c.id
            c.toEntry(path)
        }
    }

    override fun stat(path: String): VaultEntry? {
        val rel = VaultPaths.normalize(path)
        if (rel.isEmpty()) return VaultEntry("", "", true, 0, 0, false)
        val parentId = idOf(VaultPaths.parent(rel)) ?: return null
        val child = children(parentId).firstOrNull { it.name == VaultPaths.name(rel) } ?: return null
        ids[rel] = child.id
        return child.toEntry(rel)
    }

    override fun readBytes(path: String): ByteArray = io(path) {
        resolver.openInputStream(uriOf(path))?.use { it.readBytes() } ?: throw VaultException.NotFound(path)
    }

    override fun readHead(path: String, max: Int): ByteArray = io(path) {
        resolver.openInputStream(uriOf(path))?.use { input ->
            val buf = ByteArray(max)
            var n = 0
            while (n < max) {
                val r = input.read(buf, n, max - n)
                if (r < 0) break
                n += r
            }
            buf.copyOf(n)
        } ?: throw VaultException.NotFound(path)
    }

    override fun openChannel(path: String): FileChannel = io(path) {
        val pfd = resolver.openFileDescriptor(uriOf(path), "r") ?: throw VaultException.NotFound(path)
        // Closing the channel closes the descriptor too.
        ParcelFileDescriptor.AutoCloseInputStream(pfd).channel
    }

    override fun writeAtomic(path: String, bytes: ByteArray): WriteOutcome = io(path) {
        val rel = VaultPaths.checkWritable(path)
        val uri = if (stat(rel) == null) create(rel, isDir = false) else uriOf(rel)
        val pfd = resolver.openFileDescriptor(uri, "rwt") ?: throw VaultException.PermissionDenied(rel)
        pfd.use { fd ->
            FileOutputStream(fd.fileDescriptor).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
        }
        WriteOutcome.IN_PLACE
    }

    override fun createNew(path: String, bytes: ByteArray): WriteOutcome {
        val rel = VaultPaths.checkWritable(path)
        if (stat(rel) != null) throw VaultException.AlreadyExists(rel)
        return writeAtomic(rel, bytes)
    }

    override fun mkdirs(dir: String) {
        val rel = VaultPaths.checkWritable(dir)
        var current = ""
        for (part in rel.split('/')) {
            current = VaultPaths.join(current, part)
            val e = stat(current)
            if (e == null) io(current) { create(current, isDir = true) } else if (!e.isDirectory) throw VaultException.AlreadyExists(current)
        }
    }

    override fun move(from: String, to: String) {
        val src = VaultPaths.normalize(from)
        val dst = VaultPaths.checkWritable(to)
        if (stat(dst) != null) throw VaultException.AlreadyExists(dst)
        if (VaultPaths.isInside(dst, src)) throw VaultException.PathRejected(to, "a folder can't move inside itself.")
        io(dst) {
            val dstParent = VaultPaths.parent(dst)
            if (dstParent.isNotEmpty() && stat(dstParent) == null) mkdirs(dstParent)
            var uri = uriOf(src)
            if (VaultPaths.parent(src) != dstParent) {
                uri = DocumentsContract.moveDocument(resolver, uri, uriOf(VaultPaths.parent(src)), uriOf(dstParent))
                    ?: throw IOException("This storage provider can't move files.")
            }
            if (VaultPaths.name(src) != VaultPaths.name(dst)) {
                DocumentsContract.renameDocument(resolver, uri, VaultPaths.name(dst)) ?: throw IOException("Rename refused.")
            }
            ids.clear()
        }
    }

    override fun walk(dir: String, includeHidden: Boolean): List<VaultEntry> {
        val out = ArrayList<VaultEntry>()
        fun visit(rel: String) {
            for (e in runCatching { list(rel) }.getOrDefault(emptyList())) {
                if (!includeHidden && e.name.startsWith('.')) continue
                out += e
                if (e.isDirectory) visit(e.path)
            }
        }
        visit(VaultPaths.normalize(dir))
        return out
    }

    override fun absolutePath(path: String): String? = null

    /** Content URI for "Open with…" on the SAF path. */
    fun documentUri(path: String): Uri = uriOf(path)

    private data class Child(val id: String, val name: String, val mime: String, val size: Long, val modified: Long, val flags: Int) {
        val isDir get() = mime == Document.MIME_TYPE_DIR
        fun toEntry(path: String) = VaultEntry(
            path = path, name = name, isDirectory = isDir, size = if (isDir) 0 else size, modified = modified,
            readOnly = !isDir && (flags and Document.FLAG_SUPPORTS_WRITE) == 0,
        )
    }

    private fun children(parentId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)
        val out = ArrayList<Child>()
        resolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out += Child(c.getString(0), c.getString(1), c.getString(2) ?: "", c.getLong(3), c.getLong(4), c.getInt(5))
            }
        }
        return out
    }

    private fun idOf(rel: String): String? {
        if (rel.isEmpty()) return rootId
        ids[rel]?.let { return it }
        return stat(rel)?.let { ids[rel] }
    }

    private fun uriOf(path: String): Uri {
        val rel = VaultPaths.normalize(path)
        val id = idOf(rel) ?: throw VaultException.NotFound(rel)
        return DocumentsContract.buildDocumentUriUsingTree(tree, id)
    }

    private fun create(rel: String, isDir: Boolean): Uri {
        val parent = uriOf(VaultPaths.parent(rel))
        val mime = if (isDir) Document.MIME_TYPE_DIR else FileKinds.mimeType(VaultPaths.name(rel))
        val uri = DocumentsContract.createDocument(resolver, parent, mime, VaultPaths.name(rel)) ?: throw IOException("Create refused.")
        ids[rel] = DocumentsContract.getDocumentId(uri)
        return uri
    }

    private inline fun <T> io(path: String, block: () -> T): T = try {
        block()
    } catch (e: VaultException) {
        throw e
    } catch (_: FileNotFoundException) {
        throw VaultException.NotFound(path)
    } catch (e: SecurityException) {
        throw VaultException.PermissionDenied(path).also { it.initCause(e) }
    } catch (e: IOException) {
        throw VaultException.IoFailure(path, e)
    } catch (e: IllegalArgumentException) {
        throw VaultException.IoFailure(path, e)
    }
}

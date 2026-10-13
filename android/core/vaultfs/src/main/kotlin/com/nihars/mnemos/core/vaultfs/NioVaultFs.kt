package com.nihars.mnemos.core.vaultfs

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.SecureRandom

/** Fast vault access through java.nio (needs All files access on Android). */
class NioVaultFs(rootDir: Path) : VaultFs {
    private val root: Path = rootDir.toAbsolutePath().normalize()
    private val realRoot: Path = runCatching { root.toRealPath() }.getOrDefault(root)
    private val random = SecureRandom()

    override val rootLabel: String = root.toString()
    override val isFast: Boolean = true

    override fun list(dir: String): List<VaultEntry> = io(dir) {
        val base = resolve(dir)
        Files.newDirectoryStream(base).use { stream ->
            stream.mapNotNull { child -> entry(VaultPaths.join(VaultPaths.normalize(dir), child.fileName.toString()), child) }
        }
    }

    override fun stat(path: String): VaultEntry? = io(path) {
        val p = resolve(path)
        if (!Files.exists(p)) null else entry(VaultPaths.normalize(path), p)
    }

    override fun readBytes(path: String): ByteArray = io(path) { Files.readAllBytes(resolve(path)) }

    override fun readHead(path: String, max: Int): ByteArray = io(path) {
        FileChannel.open(resolve(path), StandardOpenOption.READ).use { ch ->
            val buf = ByteBuffer.allocate(minOf(max.toLong(), ch.size()).toInt())
            while (buf.hasRemaining() && ch.read(buf) >= 0) Unit
            buf.array().copyOf(buf.position())
        }
    }

    override fun openChannel(path: String): FileChannel = io(path) { FileChannel.open(resolve(path), StandardOpenOption.READ) }

    override fun writeAtomic(path: String, bytes: ByteArray): WriteOutcome = io(path) {
        val rel = VaultPaths.checkWritable(path)
        val target = resolve(rel)
        val parent = target.parent
        val temp = parent.resolve(".${target.fileName}.mnemos-${java.lang.Long.toHexString(random.nextLong())}.tmp")
        val tempChannel = try {
            FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        } catch (e: FileSystemException) {
            if (e is NoSuchFileException) throw e
            // Folder refuses new files but the file itself may be writable: overwrite in place.
            if (Files.exists(target) && Files.isWritable(target)) return@io writeInPlace(target, bytes)
            throw VaultException.PermissionDenied(rel)
        }
        try {
            tempChannel.use { ch ->
                writeFully(ch, bytes)
                ch.force(true)
            }
            copyPermissions(from = target, to = temp)
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(parent)
            WriteOutcome.ATOMIC
        } catch (e: IOException) {
            runCatching { Files.deleteIfExists(temp) }
            throw e
        }
    }

    override fun createNew(path: String, bytes: ByteArray): WriteOutcome {
        val rel = VaultPaths.checkWritable(path)
        if (stat(rel) != null) throw VaultException.AlreadyExists(rel)
        return writeAtomic(rel, bytes)
    }

    override fun mkdirs(dir: String) {
        val rel = VaultPaths.checkWritable(dir)
        io(rel) { Files.createDirectories(resolve(rel)) }
    }

    override fun move(from: String, to: String) {
        val src = VaultPaths.normalize(from)
        val dst = VaultPaths.checkWritable(to)
        if (src.isEmpty()) throw VaultException.PathRejected(from, "that is the vault folder itself.")
        if (VaultPaths.isInside(dst, src)) throw VaultException.PathRejected(to, "a folder can't move inside itself.")
        io(dst) {
            val target = resolve(dst)
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw VaultException.AlreadyExists(dst)
            Files.createDirectories(target.parent)
            // No REPLACE_EXISTING: a racing file at the target makes this fail instead of being overwritten.
            Files.move(resolve(src), target, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    override fun walk(dir: String, includeHidden: Boolean): List<VaultEntry> = io(dir) {
        val start = resolve(dir)
        val out = ArrayList<VaultEntry>()
        Files.walkFileTree(
            start,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(d: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (d != start && !includeHidden && d.fileName.toString().startsWith('.')) return FileVisitResult.SKIP_SUBTREE
                    if (d != start) out += entryOf(d, attrs)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(f: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (includeHidden || !f.fileName.toString().startsWith('.')) out += entryOf(f, attrs)
                    return FileVisitResult.CONTINUE
                }

                // An unreadable folder must not abort the whole scan.
                override fun visitFileFailed(f: Path, e: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
        out
    }

    override fun absolutePath(path: String): String = resolve(path).toString()

    /** Resolves inside the vault and refuses symlinks that point outside it. */
    internal fun resolve(path: String): Path {
        val rel = VaultPaths.normalize(path)
        val p = if (rel.isEmpty()) root else root.resolve(rel).normalize()
        if (!p.startsWith(root)) throw VaultException.PathRejected(path, "it is outside the vault.")
        val existing = generateSequence(p) { it.parent }.firstOrNull { Files.exists(it) } ?: root
        val real = runCatching { existing.toRealPath() }.getOrDefault(existing)
        if (!real.startsWith(realRoot)) throw VaultException.PathRejected(path, "it links outside the vault.")
        return p
    }

    private fun entryOf(p: Path, attrs: BasicFileAttributes): VaultEntry {
        val rel = root.relativize(p).toString().replace('\\', '/')
        return VaultEntry(
            path = rel,
            name = p.fileName.toString(),
            isDirectory = attrs.isDirectory,
            size = if (attrs.isDirectory) 0 else attrs.size(),
            modified = attrs.lastModifiedTime().toMillis(),
            readOnly = false,
        )
    }

    private fun entry(rel: String, p: Path): VaultEntry? {
        val attrs = runCatching { Files.readAttributes(p, BasicFileAttributes::class.java) }.getOrNull() ?: return null
        return entryOf(p, attrs).copy(path = rel, readOnly = !attrs.isDirectory && !Files.isWritable(p))
    }

    private fun writeInPlace(target: Path, bytes: ByteArray): WriteOutcome {
        FileChannel.open(target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { ch ->
            writeFully(ch, bytes)
            ch.force(true)
        }
        return WriteOutcome.IN_PLACE
    }

    private fun writeFully(ch: FileChannel, bytes: ByteArray) {
        val buf = ByteBuffer.wrap(bytes)
        while (buf.hasRemaining()) ch.write(buf)
    }

    // Keeps the file mode (e.g. a script stays executable) when the temp replaces it.
    private fun copyPermissions(from: Path, to: Path) {
        if (!Files.exists(from)) return
        runCatching { Files.setPosixFilePermissions(to, Files.getPosixFilePermissions(from)) }
    }

    // Makes the rename itself durable; not supported everywhere, so failures are ignored.
    private fun syncDirectory(dir: Path) {
        runCatching { FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) } }
    }

    private inline fun <T> io(path: String, block: () -> T): T = try {
        block()
    } catch (e: VaultException) {
        throw e
    } catch (_: NoSuchFileException) {
        throw VaultException.NotFound(path)
    } catch (_: FileAlreadyExistsException) {
        throw VaultException.AlreadyExists(path)
    } catch (_: AccessDeniedException) {
        throw VaultException.PermissionDenied(path)
    } catch (e: IOException) {
        throw VaultException.IoFailure(path, e)
    } catch (e: SecurityException) {
        throw VaultException.PermissionDenied(path).also { it.initCause(e) }
    }
}

package com.nihars.mnemos.core.vaultfs

import com.nihars.mnemos.core.text.Versions
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.LocalDateTime

class VaultFsTest {
    private lateinit var root: Path
    private lateinit var fs: NioVaultFs

    @Before fun setUp() {
        root = Files.createTempDirectory("vault")
        fs = NioVaultFs(root)
    }

    private fun write(rel: String, text: String) {
        val p = root.resolve(rel)
        Files.createDirectories(p.parent)
        Files.write(p, text.toByteArray())
    }

    @Test fun atomicWriteReplacesAndLeavesNoTemp() {
        write("a.md", "old")
        assertEquals(WriteOutcome.ATOMIC, fs.writeAtomic("a.md", "new".toByteArray()))
        assertEquals("new", String(Files.readAllBytes(root.resolve("a.md"))))
        assertEquals(listOf("a.md"), Files.list(root).map { it.fileName.toString() }.toList())
    }

    @Test fun atomicWriteKeepsFileMode() {
        write("run.sh", "echo")
        Files.setPosixFilePermissions(root.resolve("run.sh"), PosixFilePermissions.fromString("rwxr-x---"))
        fs.writeAtomic("run.sh", "echo hi".toByteArray())
        assertEquals("rwxr-x---", PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("run.sh"))))
    }

    @Test fun readOnlyFolderFallsBackToInPlace() {
        if (System.getProperty("user.name") == "root") return // root ignores folder permissions
        write("ro/n.md", "old")
        val dir = root.resolve("ro")
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            assertEquals(WriteOutcome.IN_PLACE, fs.writeAtomic("ro/n.md", "new".toByteArray()))
            assertEquals("new", String(Files.readAllBytes(dir.resolve("n.md"))))
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test fun pathEscapesAreRejected() {
        for (bad in listOf("../x", "a/../../x", "a/..")) {
            try {
                fs.readBytes(bad)
                fail("accepted $bad")
            } catch (_: VaultException.PathRejected) {
            }
        }
    }

    @Test fun symlinkOutsideVaultIsRejected() {
        val outside = Files.createTempDirectory("outside")
        Files.write(outside.resolve("secret.txt"), "s".toByteArray())
        Files.createSymbolicLink(root.resolve("link"), outside)
        try {
            fs.readBytes("link/secret.txt")
            fail("followed symlink out of the vault")
        } catch (_: VaultException.PathRejected) {
        }
    }

    @Test fun hiddenFoldersAreNotWritableExceptTrash() {
        try {
            fs.writeAtomic(".obsidian/app.json", "{}".toByteArray())
            fail("wrote into a hidden folder")
        } catch (_: VaultException.PathRejected) {
        }
        fs.mkdirs(".trash")
        fs.writeAtomic(".trash/x.md", "x".toByteArray())
    }

    @Test fun moveNeverOverwrites() {
        write("a.md", "a")
        write("b.md", "b")
        try {
            fs.move("a.md", "b.md")
            fail("overwrote b.md")
        } catch (_: VaultException.AlreadyExists) {
        }
        assertEquals("b", String(Files.readAllBytes(root.resolve("b.md"))))
        fs.move("a.md", "sub/dir/a2.md")
        assertTrue(Files.exists(root.resolve("sub/dir/a2.md")))
    }

    @Test fun trashNamesAndRestore() {
        write("x/note.md", "1")
        write("y/note.md", "2")
        val trash = Trash(fs)
        assertEquals(".trash/note.md", trash.moveToTrash("x/note.md"))
        assertEquals(".trash/note 2.md", trash.moveToTrash("y/note.md"))
        trash.restore(".trash/note.md", "x/note.md")
        assertEquals("1", String(Files.readAllBytes(root.resolve("x/note.md"))))
        assertEquals(1, trash.list().size)
    }

    @Test fun walkSkipsHiddenFolders() {
        write("a.md", "")
        write(".obsidian/c.json", "")
        write("d/e.md", "")
        val paths = fs.walk().map { it.path }.toSet()
        assertEquals(setOf("a.md", "d", "d/e.md"), paths)
    }

    @Test fun saveRules() {
        val save = SaveCoordinator(fs)
        write("n.md", "v1")
        val base = Versions.of("v1".toByteArray())
        val r1 = save.save("n.md", "v2".toByteArray(), base)
        assertTrue(r1 is SaveResult.Saved)
        // Same bytes again: nothing written.
        val mtime = Files.getLastModifiedTime(root.resolve("n.md"))
        Thread.sleep(15)
        assertTrue(save.save("n.md", "v2".toByteArray(), (r1 as SaveResult.Saved).version) is SaveResult.Unchanged)
        assertEquals(mtime, Files.getLastModifiedTime(root.resolve("n.md")))
        // Someone else edits; our stale save is refused and disk is untouched.
        write("n.md", "theirs")
        val r3 = save.save("n.md", "mine".toByteArray(), r1.version)
        assertTrue(r3 is SaveResult.Conflict)
        assertArrayEquals("theirs".toByteArray(), (r3 as SaveResult.Conflict).diskBytes)
        assertEquals("theirs", String(Files.readAllBytes(root.resolve("n.md"))))
        // Keep mine.
        assertTrue(save.save("n.md", "mine".toByteArray(), r1.version, force = true) is SaveResult.Saved)
        // Deleted elsewhere.
        Files.delete(root.resolve("n.md"))
        assertEquals(SaveResult.Missing, save.save("n.md", "x".toByteArray(), base))
        assertFalse(Files.exists(root.resolve("n.md")))
        // New note (no base) is created, including its folder.
        assertTrue(save.save("new/one.md", "x".toByteArray(), null) is SaveResult.Saved)
    }

    @Test fun conflictFileParsingAndGrouping() {
        val c = ConflictFiles.parse("notes/todo.sync-conflict-20261012-093000-ABCDEFG.md")!!
        assertEquals("notes/todo.md", c.originalPath)
        assertEquals("ABCDEFG", c.device)
        assertEquals(LocalDateTime.of(2026, 10, 12, 9, 30, 0), c.created)
        assertEquals("Makefile", ConflictFiles.parse("Makefile.sync-conflict-20261012-093000-ABCDEFG")!!.originalPath)
        assertEquals(null, ConflictFiles.parse("todo.md"))
        val g = ConflictFiles.group(
            listOf("a.sync-conflict-20260101-000000-AAAAAAA.md", "a.sync-conflict-20260102-000000-BBBBBBB.md", "b.md"),
        )
        assertEquals(2, g["a.md"]!!.size)
        val copy = ConflictFiles.copyName("d/n.md", LocalDateTime.of(2026, 10, 13, 1, 5)) { it == "d/n (conflict 2026-10-13 0105).md" }
        assertEquals("d/n (conflict 2026-10-13 0105) 2.md", copy)
    }

    @Test fun hiddenRulesAndSort() {
        val rules = HiddenRules(listOf("*.bak"))
        assertTrue(rules.isHidden(".git"))
        assertTrue(rules.isHidden("x.LOCK"))
        assertTrue(rules.isHidden("~draft"))
        assertTrue(rules.isHidden("old.bak"))
        assertFalse(rules.isHidden("note.md"))
        assertFalse(HiddenRules(showHidden = true).isHidden(".git"))
        val e = { n: String, d: Boolean -> VaultEntry(n, n, d, 0, 0, false) }
        val sorted = listOf(e("note 10", false), e("z", true), e("note 2", false)).sortedForTree(TreeSort.NAME).map { it.name }
        assertEquals(listOf("z", "note 2", "note 10"), sorted)
    }
}

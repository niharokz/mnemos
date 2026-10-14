package com.nihars.mnemos.feature.editor

import com.nihars.mnemos.feature.editor.FileRename.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileRenameTest {
    @Test fun keepsFolderAndExtension() {
        assertEquals(Result.Ok("inbox/Shopping list.md"), FileRename.target("inbox/Untitled.md", "Shopping list"))
        assertEquals(Result.Ok("Plan.txt"), FileRename.target("notes.txt", "  Plan  "))
        assertEquals(Result.Ok("a/Makefile2"), FileRename.target("a/Makefile", "Makefile2"))
    }

    @Test fun typedExtensionIsNotDoubled() {
        assertEquals(Result.Ok("idea.md"), FileRename.target("Untitled.md", "idea.md"))
        assertEquals(Result.Ok("v1.2 notes.md"), FileRename.target("x.md", "v1.2 notes"))
        assertEquals(Result.Ok("data.txt.md"), FileRename.target("x.md", "data.txt"))
    }

    @Test fun editableAndSuffix() {
        assertEquals("Untitled", FileRename.editable("inbox/Untitled.md"))
        assertEquals(".md", FileRename.suffix("inbox/Untitled.md"))
        assertEquals(".CSV", FileRename.suffix("Data.CSV"))
        assertEquals("", FileRename.suffix("Makefile"))
        assertEquals(".env", FileRename.editable(".env"))
    }

    @Test fun sameNameIsNoOp() {
        assertEquals(Result.Same, FileRename.target("a/b.md", "b"))
        assertEquals(Result.Same, FileRename.target("a/b.md", "b."))
        assertEquals(Result.Ok("a/B.md"), FileRename.target("a/b.md", "B"))
    }

    @Test fun rejectsBadNames() {
        listOf("", "   ", ".hidden", "a/b", "a\\b", "what?", "x:y", "con", "LPT1", "x".repeat(300)).forEach {
            assertTrue(it, FileRename.target("n.md", it) is Result.Invalid)
        }
    }

    @Test fun placeholders() {
        assertTrue(FileRename.isPlaceholder("Untitled.md"))
        assertTrue(FileRename.isPlaceholder("inbox/Untitled 3.md"))
        assertFalse(FileRename.isPlaceholder("Untitled draft.md"))
    }
}

package com.nihars.mnemos.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end flows on the demo user (no permissions, no PIN), so they run on any emulator.
 * The demo vault is app-private: these tests never touch real notes.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DemoFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val demo: File get() = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "demo-vault")

    @Before fun signInAsDemo() {
        InstrumentationRegistry.getInstrumentation().targetContext.graph.demo.reset()
        rule.waitUntilAtLeastOneExists(hasText("Demo user"), 10_000)
        rule.onNodeWithText("Demo user").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Notes"), 10_000)
    }

    @Test fun demoShowsBadgeAndFiles() {
        rule.onNodeWithText("Demo").assertExists()
        rule.onNodeWithText("Welcome").assertExists()
        rule.onNodeWithText("Markdown tour").assertExists()
    }

    @Test fun editAutosavesAndKeepsOtherBytes() {
        val file = File(demo, "notes/todo.md")
        val before = file.readText()
        rule.onNodeWithText("notes").performClick()
        rule.waitUntilAtLeastOneExists(hasText("todo"), 5_000)
        rule.onNodeWithText("todo").performClick()
        rule.waitUntilAtLeastOneExists(hasContentDescription("Saved"), 5_000)
        // Typing goes through the EditText; the autosave delay is 800 ms.
        rule.onNodeWithContentDescription("Bold").performClick()
        rule.waitUntil(5_000) { file.readText() != before }
        assertTrue(file.readText().contains("****"))
    }

    @Test fun outsideChangeWhileDirtyRaisesConflict() {
        val file = File(demo, "Welcome.md")
        rule.onNodeWithText("Welcome").performClick()
        rule.waitUntilAtLeastOneExists(hasContentDescription("Saved"), 5_000)
        // Someone else (Syncthing) rewrites the file while we have unsaved edits.
        rule.onNodeWithContentDescription("Heading").performClick()
        file.writeText("# Changed elsewhere\n")
        rule.waitUntilAtLeastOneExists(hasText("This file changed in another app. Autosave is paused."), 10_000)
        rule.onNodeWithText("Save as copy").performClick()
        rule.waitUntil(5_000) { demo.listFiles().orEmpty().any { it.name.startsWith("Welcome (conflict ") } }
        assertEquals("# Changed elsewhere\n", file.readText())
    }

    @Test fun csvCellEditChangesOnlyThatRow() {
        val file = File(demo, "tables/runs.tsv")
        val before = file.readLines()
        rule.onNodeWithText("tables").performClick()
        rule.waitUntilAtLeastOneExists(hasText("runs.tsv"), 5_000)
        rule.onNodeWithText("runs.tsv").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Mysuru"), 5_000)
        rule.onNodeWithText("Mysuru").performClick()
        // The grid cell and the sheet's field both show the value; the field is the last one.
        rule.onAllNodesWithText("Mysuru").onLast().performTextInput("!")
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(5_000) { file.readLines() != before }
        val after = file.readLines()
        assertEquals(before.size, after.size)
        assertEquals(1, before.indices.count { before[it] != after[it] })
    }

    @Test fun trashAndRestore() {
        val note = File(demo, "templates/Daily.md")
        rule.onNodeWithText("templates").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Daily"), 5_000)
        rule.onNodeWithContentDescription("Actions for Daily.md").performClick()
        rule.onNodeWithText("Move to trash").performClick()
        rule.waitUntil(5_000) { !note.exists() && File(demo, ".trash/Daily.md").exists() }
        rule.onNodeWithText("Undo").performClick()
        rule.waitUntil(5_000) { note.exists() }
    }
}

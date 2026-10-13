package com.nihars.mnemos.core.data

import com.nihars.mnemos.core.design.Appearance
import com.nihars.mnemos.core.vaultfs.TreeSort

enum class EditorFont { SANS, MONO, SERIF }

enum class ViewMode { EDIT, SPLIT, PREVIEW }

/** How the home screen shows the vault. */
enum class HomeView(val label: String) {
    LIST("List"),
    COMPACT("Compact"),
    GRID("Grid"),
    BOX("Box"),
    TABS("Tabs"),
}

/** Formatting toolbar buttons, in their default order. */
enum class ToolbarItem(val label: String) {
    BOLD("Bold"),
    ITALIC("Italic"),
    HEADING("Heading"),
    LIST("List"),
    CHECKBOX("Checkbox"),
    LINK("Link"),
    IMAGE("Image"),
    CODE("Code"),
    QUOTE("Quote"),
    TABLE("Table"),
    INDENT("Indent"),
    OUTDENT("Outdent"),
    UNDO("Undo"),
    REDO("Redo"),
}

/** Per-user settings (owner and demo each have their own). Lock settings live in LockStore instead. */
data class AppSettings(
    val appearance: Appearance = Appearance(),
    // Vault
    val hiddenPatterns: List<String> = emptyList(),
    val showHidden: Boolean = false,
    val captureNote: String = "inbox/note.md",
    val captureFormat: String = "- {date} {time} {text}",
    // Where anything shared from other apps lands (text, links, images, files).
    val shareNote: String = "inbox/shared.md",
    val newNoteFolder: String = "",
    val newNoteTemplate: String = "{title}.md",
    val treeSort: TreeSort = TreeSort.NAME,
    val homeView: HomeView = HomeView.LIST,
    val updateFolder: String = "apps",
    // Editor
    val editorFont: EditorFont = EditorFont.SANS,
    val textSizeSp: Int = 15,
    val lineHeight: Float = 1.45f,
    val lineNumbers: Boolean = false,
    val softWrap: Boolean = true,
    val spellcheck: Boolean = false,
    val autosaveMs: Long = 800,
    val toolbar: List<ToolbarItem> = ToolbarItem.entries.toList(),
    // Preview
    val previewTextSizeSp: Int = 15,
    val previewMaxWidthDp: Int = 720,
    val showFrontMatter: Boolean = true,
)

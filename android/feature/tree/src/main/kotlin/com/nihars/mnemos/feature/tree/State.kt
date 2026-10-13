package com.nihars.mnemos.feature.tree

import androidx.compose.runtime.Immutable
import com.nihars.mnemos.core.data.FileKind
import com.nihars.mnemos.core.data.HomeView
import com.nihars.mnemos.core.data.ScanProgress
import com.nihars.mnemos.core.vaultfs.TreeSort
import com.nihars.mnemos.core.vaultfs.VaultEntry

@Immutable
data class TreeRow(
    val entry: VaultEntry,
    val depth: Int,
    val expanded: Boolean,
    val kind: FileKind,
    val pinned: Boolean,
    val conflict: Boolean,
    // Position among siblings, used for the stagger when a folder opens.
    val siblingIndex: Int,
)

/** Something the snackbar can undo. */
sealed interface UndoAction {
    val message: String

    data class Trashed(val items: List<Pair<String, String>>, override val message: String) : UndoAction
}

@Immutable
data class TreeState(
    val rows: List<TreeRow> = emptyList(),
    val pinned: List<VaultEntry> = emptyList(),
    val filter: String = "",
    val sort: TreeSort = TreeSort.NAME,
    val selection: Set<String> = emptySet(),
    val scan: ScanProgress = ScanProgress(),
    val refreshing: Boolean = false,
    val loading: Boolean = true,
    val message: String? = null,
    val undo: UndoAction? = null,
    val isDemo: Boolean = false,
    val templates: List<String> = emptyList(),
    val view: HomeView = HomeView.LIST,
    // Folder shown by the Grid, Box and Tabs views ("" = vault root).
    val currentDir: String = "",
    // Children of currentDir, for the folder-at-a-time views.
    val browse: List<TreeRow> = emptyList(),
    // First lines of notes, for Box cards.
    val previews: Map<String, String> = emptyMap(),
    // Top-level folders, for the Tabs view.
    val topFolders: List<String> = emptyList(),
    // Which tab is selected in the Tabs view ("" = All).
    val tab: String = "",
    val defaultFolder: String = "",
)

@Immutable
data class TrashRow(val entry: VaultEntry, val original: String?)

@Immutable
data class TrashState(
    val rows: List<TrashRow> = emptyList(),
    val loading: Boolean = true,
    val message: String? = null,
    // Set when the original spot is taken and the user must pick a new path.
    val askPathFor: TrashRow? = null,
)

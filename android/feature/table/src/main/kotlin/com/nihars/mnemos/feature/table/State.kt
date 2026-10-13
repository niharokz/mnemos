package com.nihars.mnemos.feature.table

import androidx.compose.runtime.Immutable
import com.nihars.mnemos.core.csv.ColumnStats
import com.nihars.mnemos.core.design.SaveStatus

/** View-only sort: the file order changes only after "Apply sort to file". */
data class SortSpec(val column: Int, val ascending: Boolean)

@Immutable
data class TableState(
    val path: String,
    val loading: Boolean = true,
    val header: List<String> = emptyList(),
    val columnCount: Int = 0,
    // Record numbers (1-based) in display order after sort/filter.
    val order: List<Int> = emptyList(),
    val widthsDp: List<Int> = emptyList(),
    val numeric: Set<Int> = emptySet(),
    val sort: SortSpec? = null,
    val filterColumn: Int? = null,
    val filterText: String = "",
    val status: SaveStatus = SaveStatus.SAVED,
    val conflict: Boolean = false,
    val message: String? = null,
    val stats: Pair<String, ColumnStats>? = null,
    val readOnly: Boolean = false,
    // Bumped whenever cells change so rows recompose.
    val revision: Int = 0,
    val undoDelete: Int? = null,
)

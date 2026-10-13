package com.nihars.mnemos.feature.conflicts

import androidx.compose.runtime.Immutable
import com.nihars.mnemos.core.text.DiffRow

@Immutable
data class ConflictState(
    val conflictPath: String,
    val originalPath: String = "",
    val originalExists: Boolean = true,
    val rows: List<DiffRow> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val message: String? = null,
    // Where to go after resolving: the file to open (or null to just go back).
    val resolvedOpen: String? = null,
    val resolved: Boolean = false,
)

package com.nihars.mnemos.feature.viewer

import androidx.compose.runtime.Immutable

@Immutable
data class LargeTextState(
    val path: String,
    val size: Long = 0,
    val lineCount: Int = 0,
    val indexing: Boolean = true,
    val query: String = "",
    val hits: List<Int> = emptyList(),
    val hitIndex: Int = -1,
    val searching: Boolean = false,
    val follow: Boolean = false,
    val canEdit: Boolean = false,
    val error: String? = null,
    // Bumped when the file grew so visible lines re-read.
    val revision: Int = 0,
)

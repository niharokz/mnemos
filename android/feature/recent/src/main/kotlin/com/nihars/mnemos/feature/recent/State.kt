package com.nihars.mnemos.feature.recent

import androidx.compose.runtime.Immutable

@Immutable
data class RecentItem(
    val path: String,
    val name: String,
    val openedAt: Long?,
    val modified: Long,
    // Changed after we last opened or saved it: Syncthing, Obsidian or a script did it.
    val changedElsewhere: Boolean,
)

@Immutable
data class RecentGroup(val title: String, val items: List<RecentItem>)

@Immutable
data class RecentState(val groups: List<RecentGroup> = emptyList(), val loading: Boolean = true)

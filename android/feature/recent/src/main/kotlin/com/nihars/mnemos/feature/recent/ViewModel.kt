package com.nihars.mnemos.feature.recent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nihars.mnemos.core.data.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Recently opened plus recently changed anywhere in the vault (from the index). */
class RecentViewModel(private val session: VaultSession) : ViewModel() {
    private val _state = MutableStateFlow(RecentState())
    val state: StateFlow<RecentState> = _state.asStateFlow()

    init {
        reload()
        viewModelScope.launch { session.changes.collect { reload() } }
    }

    fun reload() = viewModelScope.launch {
        val groups = withContext(Dispatchers.IO) { build() }
        _state.update { it.copy(groups = groups, loading = false) }
    }

    private fun build(): List<RecentGroup> {
        val opened = runCatching { session.index.recentlyOpened(100) }.getOrDefault(emptyList()).toMap()
        val modified = runCatching { session.index.recentlyModified(100) }.getOrDefault(emptyList())
        val files = runCatching { session.index.allFiles() }.getOrDefault(emptyList()).associateBy { it.path }
        val paths = LinkedHashSet<String>().apply {
            addAll(opened.keys)
            addAll(modified.map { it.path })
        }
        val items = paths.mapNotNull { p ->
            val f = files[p] ?: return@mapNotNull null
            val seen = maxOf(opened[p] ?: 0L, session.ownWrites[p] ?: 0L)
            RecentItem(p, f.name, opened[p], f.mtime, changedElsewhere = seen > 0 && f.mtime > seen + GRACE_MS)
        }.sortedByDescending { maxOf(it.openedAt ?: 0L, it.modified) }

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        fun bucket(t: Long): String {
            val d = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
            return when {
                !d.isBefore(today) -> "Today"
                d == today.minusDays(1) -> "Yesterday"
                d.isAfter(today.minusDays(7)) -> "This week"
                else -> "Older"
            }
        }
        return items.groupBy { bucket(maxOf(it.openedAt ?: 0L, it.modified)) }
            .toList()
            .sortedBy { listOf("Today", "Yesterday", "This week", "Older").indexOf(it.first) }
            .map { (title, list) -> RecentGroup(title, list) }
    }

    private companion object {
        // Our own save sets mtime a moment after we record it; don't call that "elsewhere".
        const val GRACE_MS = 3_000L
    }
}

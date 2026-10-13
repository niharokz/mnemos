package com.nihars.mnemos.feature.search

import androidx.compose.runtime.Immutable
import com.nihars.mnemos.core.index.SearchHit

enum class TypeFilter(val label: String, val exts: Set<String>) {
    ALL("All", emptySet()),
    NOTES("Notes", setOf("md", "markdown")),
    TABLES("Tables", setOf("csv", "tsv")),
    TEXT("Text & code", setOf("txt", "log", "json", "jsonl", "yaml", "yml", "ini", "conf", "toml", "sh", "py", "go", "kt", "js", "css", "html")),
}

enum class DateFilter(val label: String, val days: Int?) {
    ANY("Any time", null),
    WEEK("7 days", 7),
    MONTH("30 days", 30),
    YEAR("1 year", 365),
}

@Immutable
data class SearchState(
    val query: String = "",
    val folder: String = "",
    val type: TypeFilter = TypeFilter.ALL,
    val date: DateFilter = DateFilter.ANY,
    val hits: List<SearchHit> = emptyList(),
    val searching: Boolean = false,
    val millis: Long = 0,
    val indexing: Boolean = false,
)

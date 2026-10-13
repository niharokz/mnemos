package com.nihars.mnemos.feature.editor

data class FindOptions(val matchCase: Boolean = false, val wholeWord: Boolean = false, val regex: Boolean = false)

/** Find and replace over a text snapshot. Invalid regexes give an error instead of a crash. */
object FindEngine {
    fun pattern(query: String, o: FindOptions): Result<Regex> = runCatching {
        val body = if (o.regex) query else Regex.escape(query)
        val word = if (o.wholeWord) "\\b(?:$body)\\b" else body
        if (o.matchCase) Regex(word) else Regex(word, RegexOption.IGNORE_CASE)
    }

    /** All match ranges (end exclusive), capped so a 10 MB file can't stall the UI. */
    fun find(text: CharSequence, query: String, o: FindOptions, cap: Int = 10_000): Result<List<IntRange>> {
        if (query.isEmpty()) return Result.success(emptyList())
        return pattern(query, o).map { r -> r.findAll(text).filter { it.range.last >= it.range.first }.take(cap).map { it.range }.toList() }
    }

    /** Text with every match replaced; `$1` groups work in regex mode. */
    fun replaceAll(text: String, query: String, replacement: String, o: FindOptions): Result<String> =
        pattern(query, o).map { r -> if (o.regex) r.replace(text, replacement) else r.replace(text, Regex.escapeReplacement(replacement)) }

    /** Replacement text for one match (expands groups in regex mode). */
    fun replacementFor(match: String, query: String, replacement: String, o: FindOptions): String =
        if (!o.regex) replacement else pattern(query, o).getOrNull()?.let { r -> r.replace(match, replacement) } ?: replacement
}

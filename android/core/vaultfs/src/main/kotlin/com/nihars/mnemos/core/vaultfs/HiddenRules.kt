package com.nihars.mnemos.core.vaultfs

/** Which names the tree, search and index skip. "Show hidden files" turns all of it off. */
class HiddenRules(extraPatterns: List<String> = emptyList(), private val showHidden: Boolean = false) {
    private val patterns: List<Regex> = (DEFAULTS + extraPatterns).map { it.trim() }.filter { it.isNotEmpty() }.map(::globToRegex)

    fun isHidden(name: String): Boolean = !showHidden && (name.startsWith('.') || patterns.any { it.matches(name) })

    /** True when any segment of [path] is hidden. */
    fun isHiddenPath(path: String): Boolean = !showHidden && path.split('/').any { isHidden(it) }

    companion object {
        val DEFAULTS = listOf("*.lock", "*.tmp", "~*")

        /** `*` and `?` globs, matched against a single name, case-insensitive. */
        fun globToRegex(glob: String): Regex {
            val sb = StringBuilder()
            for (c in glob) {
                when (c) {
                    '*' -> sb.append(".*")
                    '?' -> sb.append('.')
                    else -> sb.append(Regex.escape(c.toString()))
                }
            }
            return Regex(sb.toString(), RegexOption.IGNORE_CASE)
        }
    }
}

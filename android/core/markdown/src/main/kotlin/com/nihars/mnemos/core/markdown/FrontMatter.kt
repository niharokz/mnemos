package com.nihars.mnemos.core.markdown

/**
 * YAML front matter at the very top of a note. Parsed only for display and search;
 * the text itself is never rewritten by this code.
 */
data class FrontMatter(
    val raw: String,
    // Number of lines the front matter block uses (including both fences).
    val lineCount: Int,
    val properties: List<Pair<String, String>>,
) {
    fun value(key: String): String? = properties.firstOrNull { it.first.equals(key, ignoreCase = true) }?.second

    /** `tags:` as a list, accepting `[a, b]`, `a, b`, a block list, and leading `#`. */
    val tags: List<String>
        get() = value("tags")?.split(',')?.map { it.trim().removePrefix("#") }?.filter { it.isNotEmpty() } ?: emptyList()

    companion object {
        /** Splits [text] into front matter and body. Returns null front matter when there is none. */
        fun split(text: String): Pair<FrontMatter?, String> {
            if (!text.startsWith("---")) return null to text
            val lines = text.split('\n')
            if (lines.first().trimEnd() != "---") return null to text
            val end = (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" || lines[it].trimEnd() == "..." }
                ?: return null to text
            val raw = lines.subList(1, end).joinToString("\n")
            val fm = FrontMatter(raw, end + 1, parse(lines.subList(1, end)))
            // Body keeps blank lines in place of the front matter so line numbers stay true to the file.
            val body = "\n".repeat(end + 1) + lines.subList(end + 1, lines.size).joinToString("\n")
            return fm to body
        }

        // A small YAML subset: `key: value`, `key: [a, b]`, and `key:` followed by `- item` lines.
        private fun parse(lines: List<String>): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>()
            var listKey: String? = null
            val listItems = ArrayList<String>()
            fun flush() {
                listKey?.let { out += it to listItems.joinToString(", ") }
                listKey = null
                listItems.clear()
            }
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                if (listKey != null && trimmed.startsWith("- ")) {
                    listItems += unquote(trimmed.removePrefix("- ").trim())
                    continue
                }
                flush()
                val colon = line.indexOf(':')
                if (colon <= 0 || line[0].isWhitespace()) continue
                val key = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim()
                when {
                    value.isEmpty() -> listKey = key
                    value.startsWith("[") && value.endsWith("]") ->
                        out += key to value.substring(1, value.length - 1).split(',').map { unquote(it.trim()) }.filter { it.isNotEmpty() }.joinToString(", ")
                    else -> out += key to unquote(value)
                }
            }
            flush()
            return out
        }

        private fun unquote(v: String): String =
            if (v.length >= 2 && ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith('\'') && v.endsWith('\'')))) v.substring(1, v.length - 1) else v
    }
}

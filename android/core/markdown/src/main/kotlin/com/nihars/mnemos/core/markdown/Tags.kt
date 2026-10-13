package com.nihars.mnemos.core.markdown

/** Finds `#tags` in note text, skipping code fences and inline code. */
object Tags {
    // A tag starts after whitespace or line start and needs at least one letter (so "#1" and "#" headings don't count).
    private val TAG = Regex("""(?<=^|\s)#([\p{L}\p{N}_/-]*\p{L}[\p{L}\p{N}_/-]*)""")
    private val INLINE_CODE = Regex("`[^`]*`")

    fun extract(text: String): List<String> {
        val out = LinkedHashSet<String>()
        var inFence = false
        for (line in text.lineSequence()) {
            val t = line.trimStart()
            if (t.startsWith("```") || t.startsWith("~~~")) {
                inFence = !inFence
                continue
            }
            if (inFence) continue
            TAG.findAll(INLINE_CODE.replace(line, " ")).forEach { out += it.groupValues[1].lowercase() }
        }
        return out.toList()
    }
}

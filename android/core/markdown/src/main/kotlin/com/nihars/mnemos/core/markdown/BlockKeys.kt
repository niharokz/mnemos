package com.nihars.mnemos.core.markdown

/**
 * Stable keys for preview blocks: same source text ⇒ same key, so the LazyColumn only
 * recomposes the blocks that actually changed while typing.
 */
object BlockKeys {
    /** Builds keys from each block's source; repeated identical blocks get #2, #3… */
    fun assign(sources: List<String>): List<String> {
        val seen = HashMap<String, Int>()
        return sources.map { src ->
            val h = fnv64(src)
            val n = (seen[h] ?: 0) + 1
            seen[h] = n
            if (n == 1) h else "$h#$n"
        }
    }

    /** How many keys of [new] already existed in [old]: the blocks the preview can reuse. */
    fun reused(old: List<String>, new: List<String>): Int {
        val o = old.toHashSet()
        return new.count { it in o }
    }

    private fun fnv64(s: String): String {
        var h = -0x340d631b7bdddcdbL
        for (c in s) {
            h = h xor c.code.toLong()
            h *= 0x100000001b3L
        }
        return java.lang.Long.toHexString(h)
    }
}

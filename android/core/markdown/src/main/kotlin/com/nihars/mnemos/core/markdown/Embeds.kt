package com.nihars.mnemos.core.markdown

/**
 * Obsidian embeds — ![[photo.png]], ![[photo.png|300]], ![[photo.png|a caption]] — the way
 * Obsidian writes pasted images. Images become [Inline.Image] with `embed = true`; an embedded
 * note becomes a link to it. The web app reads the same syntax (internal/render/embed.go).
 */
object Embeds {
    private val EMBED = Regex("""!\[\[([^\[\]\n|]+)(?:\|([^\[\]\n]*))?]]""")
    private val SIZE = Regex("""^(\d+)(?:x(\d+))?$""")
    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "avif", "bmp", "svg", "heic")

    fun isImageName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in IMAGE_EXT

    /** Replaces embeds inside text runs; everything else passes through unchanged. */
    fun split(list: List<Inline>): List<Inline> {
        if (list.none { it is Inline.Text && "![[" in it.text }) return list
        val out = ArrayList<Inline>(list.size + 2)
        for (i in list) if (i is Inline.Text && "![[" in i.text) out += splitText(i.text) else out += i
        return out
    }

    private fun splitText(text: String): List<Inline> {
        val out = ArrayList<Inline>()
        var last = 0
        for (m in EMBED.findAll(text)) {
            if (m.range.first > last) out += Inline.Text(text.substring(last, m.range.first))
            out += embed(m.groupValues[1].substringBefore('#').trim(), m.groupValues[2].trim())
            last = m.range.last + 1
        }
        if (last < text.length) out += Inline.Text(text.substring(last))
        return out
    }

    private fun embed(target: String, option: String): Inline {
        val name = target.substringAfterLast('/')
        if (!isImageName(target)) {
            // A note: link to it (".md" added like Obsidian's own links).
            val dest = if ('.' in name) target else "$target.md"
            return Inline.Link(dest, listOf(Inline.Text(name)))
        }
        val size = SIZE.find(option)
        val alt = if (size != null || option.isEmpty()) name.substringBeforeLast('.') else option
        return Inline.Image(target, alt, width = size?.groupValues?.get(1)?.toIntOrNull(), embed = true)
    }
}

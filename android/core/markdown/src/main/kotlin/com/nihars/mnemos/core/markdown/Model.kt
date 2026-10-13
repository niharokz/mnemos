package com.nihars.mnemos.core.markdown

/** Inline content, kept free of Compose so this module stays plain Kotlin. */
sealed interface Inline {
    data class Text(val text: String) : Inline
    data class Code(val text: String) : Inline
    data class Emph(val children: List<Inline>) : Inline
    data class Strong(val children: List<Inline>) : Inline
    data class Strike(val children: List<Inline>) : Inline
    data class Link(val destination: String, val children: List<Inline>) : Inline
    /**
     * A picture. [embed] marks Obsidian's ![[name]], whose [destination] is a file name to find
     * in the vault rather than a path; [width] comes from ![[name|300]].
     */
    data class Image(val destination: String, val alt: String, val width: Int? = null, val embed: Boolean = false) : Inline
    data class FootnoteRef(val label: String) : Inline

    /** Soft and hard breaks both render as a new line (Obsidian keeps single line breaks). */
    data object Break : Inline
}

enum class Align { NONE, LEFT, CENTER, RIGHT }

data class ListItem(
    // null: not a task; true/false: checked state.
    val task: Boolean?,
    // File line (0-based) of the item, so a preview tap can toggle the right checkbox.
    val line: Int,
    val blocks: List<Block>,
)

/** A top-level preview block. [key] stays the same while the block's source is unchanged. */
sealed interface Block {
    val key: String
    val line: Int

    data class Heading(override val key: String, override val line: Int, val level: Int, val inlines: List<Inline>) : Block
    data class Paragraph(override val key: String, override val line: Int, val inlines: List<Inline>) : Block
    data class CodeBlock(override val key: String, override val line: Int, val language: String, val code: String) : Block
    data class Quote(override val key: String, override val line: Int, val blocks: List<Block>) : Block
    data class ListBlock(override val key: String, override val line: Int, val ordered: Boolean, val start: Int, val items: List<ListItem>) : Block
    data class Table(
        override val key: String,
        override val line: Int,
        val header: List<List<Inline>>,
        val rows: List<List<List<Inline>>>,
        val align: List<Align>,
    ) : Block
    data class Rule(override val key: String, override val line: Int) : Block

    /** Raw HTML is shown as text, never executed. */
    data class RawHtml(override val key: String, override val line: Int, val text: String) : Block
    data class Footnote(override val key: String, override val line: Int, val label: String, val blocks: List<Block>) : Block
    data class Properties(override val key: String, override val line: Int, val properties: List<Pair<String, String>>) : Block
}

/** A parsed note ready for the preview: front matter, blocks and what the index needs. */
data class MarkdownDoc(
    val blocks: List<Block>,
    val frontMatter: FrontMatter?,
    val title: String?,
    val tags: List<String>,
)

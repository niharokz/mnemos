package com.nihars.mnemos.core.markdown

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.footnotes.FootnoteDefinition
import org.commonmark.ext.footnotes.FootnoteReference
import org.commonmark.ext.footnotes.FootnotesExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.node.ListItem as MdListItem

/** commonmark + GFM extensions → [MarkdownDoc]. Thread-safe; one parser is reused. */
class MarkdownParser {
    private val parser: Parser = Parser.builder()
        .extensions(
            listOf(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                TaskListItemsExtension.create(),
                AutolinkExtension.create(),
                FootnotesExtension.create(),
            ),
        )
        .includeSourceSpans(IncludeSourceSpans.BLOCKS)
        .build()

    fun parse(text: String, fileStem: String? = null): MarkdownDoc {
        val (fm, body) = FrontMatter.split(text)
        val root = parser.parse(body)
        val lines = body.split('\n')
        val top = generateSequence(root.firstChild) { it.next }.toList()
        val sources = top.map { sourceOf(it, lines) }
        val keys = BlockKeys.assign(sources)
        val blocks = ArrayList<Block>()
        if (fm != null) blocks += Block.Properties("fm-" + BlockKeys.assign(listOf(fm.raw)).first(), 0, fm.properties)
        top.forEachIndexed { i, node -> toBlock(node, keys[i])?.let { blocks += it } }
        val title = top.filterIsInstance<Heading>().firstOrNull { it.level == 1 }?.let { plain(inlines(it)) } ?: fileStem
        val tags = (fm?.tags.orEmpty().map { it.lowercase() } + Tags.extract(body)).distinct()
        return MarkdownDoc(blocks, fm, title, tags)
    }

    private fun toBlock(node: Node, key: String): Block? {
        val line = lineOf(node)
        return when (node) {
            is Heading -> Block.Heading(key, line, node.level, inlines(node))
            is Paragraph -> Block.Paragraph(key, line, inlines(node))
            is FencedCodeBlock -> Block.CodeBlock(key, line, node.info.orEmpty().trim().substringBefore(' '), node.literal.removeSuffix("\n"))
            is IndentedCodeBlock -> Block.CodeBlock(key, line, "", node.literal.removeSuffix("\n"))
            is BlockQuote -> Block.Quote(key, line, children(node, key))
            is BulletList -> Block.ListBlock(key, line, ordered = false, start = 1, items = items(node, key))
            is OrderedList -> Block.ListBlock(key, line, ordered = true, start = node.markerStartNumber ?: 1, items = items(node, key))
            is TableBlock -> table(node, key, line)
            is ThematicBreak -> Block.Rule(key, line)
            is HtmlBlock -> Block.RawHtml(key, line, node.literal.removeSuffix("\n"))
            is FootnoteDefinition -> Block.Footnote(key, line, node.label, children(node, key))
            else -> null
        }
    }

    private fun children(parent: Node, key: String): List<Block> =
        generateSequence(parent.firstChild) { it.next }.mapIndexedNotNull { i, n -> toBlock(n, "$key/$i") }.toList()

    private fun items(list: Node, key: String): List<ListItem> =
        generateSequence(list.firstChild) { it.next }.filterIsInstance<MdListItem>().mapIndexed { i, item ->
            val marker = generateSequence(item.firstChild) { it.next }.filterIsInstance<TaskListItemMarker>().firstOrNull()
            ListItem(task = marker?.isChecked, line = lineOf(item), blocks = children(item, "$key/$i"))
        }.toList()

    private fun table(node: TableBlock, key: String, line: Int): Block.Table {
        val header = ArrayList<List<Inline>>()
        val align = ArrayList<Align>()
        val rows = ArrayList<List<List<Inline>>>()
        for (section in generateSequence(node.firstChild) { it.next }) {
            for (row in generateSequence(section.firstChild) { it.next }.filterIsInstance<TableRow>()) {
                val cells = generateSequence(row.firstChild) { it.next }.filterIsInstance<TableCell>().toList()
                if (section is TableHead) {
                    cells.forEach { c ->
                        header += inlines(c)
                        align += when (c.alignment) {
                            TableCell.Alignment.LEFT -> Align.LEFT
                            TableCell.Alignment.CENTER -> Align.CENTER
                            TableCell.Alignment.RIGHT -> Align.RIGHT
                            else -> Align.NONE
                        }
                    }
                } else {
                    rows += cells.map { inlines(it) }
                }
            }
        }
        return Block.Table(key, line, header, rows, align)
    }

    private fun inlines(parent: Node): List<Inline> {
        val out = ArrayList<Inline>()
        var n = parent.firstChild
        while (n != null) {
            when (n) {
                is Text -> out += Inline.Text(n.literal)
                is Code -> out += Inline.Code(n.literal)
                is Emphasis -> out += Inline.Emph(inlines(n))
                is StrongEmphasis -> out += Inline.Strong(inlines(n))
                is Strikethrough -> out += Inline.Strike(inlines(n))
                is Link -> out += Inline.Link(n.destination, inlines(n))
                is Image -> out += Inline.Image(n.destination, plain(inlines(n)))
                is SoftLineBreak, is HardLineBreak -> out += Inline.Break
                is HtmlInline -> out += Inline.Text(n.literal)
                is FootnoteReference -> out += Inline.FootnoteRef(n.label)
                is TaskListItemMarker -> Unit
                else -> out += inlines(n)
            }
            n = n.next
        }
        // commonmark leaves ![[…]] as text; code spans are separate nodes, so they stay untouched.
        return Embeds.split(mergeText(out))
    }

    private fun mergeText(list: List<Inline>): List<Inline> {
        val out = ArrayList<Inline>()
        for (i in list) {
            val last = out.lastOrNull()
            if (i is Inline.Text && last is Inline.Text) out[out.lastIndex] = Inline.Text(last.text + i.text) else out += i
        }
        return out
    }

    private fun lineOf(node: Node): Int = node.sourceSpans.firstOrNull()?.lineIndex ?: 0

    private fun sourceOf(node: Node, lines: List<String>): String {
        val spans = node.sourceSpans
        if (spans.isEmpty()) return node.javaClass.simpleName
        val from = spans.first().lineIndex
        val to = spans.last().lineIndex
        return lines.subList(from, minOf(to + 1, lines.size)).joinToString("\n")
    }

    companion object {
        /** Inline content flattened to plain text, for titles and alt text. */
        fun plain(list: List<Inline>): String = buildString {
            for (i in list) {
                when (i) {
                    is Inline.Text -> append(i.text)
                    is Inline.Code -> append(i.text)
                    is Inline.Emph -> append(plain(i.children))
                    is Inline.Strong -> append(plain(i.children))
                    is Inline.Strike -> append(plain(i.children))
                    is Inline.Link -> append(plain(i.children))
                    is Inline.Image -> append(i.alt)
                    is Inline.FootnoteRef -> append("[${i.label}]")
                    Inline.Break -> append(' ')
                }
            }
        }
    }
}

package com.nihars.mnemos.feature.editor

import android.graphics.Typeface
import android.text.Editable
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Colours for editor spans, taken from the current theme. */
data class HighlightPalette(
    val heading: Int,
    val mark: Int,
    val muted: Int,
    val code: Int,
    val codeBg: Int,
    val link: Int,
    val string: Int,
    val number: Int,
    val keyword: Int,
    val error: Int,
    val warn: Int,
    val ok: Int,
)

// Marker so we only ever remove spans we added (never the user's selection or IME spans).
private interface Hl
private class HlColor(c: Int) : ForegroundColorSpan(c), Hl
private class HlBg(c: Int) : BackgroundColorSpan(c), Hl
private class HlStyle(s: Int) : StyleSpan(s), Hl
private class HlMono : TypefaceSpan("monospace"), Hl
private class HlSize(f: Float) : RelativeSizeSpan(f), Hl
private class HlStrike : StrikethroughSpan(), Hl
private class HlUnderline : UnderlineSpan(), Hl

/**
 * Incremental, viewport-first syntax highlighting. Tokenizing runs on a background thread;
 * spans are applied on the main thread only if the text hasn't changed since (generation check).
 * Edits re-highlight just the touched paragraphs.
 */
class Highlighter(
    private val text: () -> Editable?,
    private val language: Language,
    private val palette: HighlightPalette,
    private val scope: CoroutineScope,
) {
    private var generation = 0
    private var fullJob: Job? = null

    /** First the window around [visibleStart], then the rest of the file in chunks. */
    fun highlightAll(visibleStart: Int = 0) {
        if (language == Language.PLAIN) return
        fullJob?.cancel()
        val e = text() ?: return
        val len = e.length
        val first = paragraphRange(e, visibleStart, visibleStart + WINDOW)
        fullJob = scope.launch {
            highlight(first.first, first.last)
            var pos = 0
            while (pos < len) {
                val r = paragraphRange(text() ?: return@launch, pos, minOf(len, pos + CHUNK))
                if (r.first != first.first) highlight(r.first, r.last)
                pos = maxOf(r.last + 1, pos + 1)
            }
        }
    }

    /** Called after every edit at [start] inserting [count] characters. */
    fun onEdit(start: Int, count: Int) {
        if (language == Language.PLAIN) return
        generation++
        val e = text() ?: return
        val edited = e.subSequence(MarkdownLines.lineStart(e, start), MarkdownLines.lineEnd(e, start + count)).toString()
        // Typing a fence or the front-matter dashes changes everything below: redo the rest of the file.
        if (language == Language.MARKDOWN && (edited.contains("```") || edited.contains("~~~") || edited.trim() == "---")) {
            highlightAll(start)
            return
        }
        val r = paragraphRange(e, start, start + count)
        scope.launch { highlight(r.first, r.last) }
    }

    // Retries a few times if the user typed meanwhile, so chunks of a long file aren't left plain.
    private suspend fun highlight(from: Int, to: Int) {
        repeat(3) {
            val e = text() ?: return
            if (from >= to || to > e.length) return
            val gen = generation
            val region = e.subSequence(from, to).toString()
            val inFence = language == Language.MARKDOWN && SyntaxTokens.endsInFence(e.subSequence(maxOf(0, from - FENCE_LOOKBACK), from))
            val tokens = withContext(Dispatchers.Default) { SyntaxTokens.tokenize(region, language, inFence, from) }
            val now = text() ?: return
            if (gen == generation && to <= now.length) {
                apply(now, from, to, tokens)
                return
            }
        }
    }

    private fun apply(e: Editable, from: Int, to: Int, tokens: List<Token>) {
        for (s in e.getSpans(from, to, Hl::class.java)) {
            val ss = e.getSpanStart(s)
            val se = e.getSpanEnd(s)
            if (ss >= from && se <= to) e.removeSpan(s)
        }
        for (t in tokens) {
            if (t.end > e.length || t.start >= t.end) continue
            for (span in spansFor(t.kind)) e.setSpan(span, t.start, t.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun spansFor(kind: TokenKind): List<Any> = when (kind) {
        TokenKind.HEADING -> listOf(HlColor(palette.heading), HlStyle(Typeface.BOLD), HlSize(1.12f))
        TokenKind.HEADING_MARK, TokenKind.LIST_MARK -> listOf(HlColor(palette.mark))
        TokenKind.BOLD -> listOf(HlStyle(Typeface.BOLD))
        TokenKind.ITALIC -> listOf(HlStyle(Typeface.ITALIC))
        TokenKind.STRIKE -> listOf(HlStrike(), HlColor(palette.muted))
        TokenKind.CODE -> listOf(HlMono(), HlColor(palette.code), HlBg(palette.codeBg))
        TokenKind.CODE_BLOCK -> listOf(HlMono(), HlBg(palette.codeBg))
        TokenKind.LINK, TokenKind.URL -> listOf(HlColor(palette.link), HlUnderline())
        TokenKind.QUOTE, TokenKind.FRONT_MATTER, TokenKind.COMMENT, TokenKind.RULE -> listOf(HlColor(palette.muted))
        TokenKind.TASK_OPEN -> listOf(HlColor(palette.mark), HlMono())
        TokenKind.TASK_DONE -> listOf(HlColor(palette.ok), HlMono())
        TokenKind.KEY, TokenKind.SECTION -> listOf(HlColor(palette.heading))
        TokenKind.STRING -> listOf(HlColor(palette.string))
        TokenKind.NUMBER -> listOf(HlColor(palette.number))
        TokenKind.KEYWORD, TokenKind.PUNCT -> listOf(HlColor(palette.keyword))
        TokenKind.LOG_ERROR -> listOf(HlColor(palette.error), HlStyle(Typeface.BOLD))
        TokenKind.LOG_WARN -> listOf(HlColor(palette.warn), HlStyle(Typeface.BOLD))
        TokenKind.LOG_INFO -> listOf(HlColor(palette.ok))
    }

    // Expands to whole paragraphs (blank-line bounded), capped so one edit never re-highlights a huge block.
    private fun paragraphRange(e: CharSequence, start: Int, end: Int): IntRange {
        var s = MarkdownLines.lineStart(e, start.coerceIn(0, e.length))
        var steps = 0
        while (s > 0 && steps < MAX_LINES_AROUND) {
            val prev = MarkdownLines.lineStart(e, s - 1)
            if (e.subSequence(prev, s - 1).isBlank()) break
            s = prev
            steps++
        }
        var t = MarkdownLines.lineEnd(e, end.coerceIn(0, e.length))
        steps = 0
        while (t < e.length && steps < MAX_LINES_AROUND) {
            val next = MarkdownLines.lineEnd(e, t + 1)
            if (e.subSequence(t + 1, next).isBlank()) break
            t = next
            steps++
        }
        return s..t
    }

    private companion object {
        const val WINDOW = 6_000
        const val CHUNK = 24_000
        const val FENCE_LOOKBACK = 60_000
        const val MAX_LINES_AROUND = 40
    }
}

/** Line helpers on any CharSequence (the Editable included) without copying it. */
object MarkdownLines {
    fun lineStart(t: CharSequence, pos: Int) = MarkdownActions.lineStart(t, pos)
    fun lineEnd(t: CharSequence, pos: Int) = MarkdownActions.lineEnd(t, pos)

    /** 0-based line number of [offset]; scans, so call it throttled. */
    fun lineOf(t: CharSequence, offset: Int): Int {
        var n = 0
        val end = offset.coerceIn(0, t.length)
        for (i in 0 until end) if (t[i] == '\n') n++
        return n
    }

    /** Offset of the start of 0-based [line]. */
    fun offsetOfLine(t: CharSequence, line: Int): Int {
        if (line <= 0) return 0
        var n = 0
        for (i in 0 until t.length) {
            if (t[i] == '\n') {
                n++
                if (n == line) return i + 1
            }
        }
        return t.length
    }
}

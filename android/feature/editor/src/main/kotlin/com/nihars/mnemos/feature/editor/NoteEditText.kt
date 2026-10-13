package com.nihars.mnemos.feature.editor

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.Layout
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText

/**
 * The editor surface: a platform EditText (fast on large files) with undo history, markdown
 * helpers, keyboard shortcuts, smart URL paste and optional line numbers.
 */
class NoteEditText(context: Context) : EditText(context) {
    interface Listener {
        /** Text changed by the user (or a helper). [start]/[count] describe the inserted range. */
        fun onEdited(start: Int, count: Int)
        fun onSaveShortcut()
        fun onFindShortcut()
        fun onScrolled(firstVisibleLine: Int)
    }

    var listener: Listener? = null
    var history: EditHistory = EditHistory()
    var markdownHelpers: Boolean = true

    // True while we apply undo/redo or programmatic text, so it isn't recorded as a user edit.
    private var applying = false
    private var removed = ""
    private var selBefore = 0

    var lineNumbers: Boolean = false
        set(value) {
            field = value
            updateGutter()
            invalidate()
        }

    private val gutterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private var basePaddingLeft = 0

    init {
        gravity = Gravity.TOP or Gravity.START
        background = null
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
        // Simple line breaking is much cheaper on big files than the default high-quality strategy.
        breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        isVerticalScrollBarEnabled = true
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                if (applying) return
                removed = s.subSequence(start, start + count).toString()
                selBefore = selectionStart
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (applying) return
                val inserted = s.subSequence(start, start + count).toString()
                invalidateLineCache(start)
                history.record(Change(start, removed, inserted, System.currentTimeMillis(), selBefore))
                listener?.onEdited(start, count)
                pendingHelper = if (removed.isEmpty() && inserted.length == 1) start to inserted[0] else null
            }

            override fun afterTextChanged(e: Editable) {
                if (applying) return
                val h = pendingHelper ?: return
                pendingHelper = null
                if (markdownHelpers) runHelper(e, h.first, h.second)
            }
        })
    }

    private var pendingHelper: Pair<Int, Char>? = null

    fun setSpellcheck(on: Boolean) {
        inputType = if (on) {
            inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS.inv()
        } else {
            inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
    }

    fun setSoftWrap(on: Boolean) = setHorizontallyScrolling(!on)

    fun setEditorTypeface(tf: Typeface) {
        typeface = tf
        gutterPaint.typeface = tf
        updateGutter()
    }

    /** Replaces all text without recording history (load, reload, "Load theirs"). Keeps the cursor if it can. */
    fun setTextSilently(text: String, keepSelection: Boolean) {
        val sel = selectionStart
        val scroll = scrollY
        applying = true
        cacheOffset = 0
        cacheLine = 0
        try {
            setText(text)
        } finally {
            applying = false
        }
        if (keepSelection) {
            setSelection(sel.coerceIn(0, text.length))
            scrollTo(0, scroll)
        }
    }

    /** Applies a helper/toolbar edit as one recorded user change. */
    fun apply(edit: TextEdit) {
        val e = editableText ?: return
        e.replace(edit.start, edit.end, edit.text)
        setSelection(edit.selStart.coerceIn(0, e.length), edit.selEnd.coerceIn(0, e.length))
    }

    fun undo() = replay(history.undo())
    fun redo() = replay(history.redo())

    private fun replay(c: Change?) {
        c ?: return
        val e = editableText ?: return
        if (c.start + c.removed.length > e.length) return
        applying = true
        invalidateLineCache(c.start)
        try {
            e.replace(c.start, c.start + c.removed.length, c.inserted)
        } finally {
            applying = false
        }
        setSelection((c.start + c.inserted.length).coerceIn(0, e.length))
        listener?.onEdited(c.start, c.inserted.length)
    }

    // Enter continues lists; typed openers get their closing pair; typing a closer steps over it.
    private fun runHelper(e: Editable, pos: Int, c: Char) {
        when {
            c == '\n' -> MarkdownActions.onEnter(e, pos)?.let { edit -> apply(edit.copy(end = edit.end + 1)) }
            pos + 1 < e.length && MarkdownActions.skipClose(e, pos + 1, c) && lastAutoClose == pos -> {
                e.delete(pos, pos + 1)
                setSelection(pos + 1)
                lastAutoClose = -1
            }
            else -> {
                val edit = MarkdownActions.autoClose(e.subSequence(0, pos), pos, pos, c) ?: return
                if (edit.text.length == 2) {
                    e.insert(pos + 1, edit.text.substring(1))
                    setSelection(pos + 1)
                    lastAutoClose = pos + 1
                }
            }
        }
    }

    // Only step over closers we inserted ourselves.
    private var lastAutoClose = -1

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val ctrl = event.isCtrlPressed
        when {
            keyCode == KeyEvent.KEYCODE_TAB && !ctrl -> {
                apply(MarkdownActions.indent(text, selectionStart, selectionEnd, outdent = event.isShiftPressed))
                return true
            }
            ctrl && keyCode == KeyEvent.KEYCODE_S -> {
                listener?.onSaveShortcut()
                return true
            }
            ctrl && keyCode == KeyEvent.KEYCODE_F -> {
                listener?.onFindShortcut()
                return true
            }
            ctrl && keyCode == KeyEvent.KEYCODE_Z -> {
                if (event.isShiftPressed) redo() else undo()
                return true
            }
            ctrl && keyCode == KeyEvent.KEYCODE_Y -> {
                redo()
                return true
            }
            ctrl && keyCode == KeyEvent.KEYCODE_B && markdownHelpers -> {
                apply(MarkdownActions.wrap(text, selectionStart, selectionEnd, "**"))
                return true
            }
            ctrl && keyCode == KeyEvent.KEYCODE_I && markdownHelpers -> {
                apply(MarkdownActions.wrap(text, selectionStart, selectionEnd, "*"))
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Smart paste: a URL pasted over a selection becomes `[selection](url)`. */
    override fun onTextContextMenuItem(id: Int): Boolean {
        if (markdownHelpers && (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) && selectionEnd > selectionStart) {
            val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
            val pasted = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
            if (pasted != null && MarkdownActions.isUrl(pasted)) {
                apply(MarkdownActions.link(text, selectionStart, selectionEnd, pasted.trim()))
                return true
            }
        }
        return super.onTextContextMenuItem(id)
    }

    override fun onScrollChanged(horiz: Int, vert: Int, oldHoriz: Int, oldVert: Int) {
        super.onScrollChanged(horiz, vert, oldHoriz, oldVert)
        val l = layout ?: return
        listener?.onScrolled(lineAt(l.getLineStart(l.getLineForVertical(vert))))
    }

    private fun updateGutter() {
        if (basePaddingLeft == 0) basePaddingLeft = paddingLeft
        gutterPaint.textSize = textSize * 0.8f
        val digits = maxOf(3, (lineCount + 1).toString().length)
        val width = if (lineNumbers) (gutterPaint.measureText("9".repeat(digits)) + textSize).toInt() else 0
        setPadding(basePaddingLeft + width, paddingTop, paddingRight, paddingBottom)
    }

    var gutterColor: Int
        get() = gutterPaint.color
        set(value) {
            gutterPaint.color = value
        }

    // Draws logical line numbers next to the first visual line of each logical line, visible lines only.
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!lineNumbers) return
        val l = layout ?: return
        val first = l.getLineForVertical(scrollY)
        val last = l.getLineForVertical(scrollY + height)
        val t = text
        var number = lineAt(l.getLineStart(first)) + 1
        val x = paddingLeft - textSize * 0.6f
        for (v in first..last) {
            val start = l.getLineStart(v)
            if (start == 0 || t[start - 1] == '\n') {
                if (v != first) number++
                canvas.drawText(number.toString(), x, (l.getLineBaseline(v) + paddingTop).toFloat(), gutterPaint)
            }
        }
    }

    // Cached newline count up to an offset, so scrolling a 1 MB note doesn't rescan from the top each frame.
    private var cacheOffset = 0
    private var cacheLine = 0

    /** 0-based logical line of [offset]. */
    fun lineAt(offset: Int): Int {
        val t = text
        val target = offset.coerceIn(0, t.length)
        if (cacheOffset > t.length) {
            cacheOffset = 0
            cacheLine = 0
        }
        var line = cacheLine
        if (target >= cacheOffset) {
            for (i in cacheOffset until target) if (t[i] == '\n') line++
        } else {
            for (i in target until cacheOffset) if (t[i] == '\n') line--
        }
        cacheOffset = target
        cacheLine = line
        return line
    }

    private fun invalidateLineCache(editStart: Int) {
        if (editStart < cacheOffset) {
            cacheOffset = 0
            cacheLine = 0
        }
    }
}

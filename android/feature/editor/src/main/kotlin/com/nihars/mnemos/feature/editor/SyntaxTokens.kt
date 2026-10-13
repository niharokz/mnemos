package com.nihars.mnemos.feature.editor

/** What a highlighted range is; the editor and the preview each map these to their own styles. */
enum class TokenKind {
    HEADING, HEADING_MARK, BOLD, ITALIC, STRIKE, CODE, CODE_BLOCK, LINK, URL, QUOTE, LIST_MARK, TASK_OPEN, TASK_DONE,
    RULE, FRONT_MATTER, KEY, STRING, NUMBER, KEYWORD, COMMENT, PUNCT, LOG_ERROR, LOG_WARN, LOG_INFO, SECTION,
}

data class Token(val start: Int, val end: Int, val kind: TokenKind)

/** Which rule set colours a file. */
enum class Language {
    MARKDOWN, JSON, YAML, INI, LOG, CODE, PLAIN;

    companion object {
        fun forExtension(ext: String): Language = when (ext.lowercase()) {
            "md", "markdown" -> MARKDOWN
            "json", "jsonl" -> JSON
            "yaml", "yml" -> YAML
            "ini", "conf", "toml", "env", "properties", "cfg" -> INI
            "log" -> LOG
            "sh", "py", "go", "kt", "kts", "js", "ts", "java", "c", "h", "cpp", "rs", "rb", "css", "html", "xml", "svg", "sql", "lua", "swift" -> CODE
            else -> PLAIN
        }

        /** Language of a fenced code block's info string. */
        fun forFence(info: String): Language = when (info.lowercase()) {
            "json" -> JSON
            "yaml", "yml" -> YAML
            "ini", "toml" -> INI
            "log" -> LOG
            "md", "markdown" -> MARKDOWN
            "" -> PLAIN
            else -> CODE
        }
    }
}

/**
 * Line-based tokenizer. Works on any region that starts at a line start, so the editor can
 * re-highlight just the edited paragraphs. [inFence] tells it the region starts inside a code fence.
 */
object SyntaxTokens {
    private val MD_HEADING = Regex("""^(#{1,6})\s.*$""")
    private val MD_LIST = Regex("""^(\s*)([-*+]|\d+[.)])\s""")
    private val MD_TASK = Regex("""^\s*(?:[-*+]|\d+[.)])\s+\[([ xX])]""")
    private val MD_QUOTE = Regex("""^\s*>.*$""")
    private val MD_RULE = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
    private val MD_BOLD = Regex("""(\*\*|__)(?=\S)(.+?)(?<=\S)\1""")
    private val MD_ITALIC = Regex("""(?<![*_\w])([*_])(?=\S)([^*_]+?)(?<=\S)\1(?![*_\w])""")
    private val MD_STRIKE = Regex("""~~(?=\S)(.+?)(?<=\S)~~""")
    private val MD_CODE = Regex("""`[^`\n]+`""")
    private val MD_LINK = Regex("""!?\[[^\]\n]*]\([^)\n]*\)""")
    private val MD_URL = Regex("""https?://[^\s<>)\]]+""")
    private val FENCE = Regex("""^\s*(```|~~~)\s*([\w+-]*)""")

    private val STRING = Regex(""""(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*'""")
    private val NUMBER = Regex("""(?<![\w.])-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?(?![\w.])""")
    private val JSON_KEY = Regex(""""(?:[^"\\\n]|\\.)*"(?=\s*:)""")
    private val JSON_LITERAL = Regex("""\b(true|false|null)\b""")
    private val YAML_KEY = Regex("""^(\s*-?\s*)([^\s#:][^:#]*?)(?=:(\s|$))""")
    private val HASH_COMMENT = Regex("""(^|\s)#.*$""")
    private val INI_SECTION = Regex("""^\s*\[[^\]]+]\s*$""")
    private val INI_KEY = Regex("""^\s*([^=:#;\s][^=:]*?)\s*(?=[=:])""")
    private val INI_COMMENT = Regex("""^\s*[;#].*$""")
    private val LOG_ERROR = Regex("""\b(ERROR|FATAL|CRITICAL|FAIL(?:ED)?|Exception|panic)\b""")
    private val LOG_WARN = Regex("""\b(WARN(?:ING)?)\b""")
    private val LOG_INFO = Regex("""\b(INFO|DEBUG|TRACE|OK)\b""")
    private val CODE_COMMENT = Regex("""//.*$|#.*$|--\s.*$""")
    private val KEYWORDS = Regex(
        """\b(fun|val|var|class|object|interface|return|if|else|when|for|while|do|in|is|import|package|def|func|function|let|const|""" +
            """struct|enum|public|private|static|void|int|true|false|null|nil|None|True|False|try|catch|finally|throw|new|async|await|""" +
            """echo|then|fi|esac|select|from|where|and|or|not)\b""",
    )

    fun tokenize(text: CharSequence, language: Language, inFence: Boolean = false, startOffset: Int = 0): List<Token> {
        val out = ArrayList<Token>()
        var lineStart = 0
        var fence = inFence
        var fenceLang = Language.PLAIN
        var frontMatter = language == Language.MARKDOWN && startOffset == 0 && text.startsWith("---")
        var lineNo = 0
        val n = text.length
        while (lineStart <= n) {
            var lineEnd = lineStart
            while (lineEnd < n && text[lineEnd] != '\n') lineEnd++
            val line = text.subSequence(lineStart, lineEnd).toString()
            val base = startOffset + lineStart
            when (language) {
                Language.MARKDOWN -> {
                    when {
                        frontMatter -> {
                            out += Token(base, base + line.length, TokenKind.FRONT_MATTER)
                            if (lineNo > 0 && (line.trimEnd() == "---" || line.trimEnd() == "...")) frontMatter = false
                        }
                        FENCE.containsMatchIn(line) -> {
                            out += Token(base, base + line.length, TokenKind.CODE_BLOCK)
                            if (!fence) fenceLang = Language.forFence(FENCE.find(line)!!.groupValues[2])
                            fence = !fence
                        }
                        fence -> {
                            out += Token(base, base + line.length, TokenKind.CODE_BLOCK)
                            if (fenceLang != Language.PLAIN && fenceLang != Language.MARKDOWN) lineTokens(line, fenceLang, base, out)
                        }
                        else -> markdownLine(line, base, out)
                    }
                }
                Language.PLAIN -> Unit
                else -> lineTokens(line, language, base, out)
            }
            lineStart = lineEnd + 1
            lineNo++
        }
        return out
    }

    /** True if [text] ends inside an open code fence (used to seed region highlighting). */
    fun endsInFence(text: CharSequence): Boolean {
        var open = false
        for (line in text.lineSequence()) if (FENCE.containsMatchIn(line)) open = !open
        return open
    }

    private fun markdownLine(line: String, base: Int, out: MutableList<Token>) {
        MD_HEADING.matchEntire(line)?.let { m ->
            out += Token(base, base + line.length, TokenKind.HEADING)
            out += Token(base, base + m.groupValues[1].length, TokenKind.HEADING_MARK)
            return
        }
        if (MD_RULE.matches(line)) {
            out += Token(base, base + line.length, TokenKind.RULE)
            return
        }
        if (MD_QUOTE.matches(line)) out += Token(base, base + line.length, TokenKind.QUOTE)
        MD_LIST.find(line)?.let { m -> out += Token(base + m.groups[2]!!.range.first, base + m.groups[2]!!.range.last + 1, TokenKind.LIST_MARK) }
        MD_TASK.find(line)?.let { m ->
            val done = m.groupValues[1] != " "
            out += Token(base + m.groups[1]!!.range.first - 1, base + m.groups[1]!!.range.last + 2, if (done) TokenKind.TASK_DONE else TokenKind.TASK_OPEN)
        }
        val code = MD_CODE.findAll(line).map { it.range }.toList()
        fun inCode(r: IntRange) = code.any { r.first >= it.first && r.last <= it.last }
        code.forEach { out += Token(base + it.first, base + it.last + 1, TokenKind.CODE) }
        MD_BOLD.findAll(line).filter { !inCode(it.range) }.forEach { out += Token(base + it.range.first, base + it.range.last + 1, TokenKind.BOLD) }
        MD_ITALIC.findAll(line).filter { !inCode(it.range) }.forEach { out += Token(base + it.range.first, base + it.range.last + 1, TokenKind.ITALIC) }
        MD_STRIKE.findAll(line).filter { !inCode(it.range) }.forEach { out += Token(base + it.range.first, base + it.range.last + 1, TokenKind.STRIKE) }
        val links = MD_LINK.findAll(line).filter { !inCode(it.range) }.map { it.range }.toList()
        links.forEach { out += Token(base + it.first, base + it.last + 1, TokenKind.LINK) }
        MD_URL.findAll(line).filter { m -> !inCode(m.range) && links.none { m.range.first >= it.first && m.range.last <= it.last } }
            .forEach { out += Token(base + it.range.first, base + it.range.last + 1, TokenKind.URL) }
    }

    private fun lineTokens(line: String, language: Language, base: Int, out: MutableList<Token>) {
        fun add(r: Regex, kind: TokenKind, group: Int = 0) = r.findAll(line).forEach { m ->
            val g = m.groups[group] ?: return@forEach
            out += Token(base + g.range.first, base + g.range.last + 1, kind)
        }
        when (language) {
            Language.JSON -> {
                add(STRING, TokenKind.STRING)
                add(JSON_KEY, TokenKind.KEY)
                add(NUMBER, TokenKind.NUMBER)
                add(JSON_LITERAL, TokenKind.KEYWORD)
            }
            Language.YAML -> {
                add(YAML_KEY, TokenKind.KEY, 2)
                add(STRING, TokenKind.STRING)
                add(NUMBER, TokenKind.NUMBER)
                add(HASH_COMMENT, TokenKind.COMMENT)
            }
            Language.INI -> {
                if (INI_COMMENT.matches(line)) {
                    out += Token(base, base + line.length, TokenKind.COMMENT)
                    return
                }
                if (INI_SECTION.matches(line)) {
                    out += Token(base, base + line.length, TokenKind.SECTION)
                    return
                }
                add(INI_KEY, TokenKind.KEY, 1)
                add(STRING, TokenKind.STRING)
                add(NUMBER, TokenKind.NUMBER)
            }
            Language.LOG -> {
                add(LOG_ERROR, TokenKind.LOG_ERROR)
                add(LOG_WARN, TokenKind.LOG_WARN)
                add(LOG_INFO, TokenKind.LOG_INFO)
                add(NUMBER, TokenKind.NUMBER)
            }
            Language.CODE -> {
                add(KEYWORDS, TokenKind.KEYWORD)
                add(NUMBER, TokenKind.NUMBER)
                add(STRING, TokenKind.STRING)
                add(CODE_COMMENT, TokenKind.COMMENT)
            }
            Language.MARKDOWN, Language.PLAIN -> Unit
        }
    }
}

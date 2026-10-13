package com.nihars.mnemos.core.text

/** How a text file is stored on disk, remembered so a save writes the same shape back. */
data class TextFormat(
    val encoding: Encoding = Encoding.UTF8,
    val bom: Boolean = false,
    val lineEnding: LineEnding = LineEnding.LF,
    // True when the file mixed LF and CRLF; a save unifies them to [lineEnding].
    val mixedEndings: Boolean = false,
) {
    companion object {
        /** Format for files the app creates: UTF-8, no BOM, LF. */
        val NEW_FILE = TextFormat()
    }
}

enum class Encoding { UTF8, UTF16LE, UTF16BE }

enum class LineEnding(val chars: String) {
    LF("\n"),
    CRLF("\r\n"),
    CR("\r"),
}

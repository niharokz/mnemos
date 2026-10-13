package com.nihars.mnemos.core.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Line icons on a 24 grid, drawn from path data here so no icon library is needed.
 * `Icon()` tints them, so the black stroke is only a placeholder.
 */
object NssIcons {
    private fun icon(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (d in paths) {
            b.addPath(
                pathData = addPathNodes(d),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    private const val FILE = "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z M14 2v4a2 2 0 0 0 2 2h4"
    private const val FOLDER =
        "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z"
    private const val CIRCLE = "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20z"

    val Search by lazy { icon("search", "M11 3a8 8 0 1 0 0 16a8 8 0 1 0 0-16z", "M21 21l-4.3-4.3") }
    val Close by lazy { icon("close", "M18 6 6 18", "M6 6l12 12") }
    val Add by lazy { icon("add", "M5 12h14", "M12 5v14") }
    val ChevronRight by lazy { icon("chevron_right", "m9 18 6-6-6-6") }
    val ChevronDown by lazy { icon("chevron_down", "m6 9 6 6 6-6") }
    val Back by lazy { icon("back", "m12 19-7-7 7-7", "M19 12H5") }
    val Folder by lazy { icon("folder", FOLDER) }
    val FolderPlus by lazy { icon("folder_plus", FOLDER, "M12 10v6", "M9 13h6") }
    val File by lazy { icon("file", FILE) }
    val FileText by lazy { icon("file_text", FILE, "M10 9H8", "M16 13H8", "M16 17H8") }
    val FilePlus by lazy { icon("file_plus", FILE, "M12 12v6", "M9 15h6") }
    val Code by lazy { icon("code", "m16 18 6-6-6-6", "M8 6l-6 6 6 6") }
    val Table by lazy { icon("table", "M3 3h18v18H3z", "M3 9h18", "M3 15h18", "M9 3v18") }
    val Image by lazy { icon("image", "M3 3h18v18H3z", "M9 9a2 2 0 1 0 0-4a2 2 0 1 0 0 4z", "M21 15l-5-5L5 21") }
    val Settings by lazy { icon("settings", "M4 21v-7", "M4 10V3", "M12 21v-9", "M12 8V3", "M20 21v-5", "M20 12V3", "M1 14h6", "M9 8h6", "M17 16h6") }
    val Recent by lazy { icon("recent", CIRCLE, "M12 6v6l4 2") }
    val More by lazy {
        icon("more", "M12 11a1 1 0 1 0 0 2a1 1 0 1 0 0-2z", "M12 4a1 1 0 1 0 0 2a1 1 0 1 0 0-2z", "M12 18a1 1 0 1 0 0 2a1 1 0 1 0 0-2z")
    }
    val Pin by lazy {
        icon(
            "pin",
            "M12 17v5",
            "M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1a2 2 0 0 0 0-4H8a2 2 0 0 0 0 4a1 1 0 0 1 1 1z",
        )
    }
    val Trash by lazy { icon("trash", "M3 6h18", "M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6", "M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2") }
    val Restore by lazy { icon("restore", "M3 12a9 9 0 1 0 9-9a9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5") }
    val Lock by lazy { icon("lock", "M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z", "M7 11V7a5 5 0 0 1 10 0v4") }
    val Fingerprint by lazy {
        icon(
            "fingerprint",
            "M12 10a2 2 0 0 0-2 2c0 1.02-.1 2.51-.26 4", "M14 13.12c0 2.38 0 6.38-1 8.88", "M17.29 21.02c.12-.6.43-2.3.5-3.02",
            "M2 12a10 10 0 0 1 18-6", "M21.8 16c.2-2 .131-5.354 0-6", "M5 19.5C5.5 18 6 15 6 12a6 6 0 0 1 .34-2",
            "M8.65 22c.21-.66.45-1.32.57-2", "M9 6.8a6 6 0 0 1 9 5.2v2",
        )
    }
    val Check by lazy { icon("check", "M20 6 9 17l-5-5") }
    val Edit by lazy { icon("edit", "M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z") }
    val Eye by lazy { icon("eye", "M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z", "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z") }
    val Split by lazy { icon("split", "M3 3h18v18H3z", "M12 3v18") }
    val Undo by lazy { icon("undo", "M3 7v6h6", "M21 17a9 9 0 0 0-9-9a9 9 0 0 0-6 2.3L3 13") }
    val Redo by lazy { icon("redo", "M21 7v6h-6", "M3 17a9 9 0 0 1 9-9a9 9 0 0 1 6 2.3l3 2.7") }
    val Bold by lazy { icon("bold", "M6 12h9a4 4 0 0 1 0 8H7a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1h7a4 4 0 0 1 0 8") }
    val Italic by lazy { icon("italic", "M19 4h-9", "M14 20H5", "M15 4 9 20") }
    val Heading by lazy { icon("heading", "M6 12h12", "M6 20V4", "M18 20V4") }
    val ListBullet by lazy { icon("list", "M8 6h13", "M8 12h13", "M8 18h13", "M3 6h.01", "M3 12h.01", "M3 18h.01") }
    val Checkbox by lazy { icon("checkbox", "M3 3h18v18H3z", "M9 12l2 2 4-4") }
    val Link by lazy {
        icon(
            "link",
            "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71",
            "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71",
        )
    }
    val Quote by lazy { icon("quote", "M4 5v14", "M9 8h11", "M9 12h11", "M9 16h7") }
    val Indent by lazy { icon("indent", "M3 8l4 4-4 4", "M21 12H11", "M21 6H11", "M21 18H11") }
    val Outdent by lazy { icon("outdent", "M7 8l-4 4 4 4", "M21 12H11", "M21 6H11", "M21 18H11") }
    val ArrowUp by lazy { icon("arrow_up", "m5 12 7-7 7 7", "M12 19V5") }
    val ArrowDown by lazy { icon("arrow_down", "M12 5v14", "M19 12l-7 7-7-7") }
    val Share by lazy { icon("share", "M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8", "M16 6l-4-4-4 4", "M12 2v13") }
    val Sort by lazy { icon("sort", "M3 6h18", "M6 12h12", "M10 18h4") }
    val Filter by lazy { icon("filter", "M22 3H2l8 9.46V19l4 2v-8.54L22 3z") }
    val Warning by lazy {
        icon("warning", "M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z", "M12 9v4", "M12 17h.01")
    }
    val Info by lazy { icon("info", CIRCLE, "M12 16v-4", "M12 8h.01") }
    val Refresh by lazy { icon("refresh", "M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8", "M21 3v5h-5") }
    val Tail by lazy { icon("tail", "M12 3v12", "M7 10l5 5 5-5", "M5 21h14") }
    val OpenWith by lazy { icon("open_with", "M15 3h6v6", "M10 14 21 3", "M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6") }
    val Copy by lazy { icon("copy", "M8 8h12v12H8z", "M4 16V4h12") }
    val Move by lazy { icon("move", "M5 9l-3 3 3 3", "M9 5l3-3 3 3", "M15 19l-3 3-3-3", "M19 9l3 3-3 3", "M2 12h20", "M12 2v20") }
    val Template by lazy { icon("template", "M3 3h18v7H3z", "M3 14h9v7H3z", "M16 14h5v7h-5z") }
    val Hash by lazy { icon("hash", "M4 9h16", "M4 15h16", "M10 3 8 21", "M16 3l-2 18") }
    val Diff by lazy {
        icon(
            "diff",
            "M18 15a3 3 0 1 0 0 6a3 3 0 1 0 0-6z", "M6 3a3 3 0 1 0 0 6a3 3 0 1 0 0-6z",
            "M13 6h3a2 2 0 0 1 2 2v7", "M11 18H8a2 2 0 0 1-2-2V9",
        )
    }
    val Stats by lazy { icon("stats", "M3 3v18h18", "M8 17V11", "M13 17V7", "M18 17v-4") }
    val Replace by lazy { icon("replace", "M14 4h6v6", "M20 4l-7 7", "M10 20H4v-6", "M4 20l7-7") }
    val Backspace by lazy { icon("backspace", "M21 4H8l-7 8 7 8h13a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2z", "M18 9l-6 6", "M12 9l6 6") }
    val Columns by lazy { icon("columns", "M3 3h18v18H3z", "M9 3v18", "M15 3v18") }
    val Rows by lazy { icon("rows", "M3 3h18v18H3z", "M3 9h18", "M3 15h18") }
    val Grid by lazy { icon("grid", "M3 3h7v7H3z", "M14 3h7v7h-7z", "M14 14h7v7h-7z", "M3 14h7v7H3z") }
    val Tabs by lazy { icon("tabs", "M3 8h18v12H3z", "M3 8V4h7v4", "M10 8V5h6v3") }
    val Palette by lazy {
        icon(
            "palette",
            "M12 22a10 10 0 1 1 10-10c0 2.2-1.8 3-3.5 3H16a2 2 0 0 0-1.6 3.2A2.1 2.1 0 0 1 12 22z",
            "M7.5 10.5h.01", "M12 7.5h.01", "M16.5 10.5h.01",
        )
    }
    val User by lazy { icon("user", "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8z", "M4 21a8 8 0 0 1 16 0") }
    val Update by lazy { icon("update", "M12 3v12", "M7 10l5 5 5-5", "M5 21h14") }
}

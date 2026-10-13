package com.nihars.mnemos.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nihars.mnemos.core.design.Appearance
import com.nihars.mnemos.core.design.ThemeColors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Settings, pins, view modes and the last open file for one user, in an app-private DataStore. */
class SettingsStore(private val store: DataStore<Preferences>) {
    val settings: Flow<AppSettings> = store.data.map { read(it) }.distinctUntilChanged()

    /** Vault-relative pinned paths, in the user's order. */
    val pins: Flow<List<String>> = store.data.map { p -> p[PINS]?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList() }.distinctUntilChanged()

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { p -> write(p, transform(read(p))) }
    }

    suspend fun setPins(paths: List<String>) = store.edit { it[PINS] = paths.distinct().joinToString("\n") }

    suspend fun togglePin(path: String) {
        val now = pins.first()
        setPins(if (path in now) now - path else now + path)
    }

    /** Keeps pins pointing at the right file after a rename or move. */
    suspend fun renamePinned(from: String, to: String) {
        val now = pins.first()
        if (now.none { it == from || it.startsWith("$from/") }) return
        setPins(now.map { if (it == from) to else if (it.startsWith("$from/")) to + it.removePrefix(from) else it })
    }

    /** Last view (Edit / Split / Preview) per file type key, e.g. "md". */
    suspend fun viewMode(typeKey: String): ViewMode? =
        store.data.first()[stringPreferencesKey("view_$typeKey")]?.let { name -> ViewMode.entries.firstOrNull { it.name == name } }

    suspend fun setViewMode(typeKey: String, mode: ViewMode) = store.edit { it[stringPreferencesKey("view_$typeKey")] = mode.name }

    suspend fun lastOpen(): String? = store.data.first()[LAST_OPEN]

    suspend fun setLastOpen(path: String?) = store.edit { if (path == null) it.remove(LAST_OPEN) else it[LAST_OPEN] = path }

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            appearance = Appearance(
                theme = enumOr(p[THEME], d.appearance.theme),
                dark = colors(p, "dark"),
                light = colors(p, "light"),
                dynamicAccent = p[DYNAMIC_ACCENT] ?: d.appearance.dynamicAccent,
                logoColor = p[LOGO_COLOR] ?: d.appearance.logoColor,
                grid = p[GRID] ?: d.appearance.grid,
                density = enumOr(p[DENSITY], d.appearance.density),
                motion = enumOr(p[MOTION], d.appearance.motion),
            ),
            hiddenPatterns = p[HIDDEN]?.split('\n')?.filter { it.isNotBlank() } ?: d.hiddenPatterns,
            showHidden = p[SHOW_HIDDEN] ?: d.showHidden,
            captureNote = p[CAPTURE_NOTE] ?: d.captureNote,
            captureFormat = p[CAPTURE_FORMAT] ?: d.captureFormat,
            shareNote = p[SHARE_NOTE] ?: d.shareNote,
            newNoteFolder = p[NEW_FOLDER] ?: d.newNoteFolder,
            newNoteTemplate = p[NEW_TEMPLATE] ?: d.newNoteTemplate,
            treeSort = enumOr(p[TREE_SORT], d.treeSort),
            homeView = enumOr(p[HOME_VIEW], d.homeView),
            updateFolder = p[UPDATE_FOLDER] ?: d.updateFolder,
            editorFont = enumOr(p[FONT], d.editorFont),
            textSizeSp = p[TEXT_SIZE] ?: d.textSizeSp,
            lineHeight = p[LINE_HEIGHT] ?: d.lineHeight,
            lineNumbers = p[LINE_NUMBERS] ?: d.lineNumbers,
            softWrap = p[SOFT_WRAP] ?: d.softWrap,
            spellcheck = p[SPELLCHECK] ?: d.spellcheck,
            autosaveMs = p[AUTOSAVE] ?: d.autosaveMs,
            toolbar = p[TOOLBAR]?.split(',')?.mapNotNull { n -> ToolbarItem.entries.firstOrNull { it.name == n } } ?: d.toolbar,
            previewTextSizeSp = p[PREVIEW_SIZE] ?: d.previewTextSizeSp,
            previewMaxWidthDp = p[PREVIEW_WIDTH] ?: d.previewMaxWidthDp,
            showFrontMatter = p[SHOW_FM] ?: d.showFrontMatter,
        )
    }

    private fun write(p: MutablePreferences, s: AppSettings) {
        p[THEME] = s.appearance.theme.name
        writeColors(p, "dark", s.appearance.dark)
        writeColors(p, "light", s.appearance.light)
        p[DYNAMIC_ACCENT] = s.appearance.dynamicAccent
        p[LOGO_COLOR] = s.appearance.logoColor
        p[GRID] = s.appearance.grid
        p[DENSITY] = s.appearance.density.name
        p[MOTION] = s.appearance.motion.name
        p[HIDDEN] = s.hiddenPatterns.joinToString("\n")
        p[SHOW_HIDDEN] = s.showHidden
        p[CAPTURE_NOTE] = s.captureNote
        p[CAPTURE_FORMAT] = s.captureFormat
        p[SHARE_NOTE] = s.shareNote
        p[NEW_FOLDER] = s.newNoteFolder
        p[NEW_TEMPLATE] = s.newNoteTemplate
        p[TREE_SORT] = s.treeSort.name
        p[HOME_VIEW] = s.homeView.name
        p[UPDATE_FOLDER] = s.updateFolder
        p[FONT] = s.editorFont.name
        p[TEXT_SIZE] = s.textSizeSp
        p[LINE_HEIGHT] = s.lineHeight
        p[LINE_NUMBERS] = s.lineNumbers
        p[SOFT_WRAP] = s.softWrap
        p[SPELLCHECK] = s.spellcheck
        p[AUTOSAVE] = s.autosaveMs
        p[TOOLBAR] = s.toolbar.joinToString(",") { it.name }
        p[PREVIEW_SIZE] = s.previewTextSizeSp
        p[PREVIEW_WIDTH] = s.previewMaxWidthDp
        p[SHOW_FM] = s.showFrontMatter
    }

    // Theme colours are stored per theme: dark_background, light_accent, …
    private fun colors(p: Preferences, theme: String) = ThemeColors(
        background = p[stringPreferencesKey("${theme}_background")].orEmpty(),
        surface = p[stringPreferencesKey("${theme}_surface")].orEmpty(),
        text = p[stringPreferencesKey("${theme}_text")].orEmpty(),
        accent = p[stringPreferencesKey("${theme}_accent")].orEmpty(),
    )

    private fun writeColors(p: MutablePreferences, theme: String, c: ThemeColors) {
        p[stringPreferencesKey("${theme}_background")] = c.background
        p[stringPreferencesKey("${theme}_surface")] = c.surface
        p[stringPreferencesKey("${theme}_text")] = c.text
        p[stringPreferencesKey("${theme}_accent")] = c.accent
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val DYNAMIC_ACCENT = booleanPreferencesKey("dynamic_accent")
        val LOGO_COLOR = stringPreferencesKey("logo_color")
        val GRID = booleanPreferencesKey("grid")
        val DENSITY = stringPreferencesKey("density")
        val MOTION = stringPreferencesKey("motion")
        val HIDDEN = stringPreferencesKey("hidden_patterns")
        val SHOW_HIDDEN = booleanPreferencesKey("show_hidden")
        val CAPTURE_NOTE = stringPreferencesKey("capture_note")
        val CAPTURE_FORMAT = stringPreferencesKey("capture_format")
        val SHARE_NOTE = stringPreferencesKey("share_note")
        val NEW_FOLDER = stringPreferencesKey("new_note_folder")
        val NEW_TEMPLATE = stringPreferencesKey("new_note_template")
        val TREE_SORT = stringPreferencesKey("tree_sort")
        val HOME_VIEW = stringPreferencesKey("home_view")
        val UPDATE_FOLDER = stringPreferencesKey("update_folder")
        val FONT = stringPreferencesKey("editor_font")
        val TEXT_SIZE = intPreferencesKey("text_size")
        val LINE_HEIGHT = floatPreferencesKey("line_height")
        val LINE_NUMBERS = booleanPreferencesKey("line_numbers")
        val SOFT_WRAP = booleanPreferencesKey("soft_wrap")
        val SPELLCHECK = booleanPreferencesKey("spellcheck")
        val AUTOSAVE = longPreferencesKey("autosave_ms")
        val TOOLBAR = stringPreferencesKey("toolbar")
        val PREVIEW_SIZE = intPreferencesKey("preview_text_size")
        val PREVIEW_WIDTH = intPreferencesKey("preview_max_width")
        val SHOW_FM = booleanPreferencesKey("show_front_matter")
        val PINS = stringPreferencesKey("pins")
        val LAST_OPEN = stringPreferencesKey("last_open")
    }
}

package com.nihars.mnemos.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nihars.mnemos.core.data.AppGraph
import com.nihars.mnemos.core.data.AppSettings
import com.nihars.mnemos.core.data.UserKind
import com.nihars.mnemos.core.data.VaultSession
import com.nihars.mnemos.core.security.BiometricKeys
import com.nihars.mnemos.core.security.PinResult
import com.nihars.mnemos.core.security.PinRules
import com.nihars.mnemos.core.security.SecuritySettings
import com.nihars.mnemos.core.design.ThemeColors
import com.nihars.mnemos.core.text.NaturalCompare
import com.nihars.mnemos.core.text.TextCodec
import com.nihars.mnemos.core.text.TextFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(private val graph: AppGraph, private val session: VaultSession) : ViewModel() {
    val settings: StateFlow<AppSettings> = session.settings
    val security: StateFlow<SecuritySettings> = graph.lockStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, SecuritySettings())

    private val _ui = MutableStateFlow(
        SettingsUiState(
            vaultLabel = session.fs.rootLabel,
            fastAccess = session.fs.isFast,
            isDemo = session.kind == UserKind.DEMO,
            versionName = runCatching { graph.app.packageManager.getPackageInfo(graph.app.packageName, 0).versionName }.getOrNull() ?: "",
        ),
    )
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) = viewModelScope.launch { session.settingsStore.update(transform) }

    /** Edits one theme's colours (dark or light). */
    fun setColors(dark: Boolean, transform: (ThemeColors) -> ThemeColors) = update { s ->
        val a = s.appearance
        s.copy(appearance = if (dark) a.copy(dark = transform(a.dark)) else a.copy(light = transform(a.light)))
    }

    fun setName(name: String) = viewModelScope.launch { graph.setOwnerName(name) }

    /** Vault folders for the default-folder picker. */
    suspend fun folders(): List<String> = withContext(Dispatchers.IO) {
        runCatching { session.fs.walk() }.getOrDefault(emptyList())
            .filter { it.isDirectory && !session.hidden.value.isHiddenPath(it.path) }
            .map { it.path }
            .sortedWith(NaturalCompare)
    }

    fun updateSecurity(transform: (SecuritySettings) -> SecuritySettings) = viewModelScope.launch { graph.lockStore.update(transform) }

    fun biometricOff() = viewModelScope.launch {
        BiometricKeys.delete()
        graph.lockStore.update { it.copy(biometric = false) }
    }

    fun biometricOn() = viewModelScope.launch { graph.lockStore.update { it.copy(biometric = true) } }

    fun changePin(current: String, next: String, confirm: String) = viewModelScope.launch {
        PinRules.problem(next, confirm)?.let { p ->
            _ui.update { it.copy(pinProblem = p) }
            return@launch
        }
        when (val r = graph.lock.checkPin(current.toCharArray())) {
            PinResult.Ok -> {
                graph.lock.setPin(next.toCharArray())
                _ui.update { it.copy(pinProblem = null, message = "PIN changed.") }
            }
            is PinResult.Wrong -> _ui.update { it.copy(pinProblem = "Current PIN is wrong.") }
            is PinResult.CoolingDown -> _ui.update { it.copy(pinProblem = "Too many tries. Wait ${r.millis / 1000} s.") }
        }
    }

    fun clearPinProblem() = _ui.update { it.copy(pinProblem = null) }

    fun rebuildIndex() = viewModelScope.launch {
        withContext(Dispatchers.IO) { session.index.clear() }
        session.rescan()
        say("Rebuilding the search index in the background.")
    }

    fun clearCaches(context: Context) = viewModelScope.launch {
        withContext(Dispatchers.IO) { context.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
        say("Caches cleared. Your notes were not touched.")
    }

    /** Opt-in: writes the current settings as JSON into the vault root. */
    fun exportSettings() = viewModelScope.launch {
        val s = settings.value
        val json = buildString {
            append("{\n")
            val fields = listOf(
                "theme" to s.appearance.theme.name, "darkColors" to listOf(s.appearance.dark.background, s.appearance.dark.surface, s.appearance.dark.text, s.appearance.dark.accent),
                "lightColors" to listOf(s.appearance.light.background, s.appearance.light.surface, s.appearance.light.text, s.appearance.light.accent),
                "dynamicAccent" to s.appearance.dynamicAccent, "logoColor" to s.appearance.logoColor, "shareNote" to s.shareNote, "homeView" to s.homeView.name,
                "grid" to s.appearance.grid, "density" to s.appearance.density.name, "motion" to s.appearance.motion.name,
                "hiddenPatterns" to s.hiddenPatterns, "showHidden" to s.showHidden, "captureNote" to s.captureNote,
                "captureFormat" to s.captureFormat, "newNoteFolder" to s.newNoteFolder, "newNoteTemplate" to s.newNoteTemplate,
                "editorFont" to s.editorFont.name, "textSizeSp" to s.textSizeSp, "lineHeight" to s.lineHeight,
                "lineNumbers" to s.lineNumbers, "softWrap" to s.softWrap, "spellcheck" to s.spellcheck, "autosaveMs" to s.autosaveMs,
                "toolbar" to s.toolbar.map { it.name }, "previewTextSizeSp" to s.previewTextSizeSp,
                "previewMaxWidthDp" to s.previewMaxWidthDp, "showFrontMatter" to s.showFrontMatter, "updateFolder" to s.updateFolder,
            )
            fields.forEachIndexed { i, (k, v) ->
                append("  \"").append(k).append("\": ").append(jsonValue(v))
                append(if (i < fields.lastIndex) ",\n" else "\n")
            }
            append("}\n")
        }
        try {
            withContext(Dispatchers.IO) { session.fs.writeAtomic("mnemos-settings.json", TextCodec.encode(json, TextFormat.NEW_FILE)) }
            session.notifyChanged("mnemos-settings.json")
            say("Exported to mnemos-settings.json in the vault.")
        } catch (e: Exception) {
            say("Export failed: ${e.message}")
        }
    }

    fun resetDemo() = viewModelScope.launch {
        if (session.kind != UserKind.DEMO) return@launch
        graph.signOut()
        withContext(Dispatchers.IO) { graph.demo.reset() }
        graph.openDemo()
        say("Demo reset.")
    }

    fun checkUpdate(context: Context) = viewModelScope.launch {
        _ui.update { it.copy(busy = true) }
        when (val r = Updater.check(context, session)) {
            is Updater.Check.Found -> _ui.update { it.copy(busy = false, update = r.update) }
            is Updater.Check.UpToDate -> _ui.update { it.copy(busy = false, message = r.message) }
            is Updater.Check.Problem -> _ui.update { it.copy(busy = false, message = r.message) }
        }
    }

    fun install(context: Context) {
        val u = _ui.value.update ?: return
        runCatching { Updater.install(context, u) }.onFailure { say("Install failed: ${it.message}") }
        _ui.update { it.copy(update = null) }
    }

    fun dismissUpdate() = _ui.update { it.copy(update = null) }
    fun messageShown() = _ui.update { it.copy(message = null) }

    private fun say(m: String) = _ui.update { it.copy(message = m) }

    private fun jsonValue(v: Any?): String = when (v) {
        null -> "null"
        is Boolean, is Number -> v.toString()
        is List<*> -> v.joinToString(", ", "[", "]") { jsonValue(it) }
        else -> "\"" + v.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    }
}

package com.nihars.mnemos.feature.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nihars.mnemos.core.data.EditorFont
import com.nihars.mnemos.core.data.LocalAppGraph
import com.nihars.mnemos.core.data.ToolbarItem
import com.nihars.mnemos.core.data.VaultSession
import com.nihars.mnemos.core.data.graphViewModel
import com.nihars.mnemos.core.design.ColorPickerDialog
import com.nihars.mnemos.core.design.FolderPickerDialog
import com.nihars.mnemos.core.design.LogoColorDialog
import com.nihars.mnemos.core.design.LogoColors
import com.nihars.mnemos.core.design.MotionPref
import com.nihars.mnemos.core.design.NameDialog
import com.nihars.mnemos.core.design.Nss
import com.nihars.mnemos.core.design.NssIcons
import com.nihars.mnemos.core.design.NssTokens
import com.nihars.mnemos.core.design.Swatches
import com.nihars.mnemos.core.design.ThemeColors
import com.nihars.mnemos.core.design.ThemeMode
import com.nihars.mnemos.core.design.UiDensity
import com.nihars.mnemos.core.security.AutoLockDelay
import com.nihars.mnemos.core.security.Biometric
import com.nihars.mnemos.core.security.BiometricOutcome
import com.nihars.mnemos.core.security.findFragmentActivity
import com.nihars.mnemos.core.vaultfs.TreeSort
import kotlin.math.roundToInt

/** A text field to edit, shown in [NameDialog]. */
private data class TextEditSpec(val title: String, val label: String, val value: String, val apply: (String) -> Unit)

/** A colour slot to edit, shown in [ColorPickerDialog]. */
private data class ColorSpec(val title: String, val current: String, val swatches: List<Long>, val apply: (String) -> Unit)

/** A list of choices, shown as a radio dialog. */
private data class ChoiceSpec(val title: String, val options: List<String>, val selected: Int, val apply: (Int) -> Unit)

@Composable
fun SettingsScreen(
    session: VaultSession,
    onChangeVault: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenFolders: () -> Unit,
    onLockNow: () -> Unit,
    onSignOut: () -> Unit,
) {
    val vm = graphViewModel(key = "settings-${session.id}") { SettingsViewModel(it, session) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val sec by vm.security.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val graph = LocalAppGraph.current
    val snackbar = remember { SnackbarHostState() }
    var text by remember { mutableStateOf<TextEditSpec?>(null) }
    var choice by remember { mutableStateOf<ChoiceSpec?>(null) }
    var changingPin by remember { mutableStateOf(false) }
    var toolbarOpen by remember { mutableStateOf(false) }
    var licences by remember { mutableStateOf(false) }
    var color by remember { mutableStateOf<ColorSpec?>(null) }
    var pickFolder by remember { mutableStateOf(false) }
    var logoPicker by remember { mutableStateOf(false) }
    val ownerName by graph.ownerName.collectAsStateWithLifecycle(initialValue = "")

    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp)) }

            if (!ui.isDemo) {
                item { Section("You") }
                item {
                    Row2("Your name", ownerName.ifEmpty { "Not set" }) {
                        text = TextEditSpec("Your name", "Name", ownerName) { v -> vm.setName(v) }
                    }
                }
            }

            item { Section("Vault") }
            item {
                Row2(
                    "Folder",
                    ui.vaultLabel + (if (!ui.fastAccess) " · slower mode" else ""),
                    onClick = if (ui.isDemo) null else onChangeVault,
                )
            }
            if (!ui.isDemo) {
                item {
                    Row2(
                        "All files access",
                        if (graph.hasAllFilesAccess()) "Granted" else "Not granted (slower mode)",
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
                            }
                        },
                    )
                }
            }
            item {
                Row2("Hidden patterns", s.hiddenPatterns.joinToString(", ").ifEmpty { "Defaults only (.*, *.lock, *.tmp, ~*)" }) {
                    text = TextEditSpec("Hidden patterns", "Globs, comma separated", s.hiddenPatterns.joinToString(", ")) { v ->
                        vm.update { it.copy(hiddenPatterns = v.split(',').map(String::trim).filter(String::isNotEmpty)) }
                    }
                }
            }
            item { SwitchRow("Show hidden files", s.showHidden) { on -> vm.update { it.copy(showHidden = on) } } }
            item { Row2("New notes go to", s.newNoteFolder.ifEmpty { "Vault root" }) { pickFolder = true } }
            item { Row2("Manage folders", "Create, rename or remove folders", onClick = onOpenFolders) }
            item {
                Row2("Shared items go to", s.shareNote) {
                    text = TextEditSpec("File for things shared from other apps", "Path in vault, like inbox/shared.md", s.shareNote) { v ->
                        vm.update { it.copy(shareNote = v.trim().trim('/').ifEmpty { "inbox/shared.md" }) }
                    }
                }
            }
            item { Row2("Capture note", s.captureNote) { text = TextEditSpec("Capture note", "Path in vault", s.captureNote) { v -> vm.update { it.copy(captureNote = v) } } } }
            item { Row2("Capture format", s.captureFormat) { text = TextEditSpec("Capture format", "{date} {time} {text}", s.captureFormat) { v -> vm.update { it.copy(captureFormat = v) } } } }
            item { Row2("New note name", s.newNoteTemplate) { text = TextEditSpec("New note name", "{title}.md or {date} {title}.md", s.newNoteTemplate) { v -> vm.update { it.copy(newNoteTemplate = v) } } } }
            item {
                Row2("Tree order", s.treeSort.name.lowercase().replaceFirstChar(Char::uppercase)) {
                    choice = ChoiceSpec("Tree order", listOf("Name", "Modified", "Size"), s.treeSort.ordinal) { i -> vm.update { it.copy(treeSort = TreeSort.entries[i]) } }
                }
            }
            item { Row2("Trash", "Restore moved files", onClick = onOpenTrash) }

            item { Section("Editor") }
            item {
                Row2("Font", s.editorFont.name.lowercase().replaceFirstChar(Char::uppercase)) {
                    choice = ChoiceSpec("Editor font", listOf("System sans", "JetBrains Mono", "Serif"), listOf(EditorFont.SANS, EditorFont.MONO, EditorFont.SERIF).indexOf(s.editorFont)) { i ->
                        vm.update { it.copy(editorFont = listOf(EditorFont.SANS, EditorFont.MONO, EditorFont.SERIF)[i]) }
                    }
                }
            }
            item { SliderRow("Text size", "${s.textSizeSp} sp", s.textSizeSp.toFloat(), 12f..24f, 11) { v -> vm.update { it.copy(textSizeSp = v.roundToInt()) } } }
            item { SliderRow("Line height", "%.2f".format(s.lineHeight), s.lineHeight, 1.1f..2.0f, 8) { v -> vm.update { it.copy(lineHeight = (v * 20).roundToInt() / 20f) } } }
            item { SwitchRow("Line numbers", s.lineNumbers) { on -> vm.update { it.copy(lineNumbers = on) } } }
            item { SwitchRow("Soft wrap", s.softWrap) { on -> vm.update { it.copy(softWrap = on) } } }
            item { SwitchRow("Spellcheck", s.spellcheck) { on -> vm.update { it.copy(spellcheck = on) } } }
            item {
                val options = listOf(400L, 800L, 1500L, 3000L)
                Row2("Autosave after", "${s.autosaveMs} ms") {
                    choice = ChoiceSpec("Autosave after typing stops", options.map { "$it ms" }, options.indexOf(s.autosaveMs)) { i -> vm.update { it.copy(autosaveMs = options[i]) } }
                }
            }
            item { Row2("Toolbar", "${s.toolbar.size} buttons") { toolbarOpen = true } }

            item { Section("Preview") }
            item { SliderRow("Text size", "${s.previewTextSizeSp} sp", s.previewTextSizeSp.toFloat(), 12f..24f, 11) { v -> vm.update { it.copy(previewTextSizeSp = v.roundToInt()) } } }
            item {
                val widths = listOf(600, 720, 840, 10_000)
                Row2("Max width on tablets", if (s.previewMaxWidthDp >= 10_000) "Full width" else "${s.previewMaxWidthDp} dp") {
                    choice = ChoiceSpec("Preview max width", listOf("600 dp", "720 dp", "840 dp", "Full width"), widths.indexOf(s.previewMaxWidthDp)) { i -> vm.update { it.copy(previewMaxWidthDp = widths[i]) } }
                }
            }
            item { SwitchRow("Show front matter", s.showFrontMatter) { on -> vm.update { it.copy(showFrontMatter = on) } } }

            item { Section("Appearance") }
            item {
                Row2("Theme", s.appearance.theme.name.lowercase().replaceFirstChar(Char::uppercase)) {
                    choice = ChoiceSpec("Theme", listOf("System", "Dark", "Light"), s.appearance.theme.ordinal) { i ->
                        vm.update { it.copy(appearance = it.appearance.copy(theme = ThemeMode.entries[i])) }
                    }
                }
            }
            // Each theme keeps its own colours; text always stays readable whatever is picked.
            for (dark in listOf(true, false)) {
                val c = if (dark) s.appearance.dark else s.appearance.light
                val label = if (dark) "Dark theme" else "Light theme"
                item { Section("$label colours") }
                item { ColorRow("Background", c.background, if (dark) NssTokens.Dark.background else NssTokens.Light.background) { color = ColorSpec("$label background", c.background, if (dark) Swatches.darkBackgrounds else Swatches.lightBackgrounds) { v -> vm.setColors(dark) { it.copy(background = v) } } } }
                item { ColorRow("Cards and panels", c.surface, if (dark) NssTokens.Dark.surface else NssTokens.Light.surface) { color = ColorSpec("$label cards", c.surface, if (dark) Swatches.darkSurfaces else Swatches.lightSurfaces) { v -> vm.setColors(dark) { it.copy(surface = v) } } } }
                item { ColorRow("Text", c.text, if (dark) NssTokens.Dark.text else NssTokens.Light.text) { color = ColorSpec("$label text", c.text, if (dark) Swatches.darkTexts else Swatches.lightTexts) { v -> vm.setColors(dark) { it.copy(text = v) } } } }
                item { ColorRow("Accent", c.accent, NssTokens.TEAL) { color = ColorSpec("$label accent", c.accent, Swatches.accents) { v -> vm.setColors(dark) { it.copy(accent = v) } } } }
                if (!c.isDefault) item { Row2("Reset $label colours", "Back to the nss look") { vm.setColors(dark) { ThemeColors() } } }
            }
            item { Section("Logo and style") }
            item { Row2("Logo colour", LogoColors.presetFor(s.appearance.logoColor).label + " · also the home-screen icon") { logoPicker = true } }
            item { SwitchRow("Accent from wallpaper (Material You)", s.appearance.dynamicAccent) { on -> vm.update { it.copy(appearance = it.appearance.copy(dynamicAccent = on)) } } }
            item { SwitchRow("Grid background", s.appearance.grid) { on -> vm.update { it.copy(appearance = it.appearance.copy(grid = on)) } } }
            item {
                Row2("Density", s.appearance.density.name.lowercase().replaceFirstChar(Char::uppercase)) {
                    choice = ChoiceSpec("Density", listOf("Comfortable", "Compact"), s.appearance.density.ordinal) { i -> vm.update { it.copy(appearance = it.appearance.copy(density = UiDensity.entries[i])) } }
                }
            }
            item {
                Row2("Motion", s.appearance.motion.name.lowercase().replaceFirstChar(Char::uppercase)) {
                    choice = ChoiceSpec("Motion", listOf("Full", "Reduced (cross-fades)", "Off"), s.appearance.motion.ordinal) { i -> vm.update { it.copy(appearance = it.appearance.copy(motion = MotionPref.entries[i])) } }
                }
            }

            if (!ui.isDemo) {
                item { Section("Security") }
                item { Row2("Lock method", if (sec.pin == null) "Not set" else "PIN or password" + (if (sec.biometric) " + fingerprint" else "")) }
                item { Row2("Change PIN", "Needs your current PIN") { changingPin = true } }
                item {
                    SwitchRow("Fingerprint unlock", sec.biometric) { on ->
                        if (!on) {
                            vm.biometricOff()
                        } else {
                            val activity = context.findFragmentActivity()
                            if (activity != null && Biometric.available(context)) {
                                Biometric.enroll(activity) { if (it == BiometricOutcome.Success) vm.biometricOn() }
                            }
                        }
                    }
                }
                item {
                    Row2("Auto-lock", sec.autoLock.label) {
                        choice = ChoiceSpec("Lock after the app is in the background for", AutoLockDelay.entries.map { it.label }, sec.autoLock.ordinal) { i ->
                            vm.updateSecurity { it.copy(autoLock = AutoLockDelay.entries[i]) }
                        }
                    }
                }
                item { SwitchRow("Lock when the screen turns off", sec.lockOnScreenOff) { on -> vm.updateSecurity { it.copy(lockOnScreenOff = on) } } }
                item { SwitchRow("Hide content in Recents", sec.hideInRecents) { on -> vm.updateSecurity { it.copy(hideInRecents = on) } } }
                item { SwitchRow("Widgets show titles only while locked", sec.widgetPrivacy) { on -> vm.updateSecurity { it.copy(widgetPrivacy = on) } } }
                item { SwitchRow("Allow quick capture while locked", sec.captureWhileLocked) { on -> vm.updateSecurity { it.copy(captureWhileLocked = on) } } }
                item { Row2("Lock now", "", onClick = onLockNow) }
                item {
                    Text(
                        "The lock protects the app, not the files. Your notes are plain files on shared storage so Syncthing and Obsidian can read them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Nss.colors.muted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            item { Section("Data") }
            item { Row2("Rebuild search index", "Reads every file again in the background") { vm.rebuildIndex() } }
            item { Row2("Clear caches", "Never touches your notes") { vm.clearCaches(context) } }
            item { Row2("Export settings", "Writes mnemos-settings.json into the vault") { vm.exportSettings() } }
            if (ui.isDemo) item { Row2("Reset demo", "Restore the sample vault") { vm.resetDemo() } }

            item { Section("Update") }
            item { Row2("Version", ui.versionName) }
            item {
                Row2("Check vault for update", "Looks in “${s.updateFolder}/” for mnemos-*.apk" + (if (ui.busy) " …" else "")) { vm.checkUpdate(context) }
            }
            item { Row2("Update folder", s.updateFolder) { text = TextEditSpec("Update folder", "Folder in vault", s.updateFolder) { v -> vm.update { it.copy(updateFolder = v.trim('/')) } } } }

            item { Section("About") }
            item { Row2("Mnemos", "Offline notes over plain files. MIT licence.") }
            item { Row2("Open-source licences", "Libraries and fonts used") { licences = true } }
            item { Row2(if (ui.isDemo) "Leave demo" else "Sign out", "Back to the sign-in screen", onClick = onSignOut) }
            item { Spacer(Modifier.heightIn(min = 48.dp)) }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    text?.let { t ->
        NameDialog(t.title, t.label, t.value, "Save", { text = null }, { v ->
            t.apply(v)
            text = null
        }, selectStem = false)
    }
    choice?.let { c -> ChoiceDialog(c) { choice = null } }
    color?.let { c ->
        ColorPickerDialog(c.title, c.current, c.swatches, onDismiss = { color = null }) { v ->
            c.apply(v)
            color = null
        }
    }
    if (logoPicker) {
        LogoColorDialog(s.appearance.logoColor, onDismiss = { logoPicker = false }) { v ->
            vm.update { it.copy(appearance = it.appearance.copy(logoColor = v)) }
            logoPicker = false
        }
    }
    if (pickFolder) {
        val folders by produceState<List<String>?>(null) { value = vm.folders() }
        folders?.let { list ->
            FolderPickerDialog("New notes go to", list, s.newNoteFolder, onDismiss = { pickFolder = false }) { f ->
                vm.update { it.copy(newNoteFolder = f) }
                pickFolder = false
            }
        }
    }
    if (changingPin) ChangePinDialog(ui.pinProblem, onDismiss = {
        changingPin = false
        vm.clearPinProblem()
    }) { cur, next, confirm -> vm.changePin(cur, next, confirm) }
    LaunchedEffect(ui.message) { if (ui.message == "PIN changed.") changingPin = false }
    if (toolbarOpen) ToolbarDialog(s.toolbar, { toolbarOpen = false }) { items -> vm.update { it.copy(toolbar = items) } }
    ui.update?.let { u ->
        AlertDialog(
            onDismissRequest = vm::dismissUpdate,
            title = { Text("Update to ${u.versionName}?") },
            text = { Text(u.changelog.ifEmpty { "No changelog in this APK." }, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = {
                    if (Updater.needsPermission(context)) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                    } else {
                        vm.install(context)
                    }
                }) { Text(if (Updater.needsPermission(context)) "Allow installs first" else "Install") }
            },
            dismissButton = { TextButton(onClick = vm::dismissUpdate) { Text("Later") } },
        )
    }
    if (licences) {
        val body = remember { runCatching { context.assets.open("licenses.txt").bufferedReader().readText() }.getOrDefault("Licence list not bundled in this build.") }
        AlertDialog(
            onDismissRequest = { licences = false },
            title = { Text("Open-source licences") },
            text = { Text(body, style = MaterialTheme.typography.bodySmall, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { licences = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp))
}

@Composable
private fun Row2(title: String, value: String, onClick: (() -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (value.isNotEmpty()) Text(value, style = MaterialTheme.typography.bodySmall, color = Nss.colors.muted)
    }
}

/** A setting with a colour dot; [hex] empty means the default colour [fallback]. */
@Composable
private fun ColorRow(title: String, hex: String, fallback: Long, onClick: () -> Unit) {
    val shown = NssTokens.parseHex(hex) ?: fallback
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(if (hex.isEmpty()) "Default" else hex, style = MaterialTheme.typography.bodySmall, color = Nss.colors.muted)
        }
        Box(Modifier.size(28.dp).clip(CircleShape).background(Color(shown)).border(1.dp, Nss.colors.line, CircleShape))
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(checked, role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SliderRow(title: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    var local by remember(current) { mutableStateOf(current) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodySmall, color = Nss.colors.muted)
        }
        Slider(value = local, onValueChange = { local = it }, onValueChangeFinished = { onChange(local) }, valueRange = range, steps = steps)
    }
}

@Composable
private fun ChoiceDialog(c: ChoiceSpec, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(c.title) },
        text = {
            Column {
                c.options.forEachIndexed { i, label ->
                    Row(
                        Modifier.fillMaxWidth().selectable(i == c.selected, role = Role.RadioButton) {
                            c.apply(i)
                            onDismiss()
                        }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = i == c.selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChangePinDialog(problem: String?, onDismiss: () -> Unit, onChange: (String, String, String) -> Unit) {
    var cur by rememberSaveable { mutableStateOf("") }
    var next by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Triple("Current", cur) { v: String -> cur = v }, Triple("New", next) { v: String -> next = v }, Triple("Repeat new", confirm) { v: String -> confirm = v })
                    .forEach { (label, value, set) ->
                        OutlinedTextField(
                            value, set, label = { Text(label) }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                        )
                    }
                if (problem != null) Text(problem, color = Nss.colors.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onChange(cur, next, confirm) }) { Text("Change") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Toolbar buttons: tick to show, arrows to reorder. */
@Composable
private fun ToolbarDialog(current: List<ToolbarItem>, onDismiss: () -> Unit, onSave: (List<ToolbarItem>) -> Unit) {
    var order by remember { mutableStateOf(current + ToolbarItem.entries.filter { it !in current }) }
    var enabled by remember { mutableStateOf(current.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Toolbar") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                order.forEachIndexed { i, item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(item in enabled, { on -> enabled = if (on) enabled + item else enabled - item })
                        Text(item.label, Modifier.weight(1f))
                        IconButton(onClick = { if (i > 0) order = order.toMutableList().apply { add(i - 1, removeAt(i)) } }, enabled = i > 0) {
                            Icon(NssIcons.ArrowUp, contentDescription = "Move ${item.label} up")
                        }
                        IconButton(onClick = { if (i < order.lastIndex) order = order.toMutableList().apply { add(i + 1, removeAt(i)) } }, enabled = i < order.lastIndex) {
                            Icon(NssIcons.ArrowDown, contentDescription = "Move ${item.label} down")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(order.filter { it in enabled })
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

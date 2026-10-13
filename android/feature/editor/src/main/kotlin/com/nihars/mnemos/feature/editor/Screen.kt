package com.nihars.mnemos.feature.editor

import android.content.ActivityNotFoundException
import android.graphics.Typeface
import android.net.Uri
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nihars.mnemos.core.data.AppSettings
import com.nihars.mnemos.core.data.EditorFont
import com.nihars.mnemos.core.data.ExternalOpen
import com.nihars.mnemos.core.data.Log
import com.nihars.mnemos.core.data.ToolbarItem
import com.nihars.mnemos.core.data.ViewMode
import com.nihars.mnemos.core.data.VaultSession
import com.nihars.mnemos.core.data.graphViewModel
import com.nihars.mnemos.core.design.Nss
import com.nihars.mnemos.core.design.NssBanner
import com.nihars.mnemos.core.design.NssIcons
import com.nihars.mnemos.core.design.R as DesignR
import com.nihars.mnemos.core.design.SaveDot
import com.nihars.mnemos.core.text.Diff
import com.nihars.mnemos.core.vaultfs.VaultPaths
import com.nihars.mnemos.feature.conflicts.DiffView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// The photo picker's limit for one go; more can be added again.
private const val MAX_PICKED_IMAGES = 10

/** Holds the live EditText so the toolbar and commands can reach it. */
private class EditorHandle {
    var view: NoteEditText? = null
    var highlighter: Highlighter? = null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    session: VaultSession,
    path: String,
    query: String?,
    editAnyway: Boolean,
    showBack: Boolean,
    onBack: () -> Unit,
    onOpenPath: (String) -> Unit,
    onOpenTable: (String) -> Unit,
) {
    val vm = graphViewModel(key = "editor-${session.id}-$path-$editAnyway") { EditorViewModel(session, path, it.lock.state, editAnyway) }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by session.settings.collectAsStateWithLifecycle()
    val handle = remember { EditorHandle() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val motion = Nss.motion
    var showDiff by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var imageChooser by remember { mutableStateOf(false) }
    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    // Pictures are copied next to the note; their markdown goes in at the cursor.
    fun insertImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            val md = vm.addImages(context.contentResolver, uris)
            val v = handle.view ?: return@launch
            if (md.isNotEmpty()) v.apply(MarkdownActions.insertBlock(v.text, v.selectionStart.coerceAtLeast(0), md))
        }
    }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICKED_IMAGES)) { insertImages(it) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val uri = cameraUri
        cameraUri = null
        if (taken && uri != null) insertImages(listOf(uri))
    }

    // Autosave when leaving the screen or the app; pause the 20 s check while hidden.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        vm.visible = false
        vm.flush()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.visible = true
        vm.checkOutside()
    }
    DisposableEffect(Unit) { onDispose { vm.flush() } }

    LaunchedEffect(state.error, state.notice) {
        (state.error ?: state.notice)?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }
    LaunchedEffect(state.loading) { if (!state.loading && !query.isNullOrBlank()) vm.revealQuery(query) }

    val view = if (!state.isMarkdown) ViewMode.EDIT else if (!wide && state.view == ViewMode.SPLIT) ViewMode.EDIT else state.view

    Box(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    if (showBack) IconButton(onClick = { vm.flush(); onBack() }) { Icon(NssIcons.Back, contentDescription = "Back") }
                },
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(VaultPaths.name(path), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(8.dp))
                            SaveDot(state.status)
                        }
                        val parent = VaultPaths.parent(path)
                        if (parent.isNotEmpty()) {
                            Text(parent.replace("/", " › "), style = MaterialTheme.typography.labelSmall, color = Nss.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                actions = {
                    if (state.isMarkdown) ViewSwitch(view, wide, vm::setView)
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(NssIcons.More, contentDescription = "More actions") }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Find and replace") }, leadingIcon = { Icon(NssIcons.Search, null) }, onClick = {
                                menu = false
                                vm.openFind(true)
                            })
                            if (state.isCsv) {
                                DropdownMenuItem(text = { Text("Table view") }, leadingIcon = { Icon(NssIcons.Table, null) }, onClick = {
                                    menu = false
                                    vm.flush()
                                    onOpenTable(path)
                                })
                            }
                            if (state.isMarkdown && !state.readOnly && view != ViewMode.PREVIEW) {
                                DropdownMenuItem(text = { Text("Add picture") }, leadingIcon = { Icon(NssIcons.Image, null) }, onClick = {
                                    menu = false
                                    imageChooser = true
                                })
                            }
                            DropdownMenuItem(text = { Text("Open with…") }, leadingIcon = { Icon(NssIcons.OpenWith, null) }, onClick = {
                                menu = false
                                vm.flush()
                                scope.launch { ExternalOpen.openWith(context, session, path)?.let { snackbar.showSnackbar(it) } }
                            })
                            DropdownMenuItem(text = { Text("Copy path") }, leadingIcon = { Icon(NssIcons.Copy, null) }, onClick = {
                                menu = false
                                ExternalOpen.copy(context, "path", path)
                            })
                        }
                    }
                },
            )
            Blockers(state, onLoadTheirs = vm::loadTheirs, onKeepMine = vm::keepMine, onCopy = vm::saveMineAsCopy, onRecreate = vm::recreate, onDiff = { showDiff = true })
            NssBanner(
                visible = state.largeWarning,
                icon = NssIcons.Warning,
                message = "Large file: editing works, but typing and saving are slower.",
                tone = Nss.colors.warn,
            ) {}
            AnimatedVisibility(state.find.open) { FindBar(state.find, vm) }
            Box(Modifier.weight(1f)) {
                val previewList = rememberLazyListState()
                AnimatedContent(view, transitionSpec = { motion.sharedAxisX(targetState.ordinal > initialState.ordinal) }, label = "view") { mode ->
                    when (mode) {
                        ViewMode.EDIT -> EditorHost(vm, handle, state, settings, Modifier.fillMaxSize())
                        ViewMode.PREVIEW -> Preview(vm, state, settings, session, previewList, onOpenPath)
                        ViewMode.SPLIT -> Row(Modifier.fillMaxSize()) {
                            EditorHost(vm, handle, state, settings, Modifier.weight(1f))
                            Surface(Modifier.width(1.dp).fillMaxSize(), color = Nss.colors.line) {}
                            Box(Modifier.weight(1f)) { Preview(vm, state, settings, session, previewList, onOpenPath) }
                            SyncPreviewScroll(state.doc, state.previewLine, previewList, enabled = true)
                        }
                    }
                }
                // Fully qualified: inside this Box, the Column's scoped overload would otherwise be picked.
                androidx.compose.animation.AnimatedVisibility(
                    visible = state.reloadedChip,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                    enter = motion.riseIn(),
                    exit = motion.fadeOutQuick(),
                ) {
                    AssistChip(onClick = {}, label = { Text("Updated from another app") }, leadingIcon = { Icon(NssIcons.Refresh, null) })
                }
            }
            if (view != ViewMode.PREVIEW && !state.readOnly && state.isMarkdown) FormatToolbar(settings.toolbar, handle, vm, onImage = { imageChooser = true })
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp))
    }

    if (imageChooser) {
        AlertDialog(
            onDismissRequest = { imageChooser = false },
            icon = { Icon(NssIcons.Image, contentDescription = null) },
            title = { Text("Add a picture") },
            text = { Text("It is saved in attachments/ next to this note and shown where the cursor is.") },
            confirmButton = {
                TextButton(onClick = {
                    imageChooser = false
                    pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("Photos") }
            },
            dismissButton = {
                TextButton(onClick = {
                    imageChooser = false
                    val target = ImageImport.cameraTarget(context)
                    cameraUri = target
                    try {
                        takePhoto.launch(target)
                    } catch (e: ActivityNotFoundException) {
                        Log.w("no camera app", e)
                        cameraUri = null
                        scope.launch { snackbar.showSnackbar("No camera app found.") }
                    }
                }) { Text("Camera") }
            },
        )
    }

    if (showDiff) {
        val conflict = state.blocker as? Blocker.Conflict
        if (conflict == null) {
            showDiff = false
        } else {
            val mine = remember(conflict) { vm.currentText() }
            val rows = remember(conflict) { Diff.sideBySide(mine, conflict.theirs) }
            Dialog(onDismissRequest = { showDiff = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column {
                        TopAppBar(
                            title = { Text("Your edits vs. disk") },
                            navigationIcon = { IconButton(onClick = { showDiff = false }) { Icon(NssIcons.Close, contentDescription = "Close diff") } },
                        )
                        DiffView(rows, leftTitle = "Mine", rightTitle = "On disk", modifier = Modifier.weight(1f))
                        Row(Modifier.fillMaxWidth().padding(8.dp)) {
                            TextButton(onClick = { showDiff = false; vm.loadTheirs() }) { Text("Load theirs") }
                            TextButton(onClick = { showDiff = false; vm.keepMine() }) { Text("Keep mine") }
                            TextButton(onClick = { showDiff = false; vm.saveMineAsCopy() }) { Text("Save mine as copy") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Blockers(
    state: EditorState,
    onLoadTheirs: () -> Unit,
    onKeepMine: () -> Unit,
    onCopy: () -> Unit,
    onRecreate: () -> Unit,
    onDiff: () -> Unit,
) {
    val b = state.blocker
    NssBanner(
        visible = b is Blocker.Conflict,
        icon = NssIcons.Warning,
        message = "This file changed in another app. Autosave is paused.",
        tone = Nss.colors.error,
    ) {
        TextButton(onClick = onDiff) { Text("View diff") }
        TextButton(onClick = onLoadTheirs) { Text("Load theirs") }
        TextButton(onClick = onKeepMine) { Text("Keep mine") }
        TextButton(onClick = onCopy) { Text("Save as copy") }
    }
    NssBanner(
        visible = b is Blocker.Missing,
        icon = NssIcons.Warning,
        message = "This file was deleted or moved by another app.",
        tone = Nss.colors.error,
    ) {
        TextButton(onClick = onRecreate) { Text("Recreate") }
        TextButton(onClick = onCopy) { Text("Save as copy") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewSwitch(view: ViewMode, wide: Boolean, onChange: (ViewMode) -> Unit) {
    val modes = if (wide) listOf(ViewMode.EDIT, ViewMode.SPLIT, ViewMode.PREVIEW) else listOf(ViewMode.EDIT, ViewMode.PREVIEW)
    SingleChoiceSegmentedButtonRow {
        modes.forEachIndexed { i, m ->
            SegmentedButton(
                selected = view == m,
                onClick = { onChange(m) },
                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                icon = {},
                label = {
                    Icon(
                        when (m) {
                            ViewMode.EDIT -> NssIcons.Edit
                            ViewMode.SPLIT -> NssIcons.Split
                            ViewMode.PREVIEW -> NssIcons.Eye
                        },
                        contentDescription = m.name.lowercase().replaceFirstChar { it.uppercase() },
                    )
                },
            )
        }
    }
}

@Composable
private fun Preview(
    vm: EditorViewModel,
    state: EditorState,
    settings: AppSettings,
    session: VaultSession,
    list: androidx.compose.foundation.lazy.LazyListState,
    onOpenPath: (String) -> Unit,
) {
    MarkdownPreview(
        doc = state.doc,
        session = session,
        notePath = vm.path,
        textSizeSp = settings.previewTextSizeSp,
        maxWidthDp = settings.previewMaxWidthDp,
        showFrontMatter = settings.showFrontMatter,
        listState = list,
        onToggleTask = vm::toggleTask,
        onOpenNote = onOpenPath,
    )
}

/** The EditText, wired to the ViewModel's commands and the highlighter. */
@Composable
private fun EditorHost(vm: EditorViewModel, handle: EditorHandle, state: EditorState, settings: AppSettings, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val nss = Nss.colors
    val palette = HighlightPalette(
        heading = cs.onSurface.toArgb(), mark = nss.ink.toArgb(), muted = nss.muted.toArgb(), code = nss.ink.toArgb(),
        codeBg = cs.surfaceContainerHigh.toArgb(), link = cs.primary.toArgb(), string = nss.ok.toArgb(), number = nss.warn.toArgb(),
        keyword = cs.secondary.toArgb(), error = nss.error.toArgb(), warn = nss.warn.toArgb(), ok = nss.ok.toArgb(),
    )
    val textColor = cs.onSurface.toArgb()
    val flash = cs.primaryContainer.copy(alpha = 0.45f).toArgb()
    val readOnly = state.readOnly
    var viewRef by remember { mutableStateOf<NoteEditText?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            NoteEditText(ctx).apply {
                val pad = (16 * ctx.resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, pad * 6)
                history = vm.history
                markdownHelpers = state.isMarkdown
                listener = object : NoteEditText.Listener {
                    override fun onEdited(start: Int, count: Int) {
                        handle.highlighter?.onEdit(start, count)
                        vm.onEdited(editableText)
                    }

                    override fun onSaveShortcut() = vm.flush()
                    override fun onFindShortcut() = vm.openFind(true)
                    override fun onScrolled(firstVisibleLine: Int) = vm.onScrolledToLine(firstVisibleLine)
                }
                vm.initialText?.let { setTextSilently(it, keepSelection = false) }
                vm.attach(editableText)
                handle.view = this
                handle.highlighter = Highlighter({ handle.view?.editableText }, state.language, palette, scope).also { it.highlightAll() }
                viewRef = this
            }
        },
        update = { v ->
            v.setTextColor(textColor)
            v.gutterColor = nss.muted.toArgb()
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.textSizeSp.toFloat())
            v.setLineSpacing(0f, settings.lineHeight)
            v.setSoftWrap(settings.softWrap)
            v.setSpellcheck(settings.spellcheck)
            v.lineNumbers = settings.lineNumbers
            v.setEditorTypeface(
                when (settings.editorFont) {
                    EditorFont.SANS -> Typeface.DEFAULT
                    EditorFont.SERIF -> Typeface.SERIF
                    EditorFont.MONO -> ResourcesCompat.getFont(v.context, DesignR.font.jetbrains_mono_regular) ?: Typeface.MONOSPACE
                },
            )
            // Read-only: selectable and copyable, but no keyboard and no edits.
            v.showSoftInputOnFocus = !readOnly
            if (readOnly) v.keyListener = null
        },
        onRelease = { v ->
            if (handle.view === v) {
                handle.view = null
                handle.highlighter = null
            }
        },
    )

    LaunchedEffect(viewRef) {
        val v = viewRef ?: return@LaunchedEffect
        // The load may have finished before this collector started.
        if (v.text.isEmpty() && !vm.initialText.isNullOrEmpty()) {
            v.setTextSilently(vm.initialText!!, keepSelection = false)
            vm.attach(v.editableText)
            handle.highlighter?.highlightAll()
        }
        vm.commands.collect { cmd ->
            when (cmd) {
                is EditorCommand.SetText -> {
                    v.setTextSilently(cmd.text, cmd.keepSelection)
                    vm.attach(v.editableText)
                    handle.highlighter?.highlightAll(v.selectionStart)
                }
                is EditorCommand.Select -> {
                    val e = v.editableText
                    val s = cmd.start.coerceIn(0, e.length)
                    val end = cmd.end.coerceIn(s, e.length)
                    v.requestFocus()
                    v.setSelection(s, end)
                    v.bringPointIntoView(s)
                    // Flash the match once so the eye finds it.
                    val span = BackgroundColorSpan(flash)
                    e.setSpan(span, s, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    scope.launch {
                        delay(700)
                        e.removeSpan(span)
                    }
                }
                is EditorCommand.Replace -> {
                    val e = v.editableText
                    if (cmd.end <= e.length) e.replace(cmd.start, cmd.end, cmd.text)
                }
                is EditorCommand.Apply -> v.apply(cmd.edit)
                EditorCommand.Undo -> v.undo()
                EditorCommand.Redo -> v.redo()
            }
        }
    }
}

/** Formatting toolbar above the keyboard, in the order chosen in Settings. */
@Composable
private fun FormatToolbar(items: List<ToolbarItem>, handle: EditorHandle, vm: EditorViewModel, onImage: () -> Unit) {
    HorizontalDivider(color = Nss.colors.line)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
        for (item in items) {
            IconButton(onClick = {
                val v = handle.view ?: return@IconButton
                val t = v.text
                val s = v.selectionStart.coerceAtLeast(0)
                val e = v.selectionEnd.coerceAtLeast(s)
                when (item) {
                    ToolbarItem.BOLD -> v.apply(MarkdownActions.wrap(t, s, e, "**"))
                    ToolbarItem.ITALIC -> v.apply(MarkdownActions.wrap(t, s, e, "*"))
                    ToolbarItem.HEADING -> v.apply(MarkdownActions.heading(t, s))
                    ToolbarItem.LIST -> v.apply(MarkdownActions.linePrefix(t, s, e, "- "))
                    ToolbarItem.CHECKBOX -> v.apply(MarkdownActions.linePrefix(t, s, e, "- [ ] "))
                    ToolbarItem.LINK -> v.apply(MarkdownActions.link(t, s, e))
                    ToolbarItem.IMAGE -> onImage()
                    ToolbarItem.CODE -> v.apply(MarkdownActions.code(t, s, e))
                    ToolbarItem.QUOTE -> v.apply(MarkdownActions.linePrefix(t, s, e, "> "))
                    ToolbarItem.TABLE -> v.apply(MarkdownActions.table(t, s))
                    ToolbarItem.INDENT -> v.apply(MarkdownActions.indent(t, s, e, outdent = false))
                    ToolbarItem.OUTDENT -> v.apply(MarkdownActions.indent(t, s, e, outdent = true))
                    ToolbarItem.UNDO -> vm.undo()
                    ToolbarItem.REDO -> vm.redo()
                }
            }) {
                Icon(
                    when (item) {
                        ToolbarItem.BOLD -> NssIcons.Bold
                        ToolbarItem.ITALIC -> NssIcons.Italic
                        ToolbarItem.HEADING -> NssIcons.Heading
                        ToolbarItem.LIST -> NssIcons.ListBullet
                        ToolbarItem.CHECKBOX -> NssIcons.Checkbox
                        ToolbarItem.LINK -> NssIcons.Link
                        ToolbarItem.IMAGE -> NssIcons.Image
                        ToolbarItem.CODE -> NssIcons.Code
                        ToolbarItem.QUOTE -> NssIcons.Quote
                        ToolbarItem.TABLE -> NssIcons.Table
                        ToolbarItem.INDENT -> NssIcons.Indent
                        ToolbarItem.OUTDENT -> NssIcons.Outdent
                        ToolbarItem.UNDO -> NssIcons.Undo
                        ToolbarItem.REDO -> NssIcons.Redo
                    },
                    contentDescription = item.label,
                )
            }
        }
    }
}

@Composable
private fun FindBar(find: FindState, vm: EditorViewModel) {
    var replaceOpen by remember { mutableStateOf(false) }
    val motion = Nss.motion
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = find.query,
                    onValueChange = { vm.setFind(query = it) },
                    placeholder = { Text("Find") },
                    singleLine = true,
                    isError = find.error != null,
                    modifier = Modifier.weight(1f),
                )
                // Animated match counter.
                AnimatedContent(find.count to find.current, transitionSpec = { motion.fadeThrough() }, label = "count") { (count, cur) ->
                    Text(
                        find.error ?: if (count == 0) "0" else "${cur + 1}/$count",
                        style = MaterialTheme.typography.labelMedium,
                        color = Nss.colors.muted,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                IconButton(onClick = { vm.findNext(-1) }) { Icon(NssIcons.ArrowUp, contentDescription = "Previous match") }
                IconButton(onClick = { vm.findNext(1) }) { Icon(NssIcons.ArrowDown, contentDescription = "Next match") }
                IconButton(onClick = { replaceOpen = !replaceOpen }) { Icon(NssIcons.Replace, contentDescription = "Replace") }
                IconButton(onClick = { vm.openFind(false) }) { Icon(NssIcons.Close, contentDescription = "Close find") }
            }
            Row {
                FilterChip(find.options.matchCase, { vm.setFind(options = find.options.copy(matchCase = !find.options.matchCase)) }, label = { Text("Aa") })
                Spacer(Modifier.width(6.dp))
                FilterChip(find.options.wholeWord, { vm.setFind(options = find.options.copy(wholeWord = !find.options.wholeWord)) }, label = { Text("Word") })
                Spacer(Modifier.width(6.dp))
                FilterChip(find.options.regex, { vm.setFind(options = find.options.copy(regex = !find.options.regex)) }, label = { Text(".*") })
            }
            AnimatedVisibility(replaceOpen) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = find.replacement,
                        onValueChange = { vm.setFind(replacement = it) },
                        placeholder = { Text("Replace with") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = vm::replaceCurrent) { Text("Replace") }
                    TextButton(onClick = { vm.replaceAll() }) { Text("All") }
                }
            }
        }
    }
}

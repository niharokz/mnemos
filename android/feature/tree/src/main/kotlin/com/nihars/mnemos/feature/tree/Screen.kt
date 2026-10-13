package com.nihars.mnemos.feature.tree

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items as staggeredItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nihars.mnemos.core.data.ExternalOpen
import com.nihars.mnemos.core.data.FileKind
import com.nihars.mnemos.core.data.Format
import com.nihars.mnemos.core.data.HomeView
import com.nihars.mnemos.core.data.VaultSession
import com.nihars.mnemos.core.data.graphViewModel
import com.nihars.mnemos.core.design.CursorRefreshIndicator
import com.nihars.mnemos.core.design.EmptyState
import com.nihars.mnemos.core.design.MotionTokens
import com.nihars.mnemos.core.design.NameDialog
import com.nihars.mnemos.core.design.Nss
import com.nihars.mnemos.core.design.NssBadge
import com.nihars.mnemos.core.design.NssIcons
import com.nihars.mnemos.core.design.rememberHaptics
import com.nihars.mnemos.core.vaultfs.TreeSort
import com.nihars.mnemos.core.vaultfs.VaultEntry
import com.nihars.mnemos.core.vaultfs.VaultPaths
import kotlinx.coroutines.launch

/** What a dialog is currently asking for. */
private sealed interface TreeDialog {
    data class NewNote(val dir: String) : TreeDialog
    data class FromTemplate(val template: String) : TreeDialog
    data class Rename(val path: String) : TreeDialog
    data object MoveSelection : TreeDialog
    data object PickTemplate : TreeDialog
}

/**
 * Home: the vault in one of five views. The + button makes a note in the default folder and
 * opens it; folders are created and removed in Manage folders, never here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TreeScreen(
    session: VaultSession,
    selectedPath: String?,
    onOpen: (String) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenFolders: () -> Unit,
) {
    val vm = graphViewModel(key = "tree-${session.id}") { TreeViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<TreeDialog?>(null) }
    val haptics = rememberHaptics()

    LaunchedEffect(state.undo) {
        val u = state.undo ?: return@LaunchedEffect
        val r = snackbar.showSnackbar(u.message, actionLabel = "Undo", duration = SnackbarDuration.Long)
        if (r == SnackbarResult.ActionPerformed) vm.undo() else vm.undoShown()
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }
    // Folder-at-a-time views: system back goes up a folder first.
    val browsing = state.view == HomeView.GRID || state.view == HomeView.BOX || state.view == HomeView.TABS
    val top = if (state.view == HomeView.TABS) state.tab else ""
    BackHandler(enabled = browsing && state.filter.isBlank() && state.currentDir != top) { vm.up() }

    val actions = RowActions(
        open = { row ->
            when {
                state.selection.isNotEmpty() -> vm.select(row.entry.path)
                row.entry.isDirectory && browsing -> vm.openDir(row.entry.path)
                row.entry.isDirectory -> vm.toggle(row.entry.path)
                else -> onOpen(row.entry.path)
            }
        },
        select = { vm.select(it) },
        pin = { vm.togglePin(it) },
        trash = { vm.trash(listOf(it)) },
        rename = { dialog = TreeDialog.Rename(it) },
        duplicate = { vm.duplicate(it) },
        newHere = { dialog = TreeDialog.NewNote(it) },
        copyPath = { ExternalOpen.copy(context, "path", it) },
        openWith = { p -> scope.launch { ExternalOpen.openWith(context, session, p)?.let { snackbar.showSnackbar(it) } } },
    )

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (state.selection.isNotEmpty()) {
                SelectionBar(
                    count = state.selection.size,
                    onClose = vm::clearSelection,
                    onMove = { dialog = TreeDialog.MoveSelection },
                    onTrash = { vm.trash(state.selection) },
                    onPin = vm::pinSelection,
                )
            } else {
                FilterBar(
                    filter = state.filter,
                    sort = state.sort,
                    view = state.view,
                    hasTemplates = state.templates.isNotEmpty(),
                    onFilter = vm::setFilter,
                    onSort = vm::setSort,
                    onView = vm::setView,
                    onTemplate = { dialog = TreeDialog.PickTemplate },
                    onFolders = onOpenFolders,
                    onTrash = onOpenTrash,
                )
            }
            if (state.scan.running) {
                val p = if (state.scan.total > 0) state.scan.done.toFloat() / state.scan.total else 0f
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text("Indexing ${state.scan.done}/${state.scan.total}", style = MaterialTheme.typography.labelSmall, color = Nss.colors.muted)
                }
            }
            if (state.view == HomeView.TABS && state.filter.isBlank()) FolderTabs(state.topFolders, state.tab, vm::selectTab)
            if (browsing && state.filter.isBlank()) Breadcrumb(state.currentDir, top, onGo = vm::openDir)
            val pull = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = vm::refresh,
                state = pull,
                modifier = Modifier.weight(1f),
                indicator = { CursorRefreshIndicator(pull.distanceFraction, state.refreshing, Modifier.align(Alignment.TopCenter)) },
            ) {
                val rows = if (browsing && state.filter.isBlank()) state.browse else state.rows
                if (!state.loading && rows.isEmpty() && state.pinned.isEmpty()) {
                    EmptyState(
                        NssIcons.Folder,
                        if (state.filter.isBlank()) "This folder is empty" else "No file names match",
                        if (state.filter.isBlank()) "Tap + to write a note." else "Try the Search tab for words inside notes.",
                        Modifier.align(Alignment.Center),
                    )
                }
                // Views cross-fade into each other.
                val motion = Nss.motion
                AnimatedContent(if (state.filter.isBlank()) state.view else HomeView.LIST, transitionSpec = { motion.fadeThrough() }, label = "homeView") { view ->
                    when (view) {
                        HomeView.LIST, HomeView.COMPACT -> TreeList(state, selectedPath, dense = view == HomeView.COMPACT, actions = actions, onOpenPinned = onOpen)
                        HomeView.TABS -> TreeList(state.copy(rows = state.browse, pinned = if (state.tab.isEmpty()) state.pinned else emptyList()), selectedPath, dense = false, actions = actions, onOpenPinned = onOpen)
                        HomeView.GRID -> GridView(state.browse, selectedPath, state.selection, actions)
                        HomeView.BOX -> BoxView(state.browse, state.previews, selectedPath, state.selection, actions)
                    }
                }
            }
        }
        // + : new note in the default folder, opened at once.
        FloatingActionButton(
            onClick = {
                haptics.confirm()
                vm.quickNote(onOpen)
            },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Icon(NssIcons.Add, contentDescription = "New note in " + state.defaultFolder.ifEmpty { "vault root" })
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp))
    }

    when (val d = dialog) {
        is TreeDialog.NewNote -> NameDialog("New note", "Title", "", "Create", { dialog = null }, { t ->
            dialog = null
            vm.newNote(d.dir, t, onOpen)
        }, selectStem = false)
        is TreeDialog.Rename -> NameDialog("Rename or move", "Path in vault", d.path, "Save", { dialog = null }, { to ->
            dialog = null
            if (to != d.path) vm.move(d.path, to)
        })
        TreeDialog.MoveSelection -> NameDialog("Move ${state.selection.size} items", "Into folder", "", "Move", { dialog = null }, { dir ->
            dialog = null
            vm.moveSelection(dir)
        }, selectStem = false)
        TreeDialog.PickTemplate -> TemplatePicker(state.templates, { dialog = null }) { dialog = TreeDialog.FromTemplate(it) }
        is TreeDialog.FromTemplate -> NameDialog("New from template", "Title", "", "Create", { dialog = null }, { t ->
            dialog = null
            vm.newFromTemplate(d.template, t, onOpen)
        }, selectStem = false)
        null -> Unit
    }
}

/** Everything a row can ask for, so every view shares the same actions. */
private class RowActions(
    val open: (TreeRow) -> Unit,
    val select: (String) -> Unit,
    val pin: (String) -> Unit,
    val trash: (String) -> Unit,
    val rename: (String) -> Unit,
    val duplicate: (String) -> Unit,
    val newHere: (String) -> Unit,
    val copyPath: (String) -> Unit,
    val openWith: (String) -> Unit,
)

/** List and Compact: the expandable tree with pinned notes on top. */
@Composable
private fun TreeList(state: TreeState, selectedPath: String?, dense: Boolean, actions: RowActions, onOpenPinned: (String) -> Unit) {
    val motion = Nss.motion
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        if (state.pinned.isNotEmpty() && state.filter.isBlank()) {
            item(key = "pinned-header", contentType = "header") { SectionLabel("Pinned") }
            items(state.pinned, key = { "pin:" + it.path }, contentType = { "pin" }) { e ->
                PinnedRow(e, selected = e.path == selectedPath, onClick = { onOpenPinned(e.path) }, modifier = Modifier.animateItem())
            }
            item(key = "files-header", contentType = "header") { SectionLabel("Files") }
        }
        items(state.rows, key = { it.entry.path }, contentType = { if (it.entry.isDirectory) "dir" else "file" }) { row ->
            TreeRowItem(
                row = row,
                selected = row.entry.path == selectedPath,
                selecting = state.selection.isNotEmpty(),
                checked = row.entry.path in state.selection,
                onClick = { actions.open(row) },
                onLongClick = { actions.select(row.entry.path) },
                onPin = { actions.pin(row.entry.path) },
                onTrash = { actions.trash(row.entry.path) },
                onRename = { actions.rename(row.entry.path) },
                onDuplicate = { actions.duplicate(row.entry.path) },
                onNewHere = { actions.newHere(row.entry.path) },
                onCopyPath = { actions.copyPath(row.entry.path) },
                onOpenWith = { actions.openWith(row.entry.path) },
                dense = dense,
                modifier = Modifier.animateItem(
                    fadeInSpec = tween(MotionTokens.MEDIUM, delayMillis = motion.stagger(row.siblingIndex), easing = MotionTokens.Standard),
                    placementSpec = if (motion.full) spring(stiffness = Spring.StiffnessMediumLow) else tween(MotionTokens.SHORT),
                    fadeOutSpec = tween(MotionTokens.SHORT),
                ),
            )
        }
    }
}

/** Grid: icon tiles, one folder at a time. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridView(rows: List<TreeRow>, selectedPath: String?, selection: Set<String>, actions: RowActions) {
    val haptics = rememberHaptics()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        gridItems(rows, key = { it.entry.path }) { row ->
            val e = row.entry
            val on = e.path == selectedPath || e.path in selection
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (on) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .animateItem()
                    .clip(MaterialTheme.shapes.medium)
                    .combinedClickable(onClick = { actions.open(row) }, onLongClick = {
                        haptics.tick()
                        actions.select(e.path)
                    }),
            ) {
                Column(Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(kindIcon(row.kind, e.isDirectory), contentDescription = null, tint = kindTint(row.kind, e.isDirectory), modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        displayName(e.name, e.isDirectory),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    if (row.pinned || row.conflict) {
                        Spacer(Modifier.height(4.dp))
                        if (row.conflict) NssBadge("conflict", Nss.colors.error) else NssBadge("pinned", MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/** Box: cards with the first lines of each note, in a staggered two-or-more column layout. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoxView(rows: List<TreeRow>, previews: Map<String, String>, selectedPath: String?, selection: Set<String>, actions: RowActions) {
    val haptics = rememberHaptics()
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(160.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalItemSpacing = 10.dp,
    ) {
        staggeredItems(rows, key = { it.entry.path }) { row ->
            val e = row.entry
            val on = e.path == selectedPath || e.path in selection
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (on) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, Nss.colors.line),
                modifier = Modifier
                    .animateItem()
                    .clip(MaterialTheme.shapes.medium)
                    .combinedClickable(onClick = { actions.open(row) }, onLongClick = {
                        haptics.tick()
                        actions.select(e.path)
                    }),
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(kindIcon(row.kind, e.isDirectory), contentDescription = null, tint = kindTint(row.kind, e.isDirectory), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(displayName(e.name, e.isDirectory), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    val preview = previews[e.path].orEmpty()
                    if (preview.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(preview, style = MaterialTheme.typography.bodySmall, color = Nss.colors.muted, maxLines = 8, overflow = TextOverflow.Ellipsis)
                    }
                    if (!e.isDirectory && e.modified > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(Format.relative(e.modified), style = MaterialTheme.typography.labelSmall, color = Nss.colors.muted)
                    }
                }
            }
        }
    }
}

/** Tabs: All plus one tab per top-level folder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderTabs(folders: List<String>, selected: String, onSelect: (String) -> Unit) {
    val tabs = listOf("") + folders
    val index = tabs.indexOf(selected).coerceAtLeast(0)
    PrimaryScrollableTabRow(selectedTabIndex = index, edgePadding = 12.dp, containerColor = MaterialTheme.colorScheme.background, divider = {}) {
        tabs.forEachIndexed { i, f ->
            Tab(selected = i == index, onClick = { onSelect(f) }, text = { Text(if (f.isEmpty()) "All" else VaultPaths.name(f), maxLines = 1) })
        }
    }
}

/** Where you are in a folder-at-a-time view; each part is tappable. */
@Composable
private fun Breadcrumb(dir: String, top: String, onGo: (String) -> Unit) {
    if (dir == top) return
    val parts = dir.split('/').filter { it.isNotEmpty() }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onGo(VaultPaths.parent(dir)) }) { Icon(NssIcons.Back, contentDescription = "Up one folder") }
        TextButton(onClick = { onGo(top) }) { Text(if (top.isEmpty()) "Vault" else VaultPaths.name(top)) }
        val start = if (top.isEmpty()) 0 else top.split('/').size
        for (i in start until parts.size) {
            Icon(NssIcons.ChevronRight, contentDescription = null, tint = Nss.colors.muted, modifier = Modifier.size(16.dp))
            val path = parts.take(i + 1).joinToString("/")
            TextButton(onClick = { onGo(path) }) { Text(parts[i], maxLines = 1) }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Nss.colors.muted,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun FilterBar(
    filter: String,
    sort: TreeSort,
    view: HomeView,
    hasTemplates: Boolean,
    onFilter: (String) -> Unit,
    onSort: (TreeSort) -> Unit,
    onView: (HomeView) -> Unit,
    onTemplate: () -> Unit,
    onFolders: () -> Unit,
    onTrash: () -> Unit,
) {
    var viewMenu by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = filter,
            onValueChange = onFilter,
            placeholder = { Text("Filter by name") },
            leadingIcon = { Icon(NssIcons.Filter, contentDescription = null) },
            trailingIcon = { if (filter.isNotEmpty()) IconButton(onClick = { onFilter("") }) { Icon(NssIcons.Close, contentDescription = "Clear filter") } },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.weight(1f),
        )
        Box {
            IconButton(onClick = { viewMenu = true }) { Icon(viewIcon(view), contentDescription = "View: ${view.label}") }
            DropdownMenu(viewMenu, onDismissRequest = { viewMenu = false }) {
                HomeView.entries.forEach { v ->
                    DropdownMenuItem(
                        text = { Text(v.label) },
                        leadingIcon = { Icon(viewIcon(v), contentDescription = null) },
                        trailingIcon = { if (v == view) Icon(NssIcons.Check, contentDescription = "Selected") },
                        onClick = {
                            viewMenu = false
                            onView(v)
                        },
                    )
                }
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(NssIcons.More, contentDescription = "Sort and more") }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                listOf(TreeSort.NAME to "Name (A–Z)", TreeSort.MODIFIED to "Recently modified", TreeSort.SIZE to "Size").forEach { (s, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        leadingIcon = { if (s == sort) Icon(NssIcons.Check, contentDescription = "Selected") else Icon(NssIcons.Sort, contentDescription = null) },
                        onClick = {
                            menu = false
                            onSort(s)
                        },
                    )
                }
                HorizontalDivider()
                if (hasTemplates) {
                    DropdownMenuItem(text = { Text("New from template") }, leadingIcon = { Icon(NssIcons.Template, null) }, onClick = {
                        menu = false
                        onTemplate()
                    })
                }
                DropdownMenuItem(text = { Text("Manage folders") }, leadingIcon = { Icon(NssIcons.FolderPlus, null) }, onClick = {
                    menu = false
                    onFolders()
                })
                DropdownMenuItem(text = { Text("Trash") }, leadingIcon = { Icon(NssIcons.Trash, null) }, onClick = {
                    menu = false
                    onTrash()
                })
            }
        }
    }
}

private fun viewIcon(v: HomeView): ImageVector = when (v) {
    HomeView.LIST -> NssIcons.ListBullet
    HomeView.COMPACT -> NssIcons.Rows
    HomeView.GRID -> NssIcons.Grid
    HomeView.BOX -> NssIcons.Template
    HomeView.TABS -> NssIcons.Tabs
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(count: Int, onClose: () -> Unit, onMove: () -> Unit, onTrash: () -> Unit, onPin: () -> Unit) {
    TopAppBar(
        title = { Text("$count selected") },
        navigationIcon = { IconButton(onClick = onClose) { Icon(NssIcons.Close, contentDescription = "Clear selection") } },
        actions = {
            IconButton(onClick = onPin) { Icon(NssIcons.Pin, contentDescription = "Pin selected") }
            IconButton(onClick = onMove) { Icon(NssIcons.Move, contentDescription = "Move selected") }
            IconButton(onClick = onTrash) { Icon(NssIcons.Trash, contentDescription = "Move selected to trash") }
        },
    )
}

fun kindIcon(kind: FileKind, isDir: Boolean): ImageVector = when {
    isDir -> NssIcons.Folder
    kind == FileKind.MARKDOWN -> NssIcons.FileText
    kind == FileKind.CSV -> NssIcons.Table
    kind == FileKind.IMAGE -> NssIcons.Image
    kind == FileKind.BINARY -> NssIcons.File
    else -> NssIcons.Code
}

@Composable
fun kindTint(kind: FileKind, isDir: Boolean): Color = when {
    isDir -> MaterialTheme.colorScheme.primary
    kind == FileKind.MARKDOWN -> MaterialTheme.colorScheme.primary
    kind == FileKind.CSV -> Nss.colors.ok
    kind == FileKind.IMAGE -> Nss.colors.warn
    kind == FileKind.BINARY -> Nss.colors.muted
    else -> MaterialTheme.colorScheme.secondary
}

/** Name without ".md"; other extensions stay visible. */
fun displayName(name: String, isDir: Boolean): String = if (!isDir && name.endsWith(".md", ignoreCase = true)) name.dropLast(3) else name

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TreeRowItem(
    row: TreeRow,
    selected: Boolean,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPin: () -> Unit,
    onTrash: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onNewHere: () -> Unit,
    onCopyPath: () -> Unit,
    onOpenWith: () -> Unit,
    modifier: Modifier = Modifier,
    dense: Boolean = false,
) {
    val haptics = rememberHaptics()
    val e = row.entry
    val dismiss = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismiss.targetValue) { if (dismiss.targetValue != SwipeToDismissBoxValue.Settled) haptics.threshold() }
    // Act once the swipe completes, then spring back; trash removes the row through the state update.
    LaunchedEffect(dismiss.currentValue) {
        when (dismiss.currentValue) {
            SwipeToDismissBoxValue.StartToEnd -> onPin()
            SwipeToDismissBoxValue.EndToStart -> onTrash()
            SwipeToDismissBoxValue.Settled -> return@LaunchedEffect
        }
        dismiss.reset()
    }
    SwipeToDismissBox(
        state = dismiss,
        modifier = modifier,
        // Files only: folders can't be pinned or trashed from the home screen.
        enableDismissFromStartToEnd = !e.isDirectory && !selecting,
        enableDismissFromEndToStart = !e.isDirectory && !selecting,
        backgroundContent = { SwipeBackground(dismiss.dismissDirection, dismiss.targetValue != SwipeToDismissBoxValue.Settled, row.pinned) },
    ) {
        var menu by remember { mutableStateOf(false) }
        val motion = Nss.motion
        val chevron by animateFloatAsState(if (row.expanded) 90f else 0f, motion.spatial(), label = "chevron")
        Surface(color = if (selected || checked) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.background) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = onClick, onLongClick = {
                        haptics.tick()
                        onLongClick()
                    })
                    .padding(start = (12 + row.depth * 16).dp, end = 4.dp, top = if (dense) 0.dp else 6.dp, bottom = if (dense) 0.dp else 6.dp)
                    .semantics { contentDescription = (if (e.isDirectory) "Folder " else "File ") + e.name },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (e.isDirectory) {
                    Icon(NssIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp).rotate(chevron), tint = Nss.colors.muted)
                } else {
                    Spacer(Modifier.width(18.dp))
                }
                Spacer(Modifier.width(6.dp))
                Icon(kindIcon(row.kind, e.isDirectory), contentDescription = null, tint = kindTint(row.kind, e.isDirectory), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        displayName(e.name, e.isDirectory),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = if (dense) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                    )
                    if (!dense && !e.isDirectory && e.modified > 0) {
                        Text(Format.relative(e.modified), style = MaterialTheme.typography.labelSmall, color = Nss.colors.muted)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (row.conflict) NssBadge("conflict", Nss.colors.error)
                    if (row.pinned) NssBadge("pinned", MaterialTheme.colorScheme.primary)
                    if (e.readOnly) NssBadge("read-only", Nss.colors.muted)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(NssIcons.More, contentDescription = "Actions for ${e.name}") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        val close = { menu = false }
                        if (e.isDirectory) ActionItem("New note here", NssIcons.FilePlus, close, onNewHere)
                        ActionItem("Rename / Move", NssIcons.Edit, close, onRename)
                        if (!e.isDirectory) ActionItem("Duplicate", NssIcons.Copy, close, onDuplicate)
                        if (!e.isDirectory) ActionItem(if (row.pinned) "Unpin" else "Pin", NssIcons.Pin, close, onPin)
                        ActionItem("Copy path", NssIcons.Copy, close, onCopyPath)
                        if (!e.isDirectory) ActionItem("Open with…", NssIcons.OpenWith, close, onOpenWith)
                        if (!e.isDirectory) ActionItem("Move to trash", NssIcons.Trash, close, onTrash)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionItem(label: String, icon: ImageVector, close: () -> Unit, action: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = {
            close()
            action()
        },
    )
}

/** Swipe background: icon grows and colour fills as the threshold nears. */
@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue, armed: Boolean, pinned: Boolean) {
    val pin = direction == SwipeToDismissBoxValue.StartToEnd
    val color = if (pin) MaterialTheme.colorScheme.primaryContainer else Nss.colors.error
    val scale by animateFloatAsState(if (armed) 1.2f else 0.8f, Nss.motion.playful(), label = "swipeIcon")
    Box(
        Modifier.fillMaxSize().background(color.copy(alpha = if (armed) 0.9f else 0.35f)).padding(horizontal = 24.dp),
        contentAlignment = if (pin) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Icon(
            if (pin) NssIcons.Pin else NssIcons.Trash,
            contentDescription = if (pin) (if (pinned) "Unpin" else "Pin") else "Move to trash",
            tint = Color.White,
            modifier = Modifier.scale(scale),
        )
    }
}

@Composable
private fun PinnedRow(e: VaultEntry, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(NssIcons.Pin, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Text(displayName(e.name, false), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            Text(VaultPaths.parent(e.path), style = MaterialTheme.typography.labelSmall, color = Nss.colors.muted, maxLines = 1)
        }
    }
}

@Composable
private fun TemplatePicker(templates: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a template") },
        text = {
            Column {
                templates.forEach { t ->
                    TextButton(onClick = { onPick(t) }, modifier = Modifier.fillMaxWidth()) { Text(VaultPaths.name(t), modifier = Modifier.fillMaxWidth()) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Trash: restore (to the original path when free, else ask), open the folder; no permanent delete in v1. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(session: VaultSession, onBack: () -> Unit) {
    val vm = graphViewModel(key = "trash-${session.id}") { TrashViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Trash") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(NssIcons.Back, contentDescription = "Back") } },
            actions = {
                IconButton(onClick = {
                    scope.launch {
                        val msg = vm.trashFolder()?.let { ExternalOpen.openFolder(context, it) } ?: "The trash folder can't be opened in slower mode."
                        msg?.let { snackbar.showSnackbar(it) }
                    }
                }) { Icon(NssIcons.OpenWith, contentDescription = "Open folder") }
            },
        )
        Text(
            "Files here are only moved, never erased. Delete them for good from a file manager or Obsidian.",
            style = MaterialTheme.typography.bodySmall,
            color = Nss.colors.muted,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        if (!state.loading && state.rows.isEmpty()) EmptyState(NssIcons.Trash, "Trash is empty", "Deleted notes land here.")
        LazyColumn(Modifier.weight(1f)) {
            items(state.rows, key = { it.entry.path }) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).animateItem(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(row.entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            (row.original?.let { "from $it · " } ?: "") + Format.relative(row.entry.modified),
                            style = MaterialTheme.typography.labelSmall,
                            color = Nss.colors.muted,
                        )
                    }
                    TextButton(onClick = { vm.restore(row) }) { Text("Restore") }
                }
            }
        }
        SnackbarHost(snackbar)
    }
    state.askPathFor?.let { row ->
        NameDialog(
            "Restore to…", "Path in vault", row.original ?: row.entry.name, "Restore",
            onDismiss = vm::dismissAsk, onConfirm = { vm.restoreTo(row, it) },
        )
    }
}

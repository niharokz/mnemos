package com.nihars.mnemos.feature.table

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nihars.mnemos.core.data.VaultSession
import com.nihars.mnemos.core.data.graphViewModel
import com.nihars.mnemos.core.design.NameDialog
import com.nihars.mnemos.core.design.Nss
import com.nihars.mnemos.core.design.NssBanner
import com.nihars.mnemos.core.design.NssIcons
import com.nihars.mnemos.core.design.SaveDot
import com.nihars.mnemos.core.vaultfs.VaultPaths
import java.util.Locale

private const val ROW_DP = 40

private data class CellRef(val record: Int, val col: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableScreen(session: VaultSession, path: String, showBack: Boolean, onBack: () -> Unit, onRaw: (String) -> Unit) {
    val vm = graphViewModel(key = "table-${session.id}-$path") { TableViewModel(session, path) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<CellRef?>(null) }
    var headerMenu by remember { mutableStateOf<Int?>(null) }
    var renaming by remember { mutableStateOf<Int?>(null) }
    var addingColumn by remember { mutableStateOf(false) }
    var confirmSort by remember { mutableStateOf(false) }
    var filterOpen by rememberSaveable { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flush() }
    DisposableEffect(Unit) { onDispose { vm.flush() } }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }
    LaunchedEffect(state.undoDelete) {
        if (state.undoDelete == null) return@LaunchedEffect
        val r = snackbar.showSnackbar("Row deleted", actionLabel = "Undo", duration = SnackbarDuration.Long)
        if (r == SnackbarResult.ActionPerformed) vm.undoDelete() else vm.undoShown()
    }

    Box(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = { if (showBack) IconButton(onClick = onBack) { Icon(NssIcons.Back, contentDescription = "Back") } },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(VaultPaths.name(path), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(8.dp))
                        SaveDot(state.status)
                    }
                },
                actions = {
                    IconButton(onClick = { filterOpen = !filterOpen }) { Icon(NssIcons.Filter, contentDescription = "Filter rows") }
                    if (!state.readOnly) IconButton(onClick = { vm.insertRow(Int.MAX_VALUE) }) { Icon(NssIcons.Add, contentDescription = "Add row") }
                    if (!state.readOnly) IconButton(onClick = { addingColumn = true }) { Icon(NssIcons.Columns, contentDescription = "Add column") }
                    IconButton(onClick = {
                        vm.flush()
                        onRaw(path)
                    }) { Icon(NssIcons.Code, contentDescription = "Raw text") }
                },
            )
            NssBanner(state.conflict, NssIcons.Warning, "This file changed in another app. Saving is paused.", Nss.colors.error) {
                TextButton(onClick = vm::loadTheirs) { Text("Load theirs") }
                TextButton(onClick = { vm.keepMine() }) { Text("Keep mine") }
            }
            NssBanner(state.sort != null && !state.readOnly, NssIcons.Sort, "Sorted for viewing only. The file keeps its order.", MaterialTheme.colorScheme.primary) {
                TextButton(onClick = { confirmSort = true }) { Text("Apply sort to file") }
            }
            if (filterOpen) {
                OutlinedTextField(
                    value = state.filterText,
                    onValueChange = { vm.setFilter(state.filterColumn, it) },
                    placeholder = { Text(state.filterColumn?.let { "Filter ${state.header.getOrElse(it) { "column" }}" } ?: "Filter all columns") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            if (!state.loading) {
                Grid(
                    state = state,
                    cell = vm::cell,
                    onCell = { r, c -> if (!state.readOnly) editing = CellRef(r, c) },
                    onHeader = { headerMenu = it },
                    onResize = vm::widen,
                    headerMenu = headerMenu,
                    menuContent = { col ->
                        DropdownMenuItem(text = { Text("Sort ascending ↑ / descending ↓ / off") }, onClick = { vm.sortBy(col) })
                        DropdownMenuItem(text = { Text("Filter this column") }, onClick = {
                            headerMenu = null
                            filterOpen = true
                            vm.setFilter(col, state.filterText)
                        })
                        DropdownMenuItem(text = { Text("Column stats") }, onClick = {
                            headerMenu = null
                            vm.showStats(col)
                        })
                        if (!state.readOnly) {
                            DropdownMenuItem(text = { Text("Rename column") }, onClick = {
                                headerMenu = null
                                renaming = col
                            })
                        }
                    },
                    onDismissMenu = { headerMenu = null },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    editing?.let { ref ->
        CellSheet(
            title = state.header.getOrElse(ref.col) { "Column ${ref.col + 1}" },
            initial = vm.cell(ref.record, ref.col),
            onDismiss = { editing = null },
            onSave = {
                vm.setCell(ref.record, ref.col, it)
                editing = null
            },
            onInsertBelow = {
                vm.insertRow(ref.record + 1)
                editing = null
            },
            onDelete = {
                vm.deleteRow(ref.record)
                editing = null
            },
        )
    }
    renaming?.let { col ->
        NameDialog("Rename column", "Name", state.header.getOrElse(col) { "" }, "Rename", { renaming = null }, {
            vm.renameColumn(col, it)
            renaming = null
        }, selectStem = false)
    }
    if (addingColumn) {
        NameDialog("Add column", "Name", "", "Add", { addingColumn = false }, {
            vm.addColumn(it)
            addingColumn = false
        }, selectStem = false)
    }
    if (confirmSort) {
        AlertDialog(
            onDismissRequest = { confirmSort = false },
            title = { Text("Apply sort to file?") },
            text = { Text("This rewrites the rows in the file in the order you see now. Other apps will see the new order.") },
            confirmButton = { TextButton(onClick = { confirmSort = false; vm.applySort() }) { Text("Rewrite file") } },
            dismissButton = { TextButton(onClick = { confirmSort = false }) { Text("Cancel") } },
        )
    }
    state.stats?.let { (name, s) ->
        AlertDialog(
            onDismissRequest = vm::hideStats,
            title = { Text(name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatLine("Rows", s.count.toString())
                    StatLine("Empty", s.empty.toString())
                    StatLine("Unique", s.unique.toString())
                    if (s.numeric) {
                        StatLine("Sum", fmt(s.sum))
                        StatLine("Min", fmt(s.min))
                        StatLine("Max", fmt(s.max))
                        StatLine("Average", fmt(s.average))
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::hideStats) { Text("Close") } },
        )
    }
}

private fun fmt(v: Double?): String = v?.let { if (it == Math.floor(it) && kotlin.math.abs(it) < 1e15) it.toLong().toString() else String.format(Locale.ROOT, "%.4f", it).trimEnd('0') } ?: "–"

@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), color = Nss.colors.muted)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

/**
 * Virtualized grid: rows through LazyColumn, columns by drawing only those inside the
 * horizontal viewport. Header row and first column stay put.
 */
@Composable
private fun Grid(
    state: TableState,
    cell: (Int, Int) -> String,
    onCell: (Int, Int) -> Unit,
    onHeader: (Int) -> Unit,
    onResize: (Int, Int) -> Unit,
    headerMenu: Int?,
    menuContent: @Composable (Int) -> Unit,
    onDismissMenu: () -> Unit,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val h = rememberScrollState()
    var viewportPx by remember { mutableIntStateOf(0) }
    val widths = state.widthsDp
    val firstW = widths.firstOrNull() ?: 120
    // Left edge (dp) of each scrolled column (1..n).
    val lefts = remember(widths) {
        val out = IntArray(maxOf(0, widths.size))
        var x = 0
        for (i in 1 until widths.size) {
            out[i] = x
            x += widths[i]
        }
        out
    }
    val totalDp = widths.drop(1).sum()
    val visible by remember(widths) {
        derivedStateOf {
            val from = with(density) { h.value.toDp().value.toInt() }
            val to = from + with(density) { viewportPx.toDp().value.toInt() }
            (1 until widths.size).filter { c -> lefts[c] + widths[c] >= from && lefts[c] <= to }
        }
    }
    val surface = MaterialTheme.colorScheme.surface
    val zebra = MaterialTheme.colorScheme.surfaceContainerHigh

    Column(modifier) {
        // Sticky header.
        Row(Modifier.height(ROW_DP.dp).background(zebra)) {
            HeaderCell(state, 0, firstW, headerMenu == 0, onHeader, onResize, menuContent, onDismissMenu)
            ScrolledRow(h, totalDp, Modifier.onSizeChanged { viewportPx = it.width }) {
                for (c in visible) {
                    Box(Modifier.offset(x = lefts[c].dp)) { HeaderCell(state, c, widths[c], headerMenu == c, onHeader, onResize, menuContent, onDismissMenu) }
                }
            }
        }
        HorizontalDivider(color = Nss.colors.line)
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(state.order, key = { _, r -> r }, contentType = { _, _ -> "row" }) { i, record ->
                val bg = if (i % 2 == 0) surface else zebra.copy(alpha = 0.5f)
                Row(Modifier.height(ROW_DP.dp).background(bg)) {
                    DataCell(cell(record, 0), firstW, 0 in state.numeric, bold = true) { onCell(record, 0) }
                    ScrolledRow(h, totalDp) {
                        for (c in visible) {
                            Box(Modifier.offset(x = lefts[c].dp)) { DataCell(cell(record, c), widths[c], c in state.numeric) { onCell(record, c) } }
                        }
                    }
                }
            }
        }
    }
}

/** A row-wide box as wide as all scrolled columns, sharing one horizontal ScrollState. */
@Composable
private fun ScrolledRow(h: ScrollState, totalDp: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxHeight().horizontalScroll(h)) {
        Box(Modifier.width(totalDp.dp).fillMaxHeight()) { content() }
    }
}

@Composable
private fun HeaderCell(
    state: TableState,
    col: Int,
    widthDp: Int,
    menuOpen: Boolean,
    onHeader: (Int) -> Unit,
    onResize: (Int, Int) -> Unit,
    menuContent: @Composable (Int) -> Unit,
    onDismissMenu: () -> Unit,
) {
    val density = LocalDensity.current
    val sort = state.sort?.takeIf { it.column == col }
    Box(Modifier.width(widthDp.dp).fillMaxHeight()) {
        Row(
            Modifier.fillMaxSize().clickable { onHeader(col) }.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                state.header.getOrElse(col) { "" }.ifEmpty { "Column ${col + 1}" },
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
            )
            if (sort != null) Icon(if (sort.ascending) NssIcons.ArrowUp else NssIcons.ArrowDown, contentDescription = if (sort.ascending) "Sorted ascending" else "Sorted descending")
        }
        // Drag handle on the right edge to resize the column.
        Box(
            Modifier.align(Alignment.CenterEnd).width(10.dp).fillMaxHeight()
                .draggable(rememberDraggableState { px -> onResize(col, with(density) { px.toDp().value.toInt() }) }, Orientation.Horizontal),
        )
        DropdownMenu(menuOpen, onDismissRequest = onDismissMenu) { menuContent(col) }
    }
}

@Composable
private fun DataCell(value: String, widthDp: Int, numeric: Boolean, bold: Boolean = false, onClick: () -> Unit) {
    Text(
        value.replace('\n', '⏎'),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = if (numeric) TextAlign.End else TextAlign.Start,
        fontWeight = if (bold) FontWeight.Medium else null,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.width(widthDp.dp).fillMaxHeight().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CellSheet(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit, onInsertBelow: () -> Unit, onDelete: () -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value, { value = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(value) }) { Text("Save") }
                OutlinedButton(onClick = onInsertBelow) { Text("Insert row below") }
                TextButton(onClick = onDelete) { Text("Delete row", color = Nss.colors.error) }
            }
        }
    }
}

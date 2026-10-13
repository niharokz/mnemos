package com.nihars.mnemos.feature.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nihars.mnemos.core.data.VaultSession
import com.nihars.mnemos.core.data.graphViewModel
import com.nihars.mnemos.core.design.NameDialog
import com.nihars.mnemos.core.design.Nss
import com.nihars.mnemos.core.design.NssBadge
import com.nihars.mnemos.core.design.NssIcons
import com.nihars.mnemos.core.text.NaturalCompare
import com.nihars.mnemos.core.vaultfs.VaultException
import com.nihars.mnemos.core.vaultfs.VaultPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class FolderRow(val path: String, val depth: Int, val files: Int)

@Immutable
data class FoldersState(
    val folders: List<FolderRow> = emptyList(),
    val defaultFolder: String = "",
    val loading: Boolean = true,
    val message: String? = null,
)

/** The one place folders are created, renamed and removed (to the trash). */
class FoldersViewModel(private val session: VaultSession) : ViewModel() {
    private val _state = MutableStateFlow(FoldersState())
    val state: StateFlow<FoldersState> = _state.asStateFlow()

    init {
        reload()
        viewModelScope.launch { session.changes.collect { reload() } }
        viewModelScope.launch { session.settings.collect { s -> _state.update { it.copy(defaultFolder = s.newNoteFolder) } } }
    }

    fun reload() = viewModelScope.launch {
        val rows = withContext(Dispatchers.IO) {
            val all = runCatching { session.fs.walk() }.getOrDefault(emptyList()).filter { !session.hidden.value.isHiddenPath(it.path) }
            val counts = all.filter { !it.isDirectory }.groupingBy { VaultPaths.parent(it.path) }.eachCount()
            all.filter { it.isDirectory }
                .sortedWith { a, b -> NaturalCompare.compare(a.path, b.path) }
                .map { FolderRow(it.path, it.path.count { c -> c == '/' }, counts[it.path] ?: 0) }
        }
        _state.update { it.copy(folders = rows, loading = false) }
    }

    fun create(path: String) = act { session.createFolder(VaultPaths.parent(path), VaultPaths.name(path)) }

    fun rename(from: String, to: String) = act {
        session.move(from, to)
        // The default folder follows a rename.
        val def = session.settings.value.newNoteFolder
        if (def == from || def.startsWith("$from/")) session.settingsStore.update { it.copy(newNoteFolder = VaultPaths.normalize(to) + def.removePrefix(from)) }
    }

    /** Moves the folder and everything in it to `.trash/` (restorable from Trash). */
    fun trash(path: String) = act {
        session.moveToTrash(path)
        val def = session.settings.value.newNoteFolder
        if (def == path || def.startsWith("$path/")) session.settingsStore.update { it.copy(newNoteFolder = "") }
        _state.update { it.copy(message = "Moved “${VaultPaths.name(path)}” to trash. Restore it from Trash.") }
    }

    fun makeDefault(path: String) = viewModelScope.launch {
        session.settingsStore.update { it.copy(newNoteFolder = path) }
        _state.update { it.copy(message = "New notes now go to “${path.ifEmpty { "vault root" }}”.") }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun act(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: VaultException) {
            _state.update { it.copy(message = e.message) }
        }
    }
}

private sealed interface FolderDialog {
    data class Create(val parent: String) : FolderDialog
    data class Rename(val path: String) : FolderDialog
    data class ConfirmTrash(val row: FolderRow) : FolderDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(session: VaultSession, onBack: () -> Unit) {
    val vm = graphViewModel(key = "folders-${session.id}") { FoldersViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<FolderDialog?>(null) }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("Manage folders") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(NssIcons.Back, contentDescription = "Back") } },
            )
            Text(
                "Create, rename or remove folders here. Removed folders go to the trash with everything inside.",
                style = MaterialTheme.typography.bodySmall,
                color = Nss.colors.muted,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 96.dp)) {
                item(key = "root") {
                    FolderLine(FolderRow("", -1, 0), isDefault = state.defaultFolder.isEmpty(), onNew = { dialog = FolderDialog.Create("") }, onDefault = { vm.makeDefault("") }, onRename = null, onTrash = null)
                }
                items(state.folders, key = { it.path }) { row ->
                    FolderLine(
                        row,
                        isDefault = state.defaultFolder == row.path,
                        onNew = { dialog = FolderDialog.Create(row.path) },
                        onDefault = { vm.makeDefault(row.path) },
                        onRename = { dialog = FolderDialog.Rename(row.path) },
                        onTrash = { dialog = FolderDialog.ConfirmTrash(row) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { dialog = FolderDialog.Create("") },
            icon = { Icon(NssIcons.FolderPlus, contentDescription = null) },
            text = { Text("New folder") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp))
    }
    when (val d = dialog) {
        is FolderDialog.Create -> NameDialog("New folder", "Name (or path, like notes/2026)", if (d.parent.isEmpty()) "" else d.parent + "/", "Create", { dialog = null }, {
            dialog = null
            vm.create(it)
        }, selectStem = false)
        is FolderDialog.Rename -> NameDialog("Rename or move folder", "Path in vault", d.path, "Save", { dialog = null }, {
            dialog = null
            if (it != d.path) vm.rename(d.path, it)
        }, selectStem = false)
        is FolderDialog.ConfirmTrash -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Move “${VaultPaths.name(d.row.path)}” to trash?") },
            text = { Text("The folder and everything in it move to .trash/. Nothing is erased; you can restore it from Trash.") },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    vm.trash(d.row.path)
                }) { Text("Move to trash", color = Nss.colors.error) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        null -> Unit
    }
}

@Composable
private fun FolderLine(
    row: FolderRow,
    isDefault: Boolean,
    onNew: () -> Unit,
    onDefault: () -> Unit,
    onRename: (() -> Unit)?,
    onTrash: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = (16 + maxOf(0, row.depth) * 16).dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(NssIcons.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (row.path.isEmpty()) "Vault root" else VaultPaths.name(row.path), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (row.path.isNotEmpty()) Text("${row.files} files", style = MaterialTheme.typography.labelSmall, color = Nss.colors.muted)
            }
            if (isDefault) NssBadge("new notes", Nss.colors.ok)
            Box {
                IconButton(onClick = { menu = true }) { Icon(NssIcons.More, contentDescription = "Actions for ${row.path.ifEmpty { "vault root" }}") }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("New folder inside") }, leadingIcon = { Icon(NssIcons.FolderPlus, null) }, onClick = {
                        menu = false
                        onNew()
                    })
                    if (!isDefault) {
                        DropdownMenuItem(text = { Text("Put new notes here") }, leadingIcon = { Icon(NssIcons.FilePlus, null) }, onClick = {
                            menu = false
                            onDefault()
                        })
                    }
                    onRename?.let { r ->
                        DropdownMenuItem(text = { Text("Rename / Move") }, leadingIcon = { Icon(NssIcons.Edit, null) }, onClick = {
                            menu = false
                            r()
                        })
                    }
                    onTrash?.let { t ->
                        DropdownMenuItem(text = { Text("Move to trash") }, leadingIcon = { Icon(NssIcons.Trash, null) }, onClick = {
                            menu = false
                            t()
                        })
                    }
                }
            }
        }
    }
}

/** Lists the vault's folders for pickers (default folder, share file location). Call off the main thread. */
fun vaultFolders(session: VaultSession): List<String> =
    runCatching { session.fs.walk() }.getOrDefault(emptyList())
        .filter { it.isDirectory && !session.hidden.value.isHiddenPath(it.path) }
        .map { it.path }
        .sortedWith(NaturalCompare)

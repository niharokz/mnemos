package com.nihars.mnemos.feature.conflicts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nihars.mnemos.core.data.VaultSession
import com.nihars.mnemos.core.data.graphViewModel
import com.nihars.mnemos.core.design.Nss
import com.nihars.mnemos.core.design.NssIcons
import com.nihars.mnemos.core.vaultfs.VaultPaths

/** Diff + resolution for a `*.sync-conflict-*` copy. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ConflictScreen(session: VaultSession, conflictPath: String, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val vm = graphViewModel(key = "conflict-${session.id}-$conflictPath") { ConflictViewModel(session, conflictPath) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }
    LaunchedEffect(state.resolved) { if (state.resolved) state.resolvedOpen?.let(onOpen) ?: onBack() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Sync conflict") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(NssIcons.Back, contentDescription = "Back") } },
        )
        Text(
            "Syncthing kept two versions of “${VaultPaths.name(state.originalPath)}”. Choose one; the other goes to the trash.",
            style = MaterialTheme.typography.bodyMedium,
            color = Nss.colors.muted,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        DiffView(
            state.rows,
            leftTitle = if (state.originalExists) "Original" else "Original (missing)",
            rightTitle = "Conflict copy",
            modifier = Modifier.weight(1f).padding(top = 8.dp),
        )
        FlowRow(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.keepOriginal() }, enabled = !state.busy && state.originalExists) { Text("Keep original") }
            Button(onClick = { vm.keepConflictCopy() }, enabled = !state.busy) { Text("Keep conflict copy") }
            OutlinedButton(onClick = { vm.mergeByHand() }, enabled = !state.busy) { Text("Merge by hand") }
            TextButton(onClick = onBack) { Text("Dismiss") }
        }
        SnackbarHost(snackbar)
    }
}

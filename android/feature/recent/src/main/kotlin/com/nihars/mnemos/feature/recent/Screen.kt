package com.nihars.mnemos.feature.recent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nihars.mnemos.core.data.FileKinds
import com.nihars.mnemos.core.data.Format
import com.nihars.mnemos.core.data.VaultSession
import com.nihars.mnemos.core.data.graphViewModel
import com.nihars.mnemos.core.design.EmptyState
import com.nihars.mnemos.core.design.Nss
import com.nihars.mnemos.core.design.NssBadge
import com.nihars.mnemos.core.design.NssIcons
import com.nihars.mnemos.core.vaultfs.VaultPaths

@Composable
fun RecentScreen(session: VaultSession, onOpen: (String) -> Unit) {
    val vm = graphViewModel(key = "recent-${session.id}") { RecentViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Text("Recent", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        if (!state.loading && state.groups.isEmpty()) EmptyState(NssIcons.Recent, "Nothing yet", "Notes you open or that change elsewhere show up here.")
        LazyColumn(Modifier.weight(1f)) {
            for (g in state.groups) {
                item(key = "h-" + g.title, contentType = "header") {
                    Text(g.title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Nss.colors.muted, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp).animateItem())
                }
                items(g.items, key = { g.title + it.path }, contentType = { "item" }) { item ->
                    Surface(onClick = { onOpen(item.path) }, color = MaterialTheme.colorScheme.background, modifier = Modifier.animateItem()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (FileKinds.viewKey(item.name) == "csv") NssIcons.Table else NssIcons.FileText,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    VaultPaths.parent(item.path).ifEmpty { "/" } + " · " +
                                        (item.openedAt?.let { "opened ${Format.relative(it)}" } ?: "changed ${Format.relative(item.modified)}"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Nss.colors.muted,
                                    maxLines = 1,
                                )
                            }
                            if (item.changedElsewhere) NssBadge("changed elsewhere", Nss.colors.warn)
                        }
                    }
                }
            }
        }
    }
}

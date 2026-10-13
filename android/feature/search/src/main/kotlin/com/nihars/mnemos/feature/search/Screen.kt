package com.nihars.mnemos.feature.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nihars.mnemos.core.data.Format
import com.nihars.mnemos.core.data.VaultSession
import com.nihars.mnemos.core.data.graphViewModel
import com.nihars.mnemos.core.design.EmptyState
import com.nihars.mnemos.core.design.MotionTokens
import com.nihars.mnemos.core.design.Nss
import com.nihars.mnemos.core.design.NssBadge
import com.nihars.mnemos.core.design.NssIcons
import com.nihars.mnemos.core.index.HitKind
import com.nihars.mnemos.core.vaultfs.VaultPaths

/** One box for names, words, `#tags` and `key: value`. Tapping a result opens it at the match. */
@Composable
fun SearchScreen(session: VaultSession, onOpen: (path: String, query: String) -> Unit, autoFocus: Boolean = false) {
    val vm = graphViewModel(key = "search-${session.id}") { SearchViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val motion = Nss.motion
    LaunchedEffect(autoFocus) { if (autoFocus) focus.requestFocus() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                placeholder = { Text("Search names, words, #tags, status: open") },
                leadingIcon = { Icon(NssIcons.Search, contentDescription = null) },
                trailingIcon = { if (state.query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(NssIcons.Close, contentDescription = "Clear search") } },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            IconButton(onClick = { filtersOpen = !filtersOpen }) { Icon(NssIcons.Filter, contentDescription = "Search filters") }
        }
        AnimatedVisibility(filtersOpen) {
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = state.folder,
                    onValueChange = vm::setFolder,
                    placeholder = { Text("Only in folder (e.g. inbox)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TypeFilter.entries.forEach { t -> FilterChip(state.type == t, { vm.setType(t) }, label = { Text(t.label) }) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DateFilter.entries.forEach { d -> FilterChip(state.date == d, { vm.setDate(d) }, label = { Text(d.label) }) }
                }
            }
        }
        if (state.query.isNotBlank()) {
            Text(
                "${state.hits.size} results · ${state.millis} ms" + if (state.indexing) " · still indexing" else "",
                style = MaterialTheme.typography.labelSmall,
                color = Nss.colors.muted,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (state.query.isBlank()) {
            EmptyState(NssIcons.Search, "Search your vault", "File names, words inside notes, #tags and front matter like status: open.")
        } else if (!state.searching && state.hits.isEmpty()) {
            EmptyState(NssIcons.Search, "Nothing found", if (state.indexing) "The index is still being built; try again in a moment." else "Try fewer words or another spelling.")
        }
        LazyColumn(Modifier.weight(1f)) {
            items(state.hits, key = { it.path }, contentType = { it.kind }) { hit ->
                Surface(
                    onClick = { onOpen(hit.path, state.query) },
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.animateItem(fadeInSpec = tween(motion.enterMs()), placementSpec = tween(MotionTokens.MEDIUM), fadeOutSpec = tween(MotionTokens.SHORT)),
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(hit.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(8.dp))
                            when (hit.kind) {
                                HitKind.NAME -> NssBadge("name", MaterialTheme.colorScheme.primary)
                                HitKind.TAG -> NssBadge("tag", Nss.colors.ok)
                                HitKind.PROPERTY -> NssBadge("property", Nss.colors.warn)
                                HitKind.CONTENT -> Unit
                            }
                            if (hit.matches > 1) {
                                Spacer(Modifier.width(6.dp))
                                NssBadge("${hit.matches}×", Nss.colors.muted)
                            }
                        }
                        Text(
                            VaultPaths.parent(hit.path).ifEmpty { "/" } + " · " + Format.relative(hit.mtime),
                            style = MaterialTheme.typography.labelSmall,
                            color = Nss.colors.muted,
                        )
                        hit.snippet?.let {
                            Text(highlight(it, MaterialTheme.colorScheme.primary), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

private fun com.nihars.mnemos.core.design.Motion.enterMs(): Int = if (full) MotionTokens.MEDIUM else MotionTokens.SHORT

/** Turns the index's \u0002match\u0003 markers into bold coloured spans. */
private fun highlight(snippet: String, color: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < snippet.length) {
        val s = snippet.indexOf('\u0002', i)
        if (s < 0) {
            append(snippet.substring(i).replace("\u0003", ""))
            break
        }
        append(snippet.substring(i, s))
        val e = snippet.indexOf('\u0003', s).let { if (it < 0) snippet.length else it }
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(snippet.substring(s + 1, e)) }
        i = e + 1
    }
}

package com.nihars.mnemos.feature.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nihars.mnemos.core.data.Format
import com.nihars.mnemos.core.data.VaultSession
import com.nihars.mnemos.core.design.EmptyState
import com.nihars.mnemos.core.design.Nss
import com.nihars.mnemos.core.design.NssIcons
import com.nihars.mnemos.core.markdown.MarkdownDoc
import com.nihars.mnemos.core.markdown.MarkdownParser
import com.nihars.mnemos.core.text.TextCodec
import com.nihars.mnemos.core.vaultfs.ConflictFiles
import com.nihars.mnemos.core.vaultfs.VaultEntry
import com.nihars.mnemos.core.vaultfs.VaultPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.absoluteValue

/**
 * Flow: every note in the vault, newest first, one per screen. Swipe up for the next, like
 * Shorts. The pages next to the visible one are composed ahead, so their markdown is already
 * parsed when you get there. Tap Open to edit.
 */
@Composable
fun NoteFlow(session: VaultSession, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val settings by session.settings.collectAsStateWithLifecycle()
    val hidden by session.hidden.collectAsStateWithLifecycle()
    val notes by produceState<List<VaultEntry>?>(null, hidden) {
        value = withContext(Dispatchers.IO) {
            runCatching { session.fs.walk("") }.getOrDefault(emptyList())
                .filter { !it.isDirectory && VaultPaths.ext(it.name) == "md" && !ConflictFiles.isConflict(it.path) && !hidden.isHiddenPath(it.path) }
                .sortedByDescending { it.modified }
        }
    }
    val list = notes
    when {
        list == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        list.isEmpty() -> Box(modifier.fillMaxSize()) { EmptyState(NssIcons.FileText, "No notes yet", "Tap + to write one.", Modifier.align(Alignment.Center)) }
        else -> {
            val pager = rememberPagerState { list.size }
            val motion = Nss.motion
            VerticalPager(pager, modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { list[it].path }) { i ->
                val e = list[i]
                Column(
                    Modifier.fillMaxSize().graphicsLayer {
                        // pages settle in with a slight scale and fade as they snap
                        if (motion.off) return@graphicsLayer
                        val off = (pager.currentPage - i + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
                        val s = 1f - 0.06f * off
                        scaleX = s
                        scaleY = s
                        alpha = 1f - 0.5f * off
                    },
                ) {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(VaultPaths.stem(e.name), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(VaultPaths.parent(e.path), Format.relative(e.modified), "${i + 1} / ${list.size}").filter { it.isNotEmpty() }.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = Nss.colors.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        FilledTonalButton(onClick = { onOpen(e.path) }) { Text("Open") }
                    }
                    HorizontalDivider(Modifier.padding(top = 8.dp), color = Nss.colors.line)
                    val doc by produceState<MarkdownDoc?>(null, e.path, e.modified) {
                        value = withContext(Dispatchers.IO) {
                            runCatching { MarkdownParser().parse(TextCodec.decode(session.fs.readBytes(e.path)).text, VaultPaths.stem(e.name)) }.getOrNull()
                        }
                    }
                    MarkdownPreview(
                        doc = doc,
                        session = session,
                        notePath = e.path,
                        textSizeSp = settings.previewTextSizeSp,
                        maxWidthDp = settings.previewMaxWidthDp,
                        showFrontMatter = false,
                        listState = rememberLazyListState(),
                        onToggleTask = {},
                        onOpenNote = onOpen,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

package com.nihars.mnemos.feature.viewer

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nihars.mnemos.core.data.ExternalOpen
import com.nihars.mnemos.core.data.Format
import com.nihars.mnemos.core.data.VaultSession
import com.nihars.mnemos.core.data.graphViewModel
import com.nihars.mnemos.core.design.EmptyState
import com.nihars.mnemos.core.design.JetBrainsMono
import com.nihars.mnemos.core.design.Nss
import com.nihars.mnemos.core.design.NssIcons
import com.nihars.mnemos.core.vaultfs.VaultEntry
import com.nihars.mnemos.core.vaultfs.VaultPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Big text files: virtualized lines, search, jump to line, follow tail, and "Edit anyway" up to 10 MB. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LargeTextScreen(session: VaultSession, path: String, showBack: Boolean, onBack: () -> Unit, onEditAnyway: (String) -> Unit) {
    val vm = graphViewModel(key = "large-${session.id}-$path") { LargeTextViewModel(session, path) }
    val state by vm.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    var jump by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.hitIndex) { state.hits.getOrNull(state.hitIndex)?.let { list.animateScrollToItem(it) } }
    LaunchedEffect(state.lineCount, state.follow) {
        if (state.follow && !state.indexing && state.lineCount > 0) list.scrollToItem(state.lineCount - 1)
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            navigationIcon = { if (showBack) IconButton(onClick = onBack) { Icon(NssIcons.Back, contentDescription = "Back") } },
            title = {
                Column {
                    Text(VaultPaths.name(path), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Read-only · ${Format.size(state.size)} · ${state.lineCount} lines", style = MaterialTheme.typography.labelSmall, color = Nss.colors.muted)
                }
            },
            actions = {
                if (state.canEdit) TextButton(onClick = { onEditAnyway(path) }) { Text("Edit anyway") }
            },
        )
        if (state.indexing) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::search,
                placeholder = { Text("Search") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (state.searching) "…" else if (state.hits.isEmpty()) "0" else "${state.hitIndex + 1}/${state.hits.size}",
                modifier = Modifier.padding(horizontal = 8.dp),
                color = Nss.colors.muted,
            )
            IconButton(onClick = { vm.nextHit(-1) }) { Icon(NssIcons.ArrowUp, contentDescription = "Previous match") }
            IconButton(onClick = { vm.nextHit(1) }) { Icon(NssIcons.ArrowDown, contentDescription = "Next match") }
        }
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = jump,
                onValueChange = { jump = it.filter(Char::isDigit) },
                placeholder = { Text("Line") },
                singleLine = true,
                modifier = Modifier.width(110.dp),
            )
            OutlinedButton(onClick = {
                jump.toIntOrNull()?.let { n -> scope.launch { list.scrollToItem((n - 1).coerceIn(0, maxOf(0, state.lineCount - 1))) } }
            }) { Text("Go") }
            FilterChip(state.follow, { vm.setFollow(!state.follow) }, label = { Text("Follow tail") }, leadingIcon = { Icon(NssIcons.Tail, null) })
        }
        state.error?.let { Text(it, color = Nss.colors.error, modifier = Modifier.padding(12.dp)) }
        val mono = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, lineHeight = 17.sp)
        val hitSet = remember(state.hits) { state.hits.toHashSet() }
        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(state.lineCount, key = { it }, contentType = { "line" }) { i ->
                // Reading revision makes visible lines re-decode after the file grew.
                val text = remember(i, state.revision) { vm.line(i) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Text("${i + 1}", style = mono, color = Nss.colors.muted, modifier = Modifier.width(56.dp))
                    Text(
                        text,
                        style = mono,
                        color = if (i in hitSet) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Images: decoded off the main thread at screen size, pinch to zoom, double-tap to reset. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageScreen(session: VaultSession, path: String, showBack: Boolean, onBack: () -> Unit) {
    val maxPx = with(LocalDensity.current) { (LocalConfiguration.current.screenWidthDp.dp.toPx() * 2).toInt() }
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = session.fs.readBytes(path)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxPx) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 8f)
        offset += pan
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(VaultPaths.name(path), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { if (showBack) IconButton(onClick = onBack) { Icon(NssIcons.Back, contentDescription = "Back") } },
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val bmp = bitmap
            if (bmp == null) {
                Text("Loading…", color = Nss.colors.muted)
            } else {
                Image(
                    bmp,
                    contentDescription = VaultPaths.name(path),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = {
                                scale = 1f
                                offset = Offset.Zero
                            })
                        }
                        .transformable(transform)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            }
        }
    }
}

/** Anything that isn't text or an image: details and "Open with…" (works offline with the phone's own apps). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BinaryScreen(session: VaultSession, path: String, showBack: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val entry by produceState<VaultEntry?>(null, path) { value = withContext(Dispatchers.IO) { runCatching { session.fs.stat(path) }.getOrNull() } }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(VaultPaths.name(path), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { if (showBack) IconButton(onClick = onBack) { Icon(NssIcons.Back, contentDescription = "Back") } },
        )
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EmptyState(NssIcons.File, VaultPaths.name(path), "This file isn't text, so Mnemos can't show it. Another app on your phone may.")
            entry?.let {
                Text("Size: ${Format.size(it.size)}", style = MaterialTheme.typography.bodyMedium)
                Text("Modified: ${Format.dateTime(it.modified)}", style = MaterialTheme.typography.bodyMedium)
                Text("Path: $path", style = MaterialTheme.typography.bodySmall, color = Nss.colors.muted)
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { scope.launch { ExternalOpen.openWith(context, session, path)?.let { snackbar.showSnackbar(it) } } }) {
                Icon(NssIcons.OpenWith, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Open with…")
            }
        }
        SnackbarHost(snackbar)
    }
}

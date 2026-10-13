package com.nihars.mnemos.feature.editor

import android.graphics.BitmapFactory
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nihars.mnemos.core.data.VaultSession
import com.nihars.mnemos.core.design.JetBrainsMono
import com.nihars.mnemos.core.design.MotionTokens
import com.nihars.mnemos.core.design.Nss
import com.nihars.mnemos.core.markdown.Align
import com.nihars.mnemos.core.markdown.Block
import com.nihars.mnemos.core.markdown.Inline
import com.nihars.mnemos.core.markdown.ListItem
import com.nihars.mnemos.core.markdown.MarkdownDoc
import com.nihars.mnemos.core.vaultfs.VaultPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Colours for inline markdown, read once per composition from the theme. */
private data class PreviewColors(val link: Color, val code: Color, val codeBg: Color, val muted: Color)

/**
 * Native markdown preview: one LazyColumn item per top-level block, keyed by the block's
 * source, so typing only recomposes the block being edited.
 */
@Composable
fun MarkdownPreview(
    doc: MarkdownDoc?,
    session: VaultSession,
    notePath: String,
    textSizeSp: Int,
    maxWidthDp: Int,
    showFrontMatter: Boolean,
    listState: LazyListState,
    onToggleTask: (Int) -> Unit,
    onOpenNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val colors = PreviewColors(cs.primary, Nss.colors.ink, cs.surfaceContainerHigh, Nss.colors.muted)
    val base = MaterialTheme.typography.bodyLarge.copy(fontSize = textSizeSp.sp, lineHeight = (textSizeSp * 1.55f).sp)
    val ctx = BlockCtx(session, notePath, colors, base, onToggleTask, onOpenNote)
    val motion = Nss.motion
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            state = listState,
            modifier = Modifier.widthIn(max = maxWidthDp.dp).fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val blocks = doc?.blocks.orEmpty().filter { showFrontMatter || it !is Block.Properties }
            items(blocks, key = { it.key }, contentType = { it.javaClass.simpleName }) { b ->
                Box(
                    Modifier.animateItem(
                        fadeInSpec = tween(if (motion.full) MotionTokens.MEDIUM else MotionTokens.SHORT),
                        placementSpec = tween(MotionTokens.MEDIUM, easing = MotionTokens.Standard),
                        fadeOutSpec = tween(MotionTokens.SHORT),
                    ),
                ) { BlockView(b, ctx) }
            }
        }
    }
}

private class BlockCtx(
    val session: VaultSession,
    val notePath: String,
    val colors: PreviewColors,
    val base: TextStyle,
    val onToggleTask: (Int) -> Unit,
    val onOpenNote: (String) -> Unit,
)

@Composable
private fun BlockView(b: Block, ctx: BlockCtx) {
    when (b) {
        is Block.Heading -> {
            val scale = when (b.level) {
                1 -> 1.6f
                2 -> 1.35f
                3 -> 1.18f
                else -> 1.05f
            }
            Text(
                inline(b.inlines, ctx),
                style = ctx.base.copy(fontSize = ctx.base.fontSize * scale, lineHeight = ctx.base.fontSize * scale * 1.3f, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(top = if (b.level <= 2) 8.dp else 4.dp),
            )
            if (b.level <= 2) HorizontalDivider(Modifier.padding(top = 6.dp), color = Nss.colors.line)
        }
        is Block.Paragraph -> {
            // A paragraph of only pictures (one per line, as Mnemos inserts them) shows them full width.
            val images = b.inlines.filterIsInstance<Inline.Image>()
            val onlyImages = images.isNotEmpty() && b.inlines.all { it is Inline.Image || it == Inline.Break || (it is Inline.Text && it.text.isBlank()) }
            if (onlyImages) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { images.forEach { ImageBlock(it, ctx) } }
            } else {
                Text(inline(b.inlines, ctx), style = ctx.base)
            }
        }
        is Block.CodeBlock -> CodeBlockView(b)
        is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primaryContainer))
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { b.blocks.forEach { BlockView(it, ctx) } }
        }
        is Block.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            b.items.forEachIndexed { i, item -> ListItemView(item, if (b.ordered) "${b.start + i}." else "•", ctx) }
        }
        is Block.Table -> TableView(b, ctx)
        is Block.Rule -> HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Nss.colors.line)
        is Block.RawHtml -> Text(b.text, style = ctx.base.copy(fontFamily = JetBrainsMono, fontSize = ctx.base.fontSize * 0.85f), color = ctx.colors.muted)
        is Block.Footnote -> Row {
            Text("[${b.label}]", style = ctx.base.copy(fontSize = ctx.base.fontSize * 0.85f), color = ctx.colors.muted)
            Spacer(Modifier.width(8.dp))
            Column { b.blocks.forEach { BlockView(it, ctx) } }
        }
        is Block.Properties -> PropertiesCard(b)
    }
}

@Composable
private fun ListItemView(item: ListItem, bullet: String, ctx: BlockCtx) {
    Row(verticalAlignment = Alignment.Top) {
        if (item.task != null) {
            // A real file write with the usual version check.
            Checkbox(checked = item.task == true, onCheckedChange = { ctx.onToggleTask(item.line) }, modifier = Modifier.height(24.dp))
        } else {
            Text(bullet, style = ctx.base, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(24.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item.blocks.forEach { child ->
                if (child is Block.Paragraph && item.task == true) {
                    Text(inline(child.inlines, ctx), style = ctx.base.copy(textDecoration = TextDecoration.LineThrough), color = ctx.colors.muted)
                } else {
                    BlockView(child, ctx)
                }
            }
        }
    }
}

@Composable
private fun CodeBlockView(b: Block.CodeBlock) {
    val ink = Nss.colors.ink
    val muted = Nss.colors.muted
    val warn = Nss.colors.warn
    val ok = Nss.colors.ok
    val err = Nss.colors.error
    val accent2 = MaterialTheme.colorScheme.secondary
    val text = remember(b.code, b.language) {
        val toks = SyntaxTokens.tokenize(b.code, Language.forFence(b.language))
        buildAnnotatedString {
            append(b.code)
            for (t in toks) {
                val style = when (t.kind) {
                    TokenKind.STRING -> SpanStyle(color = ok)
                    TokenKind.NUMBER -> SpanStyle(color = warn)
                    TokenKind.KEYWORD -> SpanStyle(color = accent2)
                    TokenKind.COMMENT -> SpanStyle(color = muted, fontStyle = FontStyle.Italic)
                    TokenKind.KEY, TokenKind.SECTION -> SpanStyle(color = ink)
                    TokenKind.LOG_ERROR -> SpanStyle(color = err)
                    TokenKind.LOG_WARN -> SpanStyle(color = warn)
                    else -> null
                } ?: continue
                if (t.end <= b.code.length) addStyle(style, t.start, t.end)
            }
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp)) {
        Text(
            text,
            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, lineHeight = 19.sp),
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(14.dp),
        )
    }
}

@Composable
private fun TableView(b: Block.Table, ctx: BlockCtx) {
    val line = Nss.colors.line
    Row(Modifier.horizontalScroll(rememberScrollState()).border(1.dp, line, RoundedCornerShape(8.dp))) {
        val cols = maxOf(b.header.size, b.rows.maxOfOrNull { it.size } ?: 0)
        for (c in 0 until cols) {
            val align = when (b.align.getOrNull(c)) {
                Align.CENTER -> TextAlign.Center
                Align.RIGHT -> TextAlign.End
                else -> TextAlign.Start
            }
            Column(Modifier.widthIn(min = 64.dp, max = 280.dp)) {
                Text(
                    inline(b.header.getOrNull(c).orEmpty(), ctx),
                    style = ctx.base.copy(fontWeight = FontWeight.SemiBold),
                    textAlign = align,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(8.dp),
                )
                b.rows.forEach { row ->
                    Text(inline(row.getOrNull(c).orEmpty(), ctx), style = ctx.base, textAlign = align, modifier = Modifier.fillMaxWidth().padding(8.dp))
                }
            }
        }
    }
}

@Composable
private fun PropertiesCard(b: Block.Properties) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Properties", style = MaterialTheme.typography.labelMedium, color = Nss.colors.muted)
            b.properties.forEach { (k, v) ->
                Row {
                    Text(k, style = MaterialTheme.typography.bodySmall, color = Nss.colors.muted, modifier = Modifier.width(110.dp))
                    Text(v, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * Vault images — relative links and Obsidian ![[embeds]] — decoded off the main thread with
 * downsampling. Tap for full screen. Remote images can't load: the app is offline by design.
 */
@Composable
private fun ImageBlock(img: Inline.Image, ctx: BlockCtx) {
    val dest = img.destination
    if (dest.startsWith("http://") || dest.startsWith("https://")) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp)) {
            Text("Remote image (offline): ${img.alt.ifEmpty { dest }}", style = MaterialTheme.typography.bodySmall, color = ctx.colors.muted, modifier = Modifier.padding(12.dp))
        }
        return
    }
    val bitmap by produceState<ImageBitmap?>(null, dest, img.embed) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val rel = if (img.embed) resolveEmbed(ctx.session, ctx.notePath, dest) else resolveRelative(ctx.notePath, dest.removePrefix("file:"))
                rel?.let { decodeImage(ctx.session.fs.readBytes(it), MAX_PREVIEW_PX) }
            }.getOrNull()
        }
    }
    var fullScreen by remember { mutableStateOf(false) }
    val bmp = bitmap
    if (bmp == null) {
        Text("🖼 ${img.alt.ifEmpty { dest }}", style = MaterialTheme.typography.bodySmall, color = ctx.colors.muted)
    } else {
        val size = img.width?.let { Modifier.widthIn(max = it.dp) } ?: Modifier
        Image(
            bmp,
            contentDescription = img.alt.ifEmpty { null },
            contentScale = ContentScale.FillWidth,
            modifier = size.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = "Show full screen") { fullScreen = true },
        )
        if (fullScreen) ImageViewer(bmp, img.alt, onDismiss = { fullScreen = false })
    }
}

// Wide enough for a tablet column; bigger photos are decoded at a fraction of their size.
private const val MAX_PREVIEW_PX = 1600

/** Decodes [bytes] at the smallest power-of-two scale still at least [maxPx] wide. */
private fun decodeImage(bytes: ByteArray, maxPx: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxPx) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

/**
 * Finds the file an Obsidian embed means, as Obsidian and the web app do: from the note's
 * folder, its attachments/ folder, the vault root, then any file of that name — the one
 * closest to the note wins.
 */
internal fun resolveEmbed(session: VaultSession, notePath: String, target: String): String? {
    val dir = VaultPaths.parent(notePath)
    val candidates = listOf(VaultPaths.join(dir, target), VaultPaths.join(VaultPaths.join(dir, "attachments"), target), target)
    for (c in candidates) {
        val p = runCatching { normalizeDots(c) }.getOrNull() ?: continue
        if (p.isNotEmpty() && !p.startsWith("..") && runCatching { session.fs.exists(p) }.getOrDefault(false)) return p
    }
    val name = VaultPaths.name(target)
    return session.index.byName(name)
        .map { it.path }
        .filter { it == target || it.endsWith("/$target") }
        .minWithOrNull(compareBy<String>({ -sharedFolders(VaultPaths.parent(it), dir) }, { it.length }, { it }))
}

// How many leading folders two folder paths have in common.
private fun sharedFolders(a: String, b: String): Int {
    if (a.isEmpty() || b.isEmpty()) return 0
    val x = a.split('/')
    val y = b.split('/')
    var n = 0
    while (n < x.size && n < y.size && x[n] == y[n]) n++
    return n
}

/** A picture on its own, full screen: pinch to zoom, drag to pan, double-tap to reset, tap to close. */
@Composable
private fun ImageViewer(bitmap: ImageBitmap, alt: String, onDismiss: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
        offset = if (scale == 1f) Offset.Zero else offset + pan * scale
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onDismiss() },
                        onDoubleTap = {
                            scale = 1f
                            offset = Offset.Zero
                        },
                    )
                }
                .transformable(transform),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap,
                contentDescription = alt.ifEmpty { null },
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            )
        }
    }
}

private const val MAX_ZOOM = 6f

/** Resolves a link relative to the note's folder; leading "/" means vault root. */
fun resolveRelative(notePath: String, target: String): String {
    val clean = java.net.URLDecoder.decode(target.substringBefore('#').substringBefore('?'), "UTF-8")
    return if (clean.startsWith("/")) VaultPaths.normalize(clean) else normalizeDots(VaultPaths.join(VaultPaths.parent(notePath), clean))
}

// Resolves "a/../b" inside the vault; anything climbing out of it stays rejected later by VaultFs.
private fun normalizeDots(path: String): String {
    val out = ArrayList<String>()
    for (p in path.split('/')) {
        when (p) {
            "", "." -> Unit
            ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex) else out += ".."
            else -> out += p
        }
    }
    return out.joinToString("/")
}

private fun inline(list: List<Inline>, ctx: BlockCtx): AnnotatedString = buildAnnotatedString { appendInlines(list, ctx) }

private fun AnnotatedString.Builder.appendInlines(list: List<Inline>, ctx: BlockCtx) {
    for (i in list) {
        when (i) {
            is Inline.Text -> append(i.text)
            Inline.Break -> append('\n')
            is Inline.Code -> withStyle(SpanStyle(fontFamily = JetBrainsMono, color = ctx.colors.code, background = ctx.colors.codeBg)) { append(i.text) }
            is Inline.Emph -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInlines(i.children, ctx) }
            is Inline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInlines(i.children, ctx) }
            is Inline.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInlines(i.children, ctx) }
            is Inline.Image -> append("🖼 ${i.alt}")
            is Inline.FootnoteRef -> withStyle(SpanStyle(color = ctx.colors.link, fontSize = ctx.base.fontSize * 0.75f)) { append("[${i.label}]") }
            is Inline.Link -> {
                val styles = TextLinkStyles(SpanStyle(color = ctx.colors.link, textDecoration = TextDecoration.Underline))
                val dest = i.destination
                val external = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(dest)
                val link = if (external) {
                    LinkAnnotation.Url(dest, styles)
                } else {
                    // Relative links to other notes open inside the app.
                    LinkAnnotation.Clickable(dest, styles) { ctx.onOpenNote(resolveRelative(ctx.notePath, dest)) }
                }
                withLink(link) { appendInlines(i.children, ctx) }
            }
        }
    }
}

/** Scrolls the preview to the block at or above [line] (split-view scroll sync). */
@Composable
fun SyncPreviewScroll(doc: MarkdownDoc?, line: Int, state: LazyListState, enabled: Boolean) {
    LaunchedEffect(line, doc, enabled) {
        if (!enabled || doc == null) return@LaunchedEffect
        val index = doc.blocks.indexOfLast { it.line <= line }.coerceAtLeast(0)
        if (index < state.layoutInfo.totalItemsCount) state.animateScrollToItem(index)
    }
}

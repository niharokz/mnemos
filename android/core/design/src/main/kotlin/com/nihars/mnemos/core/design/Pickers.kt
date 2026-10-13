package com.nihars.mnemos.core.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Ready-made colours offered for each slot; any "#rrggbb" can also be typed. */
object Swatches {
    val darkBackgrounds = listOf(0xFF070B0F, 0xFF000000, 0xFF0D1117, 0xFF121212, 0xFF1A1B26, 0xFF101820, 0xFF1C1917, 0xFF0F172A, 0xFF14120F, 0xFF0B1A14)
    val lightBackgrounds = listOf(0xFFF6F8F8, 0xFFFFFFFF, 0xFFFAF7F0, 0xFFF4F1EA, 0xFFEEF2F7, 0xFFF3F0FA, 0xFFEFF6EF, 0xFFFFF8E7, 0xFFF1F5F9, 0xFFFDF2F4)
    val darkSurfaces = listOf(0xFF0F161B, 0xFF0A0F12, 0xFF161B22, 0xFF1E1E1E, 0xFF24283B, 0xFF1B2733, 0xFF292524, 0xFF1E293B, 0xFF1F1B16, 0xFF12261D)
    val lightSurfaces = listOf(0xFFFFFFFF, 0xFFEAF1F0, 0xFFFFFDF8, 0xFFFAF8F3, 0xFFF8FAFC, 0xFFFAF8FF, 0xFFF7FBF7, 0xFFFFFCF2, 0xFFE2E8F0, 0xFFFFF7F8)
    val darkTexts = listOf(0xFFE6ECEC, 0xFFFFFFFF, 0xFFE5E7EB, 0xFFF5F5F4, 0xFFC0CAF5, 0xFFDDE6ED, 0xFFF1E9DC, 0xFFCBD5E1)
    val lightTexts = listOf(0xFF14201F, 0xFF000000, 0xFF1F2937, 0xFF292524, 0xFF1E1B4B, 0xFF0F172A, 0xFF3F2E1E, 0xFF14532D)
    val accents = listOf(
        0xFF3A807A, 0xFF2E7D32, 0xFF0B7285, 0xFF1D4ED8, 0xFF4F46E5, 0xFF7C3AED, 0xFFA21CAF, 0xFFBE185D,
        0xFFDC2626, 0xFFEA580C, 0xFFCA8A04, 0xFF65A30D, 0xFF0D9488, 0xFF475569, 0xFF8D6E63, 0xFF111827,
    )
}

/**
 * Colour picker: swatches, a hex field, a live preview and "Use default". Mnemos keeps text
 * readable whatever is picked (see NssTokens.custom), so no choice can hide the text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPickerDialog(
    title: String,
    current: String,
    swatches: List<Long>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var hex by rememberSaveable { mutableStateOf(current) }
    val parsed = NssTokens.parseHex(hex)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    swatches.forEach { c ->
                        val selected = parsed == c
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Nss.colors.line, CircleShape)
                                .clickable { hex = NssTokens.toHex(c) }
                                .semantics { contentDescription = "Colour ${NssTokens.toHex(c)}" },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { hex = it.trim().take(7) },
                        label = { Text("Hex, like #3a807a") },
                        singleLine = true,
                        isError = hex.isNotEmpty() && parsed == null,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(parsed?.let { Color(it) } ?: Color.Transparent)
                            .border(1.dp, Nss.colors.line, RoundedCornerShape(12.dp)),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(if (parsed != null) NssTokens.toHex(parsed) else "") }, enabled = hex.isEmpty() || parsed != null) { Text("Use") } },
        dismissButton = {
            Row {
                TextButton(onClick = { onPick("") }) { Text("Use default") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Pick a folder of the vault ("" is the vault root). */
@Composable
fun FolderPickerDialog(title: String, folders: List<String>, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(listOf("") + folders, key = { "f:$it" }) { f ->
                    val on = f == current
                    Surface(
                        onClick = { onPick(f) },
                        shape = MaterialTheme.shapes.small,
                        color = if (on) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                        border = if (on) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(NssIcons.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(if (f.isEmpty()) "Vault root" else f, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Logo colour: only the presets, because each has a matching launcher icon. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogoColorDialog(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val selected = LogoColors.presetFor(current)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Logo colour") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Changes the logo in the app and the icon on your home screen.", style = MaterialTheme.typography.bodySmall, color = Nss.colors.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LogoColors.presets.forEach { p ->
                        val on = p == selected
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp).clickable { onPick(p.hex) }) {
                            val fill = if (p.hex == LogoColors.TRANSPARENT) Color.Transparent else Color(NssTokens.parseHex(p.hex) ?: NssTokens.TEAL)
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(fill)
                                    .border(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else Nss.colors.line, RoundedCornerShape(12.dp))
                                    .semantics { contentDescription = p.label },
                                contentAlignment = Alignment.Center,
                            ) {
                                androidx.compose.foundation.Image(
                                    MnemosLogo.vector(MnemosLogo.colorsFor(if (fill == Color.Transparent) null else fill)),
                                    contentDescription = null,
                                    modifier = Modifier.size(30.dp),
                                )
                            }
                            Text(p.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

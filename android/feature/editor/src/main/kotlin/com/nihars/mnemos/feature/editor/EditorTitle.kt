package com.nihars.mnemos.feature.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nihars.mnemos.core.design.Nss

/**
 * The file name in the editor's top bar. Tap it to rename in place: the name is selected, the
 * extension stays fixed after it, Done (or tapping elsewhere) renames, Esc cancels.
 */
@Composable
fun EditorTitle(
    path: String,
    editing: Boolean,
    onEditingChange: (Boolean) -> Unit,
    enabled: Boolean,
    onRename: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = MaterialTheme.typography.titleMedium
    val stem = FileRename.editable(path)
    val suffix = FileRename.suffix(path)
    if (!editing) {
        Text(
            stem + suffix,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = style,
            modifier = modifier
                .clickable(enabled = enabled, onClickLabel = "Rename") { onEditingChange(true) }
                .semantics { contentDescription = "File name ${stem + suffix}. Tap to rename." },
        )
        return
    }
    var value by remember(path) { mutableStateOf(TextFieldValue(stem, TextRange(0, stem.length))) }
    val focus = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    // Leaving composition also drops focus; this makes Done, Enter, Esc and focus loss act once.
    var finished by remember { mutableStateOf(false) }
    fun finish(rename: Boolean) {
        if (finished) return
        finished = true
        onEditingChange(false)
        if (rename) onRename(value.text)
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f, fill = false).widthIn(min = 48.dp)) {
                BasicTextField(
                    value = value,
                    onValueChange = { v -> value = v.copy(text = v.text.replace("\n", "")) },
                    singleLine = true,
                    textStyle = style.copy(color = Nss.colors.ink),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onDone = { finish(rename = true) }),
                    modifier = Modifier
                        .focusRequester(focus)
                        .onFocusChanged {
                            if (it.isFocused) hadFocus = true else if (hadFocus) finish(rename = true)
                        }
                        .onPreviewKeyEvent {
                            if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (it.key) {
                                Key.Escape -> { finish(rename = false); true }
                                Key.Enter, Key.NumPadEnter -> { finish(rename = true); true }
                                else -> false
                            }
                        }
                        .semantics { contentDescription = "New file name" },
                )
            }
            if (suffix.isNotEmpty()) {
                Spacer(Modifier.width(2.dp))
                Text(suffix, style = style, color = Nss.colors.muted, maxLines = 1)
            }
        }
    }
}

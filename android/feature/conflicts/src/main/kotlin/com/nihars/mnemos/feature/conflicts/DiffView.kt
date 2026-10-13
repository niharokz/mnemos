package com.nihars.mnemos.feature.conflicts

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nihars.mnemos.core.design.JetBrainsMono
import com.nihars.mnemos.core.design.Nss
import com.nihars.mnemos.core.text.DiffOp
import com.nihars.mnemos.core.text.DiffRow
import com.nihars.mnemos.core.text.DiffRun

/** Side-by-side, line-level diff with word-level highlights on changed lines. */
@Composable
fun DiffView(rows: List<DiffRow>, leftTitle: String, rightTitle: String, modifier: Modifier = Modifier) {
    val removed = Nss.colors.error
    val added = Nss.colors.ok
    Column(modifier) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(8.dp)) {
            Text(leftTitle, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = removed)
            Text(rightTitle, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = added)
        }
        val scroll = rememberScrollState()
        LazyColumn(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
            itemsIndexed(rows) { _, row ->
                Row(Modifier.width(IntrinsicSize.Max).height(IntrinsicSize.Min)) {
                    Cell(row.left, row.leftWords, row.changed, removed, DiffOp.DELETE)
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Nss.colors.line))
                    Cell(row.right, row.rightWords, row.changed, added, DiffOp.INSERT)
                }
            }
        }
    }
}

@Composable
private fun Cell(text: String?, words: List<DiffRun<String>>, changed: Boolean, tone: Color, op: DiffOp) {
    val bg = when {
        text == null -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
        changed -> tone.copy(alpha = 0.10f)
        else -> Color.Transparent
    }
    Text(
        styled(text, words, tone, op),
        modifier = Modifier.width(360.dp).fillMaxHeight().background(bg).padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono, fontSize = 12.sp),
    )
}

private fun styled(text: String?, words: List<DiffRun<String>>, tone: Color, op: DiffOp): AnnotatedString {
    if (text == null) return AnnotatedString("")
    if (words.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        for (w in words) {
            val s = w.items.joinToString("")
            if (w.op == op) withStyle(SpanStyle(background = tone.copy(alpha = 0.35f), fontWeight = FontWeight.Bold)) { append(s) } else append(s)
        }
    }
}

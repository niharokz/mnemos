package com.nihars.mnemos.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.nihars.mnemos.core.data.AppGraph
import com.nihars.mnemos.core.data.Capture
import com.nihars.mnemos.core.data.UserKind
import com.nihars.mnemos.core.design.BrandMark
import com.nihars.mnemos.core.design.Nss
import com.nihars.mnemos.core.design.MnemosTheme
import com.nihars.mnemos.core.security.LockState
import com.nihars.mnemos.core.vaultfs.SaveCoordinator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * Quick capture from the share sheet, the tile, the widget or the shortcut. Appending is allowed
 * while locked (if the setting is on) because it can't reveal anything; "new note" needs unlocking.
 */
class CaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val g = graph
        // Shared from another app: save straight to the share file, no sheet, then get out of the way.
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            lifecycleScope.launch {
                val result = ShareReceiver.save(this@CaptureActivity, g, intent)
                Toast.makeText(this@CaptureActivity, result.message, Toast.LENGTH_SHORT).show()
                if (result.needsUnlock) {
                    startActivity(Intent(this@CaptureActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                finish()
            }
            return
        }
        setContent {
            MnemosTheme {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    CaptureSheet(g, "", onDone = { finish() }, onOpenApp = { action ->
                        startActivity(Intent(this, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    })
                }
            }
        }
    }
}

@Composable
private fun CaptureSheet(graph: AppGraph, initial: String, onDone: () -> Unit, onOpenApp: (String?) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun append() {
        busy = true
        scope.launch {
            val security = graph.lockStore.current()
            if (security.pin == null) {
                message = "Set up Mnemos first."
                busy = false
                return@launch
            }
            val locked = graph.lock.state.value != LockState.UNLOCKED
            if (locked && !security.captureWhileLocked) {
                onOpenApp(null)
                return@launch
            }
            val session = graph.session.value?.takeIf { it.kind == UserKind.OWNER }
            val result = runCatching {
                if (session != null) {
                    session.capture(text, LocalDateTime.now())
                } else {
                    val fs = graph.ownerFs().getOrThrow()
                    Capture.append(fs, SaveCoordinator(fs), graph.ownerSettings.settings.first(), text, LocalDateTime.now())
                }
            }
            busy = false
            result.onSuccess {
                graph.onVaultChanged()
                onDone()
            }.onFailure { message = it.message ?: "Couldn't save." }
        }
    }

    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BrandMark(style = MaterialTheme.typography.titleMedium, animate = false)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Jot something down…") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).focusRequester(focus),
        )
        message?.let { Text(it, color = Nss.colors.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = ::append, enabled = text.isNotBlank() && !busy) { Text("Append") }
            // A new note opens the app, which asks for the PIN if locked.
            OutlinedButton(onClick = {
                scope.launch {
                    if (graph.lock.state.value == LockState.UNLOCKED && graph.session.value?.kind == UserKind.OWNER) {
                        val s = graph.session.value ?: return@launch
                        runCatching { s.createNote(s.settings.value.newNoteFolder, text.lineSequence().first().take(60), text) }
                            .onSuccess { onDone() }
                            .onFailure { message = it.message }
                    } else {
                        onOpenApp(MainActivity.ACTION_NEW_NOTE)
                    }
                }
            }, enabled = text.isNotBlank() && !busy) { Text("Save as new note…") }
            TextButton(onClick = onDone) { Text("Cancel") }
        }
    }
}

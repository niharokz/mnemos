package com.nihars.mnemos.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nihars.mnemos.app.graph
import com.nihars.mnemos.core.design.Nss
import com.nihars.mnemos.core.design.MnemosTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Picks the note for a Note preview or Open note widget. Lists text files from the owner's vault. */
class NotePreviewConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val g = graph
        setContent {
            MnemosTheme {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    var filter by remember { mutableStateOf("") }
                    val files by produceState<List<String>?>(null) {
                        value = withContext(Dispatchers.IO) {
                            val fs = g.ownerFs().getOrNull() ?: return@withContext null
                            runCatching { fs.walk().filter { !it.isDirectory && WidgetPrefs.isNote(it.name) }.map { it.path }.sorted() }.getOrDefault(emptyList())
                        }
                    }
                    Column(Modifier.padding(16.dp)) {
                        Text("Choose a note", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(filter, { filter = it }, placeholder = { Text("Filter") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        val list = files
                        if (list == null) {
                            Text("Open Mnemos and choose your vault first.", color = Nss.colors.muted, modifier = Modifier.padding(top = 12.dp))
                        } else {
                            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                                items(list.filter { it.contains(filter, ignoreCase = true) }.take(300)) { p ->
                                    Text(
                                        p,
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            WidgetPrefs.set(this@NotePreviewConfigActivity, id, p)
                                            WidgetUpdater.updateAll(this@NotePreviewConfigActivity)
                                            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                                            finish()
                                        }.padding(vertical = 10.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

package com.nihars.mnemos.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.nihars.mnemos.R
import com.nihars.mnemos.app.CaptureActivity
import com.nihars.mnemos.app.MainActivity
import com.nihars.mnemos.app.graph
import com.nihars.mnemos.core.data.FileKind
import com.nihars.mnemos.core.data.FileKinds
import com.nihars.mnemos.core.data.Format
import com.nihars.mnemos.core.vaultfs.VaultEntry
import com.nihars.mnemos.core.vaultfs.VaultFs
import com.nihars.mnemos.core.security.LockState
import com.nihars.mnemos.core.text.TextCodec
import com.nihars.mnemos.core.text.TextDetector
import com.nihars.mnemos.core.vaultfs.VaultPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.core.content.edit

/** Home-screen widgets as plain RemoteViews (no Glance), rendered on a background thread. */
object WidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun updateAll(context: Context) {
        val app = context.applicationContext
        val mgr = AppWidgetManager.getInstance(app)
        fun ids(cls: Class<*>) = mgr.getAppWidgetIds(ComponentName(app, cls))
        QuickCaptureWidget.render(app, mgr, ids(QuickCaptureWidget::class.java))
        NewNoteWidget.render(app, mgr, ids(NewNoteWidget::class.java))
        OpenNoteWidget.render(app, mgr, ids(OpenNoteWidget::class.java))
        scope.launch {
            PinnedNotesWidget.render(app, mgr, ids(PinnedNotesWidget::class.java))
            NotePreviewWidget.render(app, mgr, ids(NotePreviewWidget::class.java))
            QuickActionsWidget.render(app, mgr, ids(QuickActionsWidget::class.java))
            RecentNotesWidget.render(app, mgr, ids(RecentNotesWidget::class.java))
        }
    }

    /** Hides note content while locked when widget privacy is on (spec §5.7). */
    suspend fun contentHidden(context: Context): Boolean {
        val g = context.graph
        val sec = g.lockStore.current()
        return sec.widgetPrivacy && sec.pin != null && g.lock.state.value != LockState.UNLOCKED
    }

    /**
     * Opens exactly [path]. The path is also the intent's data, so every note gets its own
     * PendingIntent: two widgets can never overwrite each other's target.
     */
    fun openIntent(context: Context, path: String, requestCode: Int): PendingIntent {
        val i = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN)
            .setData(Uri.Builder().scheme("mnemos").authority("open").appendPath(path).build())
            .putExtra(MainActivity.EXTRA_PATH, path)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** "projects · 2 h ago" under a note's name; names and times only, never content. */
    fun meta(path: String, e: VaultEntry?): String =
        listOf(VaultPaths.parent(path).ifEmpty { "~" }, e?.let { Format.relative(it.modified) }.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · ")

    /** One card row for a list widget; the tap fills in its path on the list's template. */
    fun noteRow(context: Context, fs: VaultFs?, path: String): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_note_item).apply {
            setTextViewText(R.id.note_title, VaultPaths.stem(VaultPaths.name(path)))
            setTextViewText(R.id.note_meta, meta(path, fs?.let { runCatching { it.stat(path) }.getOrNull() }))
            setOnClickFillInIntent(R.id.note_item, Intent().putExtra(MainActivity.EXTRA_PATH, path))
        }

    internal val scopeForReceivers get() = scope
}

/** 1×1 shows the pen; wider shows "Quick capture". Opens the capture sheet. */
class QuickCaptureWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) = render(context, mgr, ids)

    companion object {
        fun render(context: Context, mgr: AppWidgetManager, ids: IntArray) {
            val pi = PendingIntent.getActivity(
                context, 1,
                Intent(context, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            fun views(layout: Int) = RemoteViews(context.packageName, layout).apply { setOnClickPendingIntent(android.R.id.background, pi) }
            // Size-mapped layouts (API 31): the launcher picks the one that fits.
            val mapped = RemoteViews(mapOf(SizeF(40f, 40f) to views(R.layout.widget_capture_small), SizeF(140f, 40f) to views(R.layout.widget_capture)))
            ids.forEach { mgr.updateAppWidget(it, mapped) }
        }
    }
}

/** The owner's pinned notes; names and times only, so they show even while locked. */
class PinnedNotesWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        WidgetUpdater.scopeForReceivers.launch {
            try {
                render(context, mgr, ids)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        suspend fun render(context: Context, mgr: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val pins = context.graph.ownerSettings.pins.first()
            val fs = context.graph.ownerFs().getOrNull()
            val views = RemoteViews(context.packageName, R.layout.widget_pinned)
            val items = RemoteViews.RemoteCollectionItems.Builder().setViewTypeCount(1).setHasStableIds(true)
            pins.forEach { p -> items.addItem(p.hashCode().toLong(), WidgetUpdater.noteRow(context, fs, p)) }
            views.setRemoteAdapter(R.id.pinned_list, items.build())
            views.setViewVisibility(R.id.pinned_empty, if (pins.isEmpty()) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.pinned_list, if (pins.isEmpty()) View.GONE else View.VISIBLE)
            views.setTextViewText(R.id.pinned_count, if (pins.isEmpty()) "" else pins.size.toString())
            views.setOnClickPendingIntent(R.id.pinned_new, WidgetIntents.app(context, MainActivity.ACTION_NEW_NOTE, 25))
            views.setPendingIntentTemplate(R.id.pinned_list, WidgetIntents.openTemplate(context, 2))
            ids.forEach { mgr.updateAppWidget(it, views) }
        }
    }
}

/** First lines of one chosen note (e.g. today's daily note). Content hides while locked if set. */
class NotePreviewWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        WidgetUpdater.scopeForReceivers.launch {
            try {
                render(context, mgr, ids)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDeleted(context: Context, ids: IntArray) = ids.forEach { WidgetPrefs.clear(context, it) }

    companion object {
        suspend fun render(context: Context, mgr: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val hidden = WidgetUpdater.contentHidden(context)
            val fs = context.graph.ownerFs().getOrNull()
            for (id in ids) {
                val path = WidgetPrefs.path(context, id)
                val views = RemoteViews(context.packageName, R.layout.widget_preview)
                if (path == null || fs == null) {
                    views.setTextViewText(R.id.preview_title, context.getString(R.string.widget_preview))
                    views.setTextViewText(R.id.preview_meta, "")
                    views.setTextViewText(R.id.preview_body, context.getString(R.string.widget_setup))
                } else {
                    views.setTextViewText(R.id.preview_title, VaultPaths.stem(VaultPaths.name(path)))
                    views.setTextViewText(R.id.preview_meta, WidgetUpdater.meta(path, runCatching { fs.stat(path) }.getOrNull()))
                    val body = if (hidden) {
                        context.getString(R.string.widget_locked)
                    } else {
                        runCatching {
                            val head = fs.readHead(path, 4096)
                            if (TextDetector.detect(head) == null) "" else TextCodec.decode(head).text.lines().let(::readable).take(12).joinToString("\n")
                        }.getOrDefault("")
                    }
                    views.setTextViewText(R.id.preview_body, body)
                    views.setOnClickPendingIntent(android.R.id.background, WidgetUpdater.openIntent(context, path, 100 + id))
                }
                mgr.updateAppWidget(id, views)
            }
        }
    }
}

/** Note lines as a widget shows them: front matter dropped, heading marks and tasks tidied. */
internal fun readable(lines: List<String>): List<String> {
    var body = lines
    if (body.firstOrNull()?.trim() == "---") {
        val end = body.drop(1).indexOfFirst { it.trim() == "---" }
        if (end >= 0) body = body.drop(end + 2)
    }
    return body.filter { it.isNotBlank() }.map { l ->
        l.replace(Regex("^#{1,6}\\s+"), "").replace(Regex("^(\\s*)[-*] \\[ \\]"), "$1☐").replace(Regex("^(\\s*)[-*] \\[[xX]\\]"), "$1☑").replace(Regex("^(\\s*)[-*] "), "$1• ")
    }
}

/** Which note each preview widget shows; app-private, never in the vault. */
object WidgetPrefs {
    private fun prefs(c: Context) = c.getSharedPreferences("widgets", Context.MODE_PRIVATE)
    fun path(c: Context, id: Int): String? = prefs(c).getString("preview_$id", null)
    fun set(c: Context, id: Int, path: String) = prefs(c).edit { putString("preview_$id", path) }
    fun clear(c: Context, id: Int) = prefs(c).edit { remove("preview_$id") }

    /** A note was renamed or moved in the app: every widget showing [from] now shows [to]. */
    fun renamed(c: Context, from: String, to: String) {
        val p = prefs(c)
        // A renamed folder moves every note inside it too.
        val moved = p.all.mapNotNull { (k, v) ->
            val old = v as? String ?: return@mapNotNull null
            when {
                !k.startsWith("preview_") -> null
                old == from -> k to to
                old.startsWith("$from/") -> k to to + old.substring(from.length)
                else -> null
            }
        }
        if (moved.isEmpty()) return
        p.edit { moved.forEach { (k, v) -> putString(k, v) } }
        WidgetUpdater.updateAll(c)
    }

    /** Text files a preview widget can show (the config screen lists only these). */
    internal fun isNote(name: String) = FileKinds.viewKey(name) == "md" || FileKinds.guess(name) == FileKind.TEXT
}

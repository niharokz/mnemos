package com.nihars.mnemos.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.nihars.mnemos.R
import com.nihars.mnemos.app.CaptureActivity
import com.nihars.mnemos.app.MainActivity
import com.nihars.mnemos.app.graph
import com.nihars.mnemos.core.data.FileKind
import com.nihars.mnemos.core.data.FileKinds
import com.nihars.mnemos.core.security.LockState
import com.nihars.mnemos.core.text.TextCodec
import com.nihars.mnemos.core.text.TextDetector
import com.nihars.mnemos.core.vaultfs.VaultPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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

    fun openIntent(context: Context, path: String, requestCode: Int): PendingIntent {
        val i = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN)
            .putExtra(MainActivity.EXTRA_PATH, path)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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

/** The owner's pinned notes; titles only, so they show even while locked. */
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
            val views = RemoteViews(context.packageName, R.layout.widget_pinned)
            val items = RemoteViews.RemoteCollectionItems.Builder().setViewTypeCount(1)
            pins.forEachIndexed { i, p ->
                val row = RemoteViews(context.packageName, R.layout.widget_pinned_item).apply {
                    setTextViewText(R.id.pinned_item, VaultPaths.name(p).removeSuffix(".md"))
                    setOnClickFillInIntent(R.id.pinned_item, Intent().putExtra(MainActivity.EXTRA_PATH, p))
                }
                items.addItem(i.toLong(), row)
            }
            views.setRemoteAdapter(R.id.pinned_list, items.build())
            views.setViewVisibility(R.id.pinned_empty, if (pins.isEmpty()) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.pinned_list, if (pins.isEmpty()) View.GONE else View.VISIBLE)
            // Template intent; each row fills in its path. Mutable so the fill-in can be merged.
            val template = Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            views.setPendingIntentTemplate(R.id.pinned_list, PendingIntent.getActivity(context, 2, template, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
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
                    views.setTextViewText(R.id.preview_body, context.getString(R.string.widget_setup))
                } else {
                    views.setTextViewText(R.id.preview_title, VaultPaths.name(path).removeSuffix(".md"))
                    val body = if (hidden) {
                        context.getString(R.string.widget_locked)
                    } else {
                        runCatching {
                            val head = fs.readHead(path, 4096)
                            if (TextDetector.detect(head) == null) "" else TextCodec.decode(head).text.lines().filter { it.isNotBlank() }.take(12).joinToString("\n")
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

/** Which note each preview widget shows; app-private, never in the vault. */
object WidgetPrefs {
    private fun prefs(c: Context) = c.getSharedPreferences("widgets", Context.MODE_PRIVATE)
    fun path(c: Context, id: Int): String? = prefs(c).getString("preview_$id", null)
    fun set(c: Context, id: Int, path: String) = prefs(c).edit().putString("preview_$id", path).apply()
    fun clear(c: Context, id: Int) = prefs(c).edit().remove("preview_$id").apply()

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
        p.edit().apply { moved.forEach { (k, v) -> putString(k, v) } }.apply()
        WidgetUpdater.updateAll(c)
    }

    /** Text files a preview widget can show (the config screen lists only these). */
    internal fun isNote(name: String) = FileKinds.viewKey(name) == "md" || FileKinds.guess(name) == FileKind.TEXT
}

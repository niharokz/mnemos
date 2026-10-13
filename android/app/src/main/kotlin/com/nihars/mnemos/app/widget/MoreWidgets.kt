package com.nihars.mnemos.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.nihars.mnemos.R
import com.nihars.mnemos.app.CaptureActivity
import com.nihars.mnemos.app.MainActivity
import com.nihars.mnemos.app.graph
import com.nihars.mnemos.core.data.UserKind
import com.nihars.mnemos.core.index.IndexDb
import com.nihars.mnemos.core.vaultfs.VaultPaths
import kotlinx.coroutines.launch

/** Intents shared by the widgets: open the app with an action, or open one note. */
internal object WidgetIntents {
    fun app(context: Context, action: String, requestCode: Int): PendingIntent {
        val i = Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun capture(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(context, requestCode, Intent(context, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)

    /** Template for list rows; each row fills in its note path. */
    fun openTemplate(context: Context, requestCode: Int): PendingIntent {
        val i = Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, requestCode, i, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

/** 1×1 (icon) or wider (icon + label): a new note in the default folder, opened at once. */
class NewNoteWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) = render(context, mgr, ids)

    companion object {
        fun render(context: Context, mgr: AppWidgetManager, ids: IntArray) {
            val pi = WidgetIntents.app(context, MainActivity.ACTION_NEW_NOTE, 10)
            fun v(layout: Int) = RemoteViews(context.packageName, layout).apply { setOnClickPendingIntent(android.R.id.background, pi) }
            val views = RemoteViews(mapOf(SizeF(40f, 40f) to v(R.layout.widget_new_note_small), SizeF(120f, 40f) to v(R.layout.widget_new_note)))
            ids.forEach { mgr.updateAppWidget(it, views) }
        }
    }
}

/** A shortcut to one chosen note: 1×1 or 1×2 shows icon over name, 2×1 icon beside it. */
class OpenNoteWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) = render(context, mgr, ids)

    override fun onDeleted(context: Context, ids: IntArray) = ids.forEach { WidgetPrefs.clear(context, it) }

    companion object {
        fun render(context: Context, mgr: AppWidgetManager, ids: IntArray) {
            for (id in ids) {
                val path = WidgetPrefs.path(context, id)
                fun v(layout: Int) = RemoteViews(context.packageName, layout).apply {
                    if (path == null) {
                        setTextViewText(R.id.open_title, context.getString(R.string.widget_setup))
                    } else {
                        // Names only, so the widget never shows note content.
                        setTextViewText(R.id.open_title, VaultPaths.name(path).removeSuffix(".md"))
                        setOnClickPendingIntent(android.R.id.background, WidgetUpdater.openIntent(context, path, 200 + id))
                    }
                }
                val views = RemoteViews(mapOf(SizeF(40f, 40f) to v(R.layout.widget_open_note), SizeF(120f, 40f) to v(R.layout.widget_open_note_wide)))
                mgr.updateAppWidget(id, views)
            }
        }
    }
}

/** New note, Quick capture, Search and the last opened note: 2×2 grid, 4×1 row, or 1×2 (first two). */
class QuickActionsWidget : AppWidgetProvider() {
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
            val last = context.graph.ownerSettings.lastOpen()
            fun v(layout: Int) = RemoteViews(context.packageName, layout).apply {
                setOnClickPendingIntent(R.id.act_new, WidgetIntents.app(context, MainActivity.ACTION_NEW_NOTE, 20))
                setOnClickPendingIntent(R.id.act_capture, WidgetIntents.capture(context, 21))
                if (layout != R.layout.widget_actions_col) {
                    setOnClickPendingIntent(R.id.act_search, WidgetIntents.app(context, MainActivity.ACTION_SEARCH, 22))
                    // Last note: opens it, or just the app when nothing has been opened yet.
                    setOnClickPendingIntent(
                        R.id.act_last,
                        if (last != null) WidgetUpdater.openIntent(context, last, 23) else WidgetIntents.app(context, Intent.ACTION_MAIN, 24),
                    )
                }
            }
            val views = RemoteViews(
                mapOf(
                    SizeF(60f, 110f) to v(R.layout.widget_actions_col),
                    SizeF(110f, 110f) to v(R.layout.widget_actions),
                    SizeF(250f, 50f) to v(R.layout.widget_actions_row),
                ),
            )
            ids.forEach { mgr.updateAppWidget(it, views) }
        }
    }
}

/** Notes opened or changed lately, newest first; names only. */
class RecentNotesWidget : AppWidgetProvider() {
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
        private const val LIMIT = 12

        suspend fun render(context: Context, mgr: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val paths = recent(context)
            val views = RemoteViews(context.packageName, R.layout.widget_recent)
            val items = RemoteViews.RemoteCollectionItems.Builder().setViewTypeCount(1)
            paths.forEachIndexed { i, p ->
                val row = RemoteViews(context.packageName, R.layout.widget_pinned_item).apply {
                    setTextViewText(R.id.pinned_item, VaultPaths.name(p).removeSuffix(".md"))
                    setOnClickFillInIntent(R.id.pinned_item, Intent().putExtra(MainActivity.EXTRA_PATH, p))
                }
                items.addItem(i.toLong(), row)
            }
            views.setRemoteAdapter(R.id.recent_list, items.build())
            views.setViewVisibility(R.id.recent_empty, if (paths.isEmpty()) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.recent_list, if (paths.isEmpty()) View.GONE else View.VISIBLE)
            views.setPendingIntentTemplate(R.id.recent_list, WidgetIntents.openTemplate(context, 3))
            ids.forEach { mgr.updateAppWidget(it, views) }
        }

        // From the owner's index: opened notes first, then recently changed ones.
        private fun recent(context: Context): List<String> {
            val session = context.graph.session.value?.takeIf { it.kind == UserKind.OWNER }
            val db = session?.index ?: IndexDb(context, "index-${UserKind.OWNER.key}.db")
            return try {
                val opened = runCatching { db.recentlyOpened(LIMIT) }.getOrDefault(emptyList()).map { it.first }
                val changed = runCatching { db.recentlyModified(LIMIT) }.getOrDefault(emptyList()).map { it.path }
                (opened + changed).distinct().take(LIMIT)
            } finally {
                if (session == null) db.close()
            }
        }
    }
}

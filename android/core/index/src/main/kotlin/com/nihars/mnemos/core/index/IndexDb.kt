package com.nihars.mnemos.core.index

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper

/** One row of the file table. */
data class IndexedFile(
    val path: String,
    val name: String,
    val ext: String,
    val size: Long,
    val mtime: Long,
    val isText: Boolean,
    val title: String?,
    val tags: List<String>,
    // Front matter as "key=value" lines, lower-case, for `key: value` searches.
    val properties: String,
)

/**
 * SQLite cache of the vault. Schema changes or corruption simply drop and rebuild it:
 * the vault on disk is the truth, this is only a speed-up.
 */
class IndexDb(context: Context, name: String) : SQLiteOpenHelper(context, name, null, SCHEMA) {
    /** "fts5" or "fts4", decided once per database at runtime. */
    val ftsKind: String by lazy { readMeta("fts") ?: "fts4" }

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE files(path TEXT PRIMARY KEY, name TEXT NOT NULL, ext TEXT NOT NULL, size INTEGER NOT NULL, " +
                "mtime INTEGER NOT NULL, is_text INTEGER NOT NULL, title TEXT, tags TEXT NOT NULL, fm_json TEXT NOT NULL)",
        )
        db.execSQL("CREATE INDEX files_mtime ON files(mtime DESC)")
        db.execSQL("CREATE TABLE recent(path TEXT PRIMARY KEY, opened_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
        val fts = if (supportsFts5(db)) "fts5" else "fts4"
        if (fts == "fts5") {
            db.execSQL("CREATE VIRTUAL TABLE content USING fts5(path UNINDEXED, body, tokenize='unicode61')")
        } else {
            db.execSQL("CREATE VIRTUAL TABLE content USING fts4(path, body, notindexed=path, tokenize=unicode61)")
        }
        db.insert("meta", null, ContentValues().apply { put("key", "fts"); put("value", fts) })
    }

    // Any schema change: drop everything and rebuild from the vault.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = reset(db)
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = reset(db)

    private fun reset(db: SQLiteDatabase) {
        listOf("files", "recent", "meta", "content").forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
        onCreate(db)
    }

    /** Empties every table; used by Settings → Rebuild index and when the vault folder changes. */
    fun clear() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("files", null, null)
            db.delete("content", null, null)
            db.delete("recent", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun upsert(file: IndexedFile, body: String?) {
        val db = writableDatabase
        db.insertWithOnConflict("files", null, file.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
        db.delete("content", "path = ?", arrayOf(file.path))
        if (body != null) db.insert("content", null, ContentValues().apply { put("path", file.path); put("body", body) })
    }

    fun remove(path: String) {
        val db = writableDatabase
        db.delete("files", "path = ?", arrayOf(path))
        db.delete("content", "path = ?", arrayOf(path))
        db.delete("recent", "path = ?", arrayOf(path))
    }

    /** Runs [block] in one transaction; batching makes the first index build fast. */
    fun <T> transaction(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            return block().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    /** path → (mtime, size) for change detection. */
    fun signatures(): Map<String, Pair<Long, Long>> {
        val out = HashMap<String, Pair<Long, Long>>()
        readableDatabase.rawQuery("SELECT path, mtime, size FROM files", null).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getLong(1) to c.getLong(2)
        }
        return out
    }

    fun allFiles(): List<IndexedFile> = query("SELECT * FROM files", emptyArray())

    fun file(path: String): IndexedFile? = query("SELECT * FROM files WHERE path = ?", arrayOf(path)).firstOrNull()

    /** Files with exactly this name in any folder (for Obsidian's ![[name]] embeds). */
    fun byName(name: String): List<IndexedFile> = query("SELECT * FROM files WHERE name = ?", arrayOf(name))

    fun recentlyModified(limit: Int): List<IndexedFile> = query("SELECT * FROM files ORDER BY mtime DESC LIMIT $limit", emptyArray())

    fun markOpened(path: String, at: Long) {
        writableDatabase.insertWithOnConflict(
            "recent", null,
            ContentValues().apply { put("path", path); put("opened_at", at) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** path → last opened time, newest first. */
    fun recentlyOpened(limit: Int): List<Pair<String, Long>> {
        val out = ArrayList<Pair<String, Long>>()
        readableDatabase.rawQuery("SELECT path, opened_at FROM recent ORDER BY opened_at DESC LIMIT $limit", null).use { c ->
            while (c.moveToNext()) out += c.getString(0) to c.getLong(1)
        }
        return out
    }

    internal fun query(sql: String, args: Array<String>): List<IndexedFile> {
        val out = ArrayList<IndexedFile>()
        readableDatabase.rawQuery(sql, args).use { c ->
            val iPath = c.getColumnIndexOrThrow("path")
            val iName = c.getColumnIndexOrThrow("name")
            val iExt = c.getColumnIndexOrThrow("ext")
            val iSize = c.getColumnIndexOrThrow("size")
            val iMtime = c.getColumnIndexOrThrow("mtime")
            val iText = c.getColumnIndexOrThrow("is_text")
            val iTitle = c.getColumnIndexOrThrow("title")
            val iTags = c.getColumnIndexOrThrow("tags")
            val iFm = c.getColumnIndexOrThrow("fm_json")
            while (c.moveToNext()) {
                out += IndexedFile(
                    path = c.getString(iPath),
                    name = c.getString(iName),
                    ext = c.getString(iExt),
                    size = c.getLong(iSize),
                    mtime = c.getLong(iMtime),
                    isText = c.getInt(iText) != 0,
                    title = c.getString(iTitle),
                    tags = c.getString(iTags).split(',').filter { it.isNotEmpty() },
                    properties = c.getString(iFm),
                )
            }
        }
        return out
    }

    private fun readMeta(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun IndexedFile.toValues() = ContentValues().apply {
        put("path", path)
        put("name", name)
        put("ext", ext)
        put("size", size)
        put("mtime", mtime)
        put("is_text", if (isText) 1 else 0)
        put("title", title)
        // Commas around every tag so `LIKE '%,tag,%'` matches whole tags only.
        put("tags", if (tags.isEmpty()) "" else tags.joinToString(",", prefix = ",", postfix = ","))
        put("fm_json", properties)
    }

    private companion object {
        const val SCHEMA = 1

        // Framework SQLite differs by device; probe instead of guessing (spec: verify at runtime).
        fun supportsFts5(db: SQLiteDatabase): Boolean = try {
            db.execSQL("CREATE VIRTUAL TABLE temp.fts5_probe USING fts5(x)")
            db.execSQL("DROP TABLE temp.fts5_probe")
            true
        } catch (_: SQLiteException) {
            false
        }
    }
}

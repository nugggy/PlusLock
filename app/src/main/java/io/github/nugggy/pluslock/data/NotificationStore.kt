package io.github.nugggy.pluslock.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class SavedNotification(
    val id: Long,
    val msgId: String,
    val pkg: String,
    val app: String,
    val title: String,
    val body: String,
    val time: Long,
    val unsent: Boolean,
)

/** On-device notification history. Nothing here ever leaves the phone. */
class NotificationStore private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "vault.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE n (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                msgid TEXT NOT NULL UNIQUE,
                nkey TEXT,
                pkg TEXT NOT NULL,
                app TEXT,
                title TEXT,
                body TEXT,
                time INTEGER NOT NULL,
                unsent INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("CREATE INDEX n_time ON n(time)")
        db.execSQL("CREATE INDEX n_pkg ON n(pkg)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insert(msgId: String, key: String, pkg: String, app: String, title: String, body: String, time: Long) {
        val v = ContentValues().apply {
            put("msgid", msgId); put("nkey", key); put("pkg", pkg); put("app", app)
            put("title", title); put("body", body); put("time", time)
        }
        writableDatabase.insertWithOnConflict("n", null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /** Flags messages that vanished from a conversation and returns them. */
    fun markUnsent(msgIds: Collection<String>): List<SavedNotification> {
        if (msgIds.isEmpty()) return emptyList()
        val marks = msgIds.joinToString(",") { "?" }
        val args = msgIds.toTypedArray()
        val before = rows("msgid IN ($marks) AND unsent = 0", args, 50)
        writableDatabase.execSQL("UPDATE n SET unsent = 1 WHERE msgid IN ($marks)", args)
        return before
    }

    fun query(search: String?, pkg: String?, unsentOnly: Boolean, limit: Int = 400): List<SavedNotification> {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (!search.isNullOrBlank()) {
            where += "(title LIKE ? OR body LIKE ? OR app LIKE ?)"
            val s = "%$search%"
            args += listOf(s, s, s)
        }
        if (pkg != null) { where += "pkg = ?"; args += pkg }
        if (unsentOnly) where += "unsent = 1"
        return rows(where.joinToString(" AND ").ifEmpty { null }, args.toTypedArray(), limit)
    }

    private fun rows(where: String?, args: Array<String>, limit: Int): List<SavedNotification> {
        val out = mutableListOf<SavedNotification>()
        readableDatabase.query(
            "n", arrayOf("id", "msgid", "pkg", "app", "title", "body", "time", "unsent"),
            where, args, null, null, "time DESC", limit.toString(),
        ).use { c ->
            while (c.moveToNext()) {
                out += SavedNotification(
                    c.getLong(0), c.getString(1), c.getString(2), c.getString(3) ?: "",
                    c.getString(4) ?: "", c.getString(5) ?: "", c.getLong(6), c.getInt(7) == 1,
                )
            }
        }
        return out
    }

    /** Apps in the vault with how many entries each has, busiest first. */
    fun apps(): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        readableDatabase.rawQuery("SELECT pkg, COUNT(*) c FROM n GROUP BY pkg ORDER BY c DESC", null).use { c ->
            while (c.moveToNext()) out += c.getString(0) to c.getInt(1)
        }
        return out
    }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM n", null).use { c ->
        if (c.moveToFirst()) c.getInt(0) else 0
    }

    fun prune(days: Int) {
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        writableDatabase.delete("n", "time < ?", arrayOf(cutoff.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete("n", "id = ?", arrayOf(id.toString()))
    }

    fun clear() {
        writableDatabase.delete("n", null, null)
    }

    companion object {
        @Volatile private var inst: NotificationStore? = null
        fun get(ctx: Context): NotificationStore =
            inst ?: synchronized(this) { inst ?: NotificationStore(ctx).also { inst = it } }
    }
}

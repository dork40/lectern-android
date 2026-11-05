package app.lectern.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Everything Lectern remembers, in one SQLite file. All calls are blocking; use a background thread. */
class Db(context: Context) : SQLiteOpenHelper(context, "lectern.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE books (id TEXT PRIMARY KEY, json TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE progress (book_id TEXT PRIMARY KEY, position REAL NOT NULL, speed REAL NOT NULL, " +
                "finished INTEGER NOT NULL, last_played INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT, book_id TEXT NOT NULL, " +
                "position REAL NOT NULL, note TEXT NOT NULL, created INTEGER NOT NULL)"
        )
        db.execSQL("CREATE TABLE listening (day TEXT NOT NULL, book_id TEXT NOT NULL, seconds REAL NOT NULL, PRIMARY KEY (day, book_id))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // --- books ---

    fun books(): List<Book> = readableDatabase.rawQuery("SELECT json FROM books", null).use { c ->
        buildList { while (c.moveToNext()) runCatching { add(Book.fromJson(c.getString(0))) } }
    }

    fun putBook(b: Book) {
        writableDatabase.insertWithOnConflict(
            "books", null, ContentValues().apply { put("id", b.id); put("json", b.toJson()) }, SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun deleteBooks(ids: Collection<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { db.delete("books", "id = ?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // --- progress ---

    fun allProgress(): Map<String, Progress> = readableDatabase.rawQuery("SELECT * FROM progress", null).use { c ->
        buildMap { while (c.moveToNext()) progressFrom(c).let { put(it.bookId, it) } }
    }

    fun progress(bookId: String): Progress? =
        readableDatabase.rawQuery("SELECT * FROM progress WHERE book_id = ?", arrayOf(bookId)).use { c ->
            if (c.moveToFirst()) progressFrom(c) else null
        }

    private fun progressFrom(c: android.database.Cursor) = Progress(
        bookId = c.getString(c.getColumnIndexOrThrow("book_id")),
        position = c.getDouble(c.getColumnIndexOrThrow("position")),
        speed = c.getFloat(c.getColumnIndexOrThrow("speed")),
        finished = c.getInt(c.getColumnIndexOrThrow("finished")) != 0,
        lastPlayed = c.getLong(c.getColumnIndexOrThrow("last_played")),
    )

    fun putProgress(p: Progress) {
        writableDatabase.insertWithOnConflict(
            "progress", null,
            ContentValues().apply {
                put("book_id", p.bookId); put("position", p.position); put("speed", p.speed)
                put("finished", if (p.finished) 1 else 0); put("last_played", p.lastPlayed)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    // --- bookmarks ---

    fun bookmarks(bookId: String): List<Bookmark> =
        readableDatabase.rawQuery("SELECT * FROM bookmarks WHERE book_id = ? ORDER BY position", arrayOf(bookId)).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Bookmark(c.getLong(0), c.getString(1), c.getDouble(2), c.getString(3), c.getLong(4))
                )
            }
        }

    fun addBookmark(bookId: String, position: Double, note: String): Long =
        writableDatabase.insert("bookmarks", null, ContentValues().apply {
            put("book_id", bookId); put("position", position); put("note", note); put("created", System.currentTimeMillis())
        })

    fun updateBookmark(id: Long, note: String) {
        writableDatabase.update("bookmarks", ContentValues().apply { put("note", note) }, "id = ?", arrayOf(id.toString()))
    }

    fun deleteBookmark(id: Long) {
        writableDatabase.delete("bookmarks", "id = ?", arrayOf(id.toString()))
    }

    // --- listening stats ---

    fun addListening(day: String, bookId: String, seconds: Double) {
        writableDatabase.execSQL(
            "INSERT INTO listening (day, book_id, seconds) VALUES (?, ?, ?) " +
                "ON CONFLICT(day, book_id) DO UPDATE SET seconds = seconds + excluded.seconds",
            arrayOf(day, bookId, seconds),
        )
    }

    /** (day "yyyy-MM-dd", book id, seconds) for every day with listening. */
    fun listening(): List<Triple<String, String, Double>> =
        readableDatabase.rawQuery("SELECT day, book_id, seconds FROM listening", null).use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getDouble(2))) }
        }
}

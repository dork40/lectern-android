package app.lectern.data

import org.json.JSONArray
import org.json.JSONObject

/** One audio file of a book. [offset] is where it starts in book time (seconds). */
data class Track(val uri: String, val duration: Double, val offset: Double, val title: String)

data class Chapter(val title: String, val start: Double, val end: Double) {
    val duration get() = end - start
}

data class Book(
    val id: String,
    val title: String,
    val author: String = "",
    val narrator: String = "",
    val series: String = "",
    val seriesIndex: String = "",
    val year: String = "",
    val duration: Double,
    val tracks: List<Track>,
    val chapters: List<Chapter>,
    val cover: String? = null,
    /** Folder the book lives in (SAF document id), used for the Smart AudioBook Player import. */
    val dirDocId: String? = null,
    val signature: String = "",
    val added: Long = System.currentTimeMillis(),
) {
    fun chapterIndex(pos: Double): Int {
        if (chapters.isEmpty()) return -1
        var i = chapters.binarySearchBy(pos + 0.005) { it.start } // tolerate ms rounding at chapter starts
        if (i < 0) i = -i - 2
        return i.coerceIn(0, chapters.lastIndex)
    }

    /** Book position → (track index, seconds into that track). */
    fun locate(pos: Double): Pair<Int, Double> {
        val p = pos.coerceIn(0.0, duration)
        for (i in tracks.indices.reversed()) {
            if (p >= tracks[i].offset) return i to (p - tracks[i].offset).coerceAtMost(tracks[i].duration)
        }
        return 0 to 0.0
    }

    val seriesLabel: String
        get() = when {
            series.isNotBlank() && seriesIndex.isNotBlank() -> "$series · Book $seriesIndex"
            series.isNotBlank() -> series
            else -> ""
        }

    fun toJson(): String = JSONObject()
        .put("id", id).put("title", title).put("author", author).put("narrator", narrator)
        .put("series", series).put("seriesIndex", seriesIndex).put("year", year)
        .put("duration", duration).put("cover", cover).put("dirDocId", dirDocId)
        .put("signature", signature).put("added", added)
        .put("tracks", JSONArray(tracks.map {
            JSONObject().put("uri", it.uri).put("duration", it.duration).put("offset", it.offset).put("title", it.title)
        }))
        .put("chapters", JSONArray(chapters.map {
            JSONObject().put("title", it.title).put("start", it.start).put("end", it.end)
        }))
        .toString()

    companion object {
        fun fromJson(s: String): Book {
            val o = JSONObject(s)
            val tr = o.getJSONArray("tracks")
            val ch = o.getJSONArray("chapters")
            return Book(
                id = o.getString("id"),
                title = o.optString("title"),
                author = o.optString("author"),
                narrator = o.optString("narrator"),
                series = o.optString("series"),
                seriesIndex = o.optString("seriesIndex"),
                year = o.optString("year"),
                duration = o.getDouble("duration"),
                tracks = List(tr.length()) { i ->
                    tr.getJSONObject(i).let { Track(it.getString("uri"), it.getDouble("duration"), it.getDouble("offset"), it.optString("title")) }
                },
                chapters = List(ch.length()) { i ->
                    ch.getJSONObject(i).let { Chapter(it.optString("title"), it.getDouble("start"), it.getDouble("end")) }
                },
                cover = o.optString("cover").takeIf { it.isNotBlank() && it != "null" },
                dirDocId = o.optString("dirDocId").takeIf { it.isNotBlank() && it != "null" },
                signature = o.optString("signature"),
                added = o.optLong("added"),
            )
        }
    }
}

data class Progress(
    val bookId: String,
    val position: Double = 0.0,
    val speed: Float = 1f,
    val finished: Boolean = false,
    /** When the book was last played (ms), 0 if never. */
    val lastPlayed: Long = 0,
)

data class Bookmark(val id: Long, val bookId: String, val position: Double, val note: String, val created: Long)

enum class Status { NEW, STARTED, FINISHED }

data class LibraryBook(val book: Book, val progress: Progress) {
    val status: Status
        get() = when {
            progress.finished -> Status.FINISHED
            progress.position > 1.0 || progress.lastPlayed > 0 -> Status.STARTED
            else -> Status.NEW
        }
    val fraction: Float
        get() = if (progress.finished) 1f else if (book.duration > 0) (progress.position / book.duration).toFloat().coerceIn(0f, 1f) else 0f
}

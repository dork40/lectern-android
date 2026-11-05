package app.lectern.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import app.lectern.data.Book
import app.lectern.data.Chapter
import app.lectern.data.Db
import app.lectern.data.Progress
import app.lectern.data.Track
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Finds books under the library folder (a SAF tree) and reads their metadata.
 * The grouping rules follow desktop Lectern's scanner.py.
 */
class Scanner(private val context: Context, private val db: Db) {
    private val resolver = context.contentResolver

    data class Entry(val docId: String, val name: String, val isDir: Boolean, val size: Long, val mtime: Long)

    private data class Candidate(
        val rel: String,
        val kind: String, // "file" | "folder"
        val files: List<Pair<Entry, String>>, // entry + relative path
        val dirDocId: String,
        val dirEntries: List<Entry>,
        val exclusive: Boolean,
    )

    data class Result(val added: Int, val updated: Int, val removed: Int, val imported: Int)

    fun scan(tree: Uri, onProgress: (Int, Int) -> Unit): Result {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val candidates = mutableListOf<Candidate>()
        discover(tree, rootId, "", true, candidates)

        val existing = db.books().associateBy { it.id }
        val seen = mutableSetOf<String>()
        var added = 0
        var updated = 0
        var imported = 0
        candidates.forEachIndexed { i, c ->
            onProgress(i, candidates.size)
            val id = hash(c.rel).take(16)
            seen += id
            val sig = hash(c.files.joinToString("|") { "${it.first.name}:${it.first.size}:${it.first.mtime}" })
            val old = existing[id]
            if (old != null && old.signature == sig) return@forEachIndexed
            val book = runCatching { build(tree, id, sig, c, old) }
                .onFailure { Log.w("Lectern", "Couldn't read ${c.rel}", it) }
                .getOrNull() ?: return@forEachIndexed
            db.putBook(book)
            if (old == null) added++ else updated++
            if (db.progress(id) == null && importSabp(tree, book, c)) imported++
        }
        onProgress(candidates.size, candidates.size)
        val removed = existing.keys - seen
        if (removed.isNotEmpty()) db.deleteBooks(removed) // progress stays, so a book that comes back keeps its place
        return Result(added, updated, removed.size, imported)
    }

    // --- walking ---

    private fun list(tree: Uri, docId: String): List<Entry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        return resolver.query(uri, cols, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Entry(
                        docId = c.getString(0),
                        name = c.getString(1) ?: "",
                        isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                        size = c.getLong(3),
                        mtime = c.getLong(4),
                    )
                )
            }
        } ?: emptyList()
    }

    private fun discover(tree: Uri, dirId: String, rel: String, isRoot: Boolean, out: MutableList<Candidate>) {
        val entries = list(tree, dirId).filterNot { it.name.startsWith(".") }
        val dirs = entries.filter { it.isDir }.sortedWith(compareBy(Names.naturalOrder) { it.name })
        val audio = entries.filter { !it.isDir && isAudio(it.name) }.sortedWith(compareBy(Names.naturalOrder) { it.name })
        fun relOf(name: String) = if (rel.isEmpty()) name else "$rel/$name"

        // CD1/Disc 2/... subfolders are merged into this folder's book.
        val discFiles = mutableListOf<Pair<Entry, String>>()
        val walk = mutableListOf<Entry>()
        for (d in dirs) {
            if (discDir.matches(d.name)) {
                val sub = list(tree, d.docId).filter { !it.isDir && isAudio(it.name) }.sortedWith(compareBy(Names.naturalOrder) { it.name })
                if (sub.isNotEmpty()) {
                    discFiles += sub.map { it to "${relOf(d.name)}/${it.name}" }
                    continue
                }
            }
            walk += d
        }

        val files = audio.map { it to relOf(it.name) }
        if (files.isNotEmpty() || discFiles.isNotEmpty()) {
            val groups = (if (files.isNotEmpty()) groupFiles(tree, files) else emptyList()).map { it.toMutableList() }.toMutableList()
            if (discFiles.isNotEmpty()) {
                val multi = groups.firstOrNull { it.size > 1 || !standalone(it[0].first.name) }
                if (multi != null) multi += discFiles else groups += discFiles.toMutableList()
            }
            val alone = groups.size == 1 && !isRoot
            var folderUsed = false
            for (g in groups) {
                if (g.size == 1) {
                    out += Candidate(g[0].second, "file", g, dirId, entries, alone && walk.isEmpty())
                } else {
                    val path = if (!folderUsed) rel.ifEmpty { "/" } else "$rel::${g[0].first.name}"
                    folderUsed = true
                    out += Candidate(path, "folder", g, dirId, entries, alone)
                }
            }
        }
        for (d in walk) discover(tree, d.docId, relOf(d.name), false, out)
    }

    /** Splits one folder's audio files into books; see desktop scanner.group_files. */
    private fun groupFiles(tree: Uri, files: List<Pair<Entry, String>>): List<List<Pair<Entry, String>>> {
        val books = files.filter { standalone(it.first.name) }.map { listOf(it) }.toMutableList()
        val rest = files.filterNot { standalone(it.first.name) }
        if (rest.size == 1 || (rest.isNotEmpty() && looksLikeParts(rest.map { it.first.name.substringBeforeLast('.') }))) {
            return books + listOf(rest)
        }
        val byAlbum = linkedMapOf<String, MutableList<Pair<Entry, String>>>()
        for (f in rest) byAlbum.getOrPut(albumOf(tree, f.first)) { mutableListOf() } += f
        for ((key, group) in byAlbum) {
            if (key.isNotEmpty() && group.size > 1) books += group else group.forEach { books += listOf(it) }
        }
        return books
    }

    private fun looksLikeParts(stems: List<String>): Boolean {
        if (stems.all { numbered.containsMatchIn(it) }) return true
        val lower = stems.map { it.lowercase() }
        var prefix = lower.first()
        for (s in lower) while (!s.startsWith(prefix)) prefix = prefix.dropLast(1)
        return prefix.trim(' ', '-', '_', '.').length >= 3 && lower.all { Regex("""\d""").containsMatchIn(it.substring(prefix.length)) }
    }

    private fun albumOf(tree: Uri, e: Entry): String = withRetriever(docUri(tree, e.docId)) {
        it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim()?.lowercase()
    } ?: ""

    // --- building ---

    private fun build(tree: Uri, id: String, sig: String, c: Candidate, old: Book?): Book {
        var offset = 0.0
        val tracks = mutableListOf<Track>()
        var tags: Map<String, String?> = emptyMap()
        var picture: ByteArray? = null
        for ((i, f) in c.files.withIndex()) {
            val uri = docUri(tree, f.first.docId)
            val (dur, t, pic) = withRetriever(uri) { r ->
                val d = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: 0.0) / 1000.0
                val tg = mapOf(
                    "title" to r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    "album" to r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    "artist" to r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    "albumartist" to r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                    "author" to r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR),
                    "composer" to r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER),
                    "writer" to r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_WRITER),
                    "year" to r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR),
                    "date" to r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE),
                )
                Triple(d, tg, if (i == 0) r.embeddedPicture else null)
            } ?: Triple(0.0, emptyMap(), null)
            if (i == 0) {
                tags = t
                picture = pic
            }
            val stem = f.first.name.substringBeforeLast('.')
            val title = if (c.kind == "folder") (t["title"]?.takeIf { it.isNotBlank() } ?: Names.cleanTrackTitle(stem)) else stem
            tracks += Track(uri.toString(), dur, offset, title)
            offset += dur
        }
        val duration = offset

        // Chapters: embedded ones for single-file books, one per file for folders.
        val raw: List<Pair<Double, String>> = if (c.kind == "file") {
            val name = c.files[0].first.name.lowercase()
            val uri = Uri.parse(tracks[0].uri)
            when {
                name.endsWith(".m4b") || name.endsWith(".m4a") || name.endsWith(".mp4") -> mp4Chapters(uri)
                else -> otherChapters(uri)
            }
        } else tracks.map { it.offset to it.title }
        val chapters = cleanChapters(raw, duration)

        // Title, author & co: tags first, file and folder names as the fallback.
        val guess = Names.guessFromPath(c.rel.substringBefore("::"), c.kind == "file")
        fun tag(vararg k: String) = k.firstNotNullOfOrNull { tags[it]?.trim()?.takeIf { v -> v.isNotEmpty() } }
        val rawTitle = (if (c.kind == "file") tag("album", "title") else tag("album")) ?: guess.title ?: c.files[0].first.name
        val (title, suffixSeries, suffixIndex) = Names.splitSeriesSuffix(rawTitle)
        val author = tag("albumartist", "artist", "author", "writer") ?: guess.author ?: ""
        val narrator = tag("composer")?.takeIf { !it.equals(author, true) } ?: guess.narrator ?: ""
        val year = (tag("year") ?: tag("date")?.take(4))?.takeIf { Regex("""\d{4}""").matches(it) } ?: guess.year ?: ""

        val cover = saveCover(id, tree, c, picture) ?: old?.cover
        return Book(
            id = id,
            title = title.ifBlank { rawTitle },
            author = author,
            narrator = narrator,
            series = suffixSeries ?: guess.series ?: "",
            seriesIndex = suffixIndex ?: guess.seriesIndex ?: "",
            year = year,
            duration = duration,
            tracks = tracks,
            chapters = chapters.ifEmpty { listOf(Chapter(title, 0.0, duration)) },
            cover = cover,
            dirDocId = c.dirDocId,
            signature = sig,
            added = old?.added ?: System.currentTimeMillis(),
        )
    }

    private fun mp4Chapters(uri: Uri): List<Pair<Double, String>> = runCatching {
        resolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { Mp4Chapters(it).read() }
        }
    }.getOrDefault(emptyList())

    /** ID3 CHAP frames (MP3) and Vorbis CHAPTERxxx comments (Ogg/Opus/FLAC). */
    private fun otherChapters(uri: Uri): List<Pair<Double, String>> = runCatching {
        val groups = MetadataRetriever.retrieveMetadata(context, MediaItem.fromUri(uri)).get(20, TimeUnit.SECONDS)
        val out = mutableListOf<Pair<Double, String>>()
        val vorbis = mutableMapOf<String, String>()
        for (g in 0 until groups.length) {
            val group = groups[g]
            for (t in 0 until group.length) {
                val md = group.getFormat(t).metadata ?: continue
                for (e in 0 until md.length()) when (val entry = md[e]) {
                    is ChapterFrame -> {
                        val name = (0 until entry.subFrameCount).map { entry.getSubFrame(it) }
                            .filterIsInstance<TextInformationFrame>().firstOrNull { it.id == "TIT2" }?.values?.firstOrNull()
                        out += entry.startTimeMs / 1000.0 to (name ?: "")
                    }
                    is VorbisComment -> vorbis[entry.key.uppercase()] = entry.value
                }
            }
        }
        vorbis.filterKeys { Regex("""CHAPTER\d+""").matches(it) }.forEach { (k, v) ->
            parseTimestamp(v)?.let { out += it to (vorbis["${k}NAME"] ?: "") }
        }
        out
    }.getOrDefault(emptyList())

    private fun parseTimestamp(t: String): Double? {
        val m = Regex("""^(\d+):(\d{1,2}):(\d{1,2}(?:\.\d+)?)$""").find(t.trim()) ?: return null
        return m.groupValues[1].toDouble() * 3600 + m.groupValues[2].toDouble() * 60 + m.groupValues[3].toDouble()
    }

    /** Sort, drop duplicates / out-of-range starts, and make the first chapter start at 0. */
    private fun cleanChapters(raw: List<Pair<Double, String>>, duration: Double): List<Chapter> {
        val starts = mutableListOf<Pair<Double, String>>()
        for ((start, title) in raw.sortedBy { it.first }) {
            if (duration > 0 && start >= duration) continue
            if (starts.isNotEmpty() && Math.abs(starts.last().first - start) < 0.01) continue
            starts += maxOf(0.0, start) to title.trim().ifEmpty { "Chapter ${starts.size + 1}" }
        }
        if (starts.isNotEmpty()) {
            if (starts[0].first > 0.5) starts.add(0, 0.0 to "Introduction") else starts[0] = 0.0 to starts[0].second
        }
        return starts.mapIndexed { i, (s, t) -> Chapter(t, s, if (i + 1 < starts.size) starts[i + 1].first else duration) }
    }

    private fun saveCover(id: String, tree: Uri, c: Candidate, embedded: ByteArray?): String? {
        val images = c.dirEntries.filter { !it.isDir && imageExt.any { ext -> it.name.lowercase().endsWith(ext) } }
        val stem = c.files[0].first.name.substringBeforeLast('.').lowercase()
        val local = images.firstOrNull { it.name.substringBeforeLast('.').lowercase() in preferredCovers }
            ?: images.firstOrNull { it.name.substringBeforeLast('.').lowercase() == stem }
            ?: images.firstOrNull().takeIf { c.exclusive }
        val bytes = local?.let { e -> runCatching { resolver.openInputStream(docUri(tree, e.docId))!!.use { it.readBytes() } }.getOrNull() }
            ?: embedded ?: return null
        return runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            var sample = 1
            while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= 1200) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val dir = File(context.filesDir, "covers").apply { mkdirs() }
            val file = File(dir, "$id-${System.currentTimeMillis()}.jpg")
            dir.listFiles { f -> f.name.startsWith("$id-") }?.forEach { it.delete() }
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            file.absolutePath
        }.getOrNull()
    }

    /** Carries over a book's place from Smart AudioBook Player (position.sabp.dat beside the book). */
    private fun importSabp(tree: Uri, book: Book, c: Candidate): Boolean {
        if (!c.exclusive) return false
        val f = c.dirEntries.firstOrNull { it.name == "position.sabp.dat" } ?: return false
        val data = runCatching { resolver.openInputStream(docUri(tree, f.docId))!!.use { it.readBytes() } }.getOrNull() ?: return false
        val p = Sabp.parse(data) ?: return false
        val track = book.tracks.getOrNull(p.fileIndex) ?: book.tracks.first()
        val pos = if (p.finished) book.duration else (track.offset + p.filePosition).coerceIn(0.0, book.duration)
        if (pos <= 0.0 && !p.finished) return false
        db.putProgress(Progress(book.id, pos, p.speed, p.finished, p.lastPlayed))
        return true
    }

    private fun <T> withRetriever(uri: Uri, f: (MediaMetadataRetriever) -> T): T? = runCatching {
        resolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(pfd.fileDescriptor)
                f(r)
            } finally {
                r.release()
            }
        }
    }.getOrNull()

    private fun docUri(tree: Uri, docId: String) = DocumentsContract.buildDocumentUriUsingTree(tree, docId)

    companion object {
        val audioExt = setOf("m4b", "m4a", "mp3", "ogg", "oga", "opus", "flac", "aac", "wav", "mp4")
        private val imageExt = listOf(".jpg", ".jpeg", ".png", ".webp")
        private val preferredCovers = setOf("cover", "folder", "front", "embeddedcover", "albumart", "poster")
        private val discDir = Regex("""^(?:cd|dis[ck]|part|vol(?:ume)?)\s*[-_.]?\s*\d+\b.*$""", RegexOption.IGNORE_CASE)
        private val numbered = Regex(
            """^\s*(?:\d{1,4}\b|\d{1,4}[-_.)]|(?:part|pt|chapter|chap|ch|track|disc|cd|side)\s*[-_.]?\s*\d+)""",
            RegexOption.IGNORE_CASE,
        )

        fun isAudio(name: String) = name.substringAfterLast('.', "").lowercase() in audioExt
        private fun standalone(name: String) = name.lowercase().let { it.endsWith(".m4b") || it.endsWith(".m4a") }

        fun hash(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

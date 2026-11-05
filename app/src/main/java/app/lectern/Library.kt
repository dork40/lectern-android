package app.lectern

import android.content.Context
import android.net.Uri
import app.lectern.data.Book
import app.lectern.data.Bookmark
import app.lectern.data.Db
import app.lectern.data.LibraryBook
import app.lectern.data.Prefs
import app.lectern.data.Progress
import app.lectern.scan.Scanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The book list and everything stored about each book. */
class Library(private val context: Context, val db: Db, val prefs: Prefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _books = MutableStateFlow<List<LibraryBook>>(emptyList())
    val books: StateFlow<List<LibraryBook>> = _books.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Scan progress 0..1 while a scan runs, null otherwise. */
    private val _scan = MutableStateFlow<Float?>(null)
    val scan: StateFlow<Float?> = _scan.asStateFlow()

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Bumped whenever bookmarks change, so screens showing them refresh. */
    private val _bookmarkTick = MutableStateFlow(0)
    val bookmarkTick: StateFlow<Int> = _bookmarkTick.asStateFlow()

    init {
        scope.launch {
            reload()
            _loaded.value = true
            if (prefs.value.libraryUri != null) scan(quiet = true)
        }
    }

    // --- Smart AudioBook Player: it saves its place in position.sabp.dat beside each book ---

    private val sabpDocs = java.util.concurrent.ConcurrentHashMap<String, Uri>()

    /** The book's position.sabp.dat, if Smart AudioBook Player has one for it (and the folder holds only this book). */
    private fun sabpFile(b: Book): Uri? {
        sabpDocs[b.id]?.let { return it }
        val tree = prefs.value.libraryUri?.let(Uri::parse) ?: return null
        val dir = b.dirDocId ?: return null
        if (_books.value.count { it.book.dirDocId == dir } > 1) return null
        val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir)
        val cols = arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val docId = runCatching {
            context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                var found: String? = null
                while (c.moveToNext()) if (c.getString(1) == "position.sabp.dat") found = c.getString(0)
                found
            }
        }.getOrNull() ?: return null
        return android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, docId).also { sabpDocs[b.id] = it }
    }

    data class SabpPlace(val bookId: String, val position: Double, val speed: Float, val finished: Boolean, val lastPlayed: Long)

    /**
     * Places Smart AudioBook Player saved after Lectern last played each book. Ones further along
     * (or for books never played in Lectern) are applied silently; ones behind Lectern's spot are
     * returned so the user can choose, since following them would lose listening.
     */
    fun pullFromSabp(): List<SabpPlace> {
        if (!prefs.value.syncSabp) return emptyList()
        val offers = mutableListOf<SabpPlace>()
        for (lb in _books.value) {
            val file = sabpFile(lb.book) ?: continue
            val data = runCatching { context.contentResolver.openInputStream(file)!!.use { it.readBytes() } }.getOrNull() ?: continue
            val p = app.lectern.scan.Sabp.parse(data) ?: continue
            if (p.lastPlayed <= lb.progress.lastPlayed + 2_000 || p.lastPlayed <= prefs.sabpSeen(lb.book.id)) continue
            val track = lb.book.tracks.getOrNull(p.fileIndex) ?: lb.book.tracks.first()
            val pos = if (p.finished) lb.book.duration else (track.offset + p.filePosition).coerceIn(0.0, lb.book.duration)
            val place = SabpPlace(lb.book.id, pos, p.speed, p.finished, p.lastPlayed)
            val neverPlayed = lb.progress.lastPlayed == 0L && lb.progress.position < 1
            when {
                // You listened further there (e.g. at night): follow it, nothing to ask.
                neverPlayed || place.finished || pos >= lb.progress.position + 30 -> adopt(place)
                // Practically the same spot (e.g. right after Lectern moved it).
                Math.abs(pos - lb.progress.position) < 30 -> prefs.setSabpSeen(lb.book.id, place.lastPlayed)
                // It's behind Lectern: probably reopened at an old spot. Ask rather than lose your place.
                else -> offers += place
            }
        }
        return offers
    }

    fun adopt(place: SabpPlace) {
        prefs.setSabpSeen(place.bookId, place.lastPlayed)
        val p = progress(place.bookId)
        saveProgress(p.copy(position = place.position, speed = place.speed, finished = place.finished, lastPlayed = place.lastPlayed))
    }

    fun decline(place: SabpPlace) = prefs.setSabpSeen(place.bookId, place.lastPlayed)

    /** Set when syncing needs notification access; the library shows a one-tap prompt. */
    private val _needsSabpAccess = MutableStateFlow(false)
    val needsSabpAccess: StateFlow<Boolean> = _needsSabpAccess.asStateFlow()
    fun recheckSabpAccess() { if (app.lectern.play.SabpLink.hasAccess(context)) _needsSabpAccess.value = false }

    /** When you pause Lectern, bring Smart AudioBook Player to the same spot (see SabpLink). */
    fun moveSabpTo(b: Book, target: Double) = scope.launch {
        if (!prefs.value.syncSabp) return@launch
        val r = app.lectern.play.SabpLink.moveTo(context, b, target)
        android.util.Log.i("Lectern", "Smart AudioBook Player sync: $r")
        when (r) {
            is app.lectern.play.SabpLink.Result.Failed -> messages.tryEmit("Couldn't move Smart AudioBook Player to your spot")
            app.lectern.play.SabpLink.Result.NeedsAccess -> _needsSabpAccess.value = true
            else -> Unit
        }
    }

    private fun reload() {
        val progress = db.allProgress()
        _books.value = db.books().map { b -> LibraryBook(b, progress[b.id] ?: Progress(b.id, speed = prefs.value.lastSpeed)) }
    }

    fun get(id: String): LibraryBook? = _books.value.firstOrNull { it.book.id == id }

    fun progress(id: String): Progress = get(id)?.progress ?: db.progress(id) ?: Progress(id, speed = prefs.value.lastSpeed)

    fun setFolder(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        prefs.update { it.copy(libraryUri = uri.toString()) }
        scan()
    }

    fun scan(quiet: Boolean = false) {
        val uri = prefs.value.libraryUri?.let(Uri::parse) ?: return
        if (_scan.value != null) return
        _scan.value = 0f
        scope.launch {
            try {
                val r = Scanner(context, db).scan(uri) { done, total -> _scan.value = if (total == 0) 1f else done.toFloat() / total }
                reload()
                if (!quiet || r.added + r.removed + r.imported > 0) {
                    val parts = listOfNotNull(
                        r.added.takeIf { it > 0 }?.let { "$it new" },
                        r.updated.takeIf { it > 0 }?.let { "$it updated" },
                        r.removed.takeIf { it > 0 }?.let { "$it removed" },
                        r.imported.takeIf { it > 0 }?.let { "$it places imported from Smart AudioBook Player" },
                    )
                    messages.tryEmit(if (parts.isEmpty()) "Library is up to date" else parts.joinToString(" · "))
                }
            } catch (e: SecurityException) {
                messages.tryEmit("Lectern lost access to your Audiobooks folder. Pick it again in Settings.")
            } catch (e: Exception) {
                messages.tryEmit("Scan failed: ${e.message}")
            } finally {
                _scan.value = null
            }
        }
    }

    /** Updates the in-memory copy right away; the database write happens in the background. */
    fun saveProgress(p: Progress) {
        _books.update { list -> list.map { if (it.book.id == p.bookId) it.copy(progress = p) else it } }
        scope.launch { db.putProgress(p) }
    }

    fun setFinished(id: String, finished: Boolean) {
        val p = progress(id)
        val b = get(id)?.book
        saveProgress(p.copy(finished = finished, position = if (finished) (b?.duration ?: p.position) else 0.0))
    }

    fun addListening(day: String, bookId: String, seconds: Double) = scope.launch { db.addListening(day, bookId, seconds) }

    // Bookmarks are small and read rarely, so they go straight to the database.
    fun bookmarks(id: String): List<Bookmark> = db.bookmarks(id)

    fun addBookmark(book: Book, position: Double, note: String) = scope.launch {
        db.addBookmark(book.id, position, note)
        _bookmarkTick.value++
    }

    fun updateBookmark(id: Long, note: String) = scope.launch { db.updateBookmark(id, note); _bookmarkTick.value++ }

    fun deleteBookmark(id: Long) = scope.launch { db.deleteBookmark(id); _bookmarkTick.value++ }
}

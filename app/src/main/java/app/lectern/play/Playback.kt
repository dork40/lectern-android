package app.lectern.play

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import app.lectern.LecternApp
import app.lectern.Library
import app.lectern.cast.Caster
import app.lectern.data.Book
import app.lectern.data.Progress
import app.lectern.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import kotlin.math.sqrt

data class SleepState(
    val endOfChapter: Boolean,
    /** Real time left, ms. */
    val leftMs: Long,
    val fading: Boolean,
)

data class Undo(val label: String, val from: Double)

data class NowState(
    val book: Book? = null,
    /** Book position, seconds. */
    val position: Double = 0.0,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val speed: Float = 1f,
    val chapter: Int = -1,
    val sleep: SleepState? = null,
)

/**
 * The one player in the app. Screens, the notification, the lock screen and Bluetooth
 * controls all drive it. Positions are in book seconds; tracks are mapped transparently.
 */
@OptIn(UnstableApi::class)
object Playback {
    private lateinit var app: Context
    private lateinit var lib: Library
    lateinit var exo: ExoPlayer
        private set

    /** What the media session sees: next/previous mean chapters, play applies rewind-on-resume. */
    lateinit var player: Player
        private set

    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(NowState())
    val state: StateFlow<NowState> = _state.asStateFlow()

    private val _undo = MutableStateFlow<Undo?>(null)
    val undo: StateFlow<Undo?> = _undo.asStateFlow()

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private var book: Book? = null
    private var sessionId = C.AUDIO_SESSION_ID_UNSET
    private var lastPausedAt = 0L
    private var lastSaved = 0L
    private var savedPos = -1.0
    private var listenAcc = 0.0
    private var lastFlush = 0L
    private var shownChapter = -1

    private val settings: Settings get() = lib.prefs.value

    fun init(context: Context, library: Library) {
        app = context.applicationContext
        lib = library
        sessionId = Util.generateAudioSessionIdV21(app)
        exo = ExoPlayer.Builder(app)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        exo.setAudioSessionId(sessionId)
        player = ChapterPlayer(exo)
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) {
                    lastPausedAt = System.currentTimeMillis()
                    save(force = true)
                    flushListening()
                }
                publish()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // You paused Lectern (button, headset, unplugging, sleep timer): bring Smart AudioBook
                // Player to the same spot. Not when Lectern paused because another app took the audio.
                if (!playWhenReady && !Caster.casting &&
                    (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST || reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
                ) book?.let { b ->
                    lib.moveSabpTo(b, position())
                    // And tell Lectern on the PC, if it's on this Wi-Fi.
                    val p = lib.progress(b.id).copy(position = position(), lastPlayed = System.currentTimeMillis())
                    main.launch { LecternApp.instance.pcSync.push(b, p) }
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) book?.let { b ->
                    val done = Progress(b.id, b.duration, exo.playbackParameters.speed, true, System.currentTimeMillis())
                    lib.saveProgress(done)
                    savedPos = b.duration
                    messages.tryEmit("Finished ${b.title}")
                }
                publish()
            }
        })
        Caster.init(app)
        Caster.onSessionChange = { started -> main.launch { if (started) toRemote() else toLocal() } }
        Caster.onStatus = {
            main.launch {
                if (Caster.finished) book?.let { b ->
                    lib.saveProgress(Progress(b.id, b.duration, exo.playbackParameters.speed, true, System.currentTimeMillis()))
                }
                if (!Caster.playing) save(force = true)
                publish()
            }
        }
        main.launch { lib.prefs.settings.collect(::applyEffects) }
        main.launch { tick() }
        // Reopen whatever was playing last time, paused.
        main.launch {
            lib.loaded.first { it }
            lib.prefs.currentBook?.let { id -> lib.get(id)?.let { open(it.book, play = false) } }
        }
    }

    // --- opening & transport ---

    fun open(b: Book, play: Boolean = true, at: Double? = null) {
        if (book?.id == b.id) {
            at?.let { seekTo(it) }
            if (play) play()
            return
        }
        save(force = true)
        flushListening()
        book = b
        lib.prefs.currentBook = b.id
        val p = lib.progress(b.id)
        val start = at ?: if (p.finished) 0.0 else p.position
        val art = b.cover?.let { Uri.fromFile(File(it)) }
        val items = b.tracks.mapIndexed { i, t ->
            MediaItem.Builder().setUri(t.uri).setMediaId("${b.id}#$i").setMediaMetadata(meta(b, t.title, art)).build()
        }
        lastPausedAt = p.lastPlayed
        if (Caster.casting) {
            exo.setPlaybackSpeed(p.speed)
            Caster.load(b, start, p.speed, autoplay = play)
            savedPos = start
            shownChapter = -1
            publish()
            return
        }
        val (idx, off) = b.locate(start)
        exo.setMediaItems(items, idx, (off * 1000).toLong())
        exo.setPlaybackSpeed(p.speed)
        exo.prepare()
        savedPos = start
        shownChapter = -1
        _undo.value = null
        if (p.finished && at == null) lib.saveProgress(p.copy(finished = false, position = 0.0))
        publish()
        // Show the media controls in the notification area right away, not only after Play.
        ensureService()
        if (play) play()
    }

    private var serviceLink: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>? = null

    /**
     * Starts the media service and stays connected to it. Media3 only publishes the session and its
     * notification once a controller connects, so this is what puts the controls in the notification
     * area and on the lock screen as soon as a book is loaded, even before you press play.
     */
    private fun ensureService() {
        runCatching { app.startService(Intent(app, PlaybackService::class.java)) }
        if (serviceLink == null) {
            val token = androidx.media3.session.SessionToken(app, android.content.ComponentName(app, PlaybackService::class.java))
            serviceLink = androidx.media3.session.MediaController.Builder(app, token).buildAsync()
        }
    }

    /** Current book's media items at its saved spot, for playback resumption (null if none). */
    suspend fun resumption(): androidx.media3.session.MediaSession.MediaItemsWithStartPosition? {
        lib.loaded.first { it }
        if (book == null) lib.prefs.currentBook?.let { id -> lib.get(id)?.let { open(it.book, play = false) } }
        val b = book ?: return null
        val items = (0 until exo.mediaItemCount).map { exo.getMediaItemAt(it) }
        if (items.isEmpty()) return null
        val (idx, off) = b.locate(position())
        return androidx.media3.session.MediaSession.MediaItemsWithStartPosition(items, idx, (off * 1000).toLong())
    }

    private fun meta(b: Book, title: String, art: Uri?) = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(b.title)
        .setAlbumTitle(b.title)
        .setAlbumArtist(b.author)
        .setArtworkUri(art)
        .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
        .build()

    fun play() {
        val b = book ?: return
        // Back after a long break: start a few seconds earlier so you can find the thread.
        if (settings.rewindOnResume && lastPausedAt > 0 && System.currentTimeMillis() - lastPausedAt > 10 * 60_000) {
            seekTo(position() - 10, undoable = false)
        }
        if (exo.playbackState == Player.STATE_ENDED) seekTo(0.0, undoable = false)
        lastPausedAt = 0
        if (Caster.casting) {
            Caster.play()
            lib.saveProgress(lib.progress(b.id).copy(lastPlayed = System.currentTimeMillis(), finished = false))
            return
        }
        ensureService()
        if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
        exo.play()
        lib.saveProgress(lib.progress(b.id).copy(lastPlayed = System.currentTimeMillis(), finished = false))
    }

    fun pause() = if (Caster.casting) Caster.pause() else exo.pause()

    fun toggle() = if (isPlaying()) pause() else play()

    private fun isPlaying() = if (Caster.casting) Caster.playing else exo.playWhenReady && exo.playbackState != Player.STATE_ENDED

    fun position(): Double {
        val b = book ?: return 0.0
        if (Caster.casting) return (Caster.position() ?: Caster.lastPosition()).coerceIn(0.0, b.duration)
        val t = b.tracks.getOrNull(exo.currentMediaItemIndex) ?: return 0.0
        return (t.offset + exo.currentPosition / 1000.0).coerceIn(0.0, b.duration)
    }

    fun seekTo(pos: Double, undoable: Boolean = false, label: String = "") {
        val b = book ?: return
        val from = position()
        if (undoable && Math.abs(pos - from) > 30) _undo.value = Undo(label, from)
        if (Caster.casting) {
            Caster.seek(b, pos.coerceIn(0.0, maxOf(0.0, b.duration - 0.5)))
            publish()
            save(force = true)
            return
        }
        val (idx, off) = b.locate(pos.coerceIn(0.0, maxOf(0.0, b.duration - 0.5)))
        // Round up so a jump to a chapter start lands inside that chapter, not a hair before it.
        exo.seekTo(idx, Math.ceil(off * 1000).toLong())
        publish()
        save(force = true)
    }

    fun skip(seconds: Int) = seekTo(position() + seconds)

    fun nextChapter() {
        val b = book ?: return
        val i = b.chapterIndex(position())
        if (i + 1 < b.chapters.size) seekTo(b.chapters[i + 1].start, true, "Jumped to ${b.chapters[i + 1].title}")
    }

    /** Back to the start of this chapter, or to the previous one if we're within 3 s of the start. */
    fun previousChapter() {
        val b = book ?: return
        val pos = position()
        val i = b.chapterIndex(pos)
        if (i < 0) return seekTo(0.0)
        val target = if (pos - b.chapters[i].start > 3 || i == 0) b.chapters[i].start else b.chapters[i - 1].start
        seekTo(target, true, "Jumped back")
    }

    fun jumpToChapter(i: Int) {
        val b = book ?: return
        b.chapters.getOrNull(i)?.let { seekTo(it.start, true, "Jumped to ${it.title}") }
    }

    fun undoJump() {
        val u = _undo.value ?: return
        _undo.value = null
        seekTo(u.from)
    }

    fun clearUndo() {
        _undo.value = null
    }

    fun setSpeed(speed: Float) {
        val s = (Math.round(speed * 20) / 20f).coerceIn(0.5f, 3f)
        exo.setPlaybackSpeed(s)
        if (Caster.casting) Caster.setSpeed(s)
        book?.let { b -> lib.saveProgress(lib.progress(b.id).copy(speed = s)) }
        lib.prefs.update { it.copy(lastSpeed = s) }
        publish()
    }

    fun addBookmark(note: String = "") {
        val b = book ?: return
        lib.addBookmark(b, position(), note)
        messages.tryEmit("Bookmark added")
    }

    // --- casting hand-over ---

    /** A cast session started: move the book to the TV at the same spot, keeping play/pause state. */
    private fun toRemote() {
        val b = book ?: return publish()
        val pos = position()
        val wasPlaying = exo.isPlaying
        exo.pause()
        exo.clearMediaItems() // drops the phone's notification; Cast shows its own while casting
        Caster.load(b, pos, exo.playbackParameters.speed, autoplay = wasPlaying)
        publish()
    }

    /** Back to the phone where the TV left off, paused. */
    private fun toLocal() {
        val b = book ?: return publish()
        val pos = Caster.lastPosition()
        book = null
        open(b, play = false, at = pos)
        save(force = true)
    }

    // --- the loop: state, saving, stats, sleep timer ---

    private suspend fun tick() {
        var last = SystemClock.elapsedRealtime()
        while (true) {
            delay(250)
            val now = SystemClock.elapsedRealtime()
            val dt = now - last
            last = now
            val b = book ?: continue
            val playingNow = if (Caster.casting) Caster.playing && !Caster.buffering else exo.isPlaying
            if (playingNow) {
                listenAcc += dt / 1000.0
                sleepTick(dt)
            }
            val ch = b.chapterIndex(position())
            if (ch != shownChapter && ch >= 0) {
                shownChapter = ch
                updateNotificationTitle(b, ch)
            }
            if (playingNow && now - lastSaved > 3_000) save()
            if (listenAcc > 0 && now - lastFlush > 30_000) flushListening()
            shakeWatch()
            publish()
        }
    }

    private fun publish() {
        val b = book
        val pos = position()
        _state.value = NowState(
            book = b,
            position = pos,
            playing = isPlaying(),
            buffering = if (Caster.casting) Caster.buffering else exo.playbackState == Player.STATE_BUFFERING,
            speed = exo.playbackParameters.speed,
            chapter = b?.chapterIndex(pos) ?: -1,
            sleep = sleepState(),
        )
    }

    private fun save(force: Boolean = false) {
        val b = book ?: return
        val pos = position()
        lastSaved = SystemClock.elapsedRealtime()
        if (!force && Math.abs(pos - savedPos) < 0.5) return
        savedPos = pos
        val p = lib.progress(b.id)
        lib.saveProgress(p.copy(position = pos, speed = exo.playbackParameters.speed, lastPlayed = System.currentTimeMillis()))
    }

    /**
     * Smart AudioBook Player was just playing this book (seen live through its media session):
     * take its spot as Lectern's, no questions, unless Lectern itself is playing right now.
     */
    fun followSabp(b: Book, pos: Double) = main.launch {
        if (book?.id == b.id && isPlaying()) return@launch
        val p = lib.progress(b.id)
        val now = System.currentTimeMillis()
        lib.saveProgress(p.copy(position = pos.coerceIn(0.0, b.duration), finished = false, lastPlayed = now))
        // Its own file save happens around now too; don't ask about it later.
        lib.prefs.setSabpSeen(b.id, now + 120_000)
        if (book?.id == b.id) {
            lastPausedAt = now
            val (idx, off) = b.locate(pos)
            exo.seekTo(idx, Math.ceil(off * 1000).toLong())
            savedPos = pos
            publish()
        }
        android.util.Log.i("Lectern", "Following Smart AudioBook Player: ${b.title} at ${"%.0f".format(pos)} s")
    }

    /** Two-way sync with Lectern on the PC (call when Lectern comes to the front). */
    suspend fun syncFromPc() {
        LecternApp.instance.pcSync.syncAll(
            isPlaying = { id -> book?.id == id && isPlaying() },
            moved = { b, pos ->
                main.launch {
                    if (book?.id == b.id && !isPlaying()) {
                        lastPausedAt = lib.progress(b.id).lastPlayed
                        val (idx, off) = b.locate(pos)
                        exo.seekTo(idx, Math.ceil(off * 1000).toLong())
                        savedPos = pos
                        publish()
                    }
                }
            },
        )
    }

    /** A newer Smart AudioBook Player place for the current book, waiting for the user's answer. */
    private val _sabpOffer = MutableStateFlow<Library.SabpPlace?>(null)
    val sabpOffer: StateFlow<Library.SabpPlace?> = _sabpOffer.asStateFlow()

    /** Checks Smart AudioBook Player's saved places (call when Lectern comes to the front). Never moves you without asking. */
    suspend fun syncFromSabp() {
        val offers = kotlinx.coroutines.withContext(Dispatchers.IO) { lib.pullFromSabp() }
        val b = book
        if (b != null && !isPlaying()) {
            val p = lib.progress(b.id)
            // A silent adoption for the loaded book: move the player there too.
            if (Math.abs(position() - (if (p.finished) 0.0 else p.position)) > 1 && offers.none { it.bookId == b.id }) {
                lastPausedAt = p.lastPlayed
                seekTo(if (p.finished) 0.0 else p.position)
            }
        }
        _sabpOffer.value = offers.firstOrNull { it.bookId == b?.id } ?: offers.maxByOrNull { it.lastPlayed }
    }

    fun acceptSabp() {
        val o = _sabpOffer.value ?: return
        _sabpOffer.value = null
        val from = if (book?.id == o.bookId) position() else null
        lib.adopt(o)
        if (book?.id == o.bookId) {
            pause()
            lastPausedAt = o.lastPlayed
            seekTo(if (o.finished) 0.0 else o.position)
            from?.let { _undo.value = Undo("Moved to Smart AudioBook Player's spot", it) }
        }
    }

    fun declineSabp() {
        val o = _sabpOffer.value ?: return
        _sabpOffer.value = null
        lib.decline(o)
    }

    private fun flushListening() {
        val b = book ?: return
        lastFlush = SystemClock.elapsedRealtime()
        if (listenAcc < 1) return
        lib.addListening(LocalDate.now().toString(), b.id, listenAcc)
        listenAcc = 0.0
    }

    /** The notification and lock screen show the chapter as the title, the book as the subtitle. */
    private fun updateNotificationTitle(b: Book, ch: Int) {
        if (b.tracks.size > 1 || Caster.casting) return // folder books: each file already carries its chapter title
        val idx = exo.currentMediaItemIndex
        val item = exo.currentMediaItem ?: return
        val title = b.chapters.getOrNull(ch)?.title ?: b.title
        if (item.mediaMetadata.title?.toString() == title) return
        exo.replaceMediaItem(idx, item.buildUpon().setMediaMetadata(meta(b, title, item.mediaMetadata.artworkUri)).build())
    }

    // --- sleep timer ---

    private var sleepMode = 0 // 0 off, 1 countdown, 2 end of chapter
    private var sleepTotalMs = 0L
    private var sleepLeftMs = 0L
    private var sleepChapter = -1
    private var sleepEndedAt = 0L

    private fun sleepState(): SleepState? {
        if (sleepMode == 0) return null
        val left = sleepLeftNow()
        return SleepState(sleepMode == 2, left, left < settings.sleepFade * 1000L)
    }

    private fun sleepLeftNow(): Long {
        val b = book ?: return 0
        return if (sleepMode == 2) {
            val end = b.chapters.getOrNull(sleepChapter)?.end ?: b.duration
            ((end - position()) / exo.playbackParameters.speed * 1000).toLong().coerceAtLeast(0)
        } else sleepLeftMs
    }

    fun startSleep(seconds: Int) {
        sleepMode = 1
        sleepTotalMs = seconds * 1000L
        sleepLeftMs = sleepTotalMs
        exo.volume = 1f
        lib.prefs.update { it.copy(lastSleep = seconds) }
        publish()
    }

    fun startSleepEndOfChapter() {
        sleepMode = 2
        sleepChapter = book?.chapterIndex(position()) ?: -1
        exo.volume = 1f
        lib.prefs.update { it.copy(lastSleep = -1) }
        publish()
    }

    fun extendSleep(minutes: Int = 5) {
        if (sleepMode == 2) { // becomes a plain countdown from what's left
            sleepLeftMs = sleepLeftNow()
            sleepTotalMs = sleepLeftMs
            sleepMode = 1
        }
        sleepLeftMs += minutes * 60_000L
        sleepTotalMs = maxOf(sleepTotalMs, sleepLeftMs)
        exo.volume = 1f
        publish()
    }

    fun cancelSleep() {
        sleepMode = 0
        sleepEndedAt = 0
        exo.volume = 1f
        publish()
    }

    /** Shake: back to the full time you picked (or re-armed on the current chapter), fade cancelled. */
    fun resetSleep() {
        when (sleepMode) {
            1 -> sleepLeftMs = sleepTotalMs
            2 -> sleepChapter = book?.chapterIndex(position()) ?: sleepChapter
            else -> return
        }
        exo.volume = 1f
        Caster.restoreVolume()
        buzz()
        messages.tryEmit(if (sleepMode == 2) "Sleep timer reset to the end of this chapter" else "Sleep timer reset to ${fmtDuration(sleepTotalMs / 1000)}")
        publish()
    }

    /** 30 min · 2 min 30 s · 45 s · 1 h 5 min */
    fun fmtDuration(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return listOfNotNull(
            h.takeIf { it > 0 }?.let { "$it h" },
            m.takeIf { it > 0 }?.let { "$it min" },
            s.takeIf { it > 0 || totalSeconds == 0L }?.let { "$it s" },
        ).joinToString(" ")
    }

    private fun sleepTick(dt: Long) {
        if (sleepMode == 0) return
        if (sleepMode == 1) sleepLeftMs -= dt
        val left = sleepLeftNow()
        val fadeMs = settings.sleepFade * 1000L
        val level = if (fadeMs > 0 && left < fadeMs) (left.toFloat() / fadeMs).coerceIn(0f, 1f) else 1f
        if (Caster.casting) { if (level < 1f) Caster.fade(level) else Caster.restoreVolume() } else exo.volume = level
        if (left <= 0) {
            sleepMode = 0
            pause()
            seekTo(position() - settings.sleepRewind)
            exo.volume = 1f
            Caster.restoreVolume()
            sleepEndedAt = SystemClock.elapsedRealtime()
            messages.tryEmit(if (settings.shakeToReset) "Sleep timer ended. Shake to keep listening." else "Sleep timer ended")
        }
    }

    // --- shake to reset ---

    private var sensors: SensorManager? = null
    private var listening = false
    private var lastShake = 0L

    private val shakeListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / SensorManager.GRAVITY_EARTH
            val now = SystemClock.elapsedRealtime()
            if (g < 2.4f || now - lastShake < 1_500) return
            lastShake = now
            main.launch { onShake() }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun onShake() {
        when {
            sleepMode != 0 -> resetSleep()
            // Fell asleep too early? Shaking soon after the timer stopped resumes and starts it again.
            SystemClock.elapsedRealtime() - sleepEndedAt < 120_000 -> {
                sleepEndedAt = 0
                val last = settings.lastSleep
                if (last < 0) startSleepEndOfChapter() else startSleep(last)
                play()
                buzz()
                messages.tryEmit("Sleep timer restarted")
            }
        }
    }

    /** Only listen to the accelerometer while a timer is running (or just ended), to save battery. */
    private fun shakeWatch() {
        val want = settings.shakeToReset &&
            (sleepMode != 0 || (sleepEndedAt > 0 && SystemClock.elapsedRealtime() - sleepEndedAt < 120_000))
        if (want == listening) return
        val sm = sensors ?: (app.getSystemService(Context.SENSOR_SERVICE) as SensorManager).also { sensors = it }
        if (want) {
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(shakeListener, it, SensorManager.SENSOR_DELAY_GAME) }
        } else {
            sm.unregisterListener(shakeListener)
        }
        listening = want
    }

    private fun buzz() {
        runCatching {
            app.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 80, 60), -1))
        }
    }

    // --- audio effects ---
    //
    // One DynamicsProcessing chain, modelled on desktop Lectern's:
    //   pre-EQ (voice clarity) → multiband compressor (boost / night mode) → limiter at −1 dBFS.
    // With everything off it's transparent apart from a safety limiter that catches clipping.

    private var dp: DynamicsProcessing? = null
    private var fallback: LoudnessEnhancer? = null

    // Upper edge (Hz) and gain (dB) of each pre-EQ band when voice clarity is on.
    private val eqBands = listOf(90f to -7f, 250f to -2f, 1800f to 0f, 4500f to 3.5f, 9000f to 1.5f, 20000f to -1f)
    private val mbcCutoffs = listOf(250f, 4000f, 20000f)

    private fun applyEffects(s: Settings) {
        exo.skipSilenceEnabled = s.skipSilence
        val d = dp ?: runCatching {
            val cfg = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                true, eqBands.size, true, mbcCutoffs.size, false, 0, true,
            ).setPreferredFrameDuration(10f).build()
            DynamicsProcessing(0, sessionId, cfg).also { dp = it }
        }.getOrNull()
        if (d == null) {
            // Very old or unusual devices: the basic loudness enhancer is better than nothing.
            runCatching {
                val l = fallback ?: LoudnessEnhancer(sessionId).also { fallback = it }
                l.setTargetGain(s.boostDb * 100)
                l.enabled = s.boostDb > 0
            }
            return
        }
        runCatching {
            for (ch in 0 until 2) {
                eqBands.forEachIndexed { i, (cutoff, gain) ->
                    d.setPreEqBandByChannelIndex(ch, i, DynamicsProcessing.EqBand(true, cutoff, if (s.voiceClarity) gain else 0f))
                }
                mbcCutoffs.forEachIndexed { i, cutoff -> d.setMbcBandByChannelIndex(ch, i, mbcBand(s, i, cutoff)) }
                d.setLimiterByChannelIndex(
                    ch,
                    // Fast, hard limiter just under full scale: loud without clipping.
                    DynamicsProcessing.Limiter(true, true, 0, 0.5f, 80f, 20f, -1f, 0f),
                )
            }
            d.enabled = true
        }
    }

    private fun mbcBand(s: Settings, index: Int, cutoff: Float): DynamicsProcessing.MbcBand {
        val boost = s.boostDb.toFloat() // 0..24
        // Higher boost = lower threshold and firmer ratio (quiet narration comes up more), plus
        // makeup gain; the −1 dBFS limiter after this keeps peaks from clipping. Worked through
        // for speech averaging −20 dBFS: +15 ≈ −13 dBFS out (as before), +24 ≈ −8 dBFS out.
        // The bass band gets a bit less makeup so loud speech stays clear rather than boomy.
        val makeup = boost * (if (index == 0) 0.75f else 0.95f) + if (s.nightMode) 6f else 0f
        val active = boost > 0 || s.nightMode
        val ratio = when {
            !active -> 1f
            s.nightMode -> maxOf(3f, 1.5f + boost / 9.6f)
            else -> 1.5f + boost / 9.6f
        }
        val threshold = if (s.nightMode) minOf(-34f, -26f - boost / 3f) else -26f - boost / 3f
        // enabled, cutoff, attack ms, release ms, ratio, threshold dB, knee dB,
        // noise-gate threshold, expander ratio, pre-gain, post-gain
        return DynamicsProcessing.MbcBand(
            true, cutoff, 12f, if (s.nightMode) 450f else 220f, ratio, threshold, 10f,
            -90f, 1f, 0f, if (active) makeup else 0f,
        )
    }

    /** Media session view of the player: chapters for next/previous, our play() for play. */
    private class ChapterPlayer(p: ExoPlayer) : ForwardingPlayer(p) {
        private val extra = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
            ).build()

        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon().addAll(extra).build()

        override fun isCommandAvailable(command: Int) = extra.contains(command) || super.isCommandAvailable(command)

        override fun play() = Playback.play()
        override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) Playback.play() else Playback.pause()
        override fun seekToNext() = Playback.nextChapter()
        override fun seekToNextMediaItem() = Playback.nextChapter()
        override fun seekToPrevious() = Playback.previousChapter()
        override fun seekToPreviousMediaItem() = Playback.previousChapter()
        override fun seekBack() = Playback.skip(-Playback.settings.skipBack)
        override fun seekForward() = Playback.skip(Playback.settings.skipForward)
        override fun getSeekBackIncrement() = Playback.settings.skipBack * 1000L
        override fun getSeekForwardIncrement() = Playback.settings.skipForward * 1000L
        override fun hasNextMediaItem() = true
        override fun hasPreviousMediaItem() = true
    }
}

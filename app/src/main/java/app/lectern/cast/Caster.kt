package app.lectern.cast

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import app.lectern.data.Book
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueData
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.Executors

/** Google Cast setup: the default media receiver, with Cast's own lock-screen notification while casting. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .setResumeSavedSession(true)
        .setCastMediaOptions(
            CastMediaOptions.Builder()
                .setNotificationOptions(
                    NotificationOptions.Builder()
                        .setTargetActivityClassName("app.lectern.MainActivity")
                        .setSkipStepMs(30_000)
                        .build()
                )
                .build()
        )
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

data class CastDevice(val id: String, val name: String, val description: String, val connecting: Boolean, val selected: Boolean)

/**
 * Plays the current book on a Chromecast / Google TV / Nest device. The phone stays in charge:
 * it serves the audio (StreamServer) and keeps progress, chapters, speed and the sleep timer.
 */
object Caster {
    private var cast: CastContext? = null
    private lateinit var router: MediaRouter
    private lateinit var selector: MediaRouteSelector

    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    val devices: StateFlow<List<CastDevice>> = _devices.asStateFlow()

    /** Name of the device we're casting to, null when playing on the phone. */
    private val _active = MutableStateFlow<String?>(null)
    val active: StateFlow<String?> = _active.asStateFlow()

    val available get() = cast != null

    /** Called when a session starts (true) or ends (false). */
    var onSessionChange: ((Boolean) -> Unit)? = null
    var onStatus: (() -> Unit)? = null

    private val session: CastSession? get() = cast?.sessionManager?.currentCastSession?.takeIf { it.isConnected }
    val client: RemoteMediaClient? get() = session?.remoteMediaClient
    val casting get() = _active.value != null && client != null

    private var queueOffsets: List<Double> = emptyList()
    private var originalVolume: Double? = null
    private var lastKnown = 0.0

    fun init(context: Context) {
        router = MediaRouter.getInstance(context)
        selector = MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
            .build()
        cast = runCatching { CastContext.getSharedInstance(context, Executors.newSingleThreadExecutor()).result }
            .recoverCatching { CastContext.getSharedInstance(context) }
            .onFailure { Log.w("Lectern", "Google Cast unavailable", it) }
            .getOrNull()
        cast?.sessionManager?.addSessionManagerListener(listener, CastSession::class.java)
        StreamServer.start(context)
    }

    // --- device list for the Play On sheet ---

    private val routeCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteRemoved(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteChanged(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteSelected(r: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()
        override fun onRouteUnselected(r: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()
    }

    fun startDiscovery() {
        if (cast == null) return
        router.addCallback(selector, routeCallback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        refresh()
    }

    fun stopDiscovery() {
        if (cast == null) return
        router.removeCallback(routeCallback)
    }

    private fun refresh() {
        _devices.value = router.routes
            .filter { !it.isDefaultOrBluetooth && it.isEnabled && it.matchesSelector(selector) }
            .map {
                CastDevice(
                    it.id, it.name, it.description ?: "",
                    connecting = it.connectionState == MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTING,
                    selected = it.isSelected,
                )
            }
    }

    fun connect(id: String) {
        router.routes.firstOrNull { it.id == id }?.let { router.selectRoute(it) }
    }

    /** Back to the phone; the session's last position is handed back by onSessionChange. */
    fun disconnect() {
        lastKnown = position() ?: lastKnown
        cast?.sessionManager?.endCurrentSession(true)
    }

    private val listener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(s: CastSession, id: String) = started(s)
        override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) = started(s)
        override fun onSessionEnded(s: CastSession, error: Int) = ended()
        override fun onSessionSuspended(s: CastSession, reason: Int) = Unit
        override fun onSessionStarting(s: CastSession) = Unit
        override fun onSessionStartFailed(s: CastSession, error: Int) = refresh()
        override fun onSessionEnding(s: CastSession) { lastKnown = position() ?: lastKnown }
        override fun onSessionResuming(s: CastSession, id: String) = Unit
        override fun onSessionResumeFailed(s: CastSession, error: Int) = Unit
    }

    private val statusCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            position()?.let { lastKnown = it }
            onStatus?.invoke()
        }
    }

    private fun started(s: CastSession) {
        _active.value = s.castDevice?.friendlyName ?: "TV"
        originalVolume = null
        s.remoteMediaClient?.registerCallback(statusCallback)
        refresh()
        onSessionChange?.invoke(true)
    }

    private fun ended() {
        _active.value = null
        refresh()
        onSessionChange?.invoke(false)
    }

    // --- playback on the receiver ---

    fun load(book: Book, position: Double, speed: Float, autoplay: Boolean) {
        val c = client ?: return
        val (urls, coverUrl) = StreamServer.publish(book.tracks.map { Uri.parse(it.uri) to mimeOf(it.uri, book) }, book.cover?.let(::File))
        queueOffsets = book.tracks.map { it.offset }
        val items = book.tracks.mapIndexed { i, t ->
            val md = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
                putString(MediaMetadata.KEY_TITLE, if (book.tracks.size > 1) t.title else book.title)
                putString(MediaMetadata.KEY_ARTIST, book.author)
                putString(MediaMetadata.KEY_ALBUM_TITLE, book.title)
                coverUrl?.let { addImage(WebImage(Uri.parse(it))) }
            }
            MediaQueueItem.Builder(
                MediaInfo.Builder(urls[i])
                    .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                    .setContentType(mimeOf(t.uri, book))
                    .setStreamDuration((t.duration * 1000).toLong())
                    .setMetadata(md)
                    .build()
            ).build()
        }
        val (idx, off) = book.locate(position)
        lastKnown = position
        c.load(
            MediaLoadRequestData.Builder()
                .setQueueData(MediaQueueData.Builder().setItems(items).setStartIndex(idx).setRepeatMode(MediaStatus.REPEAT_MODE_REPEAT_OFF).build())
                .setCurrentTime((off * 1000).toLong())
                .setAutoplay(autoplay)
                .setPlaybackRate(speed.coerceIn(0.5f, 2f).toDouble())
                .build()
        )
    }

    /** Book position on the receiver, seconds; null if not known (yet). */
    fun position(): Double? {
        val c = client ?: return null
        val st = c.mediaStatus ?: return null
        val idx = st.getIndexById(st.currentItemId) ?: 0
        val off = queueOffsets.getOrNull(idx) ?: 0.0
        return off + c.approximateStreamPosition / 1000.0
    }

    fun lastPosition() = lastKnown

    val playing get() = client?.let { it.isPlaying || it.isBuffering || it.isLoadingNextItem } ?: false
    val buffering get() = client?.isBuffering ?: false
    val finished get() = client?.mediaStatus?.let { it.playerState == MediaStatus.PLAYER_STATE_IDLE && it.idleReason == MediaStatus.IDLE_REASON_FINISHED } ?: false

    fun play() { client?.play() }
    fun pause() { client?.pause() }

    fun seek(book: Book, pos: Double) {
        val c = client ?: return
        val st = c.mediaStatus ?: return
        val (idx, off) = book.locate(pos)
        lastKnown = pos
        val current = st.getIndexById(st.currentItemId) ?: 0
        if (idx == current) {
            c.seek(MediaSeekOptions.Builder().setPosition(Math.ceil(off * 1000).toLong()).build())
        } else {
            st.getQueueItem(idx)?.let { c.queueJumpToItem(it.itemId, Math.ceil(off * 1000).toLong(), null) }
        }
    }

    fun setSpeed(speed: Float) { client?.setPlaybackRate(speed.coerceIn(0.5f, 2f).toDouble()) }

    /** Sleep-timer fade on the TV: scales the device volume, remembering where it started. */
    fun fade(level: Float) {
        val s = session ?: return
        val base = originalVolume ?: s.volume.also { originalVolume = it }
        runCatching { s.volume = (base * level).coerceIn(0.0, 1.0) }
    }

    fun restoreVolume() {
        val v = originalVolume ?: return
        originalVolume = null
        runCatching { session?.volume = v }
    }

    private fun mimeOf(uri: String, book: Book): String {
        val name = Uri.decode(uri).substringAfterLast('/').lowercase()
        return when {
            name.endsWith(".mp3") -> "audio/mpeg"
            name.endsWith(".ogg") || name.endsWith(".oga") || name.endsWith(".opus") -> "audio/ogg"
            name.endsWith(".flac") -> "audio/flac"
            name.endsWith(".wav") -> "audio/wav"
            name.endsWith(".aac") -> "audio/aac"
            else -> "audio/mp4"
        }
    }
}

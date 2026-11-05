package app.lectern.play

import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import app.lectern.LecternApp

/**
 * With notification access granted, Android keeps this running in the background (also with
 * the phone locked), which lets Lectern follow Smart AudioBook Player's media session live:
 * while it plays, and the moment it pauses, Lectern records that spot for the same book.
 * Lectern never reads or acts on notifications themselves.
 */
class SabpListener : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())
    private var msm: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var wasPlaying = false
    private var lastReport = 0L

    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { attach(it) }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = onState(state)
        override fun onSessionDestroyed() = attach(null)
    }

    /** While it plays, report its spot every 30 s (state callbacks only fire on changes). */
    private val tick = object : Runnable {
        override fun run() {
            if (wasPlaying) {
                report()
                main.postDelayed(this, 30_000)
            }
        }
    }

    override fun onListenerConnected() {
        val m = getSystemService(MediaSessionManager::class.java)
        msm = m
        val me = ComponentName(this, SabpListener::class.java)
        runCatching {
            m.addOnActiveSessionsChangedListener(sessions, me, main)
            attach(m.getActiveSessions(me))
        }
    }

    override fun onListenerDisconnected() {
        runCatching { msm?.removeOnActiveSessionsChangedListener(sessions) }
        attach(null)
    }

    private fun attach(list: List<MediaController>?) {
        val c = list?.firstOrNull { it.packageName == SabpLink.PKG }
        if (c?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(callback)
        controller = c
        c?.registerCallback(callback, main)
        onState(c?.playbackState)
    }

    private fun onState(st: PlaybackState?) {
        if (st == null || SabpLink.moving) return
        val playing = st.state == PlaybackState.STATE_PLAYING
        if (playing && !wasPlaying) {
            wasPlaying = true
            main.removeCallbacks(tick)
            main.postDelayed(tick, 30_000)
        } else if (!playing && wasPlaying && st.state != PlaybackState.STATE_BUFFERING) {
            // It just stopped (you paused, its sleep timer ended, …): this is the spot to keep.
            wasPlaying = false
            main.removeCallbacks(tick)
            report()
        }
    }

    private fun report() {
        val c = controller ?: return
        val st = c.playbackState ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastReport < 1_000) return
        lastReport = now
        var pos = st.position / 1000.0
        if (st.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0) {
            pos += (now - st.lastPositionUpdateTime) / 1000.0 * st.playbackSpeed
        }
        val lib = LecternApp.instance.library
        val book = lib.books.value.firstOrNull { SabpLink.sameBook(c, it.book) }?.book ?: return
        Playback.followSabp(book, pos)
    }
}

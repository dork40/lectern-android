package app.lectern.play

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.Uri
import android.util.Log
import app.lectern.data.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Moves Smart AudioBook Player to Lectern's spot through its media session (the controls on
 * the lock screen). It keeps its place privately and ignores outside files, and its media
 * browser only admits Android Auto, so this needs notification access, which is what lets
 * one app reach another app's media session.
 */
object SabpLink {
    const val PKG = "ak.alizandro.smartaudiobookplayer"

    /** True while Lectern is pressing its buttons, so the watcher ignores those moves. */
    @Volatile var moving = false

    sealed interface Result {
        data class Moved(val from: Double, val to: Double) : Result
        data object AlreadyThere : Result
        data object OtherBook : Result
        data object Playing : Result
        data object Unreachable : Result
        /** Lectern needs notification access to reach its media controls. */
        data object NeedsAccess : Result
        /** It isn't running, so there's nothing to move (it will open at its own saved spot). */
        data object NotRunning : Result
        data class Failed(val at: Double) : Result
    }

    fun hasAccess(context: Context): Boolean =
        android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.contains(ComponentName(context, SabpListener::class.java).flattenToString()) == true

    /** Opens the system page where you allow Lectern's notification access. */
    fun openAccessSettings(context: Context) {
        val detail = android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(context, SabpListener::class.java).flattenToString())
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(detail) }.onFailure {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun controller(context: Context): MediaController? {
        val msm = context.getSystemService(android.media.session.MediaSessionManager::class.java)
        return msm.getActiveSessions(ComponentName(context, SabpListener::class.java)).firstOrNull { it.packageName == PKG }
    }

    suspend fun moveTo(context: Context, book: Book, target: Double): Result = withContext(Dispatchers.Main) {
        if (!hasAccess(context)) return@withContext Result.NeedsAccess
        try {
            val c = controller(context) ?: return@withContext Result.NotRunning
            if (!sameBook(c, book)) return@withContext Result.OtherBook
            val st = c.playbackState ?: return@withContext Result.Unreachable
            if (st.state == PlaybackState.STATE_PLAYING || st.state == PlaybackState.STATE_BUFFERING) return@withContext Result.Playing
            val from = pos(c)
            // Land at or up to 10 s before Lectern's spot, never after it.
            if (target - from in 0.0..10.0) return@withContext Result.AlreadyThere

            // 1) A direct jump, if it honours one.
            c.transportControls.seekTo((target * 1000).toLong())
            delay(800)
            if (Math.abs(pos(c) - target) < 2) return@withContext Result.Moved(from, pos(c))

            // 2) Its own skip buttons, exposed as session custom actions.
            val actions = c.playbackState?.customActions.orEmpty()
            Log.i("Lectern", "SABP custom actions: ${actions.joinToString { "${it.action}=${it.name}" }}")
            val fwd = actions.firstOrNull { it.name.toString().contains("forward", true) }
            val back = actions.firstOrNull { it.name.toString().contains("rewind", true) }
            if (fwd == null || back == null) return@withContext Result.Failed(pos(c))

            moving = true
            // Its session buttons are its 10-second skips (ActionFwdSmall / ActionRewindSmall).
            // It reports its new position lazily, so count the presses from where it started
            // instead of re-reading after each one.
            val step = 10.0
            val delta = target - from
            val (n, action) = if (delta > 0) (delta / step).toInt() to fwd else Math.ceil(-delta / step).toInt() to back
            repeat(n) {
                c.transportControls.sendCustomAction(action, null)
                delay(15)
            }
            val expected = from + (if (action == fwd) n else -n) * step
            // Wait for it to settle, then check.
            var last = -1.0
            repeat(20) {
                delay(500)
                val p = pos(c)
                if (Math.abs(p - expected) < 1 || (p == last && Math.abs(p - from) > 1)) return@repeat
                last = p
            }
            val end = pos(c)
            // Note: it only writes this to storage when it next pauses or exits by itself. While it
            // stays open (paused) it keeps following Lectern; if Android closes it first, it reopens
            // at its own last saved spot.
            if (target - end in -1.0..(step + 1)) Result.Moved(from, end) else Result.Failed(end)
        } catch (e: Exception) {
            Log.w("Lectern", "Couldn't move Smart AudioBook Player", e)
            Result.Unreachable
        } finally {
            moving = false
        }
    }

    private fun pos(c: MediaController): Double = (c.playbackState?.position ?: 0L) / 1000.0

    /** Its session title is the book's folder/file name; match it loosely against the book's file. */
    fun sameBook(c: MediaController, b: Book): Boolean {
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val theirs = listOfNotNull(c.metadata?.description?.title, c.metadata?.description?.subtitle).joinToString(" ").let(::norm)
        val stem = Uri.decode(b.tracks.first().uri).substringAfterLast('/').substringAfterLast(':').substringBeforeLast('.')
        val folder = Uri.decode(b.tracks.first().uri).substringBeforeLast('/').substringAfterLast('/')
        return theirs.isNotEmpty() && (norm(stem).let { it.isNotEmpty() && theirs.contains(it) } || norm(folder).let { it.length > 5 && theirs.contains(it) })
    }
}

package app.lectern.play

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.lectern.MainActivity
import app.lectern.R
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** Hosts the media session: notification, lock screen, Bluetooth and headset controls. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_NOW_PLAYING, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, Playback.player)
            .setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                // Headset/Bluetooth Play after Lectern was closed: hand back the current book where it stopped.
                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                    MainScope().launch {
                        val r = Playback.resumption()
                        if (r != null) future.set(r) else future.setException(UnsupportedOperationException("Nothing to resume"))
                    }
                    return future
                }
            })
            .build()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_stat_lectern) })
        // Without a connected controller Media3 wouldn't show anything until Play. Adding the session
        // here puts the controls in the notification area as soon as a book is loaded.
        session?.let { addSession(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Like Smart AudioBook Player: swiping Lectern away keeps its controls in the notification
        // area (paused or playing), so you can carry on from the shade or the lock screen.
        // Dismissing the notification while paused is what closes it.
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}

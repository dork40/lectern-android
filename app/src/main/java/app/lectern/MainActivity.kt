package app.lectern

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lectern.play.Playback
import app.lectern.ui.BookScreen
import app.lectern.ui.C
import app.lectern.ui.LecternTheme
import app.lectern.ui.LibraryScreen
import app.lectern.ui.MiniPlayer
import app.lectern.ui.NowPlayingScreen
import app.lectern.ui.OnboardingScreen
import app.lectern.ui.SettingsScreen
import app.lectern.ui.StatsScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val openNowPlaying = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        openNowPlaying.value = intent?.getBooleanExtra(EXTRA_NOW_PLAYING, false) == true
        setContent { LecternTheme { Root(openNowPlaying) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_NOW_PLAYING, false)) openNowPlaying.value = true
    }

    companion object {
        const val EXTRA_NOW_PLAYING = "now_playing"
    }
}

@Composable
private fun Root(openNowPlaying: androidx.compose.runtime.MutableState<Boolean>) {
    val lib = LecternApp.instance.library
    val settings by lib.prefs.settings.collectAsStateWithLifecycle()
    val loaded by lib.loaded.collectAsStateWithLifecycle()
    val now by Playback.state.collectAsStateWithLifecycle()
    val undo by Playback.undo.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var screen by rememberSaveable { mutableStateOf("library") }
    var bookId by rememberSaveable { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(openNowPlaying.value) {
        if (openNowPlaying.value) {
            expanded = true
            openNowPlaying.value = false
        }
    }
    LaunchedEffect(Unit) { lib.messages.collect { snackbar.showSnackbar(it) } }
    // Coming back to Lectern: if Smart AudioBook Player played since, continue from its spot.
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.lifecycle.compose.LifecycleResumeEffect(loaded) {
        lib.recheckSabpAccess()
        if (loaded) scope.launch {
            Playback.syncFromSabp()
            Playback.syncFromPc()
        }
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) { Playback.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(undo) {
        val u = undo ?: return@LaunchedEffect
        val r = snackbar.showSnackbar(u.label.ifBlank { "Moved" }, actionLabel = "Undo", duration = SnackbarDuration.Short)
        if (r == SnackbarResult.ActionPerformed) Playback.undoJump() else Playback.clearUndo()
    }

    // The playback notification needs permission on Android 13+; ask the first time a book plays.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(now.playing) {
        if (now.playing && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val offer by Playback.sabpOffer.collectAsStateWithLifecycle()
    offer?.let { o -> SabpDialog(o) }

    Box(Modifier.fillMaxSize().background(C.Black)) {
        if (!loaded) return@Box
        if (settings.libraryUri == null) {
            OnboardingScreen()
            return@Box
        }
        val miniPad = if (now.book != null) 84.dp else 0.dp
        AnimatedContent(screen to bookId, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(160)) }, label = "screen") { (s, id) ->
            when (s) {
                "book" -> BookScreen(id ?: "", onBack = { screen = "library" }, onNowPlaying = { expanded = true }, bottomPad = miniPad)
                "stats" -> StatsScreen(onBack = { screen = "library" }, bottomPad = miniPad)
                "settings" -> SettingsScreen(onBack = { screen = "library" }, bottomPad = miniPad)
                else -> LibraryScreen(
                    onBook = { bookId = it; screen = "book" },
                    onNowPlaying = { expanded = true },
                    onStats = { screen = "stats" },
                    onSettings = { screen = "settings" },
                    bottomPad = miniPad,
                )
            }
        }
        if (now.book != null) {
            MiniPlayer(onOpen = { expanded = true }, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
        AnimatedVisibility(
            visible = expanded && now.book != null,
            enter = slideInVertically(tween(300)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(250)) { it } + fadeOut(tween(200)),
        ) {
            NowPlayingScreen(onClose = { expanded = false })
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (expanded) 16.dp else miniPad + 8.dp, start = 12.dp, end = 12.dp)) {
            Snackbar(it, containerColor = C.Surface2, contentColor = C.Text, actionColor = C.Accent)
        }
    }
}

@Composable
private fun SabpDialog(o: Library.SabpPlace) {
    val lib = LecternApp.instance.library
    val lb = lib.get(o.bookId) ?: return
    val b = lb.book
    fun where(pos: Double): String {
        val i = b.chapterIndex(pos)
        val ch = b.chapters.getOrNull(i) ?: return app.lectern.ui.fmtClock(pos)
        return "${ch.title} (${app.lectern.ui.fmtClock(pos - ch.start)} in)"
    }
    val now by Playback.state.collectAsStateWithLifecycle()
    val lecternPos = if (now.book?.id == b.id) now.position else lb.progress.position
    val whenPlayed = java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.getDefault()).format(java.util.Date(o.lastPlayed))
    androidx.compose.material3.AlertDialog(
        onDismissRequest = {},
        containerColor = C.Surface2,
        title = { androidx.compose.material3.Text("Smart AudioBook Player is somewhere else") },
        text = {
            androidx.compose.foundation.layout.Column {
                androidx.compose.material3.Text(b.title, style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
                androidx.compose.material3.Text("Smart AudioBook Player ($whenPlayed):\n${if (o.finished) "Finished" else where(o.position)}", color = C.Muted)
                androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
                androidx.compose.material3.Text("Lectern:\n${where(lecternPos)}", color = C.Muted)
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(Playback::acceptSabp) { androidx.compose.material3.Text("Switch to that spot", color = C.Accent) } },
        dismissButton = { androidx.compose.material3.TextButton(Playback::declineSabp) { androidx.compose.material3.Text("Keep Lectern's spot", color = C.Text) } },
    )
}


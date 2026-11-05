package app.lectern.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lectern.LecternApp
import app.lectern.play.Playback

enum class NpSheet { NONE, SPEED, SLEEP, CHAPTERS, BOOKMARKS, AUDIO, PLAY_ON }

@Composable
fun NowPlayingScreen(onClose: () -> Unit) {
    val now by Playback.state.collectAsStateWithLifecycle()
    val settings by LecternApp.instance.library.prefs.settings.collectAsStateWithLifecycle()
    val b = now.book ?: return
    val (glow, dark) = rememberCoverColors(b)
    var sheet by remember { mutableStateOf(NpSheet.NONE) }
    var drag by remember { mutableFloatStateOf(0f) }

    BackHandler(onBack = onClose)

    val ch = b.chapters.getOrNull(now.chapter)
    val chStart = ch?.start ?: 0.0
    val chDur = ch?.duration ?: b.duration
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val chFrac = scrubbing ?: ((now.position - chStart) / chDur).toFloat().coerceIn(0f, 1f)
    val shownPos = chStart + chFrac * chDur
    val leftInBook = (b.duration - now.position) / now.speed

    Box(
        Modifier
            .fillMaxSize()
            .background(C.Black)
            .background(Brush.linearGradient(listOf(glow.copy(alpha = 0.55f), dark.copy(alpha = 0.6f), C.Black)))
            .pointerInput(Unit) {
                // Swipe down to close, like most music players.
                detectVerticalDragGestures(
                    onDragEnd = { if (drag > 220f) onClose(); drag = 0f },
                    onDragCancel = { drag = 0f },
                ) { _, dy -> drag = (drag + dy).coerceAtLeast(0f) }
            },
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Close", modifier = Modifier.size(30.dp)) }
                Spacer(Modifier.weight(1f))
                val castingTo by app.lectern.cast.Caster.active.collectAsStateWithLifecycle()
                if (castingTo != null) {
                    Row(
                        Modifier.clip(CircleShape).background(C.Accent.copy(alpha = 0.2f)).clickable { sheet = NpSheet.PLAY_ON }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.CastConnected, "Playing on $castingTo", tint = C.Accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(castingTo ?: "", style = MaterialTheme.typography.labelLarge, color = C.Accent, maxLines = 1)
                    }
                } else if (app.lectern.cast.Caster.available) {
                    IconButton({ sheet = NpSheet.PLAY_ON }) { Icon(Icons.Rounded.Cast, "Play on…") }
                }
                SleepButton(now.sleep) { sheet = NpSheet.SLEEP }
                IconButton({ sheet = NpSheet.CHAPTERS }) { Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, "Chapters") }
            }

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val size = minOf(maxWidth, maxHeight - 12.dp, 380.dp)
                Cover(b, Modifier.size(size), radius = 18.dp, elevation = 24.dp)
            }

            Spacer(Modifier.height(20.dp))
            Text(b.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (b.author.isNotBlank()) {
                Text(b.author, style = MaterialTheme.typography.bodyLarge, color = C.Text.copy(alpha = 0.75f), maxLines = 1)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                listOfNotNull(ch?.title, if (b.chapters.size > 1) "${now.chapter + 1} of ${b.chapters.size}" else null).joinToString("  ·  "),
                style = MaterialTheme.typography.titleSmall, color = C.Text.copy(alpha = 0.9f), textAlign = TextAlign.Center,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(10.dp))
            Slider(
                value = chFrac,
                onValueChange = { scrubbing = it },
                onValueChangeFinished = {
                    scrubbing?.let { Playback.seekTo(chStart + it * chDur, undoable = true, label = "Moved to ${fmtClock(chStart + it * chDur)}") }
                    scrubbing = null
                },
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = C.Accent, inactiveTrackColor = Color.White.copy(alpha = 0.18f)),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(fmtClock(shownPos - chStart), style = MaterialTheme.typography.bodySmall, color = C.Text.copy(alpha = 0.7f))
                Spacer(Modifier.weight(1f))
                Text("-" + fmtClock((chStart + chDur - shownPos) / now.speed), style = MaterialTheme.typography.bodySmall, color = C.Text.copy(alpha = 0.7f))
            }
            Text(
                "${fmtLong(leftInBook)} left in book · ${(now.position / b.duration * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall, color = C.Text.copy(alpha = 0.6f),
            )

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(Playback::previousChapter, Modifier.size(52.dp)) { Icon(Icons.Rounded.SkipPrevious, "Previous chapter", modifier = Modifier.size(32.dp)) }
                IconButton({ Playback.skip(-settings.skipBack) }, Modifier.size(56.dp)) { SkipIcon(Icons.Rounded.Replay, settings.skipBack, forward = false, size = 38.dp) }
                Box(
                    Modifier.size(78.dp).clip(CircleShape).background(C.Accent).clickable(onClick = Playback::toggle),
                    contentAlignment = Alignment.Center,
                ) {
                    if (now.buffering && now.playing) CircularProgressIndicator(color = C.OnAccent, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
                    else Icon(if (now.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (now.playing) "Pause" else "Play", tint = C.OnAccent, modifier = Modifier.size(42.dp))
                }
                IconButton({ Playback.skip(settings.skipForward) }, Modifier.size(56.dp)) { SkipIcon(Icons.Rounded.Replay, settings.skipForward, forward = true, size = 38.dp) }
                IconButton(Playback::nextChapter, Modifier.size(52.dp)) { Icon(Icons.Rounded.SkipNext, "Next chapter", modifier = Modifier.size(32.dp)) }
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(fmtSpeed(now.speed)) { sheet = NpSheet.SPEED }
                PillIcon(Icons.Rounded.GraphicEq, "Audio", active = settings.boostDb > 0 || settings.voiceClarity || settings.skipSilence || settings.nightMode) { sheet = NpSheet.AUDIO }
                PillIcon(Icons.Rounded.BookmarkAdd, "Add bookmark") { Playback.addBookmark() }
                PillIcon(Icons.Rounded.Bookmarks, "Bookmarks") { sheet = NpSheet.BOOKMARKS }
            }
            Spacer(Modifier.height(18.dp))
        }
    }

    when (sheet) {
        NpSheet.SPEED -> SpeedSheet(now.speed) { sheet = NpSheet.NONE }
        NpSheet.SLEEP -> SleepSheet(now.sleep) { sheet = NpSheet.NONE }
        NpSheet.CHAPTERS -> ChaptersSheet(b, now.chapter) { sheet = NpSheet.NONE }
        NpSheet.BOOKMARKS -> BookmarksSheet(b) { sheet = NpSheet.NONE }
        NpSheet.AUDIO -> AudioSheet { sheet = NpSheet.NONE }
        NpSheet.PLAY_ON -> PlayOnSheet { sheet = NpSheet.NONE }
        NpSheet.NONE -> Unit
    }
}

@Composable
private fun SleepButton(sleep: app.lectern.play.SleepState?, onClick: () -> Unit) {
    if (sleep == null) {
        IconButton(onClick) { Icon(Icons.Rounded.Bedtime, "Sleep timer") }
    } else {
        Row(
            Modifier.clip(CircleShape).background(C.Accent.copy(alpha = 0.2f)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Bedtime, "Sleep timer", tint = C.Accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(fmtClock(sleep.leftMs / 1000.0), style = MaterialTheme.typography.labelLarge, color = C.Accent)
        }
    }
}

@Composable
private fun Pill(text: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.1f)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun PillIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(14.dp)).background(if (active) C.Accent.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.1f))
            .clickable(onClick = onClick).padding(10.dp),
    ) { Icon(icon, label, tint = if (active) C.Accent else C.Text, modifier = Modifier.size(22.dp)) }
}

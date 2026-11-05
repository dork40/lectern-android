package app.lectern.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lectern.LecternApp
import app.lectern.data.Bookmark
import app.lectern.data.Status
import app.lectern.play.Playback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun BookScreen(id: String, onBack: () -> Unit, onNowPlaying: () -> Unit, bottomPad: Dp) {
    val lib = LecternApp.instance.library
    val books by lib.books.collectAsStateWithLifecycle()
    val now by Playback.state.collectAsStateWithLifecycle()
    val tick by lib.bookmarkTick.collectAsStateWithLifecycle()
    val lb = books.firstOrNull { it.book.id == id }
    if (lb == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }
        return
    }
    val b = lb.book
    val isCurrent = now.book?.id == id
    val position = if (isCurrent) now.position else lb.progress.position
    val current = b.chapterIndex(position)
    val (glow, dark) = rememberCoverColors(b)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val marks by produceState(emptyList<Bookmark>(), id, tick) { value = withContext(Dispatchers.IO) { lib.bookmarks(id) } }

    BackHandler(onBack = onBack)
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize().background(C.Black)) {
        Box(Modifier.fillMaxWidth().height(460.dp).background(Brush.verticalGradient(listOf(glow.copy(alpha = 0.45f), dark.copy(alpha = 0.35f), C.Black))))
        LazyColumn(contentPadding = PaddingValues(top = top, bottom = bottom + bottomPad + 24.dp), modifier = Modifier.fillMaxSize()) {
            item {
                Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                    IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Cover(b, Modifier.size(230.dp), radius = 18.dp, elevation = 18.dp)
                    Spacer(Modifier.height(22.dp))
                    Text(b.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    if (b.author.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(b.author, style = MaterialTheme.typography.titleMedium, color = C.Text.copy(alpha = 0.85f))
                    }
                    val details = listOfNotNull(
                        b.narrator.takeIf { it.isNotBlank() }?.let { "Read by $it" },
                        b.seriesLabel.takeIf { it.isNotBlank() },
                        listOf(b.year, fmtLong(b.duration), "${b.chapters.size} chapters").filter { it.isNotBlank() }.joinToString(" · "),
                    )
                    details.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = C.Muted, textAlign = TextAlign.Center) }
                    Spacer(Modifier.height(18.dp))
                    if (lb.status == Status.STARTED || isCurrent) {
                        ThinBar((position / b.duration).toFloat(), Modifier.padding(horizontal = 20.dp))
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${fmtLong(b.duration - position)} left · ${(position / b.duration * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall, color = C.Muted,
                        )
                        Spacer(Modifier.height(14.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        val playing = isCurrent && now.playing
                        Button(
                            onClick = {
                                if (isCurrent) Playback.toggle() else { Playback.open(b, play = true); onNowPlaying() }
                            },
                            colors = ButtonDefaults.buttonColors(C.Accent, C.OnAccent),
                            contentPadding = PaddingValues(horizontal = 26.dp, vertical = 14.dp),
                        ) {
                            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when {
                                    playing -> "Pause"
                                    lb.status == Status.STARTED || isCurrent -> "Resume"
                                    lb.status == Status.FINISHED -> "Listen again"
                                    else -> "Play"
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        OutlinedButton(onClick = { lib.setFinished(id, lb.status != Status.FINISHED) }) {
                            Icon(
                                if (lb.status == Status.FINISHED) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                                tint = if (lb.status == Status.FINISHED) C.Accent else C.Muted, modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (lb.status == Status.FINISHED) "Finished" else "Mark finished", color = C.Text)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                TabRow(
                    selectedTabIndex = tab, containerColor = Color.Transparent, contentColor = C.Text,
                    indicator = { pos -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(pos[tab]), color = C.Accent) },
                ) {
                    Tab(tab == 0, { tab = 0 }, text = { Text("Chapters · ${b.chapters.size}") })
                    Tab(tab == 1, { tab = 1 }, text = { Text("Bookmarks · ${marks.size}") })
                }
                Spacer(Modifier.height(4.dp))
            }
            if (tab == 0) {
                itemsIndexed(b.chapters, key = { i, _ -> "c$i" }) { i, ch ->
                    val active = i == current && (isCurrent || lb.status == Status.STARTED)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isCurrent) Playback.jumpToChapter(i) else Playback.open(b, play = true, at = ch.start)
                                onNowPlaying()
                            }
                            .background(if (active) C.AccentDim.copy(alpha = 0.22f) else Color.Transparent)
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${i + 1}", style = MaterialTheme.typography.labelMedium, color = if (active) C.Accent else C.Faint,
                            modifier = Modifier.width(36.dp),
                        )
                        Text(
                            ch.title, style = MaterialTheme.typography.bodyLarge, color = if (active) C.Accent else if (ch.end <= position) C.Muted else C.Text,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(fmtClock(ch.duration), style = MaterialTheme.typography.bodySmall, color = C.Muted)
                    }
                }
            } else {
                if (marks.isEmpty()) item {
                    Text(
                        "No bookmarks yet. Tap the bookmark button while listening to save a spot.",
                        color = C.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(24.dp),
                    )
                }
                itemsIndexed(marks, key = { _, m -> "m${m.id}" }) { _, m ->
                    BookmarkRow(b, m, onClick = {
                        if (isCurrent) Playback.seekTo(m.position, true, "Jumped to bookmark") else Playback.open(b, play = true, at = m.position)
                        onNowPlaying()
                    })
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(top).background(Brush.verticalGradient(listOf(C.Black, C.Black.copy(alpha = 0.6f)))))
    }
}

@Composable
fun BookmarkRow(b: app.lectern.data.Book, m: Bookmark, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(C.AccentDim.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Bookmark, null, tint = C.Accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(m.note.ifBlank { b.chapters.getOrNull(b.chapterIndex(m.position))?.title ?: "Bookmark" }, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                fmtClock(m.position) + (if (m.note.isNotBlank()) " · " + (b.chapters.getOrNull(b.chapterIndex(m.position))?.title ?: "") else ""),
                style = MaterialTheme.typography.bodySmall, color = C.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

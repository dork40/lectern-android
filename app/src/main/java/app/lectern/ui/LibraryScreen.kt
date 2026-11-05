package app.lectern.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lectern.LecternApp
import app.lectern.data.LibraryBook
import app.lectern.data.Status
import app.lectern.play.Playback

private fun LazyGridScope.full(key: String, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
fun LibraryScreen(onBook: (String) -> Unit, onNowPlaying: () -> Unit, onStats: () -> Unit, onSettings: () -> Unit, bottomPad: androidx.compose.ui.unit.Dp) {
    val lib = LecternApp.instance.library
    val books by lib.books.collectAsStateWithLifecycle()
    val scan by lib.scan.collectAsStateWithLifecycle()
    val settings by lib.prefs.settings.collectAsStateWithLifecycle()
    val now by Playback.state.collectAsStateWithLifecycle()
    val needsAccess by lib.needsSabpAccess.collectAsStateWithLifecycle()

    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    var sortMenu by remember { mutableStateOf(false) }

    val shown = remember(books, query, filter, settings.sort) {
        val q = query.trim().lowercase()
        books.filter { lb ->
            (q.isEmpty() || listOf(lb.book.title, lb.book.author, lb.book.narrator, lb.book.series).any { it.lowercase().contains(q) }) &&
                when (filter) {
                    "progress" -> lb.status == Status.STARTED
                    "new" -> lb.status == Status.NEW
                    "done" -> lb.status == Status.FINISHED
                    else -> true
                }
        }.sortedWith(
            when (settings.sort) {
                "title" -> compareBy { it.book.title.lowercase() }
                "author" -> compareBy<LibraryBook> { it.book.author.lowercase() }.thenBy { it.book.series.lowercase() }
                    .thenBy { it.book.seriesIndex.toDoubleOrNull() ?: 0.0 }.thenBy { it.book.title.lowercase() }
                "added" -> compareByDescending { it.book.added }
                else -> compareByDescending<LibraryBook> { it.progress.lastPlayed }.thenBy { it.book.author.lowercase() }
                    .thenBy { it.book.seriesIndex.toDoubleOrNull() ?: 0.0 }
            }
        )
    }
    val hero = remember(books, now.book?.id) {
        now.book?.let { b -> books.firstOrNull { it.book.id == b.id } }
            ?: books.filter { it.status == Status.STARTED }.maxByOrNull { it.progress.lastPlayed }
    }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize().background(C.Black)) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + 8.dp, bottom = bottom + bottomPad + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            full("header") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Library", style = MaterialTheme.typography.headlineLarge)
                        Text(
                            "${books.size} book${if (books.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodyMedium, color = C.Muted,
                        )
                    }
                    IconButton({ searching = !searching; if (!searching) query = "" }) {
                        Icon(if (searching) Icons.Rounded.Close else Icons.Rounded.Search, "Search")
                    }
                    IconButton(onStats) { Icon(Icons.Rounded.BarChart, "Listening stats") }
                    IconButton(onSettings) { Icon(Icons.Rounded.Settings, "Settings") }
                }
            }
            if (searching) full("search") {
                OutlinedTextField(
                    query, { query = it }, singleLine = true, placeholder = { Text("Title, author, narrator, series") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Accent, unfocusedBorderColor = C.Stroke, cursorColor = C.Accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (needsAccess && settings.syncSabp) full("sabp-access") { AccessBanner() }
            if (scan != null) full("scan") {
                Column {
                    Text("Scanning your Audiobooks folder…", style = MaterialTheme.typography.bodySmall, color = C.Muted)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { scan ?: 0f }, color = C.Accent, trackColor = C.Stroke, modifier = Modifier.fillMaxWidth().clip(CircleShape))
                }
            }
            if (hero != null && query.isBlank()) full("hero") {
                HeroCard(hero, isCurrent = now.book?.id == hero.book.id, playing = now.playing && now.book?.id == hero.book.id, chapter = if (now.book?.id == hero.book.id) now.chapter else hero.book.chapterIndex(hero.progress.position), onOpen = {
                    if (now.book?.id == hero.book.id) onNowPlaying() else onBook(hero.book.id)
                })
            }
            full("filters") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("all" to "All", "progress" to "In progress", "new" to "Not started", "done" to "Finished").forEach { (k, label) ->
                            FilterChip(
                                selected = filter == k, onClick = { filter = k }, label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = C.AccentDim.copy(alpha = 0.5f), selectedLabelColor = C.Text),
                            )
                        }
                    }
                    Box {
                        IconButton({ sortMenu = true }) { Icon(Icons.Rounded.Sort, "Sort") }
                        DropdownMenu(sortMenu, { sortMenu = false }, containerColor = C.Surface2) {
                            listOf("recent" to "Recently played", "title" to "Title", "author" to "Author & series", "added" to "Recently added").forEach { (k, label) ->
                                DropdownMenuItem(
                                    text = { Text(label, color = if (settings.sort == k) C.Accent else C.Text) },
                                    onClick = { lib.prefs.update { it.copy(sort = k) }; sortMenu = false },
                                )
                            }
                        }
                    }
                }
            }
            items(shown, key = { it.book.id }) { lb -> BookTile(lb, onClick = { onBook(lb.book.id) }) }
            if (shown.isEmpty() && scan == null) full("empty") {
                Text(
                    if (books.isEmpty()) "No audiobooks found yet. Put books in your Audiobooks folder, or pick another folder in Settings."
                    else "Nothing matches.",
                    color = C.Muted, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 40.dp, horizontal = 8.dp),
                )
            }
        }
        // Keep content from colliding with the status bar while scrolling.
        Box(Modifier.fillMaxWidth().height(top).background(Brush.verticalGradient(listOf(C.Black, C.Black.copy(alpha = 0.7f)))))
    }
}

@Composable
private fun HeroCard(lb: LibraryBook, isCurrent: Boolean, playing: Boolean, chapter: Int, onOpen: () -> Unit) {
    val b = lb.book
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(C.AccentDim.copy(alpha = 0.35f), C.Surface)))
            .clickable(onClick = onOpen)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(b, Modifier.size(96.dp), radius = 12.dp, elevation = 6.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("CONTINUE LISTENING", style = MaterialTheme.typography.labelSmall, color = C.Accent)
            Spacer(Modifier.height(4.dp))
            Text(b.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            b.chapters.getOrNull(chapter)?.let {
                Text(it.title, style = MaterialTheme.typography.bodySmall, color = C.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            ThinBar(lb.fraction)
            Spacer(Modifier.height(4.dp))
            Text(
                "${fmtLong(b.duration - lb.progress.position)} left · ${(lb.fraction * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall, color = C.Muted,
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.size(54.dp).clip(CircleShape).background(C.Accent).clickable {
                if (isCurrent) Playback.toggle() else Playback.open(b, play = true)
            },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = C.OnAccent, modifier = Modifier.size(30.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookTile(lb: LibraryBook, onClick: () -> Unit) {
    val b = lb.book
    Column(Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(4.dp)) {
        Box {
            Cover(b, Modifier.fillMaxWidth().aspectRatio(1f), radius = 12.dp, elevation = 4.dp)
            if (lb.status == Status.FINISHED) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(26.dp).clip(CircleShape).background(C.Accent),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.CheckCircle, "Finished", tint = C.OnAccent, modifier = Modifier.size(20.dp)) }
            }
        }
        if (lb.status == Status.STARTED) {
            Spacer(Modifier.height(8.dp))
            ThinBar(lb.fraction)
        }
        Spacer(Modifier.height(8.dp))
        Text(b.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOf(b.author, b.seriesIndex.takeIf { it.isNotBlank() }?.let { "#$it" }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = C.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun MiniPlayer(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val now by Playback.state.collectAsStateWithLifecycle()
    val b = now.book ?: return
    val ch = b.chapters.getOrNull(now.chapter)
    val frac = ch?.let { ((now.position - it.start) / it.duration).toFloat() } ?: 0f
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(C.Surface2)
            .clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(b, Modifier.size(44.dp), radius = 8.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(b.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ch?.title ?: b.author, style = MaterialTheme.typography.bodySmall, color = C.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton({ Playback.skip(-LecternApp.instance.library.prefs.value.skipBack) }) {
                SkipIcon(Icons.Rounded.Replay, LecternApp.instance.library.prefs.value.skipBack, forward = false, size = 28.dp)
            }
            IconButton(Playback::toggle) {
                Icon(if (now.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (now.playing) "Pause" else "Play", modifier = Modifier.size(32.dp))
            }
        }
        ThinBar(frac, Modifier.padding(horizontal = 14.dp).padding(bottom = 8.dp), track = Color.White.copy(alpha = 0.08f))
    }
}

@Composable
private fun AccessBanner() {
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.Surface2)
            .clickable { app.lectern.play.SabpLink.openAccessSettings(context) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Sync, null, tint = C.Accent, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Keep Smart AudioBook Player at your spot", style = MaterialTheme.typography.titleSmall)
            Text(
                "Tap, then turn on “Allow notification access” for Lectern. That's what lets it press Smart AudioBook Player's skip buttons when you pause. Lectern doesn't read your notifications.",
                style = MaterialTheme.typography.bodySmall, color = C.Muted,
            )
        }
    }
}


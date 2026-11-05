package app.lectern.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lectern.LecternApp
import app.lectern.data.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private data class Stats(
    val today: Double, val week: Double, val total: Double, val streak: Int,
    val days: List<Pair<LocalDate, Double>>, val top: List<Pair<String, Double>>,
)

@Composable
fun StatsScreen(onBack: () -> Unit, bottomPad: Dp) {
    val lib = LecternApp.instance.library
    val books by lib.books.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    val stats by produceState<Stats?>(null) {
        value = withContext(Dispatchers.IO) {
            val rows = lib.db.listening()
            val byDay = rows.groupBy { it.first }.mapValues { e -> e.value.sumOf { it.third } }
            val today = LocalDate.now()
            var streak = 0
            var d = if ((byDay[today.toString()] ?: 0.0) >= 60) today else today.minusDays(1)
            while ((byDay[d.toString()] ?: 0.0) >= 60) { streak++; d = d.minusDays(1) }
            Stats(
                today = byDay[today.toString()] ?: 0.0,
                week = (0L..6L).sumOf { byDay[today.minusDays(it).toString()] ?: 0.0 },
                total = rows.sumOf { it.third },
                streak = streak,
                days = (13L downTo 0L).map { today.minusDays(it).let { day -> day to (byDay[day.toString()] ?: 0.0) } },
                top = rows.groupBy { it.second }.mapValues { e -> e.value.sumOf { it.third } }.toList().sortedByDescending { it.second }.take(5),
            )
        }
    }
    Column(
        Modifier.fillMaxSize().background(C.Black).verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding()
            .padding(bottom = bottomPad + 16.dp),
    ) {
        Row(Modifier.padding(4.dp)) { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("Listening", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(16.dp))
            val s = stats ?: return@Column
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("Today", fmtLong(s.today), Modifier.weight(1f))
                Tile("This week", fmtLong(s.week), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("Day streak", "${s.streak}", Modifier.weight(1f))
                Tile("Total", fmtLong(s.total), Modifier.weight(1f))
                Tile("Finished", "${books.count { it.status == Status.FINISHED }}", Modifier.weight(1f))
            }
            Spacer(Modifier.height(22.dp))
            Text("LAST 14 DAYS", style = MaterialTheme.typography.labelSmall, color = C.Muted)
            Spacer(Modifier.height(10.dp))
            val max = maxOf(s.days.maxOf { it.second }, 1.0)
            Canvas(Modifier.fillMaxWidth().height(140.dp)) {
                val gap = 6.dp.toPx()
                val w = (size.width - gap * (s.days.size - 1)) / s.days.size
                s.days.forEachIndexed { i, (_, secs) ->
                    val h = maxOf(3.dp.toPx(), (secs / max * size.height).toFloat())
                    drawRoundRect(
                        if (i == s.days.lastIndex) C.Accent else C.AccentDim,
                        topLeft = Offset(i * (w + gap), size.height - h), size = Size(w, h), cornerRadius = CornerRadius(6.dp.toPx()),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                s.days.forEachIndexed { i, (day, _) ->
                    Text(
                        if (i % 2 == 1) "" else day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                        style = MaterialTheme.typography.bodySmall, color = C.Faint, modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            if (s.top.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                Text("MOST LISTENED", style = MaterialTheme.typography.labelSmall, color = C.Muted)
                Spacer(Modifier.height(8.dp))
                s.top.forEach { (id, secs) ->
                    val lb = books.firstOrNull { it.book.id == id } ?: return@forEach
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Cover(lb.book, Modifier.size(44.dp), radius = 8.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(lb.book.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(fmtLong(secs), style = MaterialTheme.typography.labelLarge, color = C.Accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(C.Surface).padding(16.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = C.Muted)
        Spacer(Modifier.height(6.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
    }
}

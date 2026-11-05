package app.lectern.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import app.lectern.data.Book
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val placeholderHues = listOf(
    Color(0xFF8E5BD0) to Color(0xFF3A1F5C),
    Color(0xFF4F8BD9) to Color(0xFF1C2E4E),
    Color(0xFFD9785B) to Color(0xFF4E2418),
    Color(0xFF4FB59A) to Color(0xFF173B33),
    Color(0xFFC9A44A) to Color(0xFF433413),
)

/** Book cover, or a generated one with the title when the book has no art. */
@Composable
fun Cover(book: Book, modifier: Modifier = Modifier, radius: Dp = 12.dp, elevation: Dp = 0.dp) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape, ambientColor = Color.Black, spotColor = Color.Black) else Modifier)
            .clip(shape)
            .background(C.Surface2),
    ) {
        if (book.cover != null) {
            AsyncImage(model = File(book.cover), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            val (a, b) = placeholderHues[(book.title.hashCode() and 0x7fffffff) % placeholderHues.size]
            Box(
                Modifier.fillMaxSize().background(Brush.linearGradient(listOf(a, b))).padding(12.dp),
                contentAlignment = Alignment.BottomStart,
            ) {
                Text(
                    book.title, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp,
                    lineHeight = 18.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Two colours pulled from the cover, used to tint the now-playing background like desktop Lectern. */
@Composable
fun rememberCoverColors(book: Book?): Pair<Color, Color> {
    var colors by remember(book?.cover) { mutableStateOf(C.AccentDim to Color(0xFF2A1636)) }
    LaunchedEffect(book?.cover) {
        val path = book?.cover ?: return@LaunchedEffect
        colors = withContext(Dispatchers.Default) {
            runCatching {
                val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 4 })
                // Use the cover's dominant colour rather than its most vibrant one, so a small
                // bright badge (e.g. Audible's yellow corner) doesn't tint the whole screen.
                val p = Palette.from(bmp).maximumColorCount(16).generate()
                val main = p.swatches
                    .filter { it.hsl[1] > 0.22f && it.hsl[2] in 0.18f..0.72f }
                    .maxByOrNull { it.population }?.rgb
                    ?: p.getMutedColor(0xFF7A4FA8.toInt())
                val dark = p.getDarkMutedColor(p.getDarkVibrantColor(0xFF2A1636.toInt()))
                Color(main) to Color(dark)
            }.getOrDefault(colors)
        }
    }
    return colors
}

@Composable
fun ThinBar(fraction: Float, modifier: Modifier = Modifier, color: Color = C.Accent, track: Color = Color.White.copy(alpha = 0.12f)) {
    Box(modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).clip(CircleShape).background(color))
    }
}

/** A replay/forward arrow with the number of seconds in it, like desktop Lectern's skip buttons. */
@Composable
fun SkipIcon(icon: ImageVector, seconds: Int, forward: Boolean, size: Dp = 34.dp, tint: Color = C.Text) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size).graphicsLayer { if (forward) scaleX = -1f })
        Text(
            "$seconds", color = tint, fontSize = (size.value * 0.27f).sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = (size.value * 0.1f).dp),
        )
    }
}

// --- formatting ---

/** 13 h 11 m · 42 m · 30 s */
fun fmtLong(seconds: Double): String {
    val s = seconds.toLong().coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    return when {
        h > 0 -> "$h h ${m} m"
        m > 0 -> "$m m"
        else -> "${s} s"
    }
}

/** 1:02:03 or 12:03 */
fun fmtClock(seconds: Double): String {
    val s = seconds.toLong().coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun fmtSpeed(speed: Float): String {
    val s = "%.2f".format(speed).trimEnd('0').let { if (it.endsWith(".")) it + "0" else it }
    return "$s×"
}

package app.lectern.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lectern.LecternApp
import app.lectern.data.Book
import app.lectern.data.Bookmark
import app.lectern.play.Playback
import app.lectern.play.SleepState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sheet(onDismiss: () -> Unit, title: String, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = C.Surface,
        scrimColor = Color.Black.copy(alpha = 0.55f),
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) C.Accent else C.Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) { Text(text, color = if (selected) C.OnAccent else C.Text, style = MaterialTheme.typography.labelLarge) }
}

// --- speed ---

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeedSheet(speed: Float, onDismiss: () -> Unit) {
    Sheet(onDismiss, "Playback speed") {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ Playback.setSpeed(speed - 0.05f) }) { Icon(Icons.Rounded.Remove, "Slower") }
                Text(fmtSpeed(speed), fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                IconButton({ Playback.setSpeed(speed + 0.05f) }) { Icon(Icons.Rounded.Add, "Faster") }
            }
            Slider(
                value = speed, onValueChange = { Playback.setSpeed(it) }, valueRange = 0.5f..3f, steps = 49,
                colors = SliderDefaults.colors(thumbColor = C.Accent, activeTrackColor = C.Accent, inactiveTrackColor = C.Stroke, activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.8f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f).forEach { s ->
                    Chip(fmtSpeed(s), selected = Math.abs(s - speed) < 0.01f) { Playback.setSpeed(s) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Pitch stays natural. Remembered for this book.", style = MaterialTheme.typography.bodySmall, color = C.Muted)
        }
    }
}

// --- sleep timer ---

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SleepSheet(sleep: SleepState?, onDismiss: () -> Unit) {
    val settings by LecternApp.instance.library.prefs.settings.collectAsStateWithLifecycle()
    var custom by remember { mutableStateOf(false) }
    Sheet(onDismiss, "Sleep timer") {
        Column(Modifier.padding(horizontal = 24.dp)) {
            if (sleep != null) {
                Text(
                    if (sleep.endOfChapter) "Stops at the end of this chapter" else "Stops in",
                    style = MaterialTheme.typography.bodyMedium, color = C.Muted,
                )
                Text(fmtClock(sleep.leftMs / 1000.0), fontSize = 52.sp, fontWeight = FontWeight.ExtraBold, color = if (sleep.fading) C.Muted else C.Text)
                if (sleep.fading) Text("Fading out…", style = MaterialTheme.typography.bodySmall, color = C.Accent)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ Playback.extendSleep(5) }, colors = ButtonDefaults.buttonColors(C.Accent, C.OnAccent)) { Text("+5 min") }
                    OutlinedButton(Playback::resetSleep) { Text("Reset", color = C.Text) }
                    OutlinedButton({ Playback.cancelSleep(); onDismiss() }) { Text("Turn off", color = C.Text) }
                }
                if (settings.shakeToReset) {
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Vibration, null, tint = C.Accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Shake your phone to reset the timer", style = MaterialTheme.typography.bodyMedium, color = C.Muted)
                    }
                }
            } else if (custom) {
                // Starts from your last custom time (or 20 min), as hours : minutes : seconds.
                val last = settings.lastSleep.takeIf { it > 0 } ?: 20 * 60
                var h by remember { mutableIntStateOf(last / 3600) }
                var m by remember { mutableIntStateOf((last % 3600) / 60) }
                var sec by remember { mutableIntStateOf(last % 60) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    Stepper("hours", h, max = 23, onChange = { h = it })
                    Text(":", fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = C.Muted, modifier = Modifier.padding(horizontal = 8.dp))
                    Stepper("minutes", m, max = 59, pad = true, onChange = { m = it })
                    Text(":", fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = C.Muted, modifier = Modifier.padding(horizontal = 8.dp))
                    Stepper("seconds", sec, max = 59, pad = true, onChange = { sec = it })
                }
                Spacer(Modifier.height(6.dp))
                Text("Tap a number to type it, or hold + / − to change it quickly.", style = MaterialTheme.typography.bodySmall, color = C.Faint)
                Spacer(Modifier.height(14.dp))
                Button(
                    { Playback.startSleep(h * 3600 + m * 60 + sec); onDismiss() }, enabled = h * 3600 + m * 60 + sec > 0,
                    colors = ButtonDefaults.buttonColors(C.Accent, C.OnAccent), modifier = Modifier.fillMaxWidth(),
                ) {
                    val total = h * 3600 + m * 60 + sec
                    Text(if (total > 0) "Start · ${Playback.fmtDuration(total.toLong())}" else "Start")
                }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 30, 45, 60).forEach { min ->
                        Chip("$min min", selected = settings.lastSleep == min * 60) { Playback.startSleep(min * 60); onDismiss() }
                    }
                    Chip("End of chapter", selected = settings.lastSleep == -1) { Playback.startSleepEndOfChapter(); onDismiss() }
                    val presets = setOf(5, 10, 15, 30, 45, 60).map { it * 60 }
                    Chip(
                        if (settings.lastSleep > 0 && settings.lastSleep !in presets) "Custom · ${Playback.fmtDuration(settings.lastSleep.toLong())}" else "Custom…",
                        selected = settings.lastSleep > 0 && settings.lastSleep !in presets,
                    ) { custom = true }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    buildString {
                        append("Counts down only while playing")
                        if (settings.sleepFade > 0) append(", fades out over the last ${settings.sleepFade} s")
                        if (settings.sleepRewind > 0) append(", then rewinds ${settings.sleepRewind} s")
                        append(".")
                        if (settings.shakeToReset) append(" Shake the phone to reset it.")
                    },
                    style = MaterialTheme.typography.bodySmall, color = C.Muted,
                )
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, max: Int, pad: Boolean = false, onChange: (Int) -> Unit) {
    val current by androidx.compose.runtime.rememberUpdatedState(value)
    var editing by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RepeatButton(Icons.Rounded.Add, "More $label") { onChange((current + 1).coerceAtMost(max)) }
        if (editing) {
            var text by remember { mutableStateOf(value.toString()) }
            val focus = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            OutlinedTextField(
                text,
                { t -> text = t.filter(Char::isDigit).take(3); text.toIntOrNull()?.let { onChange(it.coerceIn(0, max)) } },
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { editing = false }),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Accent, unfocusedBorderColor = C.Stroke, cursorColor = C.Accent),
                modifier = Modifier.width(84.dp).focusRequester(focus)
                    .onFocusChanged { if (!it.isFocused && editing) editing = it.isFocused },
            )
        } else {
            Text(
                if (pad) "%02d".format(value) else "$value",
                fontSize = 40.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { editing = true }.padding(horizontal = 6.dp),
            )
        }
        Text(label, style = MaterialTheme.typography.bodySmall, color = C.Muted)
        RepeatButton(Icons.Rounded.Remove, "Less $label") { onChange((current - 1).coerceAtLeast(0)) }
    }
}

/** A + / − button that repeats, speeding up, while held. */
@Composable
private fun RepeatButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onStep: () -> Unit) {
    val step by androidx.compose.runtime.rememberUpdatedState(onStep)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(C.Surface2)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    step()
                    val job = scope.launch {
                        kotlinx.coroutines.delay(400)
                        var wait = 120L
                        while (true) {
                            step()
                            kotlinx.coroutines.delay(wait)
                            wait = (wait * 0.85).toLong().coerceAtLeast(30)
                        }
                    }
                    tryAwaitRelease()
                    job.cancel()
                })
            },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label) }
}

// --- chapters ---

@Composable
fun ChaptersSheet(b: Book, current: Int, onDismiss: () -> Unit) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))
    Sheet(onDismiss, "Chapters") {
        LazyColumn(state = list, modifier = Modifier.heightIn(max = 560.dp)) {
            itemsIndexed(b.chapters) { i, ch ->
                val active = i == current
                Row(
                    Modifier.fillMaxWidth().clickable { Playback.jumpToChapter(i); onDismiss() }
                        .background(if (active) C.AccentDim.copy(alpha = 0.25f) else Color.Transparent)
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = if (active) C.Accent else C.Faint, modifier = Modifier.width(36.dp))
                    Text(ch.title, color = if (active) C.Accent else C.Text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(fmtClock(ch.duration), style = MaterialTheme.typography.bodySmall, color = C.Muted)
                }
            }
        }
    }
}

// --- bookmarks ---

@Composable
fun BookmarksSheet(b: Book, onDismiss: () -> Unit) {
    val lib = LecternApp.instance.library
    val tick by lib.bookmarkTick.collectAsStateWithLifecycle()
    val marks by produceState(emptyList<Bookmark>(), b.id, tick) { value = withContext(Dispatchers.IO) { lib.bookmarks(b.id) } }
    var editing by remember { mutableStateOf<Bookmark?>(null) }
    var adding by remember { mutableStateOf(false) }
    Sheet(onDismiss, "Bookmarks") {
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            if (marks.isEmpty()) item {
                Text("No bookmarks in this book yet.", color = C.Muted, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            }
            itemsIndexed(marks, key = { _, m -> m.id }) { _, m ->
                BookmarkRow(b, m, onClick = { Playback.seekTo(m.position, true, "Jumped to bookmark"); onDismiss() }) {
                    IconButton({ editing = m }) { Icon(Icons.Rounded.Edit, "Edit note", tint = C.Muted) }
                    IconButton({ lib.deleteBookmark(m.id) }) { Icon(Icons.Rounded.Delete, "Delete", tint = C.Muted) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            { adding = true }, colors = ButtonDefaults.buttonColors(C.Accent, C.OnAccent),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        ) { Text("Bookmark this spot (${fmtClock(Playback.position())})") }
    }
    if (adding || editing != null) {
        val pos = remember { Playback.position() }
        NoteDialog(
            title = if (editing != null) "Edit note" else "Add bookmark",
            initial = editing?.note ?: "",
            onDismiss = { adding = false; editing = null },
            onSave = { note ->
                editing?.let { lib.updateBookmark(it.id, note) } ?: lib.addBookmark(b, pos, note)
                adding = false
                editing = null
            },
        )
    }
}

@Composable
private fun NoteDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var note by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = C.Surface2,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                note, { note = it }, placeholder = { Text("Note (optional)") },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Accent, unfocusedBorderColor = C.Stroke, cursorColor = C.Accent),
            )
        },
        confirmButton = { TextButton({ onSave(note.trim()) }) { Text("Save", color = C.Accent) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = C.Muted) } },
    )
}

// --- audio ---

@Composable
fun AudioSheet(onDismiss: () -> Unit) {
    val prefs = LecternApp.instance.library.prefs
    val s by prefs.settings.collectAsStateWithLifecycle()
    var boost by remember { mutableFloatStateOf(s.boostDb.toFloat()) }
    LaunchedEffect(s.boostDb) { boost = s.boostDb.toFloat() }
    Sheet(onDismiss, "Audio") {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Toggle("Skip silence", "Shortens long pauses in the narration", s.skipSilence) { v -> prefs.update { it.copy(skipSilence = v) } }
            Toggle("Voice clarity", "Cuts rumble and mud, lifts speech presence", s.voiceClarity) { v -> prefs.update { it.copy(voiceClarity = v) } }
            Toggle("Even out volume", "Night mode: quiet parts come up, loud parts go down", s.nightMode) { v -> prefs.update { it.copy(nightMode = v) } }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Volume boost", style = MaterialTheme.typography.titleMedium)
                    Text("Compressor and limiter: louder without distortion", style = MaterialTheme.typography.bodySmall, color = C.Muted)
                }
                Text(if (boost < 0.5f) "Off" else "+${boost.toInt()} dB", style = MaterialTheme.typography.labelLarge, color = C.Accent)
            }
            Slider(
                value = boost, onValueChange = { boost = it }, valueRange = 0f..24f, steps = 23,
                onValueChangeFinished = { prefs.update { it.copy(boostDb = boost.toInt()) } },
                colors = SliderDefaults.colors(thumbColor = C.Accent, activeTrackColor = C.Accent, inactiveTrackColor = C.Stroke, activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
            )
        }
    }
}

@Composable
fun Toggle(title: String, detail: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = C.Muted)
        }
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = C.Accent, checkedThumbColor = C.OnAccent))
    }
}

// --- play on (Google Cast) ---

@Composable
fun PlayOnSheet(onDismiss: () -> Unit) {
    val devices by app.lectern.cast.Caster.devices.collectAsStateWithLifecycle()
    val active by app.lectern.cast.Caster.active.collectAsStateWithLifecycle()
    androidx.compose.runtime.DisposableEffect(Unit) {
        app.lectern.cast.Caster.startDiscovery()
        onDispose { app.lectern.cast.Caster.stopDiscovery() }
    }
    Sheet(onDismiss, "Play on") {
        DeviceRow(Icons.Rounded.PhoneAndroid, "This phone", "Speaker or Bluetooth headphones", selected = active == null) {
            if (active != null) app.lectern.cast.Caster.disconnect()
            onDismiss()
        }
        devices.forEach { d ->
            DeviceRow(Icons.Rounded.Tv, d.name, if (d.connecting) "Connecting…" else d.description.ifBlank { "Google Cast" }, selected = d.selected, busy = d.connecting) {
                if (!d.selected) app.lectern.cast.Caster.connect(d.id)
            }
        }
        if (devices.isEmpty()) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.CircularProgressIndicator(color = C.Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(14.dp))
                Text("Looking for Chromecast, Google TV and Nest devices on your Wi-Fi…", style = MaterialTheme.typography.bodyMedium, color = C.Muted)
            }
        }
        Text(
            "Speed, chapters, bookmarks and the sleep timer keep working on the TV. Bluetooth devices are picked in Android's settings and work automatically.",
            style = MaterialTheme.typography.bodySmall, color = C.Faint, modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun DeviceRow(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, detail: String, selected: Boolean, busy: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .background(if (selected) C.AccentDim.copy(alpha = 0.25f) else Color.Transparent)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) C.Accent else C.Text, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = if (selected) C.Accent else C.Text)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = C.Muted)
        }
        if (busy) androidx.compose.material3.CircularProgressIndicator(color = C.Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
    }
}

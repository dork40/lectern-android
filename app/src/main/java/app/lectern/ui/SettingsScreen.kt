package app.lectern.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lectern.LecternApp

/** Opens the system folder picker, starting in /storage/emulated/0/Audiobooks. */
@Composable
fun rememberFolderPicker(): () -> Unit {
    val lib = LecternApp.instance.library
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(lib::setFolder) }
    return {
        launcher.launch(DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Audiobooks"))
    }
}

fun folderLabel(uri: String?): String {
    if (uri == null) return "Not chosen"
    val id = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(uri)) }.getOrNull() ?: return uri
    return id.replace("primary:", "Internal storage/").ifEmpty { "Internal storage" }
}

@Composable
fun OnboardingScreen() {
    val pick = rememberFolderPicker()
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(C.AccentDim.copy(alpha = 0.55f), C.Black, C.Black)))
            .statusBarsPadding().navigationBarsPadding().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(104.dp).clip(CircleShape).background(C.Accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.AutoStories, null, tint = C.Accent, modifier = Modifier.size(54.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("Welcome to Lectern", style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
            "A calm audiobook player. Choose the folder your books live in, and Lectern finds every book, its chapters and cover.\n\n" +
                "Coming from Smart AudioBook Player? Your places carry over.",
            style = MaterialTheme.typography.bodyLarge, color = C.Muted, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(pick, colors = ButtonDefaults.buttonColors(C.Accent, C.OnAccent), modifier = Modifier.fillMaxWidth()) {
            Text("Choose Audiobooks folder", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 6.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text("On the next screen, tap “Use this folder”.", style = MaterialTheme.typography.bodySmall, color = C.Faint)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, bottomPad: Dp) {
    val lib = LecternApp.instance.library
    val prefs = lib.prefs
    val s by prefs.settings.collectAsStateWithLifecycle()
    val scan by lib.scan.collectAsStateWithLifecycle()
    val pick = rememberFolderPicker()
    BackHandler(onBack = onBack)

    Column(
        // imePadding keeps the pairing code field above the keyboard.
        Modifier.fillMaxSize().background(C.Black).imePadding().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding()
            .padding(bottom = bottomPad + 16.dp),
    ) {
        Row(Modifier.padding(4.dp)) { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(10.dp))

            Section("Library")
            Text(folderLabel(s.libraryUri), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(pick) { Text("Change folder", color = C.Text) }
                OutlinedButton({ lib.scan() }, enabled = scan == null) { Text(if (scan == null) "Rescan" else "Scanning…", color = C.Text) }
            }

            Section("Smart AudioBook Player")
            Toggle(
                "Keep places in sync",
                "Pausing Lectern moves Smart AudioBook Player to the same spot (up to 10 s before yours); if you listen there instead, Lectern offers to continue from its spot",
                s.syncSabp,
            ) { v -> prefs.update { it.copy(syncSabp = v) } }

            Section("Lectern on your PC")
            PcSyncSection()

            Section("Skipping")
            Text("Skip back", style = MaterialTheme.typography.titleMedium)
            Choices(listOf(5, 10, 15, 30, 60), s.skipBack, { "$it s" }) { v -> prefs.update { it.copy(skipBack = v) } }
            Text("Skip forward", style = MaterialTheme.typography.titleMedium)
            Choices(listOf(10, 15, 30, 45, 60), s.skipForward, { "$it s" }) { v -> prefs.update { it.copy(skipForward = v) } }
            Toggle("Rewind after a break", "Back 10 s when you resume after 10+ minutes", s.rewindOnResume) { v -> prefs.update { it.copy(rewindOnResume = v) } }

            Section("Sleep timer")
            Toggle("Shake to reset", "Shake the phone to restart the timer from the full time, or to resume right after it stopped", s.shakeToReset) { v ->
                prefs.update { it.copy(shakeToReset = v) }
            }
            Text("Fade out", style = MaterialTheme.typography.titleMedium)
            Choices(listOf(0, 10, 30, 60), s.sleepFade, { if (it == 0) "Off" else "$it s" }) { v -> prefs.update { it.copy(sleepFade = v) } }
            Text("Rewind when it stops", style = MaterialTheme.typography.titleMedium)
            Choices(listOf(0, 15, 30, 60, 120), s.sleepRewind, { if (it == 0) "Off" else if (it >= 60) "${it / 60} min" else "$it s" }) { v ->
                prefs.update { it.copy(sleepRewind = v) }
            }

            Section("About")
            Text(
                "Lectern for Android 1.0, a port of Lectern for Linux. Your progress, bookmarks and stats stay on this phone.",
                style = MaterialTheme.typography.bodyMedium, color = C.Muted,
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(14.dp))
    Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = C.Accent)
    Spacer(Modifier.height(2.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choices(options: List<Int>, value: Int, label: (Int) -> String, onPick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
        options.forEach { o ->
            val sel = o == value
            Box(
                Modifier.clip(RoundedCornerShape(12.dp)).background(if (sel) C.Accent else C.Surface2)
                    .clickable { onPick(o) }.padding(horizontal = 14.dp, vertical = 9.dp),
            ) { Text(label(o), color = if (sel) C.OnAccent else C.Text, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

@Composable
private fun PcSyncSection() {
    val sync = LecternApp.instance.pcSync
    val status by sync.status.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var code by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(sync.code) }
    var busy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    fun syncNow() {
        busy = true
        scope.launch {
            app.lectern.play.Playback.syncFromPc()
            busy = false
        }
    }
    Text(
        "Keeps your places the same as Lectern on your PC, directly over your home Wi-Fi. " +
            "Find the code in PC Lectern → Preferences → Lectern for Android.",
        style = MaterialTheme.typography.bodySmall, color = C.Muted,
    )
    androidx.compose.material3.OutlinedTextField(
        code,
        { v ->
            code = v.filter(Char::isDigit).take(6)
            sync.code = code
            if (code.length == 6) syncNow()
        },
        label = { Text("Pairing code") }, singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Accent, unfocusedBorderColor = C.Stroke, cursorColor = C.Accent),
        modifier = Modifier.fillMaxWidth(),
    )
    val whenText = if (status.lastSync > 0) " · " + java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(status.lastSync)) else ""
    Text(
        (status.pcName?.let { "Lectern on $it: " } ?: "") + status.message + whenText,
        style = MaterialTheme.typography.bodyMedium, color = if (status.ok) C.Good else C.Muted,
    )
    OutlinedButton(::syncNow, enabled = !busy && code.length == 6) { Text(if (busy) "Syncing…" else "Sync now", color = C.Text) }
}


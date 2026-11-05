package app.lectern.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    val libraryUri: String? = null,
    val skipBack: Int = 15,
    val skipForward: Int = 30,
    val rewindOnResume: Boolean = true,
    val sleepFade: Int = 30,
    val sleepRewind: Int = 15,
    val shakeToReset: Boolean = true,
    val lastSpeed: Float = 1f,
    val skipSilence: Boolean = false,
    val boostDb: Int = 0,
    val voiceClarity: Boolean = false,
    val nightMode: Boolean = false,
    /** Follow Smart AudioBook Player's place (its position.sabp.dat files) when it's newer. */
    val syncSabp: Boolean = true,
    /** Last sleep timer choice in seconds, or -1 for end of chapter. */
    val lastSleep: Int = 30 * 60,
    val sort: String = "recent",
)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("lectern", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val value get() = _settings.value

    fun update(f: (Settings) -> Settings) {
        val s = f(_settings.value)
        sp.edit()
            .putString("libraryUri", s.libraryUri)
            .putInt("skipBack", s.skipBack)
            .putInt("skipForward", s.skipForward)
            .putBoolean("rewindOnResume", s.rewindOnResume)
            .putInt("sleepFade", s.sleepFade)
            .putInt("sleepRewind", s.sleepRewind)
            .putBoolean("shakeToReset", s.shakeToReset)
            .putFloat("lastSpeed", s.lastSpeed)
            .putBoolean("skipSilence", s.skipSilence)
            .putInt("boostDb", s.boostDb)
            .putBoolean("voiceClarity", s.voiceClarity)
            .putBoolean("nightMode", s.nightMode)
            .putBoolean("syncSabp", s.syncSabp)
            .putInt("lastSleepSec", s.lastSleep)
            .putString("sort", s.sort)
            .apply()
        _settings.value = s
    }

    private fun read() = Settings(
        libraryUri = sp.getString("libraryUri", null),
        skipBack = sp.getInt("skipBack", 15),
        skipForward = sp.getInt("skipForward", 30),
        rewindOnResume = sp.getBoolean("rewindOnResume", true),
        sleepFade = sp.getInt("sleepFade", 30),
        sleepRewind = sp.getInt("sleepRewind", 15),
        shakeToReset = sp.getBoolean("shakeToReset", true),
        lastSpeed = sp.getFloat("lastSpeed", 1f),
        skipSilence = sp.getBoolean("skipSilence", false),
        boostDb = sp.getInt("boostDb", 0),
        voiceClarity = sp.getBoolean("voiceClarity", false),
        nightMode = sp.getBoolean("nightMode", false),
        syncSabp = sp.getBoolean("syncSabp", true),
        // Older builds stored minutes under "lastSleep".
        lastSleep = sp.getInt("lastSleepSec", sp.getInt("lastSleep", 30).let { if (it < 0) -1 else it * 60 }),
        sort = sp.getString("sort", "recent") ?: "recent",
    )

    /** Newest Smart AudioBook Player place already handled for a book, so Lectern asks only once. */
    fun sabpSeen(bookId: String): Long = sp.getLong("sabpSeen_$bookId", 0)
    fun setSabpSeen(bookId: String, lastPlayed: Long) = sp.edit().putLong("sabpSeen_$bookId", lastPlayed).apply()

    // Not a setting, just what was playing, so the app reopens on it.
    var currentBook: String?
        get() = sp.getString("currentBook", null)
        set(v) { sp.edit().putString("currentBook", v).apply() }
}

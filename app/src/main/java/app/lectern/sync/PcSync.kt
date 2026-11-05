package app.lectern.sync

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import app.lectern.Library
import app.lectern.data.Book
import app.lectern.data.Progress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * Keeps places in step with Lectern on the PC, directly over the home Wi-Fi (no NAS, no cloud).
 * Desktop Lectern announces itself as `_lectern._tcp` and checks a 6-digit pairing code.
 * Books are matched by audio file name and length; the most recently played side wins, and a
 * book that's playing on either side is never moved.
 */
class PcSync(private val context: Context, private val lib: Library) {
    data class Status(val pcName: String? = null, val ok: Boolean = false, val message: String = "", val lastSync: Long = 0)

    private val _status = MutableStateFlow(Status(message = "Not synced yet"))
    val status: StateFlow<Status> = _status.asStateFlow()
    private val mutex = Mutex()
    private val prefs = context.getSharedPreferences("pcsync", Context.MODE_PRIVATE)

    var code: String
        get() = prefs.getString("code", "") ?: ""
        set(v) { prefs.edit().putString("code", v.filter(Char::isDigit).take(6)).apply() }

    private var host: String?
        get() = prefs.getString("host", null)
        set(v) { prefs.edit().putString("host", v).apply() }

    fun key(b: Book): String {
        val name = Uri.decode(b.tracks.first().uri).substringAfterLast('/').substringAfterLast(':').substringBeforeLast('.')
        return name.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
    }

    /**
     * Two-way sync of every book. [isPlaying] tells whether the phone is playing a book right now;
     * [moved] is called when a book's place changed here (so the player can follow).
     */
    suspend fun syncAll(isPlaying: (String) -> Boolean, moved: (Book, Double) -> Unit) = mutex.withLock {
        if (code.length != 6) {
            _status.value = _status.value.copy(ok = false, message = "Enter the pairing code from Lectern on your PC")
            return@withLock
        }
        val (base, list) = fetchBooks() ?: return@withLock
        var pulled = 0
        var pushed = 0
        for (lb in lib.books.value) {
            val pc = list.firstOrNull { it.optString("key") == key(lb.book) && Math.abs(it.optDouble("duration") - lb.book.duration) < 5 } ?: continue
            val pcLast = pc.optLong("last_played")
            val mine = lb.progress
            when {
                pcLast > mine.lastPlayed + 2_000 && !pc.optBoolean("playing") && !isPlaying(lb.book.id) -> {
                    val pos = pc.optDouble("position").coerceIn(0.0, lb.book.duration)
                    if (Math.abs(pos - mine.position) > 1 || pc.optBoolean("finished") != mine.finished) {
                        lib.saveProgress(mine.copy(position = pos, finished = pc.optBoolean("finished"), lastPlayed = pcLast))
                        moved(lb.book, pos)
                        pulled++
                    } else {
                        lib.saveProgress(mine.copy(lastPlayed = pcLast))
                    }
                }
                mine.lastPlayed > pcLast + 2_000 && !pc.optBoolean("playing") -> if (post(base, lb.book, mine)) pushed++
            }
        }
        _status.value = _status.value.copy(ok = true, lastSync = System.currentTimeMillis(), message = when {
            pulled + pushed == 0 -> "In sync"
            else -> listOfNotNull(pulled.takeIf { it > 0 }?.let { "$it from PC" }, pushed.takeIf { it > 0 }?.let { "$it to PC" }).joinToString(" · ")
        })
    }

    /** Sends one book's place to the PC (after you pause). Quietly does nothing away from home. */
    suspend fun push(b: Book, p: Progress) = mutex.withLock {
        if (code.length != 6) return@withLock
        val base = reachableBase() ?: return@withLock
        if (post(base, b, p)) _status.value = _status.value.copy(ok = true, lastSync = System.currentTimeMillis(), message = "In sync")
    }

    // --- transport ---

    private suspend fun fetchBooks(): Pair<String, List<JSONObject>>? {
        val base = reachableBase() ?: run {
            _status.value = _status.value.copy(ok = false, message = "Lectern on your PC isn't on this Wi-Fi right now")
            return null
        }
        val r = request("GET", "$base/sync/books", null) ?: return null
        if (r.first == 401) {
            _status.value = _status.value.copy(ok = false, message = "Wrong pairing code")
            return null
        }
        val json = runCatching { JSONObject(r.second) }.getOrNull() ?: return null
        val arr = json.optJSONArray("books") ?: return null
        _status.value = _status.value.copy(pcName = json.optString("device").ifBlank { null })
        return base to List(arr.length()) { arr.getJSONObject(it) }
    }

    private suspend fun post(base: String, b: Book, p: Progress): Boolean {
        val body = JSONObject()
            .put("key", key(b)).put("duration", b.duration).put("position", p.position)
            .put("finished", p.finished).put("last_played", p.lastPlayed)
        val r = request("POST", "$base/sync/progress", body.toString()) ?: return false
        return runCatching { JSONObject(r.second).optBoolean("applied") }.getOrDefault(false)
    }

    /** Last known address if it still answers, otherwise look for the PC on the network. */
    private suspend fun reachableBase(): String? {
        host?.let { h -> if (request("GET", "http://$h/sync/books", null)?.first in setOf(200, 401)) return "http://$h" }
        // The PC may list several addresses (Wi-Fi, USB tether…); keep the one that answers.
        val found = discover()?.split(',')?.firstOrNull { h ->
            request("GET", "http://$h/sync/books", null)?.first in setOf(200, 401)
        } ?: return null
        host = found
        return "http://$found"
    }

    private suspend fun request(method: String, url: String, body: String?): Pair<Int, String>? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 2_500
            c.readTimeout = 6_000
            c.setRequestProperty("X-Lectern-Code", code)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = c.responseCode
            val text = (if (status < 400) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() } ?: ""
            c.disconnect()
            status to text
        }.onFailure { Log.d("Lectern", "PC sync $method $url: ${it.message}") }.getOrNull()
    }

    private suspend fun discover(): String? = withContext(Dispatchers.Main) {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return@withContext null
        withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { cont ->
                var listener: NsdManager.DiscoveryListener? = null
                fun finish(v: String?) {
                    runCatching { listener?.let(nsd::stopServiceDiscovery) }
                    if (cont.isActive) cont.resume(v)
                }
                listener = object : NsdManager.DiscoveryListener {
                    override fun onServiceFound(info: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        nsd.resolveService(info, object : NsdManager.ResolveListener {
                            override fun onServiceResolved(s: NsdServiceInfo) {
                                @Suppress("DEPRECATION")
                                val all = if (android.os.Build.VERSION.SDK_INT >= 34) s.hostAddresses else listOfNotNull(s.host)
                                _status.value = _status.value.copy(pcName = s.serviceName.removePrefix("Lectern on "))
                                finish(all.mapNotNull { it.hostAddress }.filterNot { ':' in it }.joinToString(",") { "$it:${s.port}" }.ifEmpty { null })
                            }
                            override fun onResolveFailed(s: NsdServiceInfo, errorCode: Int) = Unit
                        })
                    }
                    override fun onDiscoveryStarted(serviceType: String) = Unit
                    override fun onDiscoveryStopped(serviceType: String) = Unit
                    override fun onServiceLost(info: NsdServiceInfo) = Unit
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = finish(null)
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                }
                nsd.discoverServices("_lectern._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
                cont.invokeOnCancellation { runCatching { nsd.stopServiceDiscovery(listener) } }
            }
        }
    }
}

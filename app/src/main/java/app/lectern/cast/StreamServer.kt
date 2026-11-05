package app.lectern.cast

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * A tiny HTTP server the TV pulls the book from. Only the book being cast is served,
 * behind a random token, with Range support so the receiver can seek.
 */
object StreamServer {
    private const val PORT = 8766
    private var server: ServerSocket? = null
    private lateinit var context: Context

    @Volatile private var token = newToken()
    @Volatile private var tracks: List<Pair<Uri, String>> = emptyList() // uri, mime
    @Volatile private var cover: File? = null

    val port get() = server?.localPort ?: PORT

    fun start(ctx: Context) {
        context = ctx.applicationContext
        if (server != null) return
        val s = runCatching { ServerSocket(PORT) }.getOrElse { ServerSocket(0) }
        server = s
        thread(name = "lectern-stream", isDaemon = true) {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { handle(c) }.onFailure { Log.d("Lectern", "stream: ${it.message}") } }
            }
        }
    }

    /** Publishes a book's files; returns the URLs the receiver should load (one per track). */
    fun publish(files: List<Pair<Uri, String>>, coverFile: File?): Pair<List<String>, String?> {
        token = newToken()
        tracks = files
        cover = coverFile
        val base = "http://${lanAddress()}:$port/$token"
        return files.indices.map { "$base/t/$it" } to coverFile?.let { "$base/cover.jpg" }
    }

    fun lanAddress(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress

    private fun handle(sock: Socket) = sock.use { s ->
        s.soTimeout = 30_000
        val input = s.getInputStream()
        val lines = readHeaders(input) ?: return
        val (method, path) = lines[0].split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        val headers = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
        val out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
        val parts = path.trim('/').split('/')
        if (parts.size < 2 || parts[0] != token) return respond(out, 404, "Not Found")

        when {
            parts[1] == "cover.jpg" -> {
                val f = cover ?: return respond(out, 404, "Not Found")
                val bytes = f.readBytes()
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${bytes.size}\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n").toByteArray())
                if (method != "HEAD") out.write(bytes)
                out.flush()
            }
            parts[1] == "t" && parts.size == 3 -> {
                val (uri, mime) = tracks.getOrNull(parts[2].toIntOrNull() ?: -1) ?: return respond(out, 404, "Not Found")
                context.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
                    val size = pfd.statSize
                    var start = 0L
                    var end = size - 1
                    val range = headers["range"]?.let { Regex("""bytes=(\d*)-(\d*)""").find(it) }
                    if (range != null) {
                        val (a, b) = range.destructured
                        if (a.isNotEmpty()) start = a.toLong()
                        if (b.isNotEmpty()) end = minOf(b.toLong(), size - 1)
                        if (a.isEmpty() && b.isNotEmpty()) { start = size - b.toLong(); end = size - 1 }
                    }
                    if (start > end || start >= size) {
                        out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nConnection: close\r\n\r\n".toByteArray())
                        out.flush()
                        return
                    }
                    val len = end - start + 1
                    val status = if (range != null) "206 Partial Content" else "200 OK"
                    out.write(
                        ("HTTP/1.1 $status\r\nContent-Type: $mime\r\nContent-Length: $len\r\nAccept-Ranges: bytes\r\n" +
                            (if (range != null) "Content-Range: bytes $start-$end/$size\r\n" else "") +
                            "Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n").toByteArray()
                    )
                    if (method == "HEAD") return out.flush()
                    FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                        val buf = ByteBuffer.allocate(256 * 1024)
                        var pos = start
                        var left = len
                        while (left > 0) {
                            buf.clear()
                            if (left < buf.capacity()) buf.limit(left.toInt())
                            val n = ch.read(buf, pos)
                            if (n <= 0) break
                            out.write(buf.array(), 0, n)
                            pos += n
                            left -= n
                        }
                    }
                    out.flush()
                }
            }
            else -> respond(out, 404, "Not Found")
        }
    }

    private fun respond(out: BufferedOutputStream, code: Int, text: String) {
        out.write("HTTP/1.1 $code $text\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
    }

    private fun readHeaders(input: InputStream): List<String>? {
        val sb = StringBuilder()
        while (sb.length < 16_384) {
            val b = input.read()
            if (b < 0) return null
            sb.append(b.toChar())
            if (sb.endsWith("\r\n\r\n")) return sb.split("\r\n").filter { it.isNotEmpty() }
        }
        return null
    }

    private fun newToken(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
}

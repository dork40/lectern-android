package app.lectern.scan

import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Reads chapters from MP4/M4B files: QuickTime chapter tracks (a text track referenced
 * through tref/chap, what most audiobook tools write) and Nero `chpl` atoms.
 * Ported from desktop Lectern's mp4chapters.py.
 */
class Mp4Chapters(private val ch: FileChannel) {
    private val size = ch.size()

    private fun read(pos: Long, len: Int): ByteBuffer {
        val b = ByteBuffer.allocate(len)
        var p = pos
        while (b.hasRemaining()) {
            val n = ch.read(b, p)
            if (n <= 0) break
            p += n
        }
        b.flip()
        return b
    }

    private data class Atom(val kind: String, val start: Long, val end: Long)

    private fun atoms(start: Long, end: Long): List<Atom> {
        val out = mutableListOf<Atom>()
        var pos = start
        while (pos + 8 <= end) {
            val h = read(pos, 16)
            if (h.remaining() < 8) break
            var sz = h.int.toLong() and 0xffffffffL
            val kind = String(ByteArray(4).also { h.get(it) }, Charsets.ISO_8859_1)
            var hdr = 8
            if (sz == 1L) {
                if (h.remaining() < 8) break
                sz = h.long
                hdr = 16
            } else if (sz == 0L) {
                sz = end - pos
            }
            if (sz < hdr) break
            out += Atom(kind, pos + hdr, pos + sz)
            pos += sz
        }
        return out
    }

    private fun find(start: Long, end: Long, kind: String) = atoms(start, end).firstOrNull { it.kind == kind }

    private fun path(start: Long, end: Long, vararg kinds: String): Atom? {
        var a = Atom("", start, end)
        for (k in kinds) a = find(a.start, a.end, k) ?: return null
        return a
    }

    private fun bytes(a: Atom) = read(a.start, (a.end - a.start).toInt())

    /** [(start seconds, title)], empty if the file has no chapters. */
    fun read(): List<Pair<Double, String>> = runCatching {
        val moov = find(0, size, "moov") ?: return emptyList()
        qtChapters(moov).ifEmpty { neroChapters(moov) }
    }.getOrDefault(emptyList())

    private data class Trak(val id: Int?, val chap: List<Int>, val timescale: Long?, val stbl: Atom?)

    private fun parseTrak(t: Atom): Trak {
        val id = find(t.start, t.end, "tkhd")?.let { a ->
            val d = bytes(a)
            val v = d.get(0).toInt()
            d.getInt(if (v == 0) 12 else 20)
        }
        val chap = path(t.start, t.end, "tref", "chap")?.let { a ->
            val d = bytes(a)
            List(d.remaining() / 4) { d.int }
        } ?: emptyList()
        val ts = path(t.start, t.end, "mdia", "mdhd")?.let { a ->
            val d = bytes(a)
            (if (d.get(0).toInt() == 1) d.getInt(20) else d.getInt(12)).toLong() and 0xffffffffL
        }
        return Trak(id, chap, ts, path(t.start, t.end, "mdia", "minf", "stbl"))
    }

    private fun qtChapters(moov: Atom): List<Pair<Double, String>> {
        val traks = atoms(moov.start, moov.end).filter { it.kind == "trak" }.map(::parseTrak)
        val chapIds = traks.flatMap { it.chap }.toSet()
        val t = traks.firstOrNull { it.id in chapIds && it.stbl != null && (it.timescale ?: 0) > 0 } ?: return emptyList()
        val stbl = t.stbl!!

        val stts = bytes(find(stbl.start, stbl.end, "stts") ?: return emptyList())
        val durations = mutableListOf<Long>()
        val n = stts.getInt(4)
        for (i in 0 until n) {
            val count = stts.getInt(8 + i * 8)
            val delta = stts.getInt(12 + i * 8).toLong() and 0xffffffffL
            repeat(count) { durations += delta }
        }

        val stsz = bytes(find(stbl.start, stbl.end, "stsz") ?: return emptyList())
        val fixed = stsz.getInt(4)
        val count = stsz.getInt(8)
        val sizes = if (fixed != 0) List(count) { fixed } else List(count) { stsz.getInt(12 + it * 4) }

        val offsets: List<Long> = find(stbl.start, stbl.end, "stco")?.let { a ->
            val d = bytes(a)
            List(d.getInt(4)) { d.getInt(8 + it * 4).toLong() and 0xffffffffL }
        } ?: find(stbl.start, stbl.end, "co64")?.let { a ->
            val d = bytes(a)
            List(d.getInt(4)) { d.getLong(8 + it * 8) }
        } ?: return emptyList()

        val stsc = bytes(find(stbl.start, stbl.end, "stsc") ?: return emptyList())
        val runs = List(stsc.getInt(4)) { i -> stsc.getInt(8 + i * 12) to stsc.getInt(12 + i * 12) }

        // Expand chunk offsets into per-sample file offsets.
        val sampleOffsets = mutableListOf<Long>()
        var sample = 0
        offsets.forEachIndexed { ci, chunkOff ->
            var perChunk = 1
            for ((first, spc) in runs) if (first <= ci + 1) perChunk = spc
            var off = chunkOff
            repeat(perChunk) {
                if (sample < sizes.size) {
                    sampleOffsets += off
                    off += sizes[sample]
                    sample++
                }
            }
        }

        val out = mutableListOf<Pair<Double, String>>()
        var time = 0L
        for (i in 0 until minOf(durations.size, sizes.size, sampleOffsets.size)) {
            out += time.toDouble() / t.timescale!! to decodeText(read(sampleOffsets[i], sizes[i]))
            time += durations[i]
        }
        return out
    }

    private fun decodeText(raw: ByteBuffer): String {
        if (raw.remaining() < 2) return ""
        val len = raw.short.toInt() and 0xffff
        val b = ByteArray(minOf(len, raw.remaining())).also { raw.get(it) }
        val bom = b.size >= 2 && ((b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) || (b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()))
        return if (bom) String(b, Charsets.UTF_16).trim() else String(b, Charsets.UTF_8).trim('\u0000').trim()
    }

    /** Nero chapters: moov/udta/chpl, start times in 100 ns units. */
    private fun neroChapters(moov: Atom): List<Pair<Double, String>> {
        val a = path(moov.start, moov.end, "udta", "chpl") ?: return emptyList()
        val d = bytes(a)
        val version = d.get(0).toInt()
        var p = 4 + if (version == 1) 4 else 0
        val count = d.get(p).toInt() and 0xff
        p += 1
        val out = mutableListOf<Pair<Double, String>>()
        repeat(count) {
            if (p + 9 > d.limit()) return out
            val start = d.getLong(p) / 10_000_000.0
            val len = d.get(p + 8).toInt() and 0xff
            val title = String(ByteArray(len).also { arr -> for (i in 0 until len) arr[i] = d.get(p + 9 + i) }, Charsets.UTF_8)
            out += start to title.trim()
            p += 9 + len
        }
        return out
    }
}

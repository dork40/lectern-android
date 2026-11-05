package app.lectern.scan

/**
 * File and folder name parsing, ported from desktop Lectern's metadata.py so both
 * apps read a library the same way.
 */
object Names {
    data class Info(
        var title: String? = null,
        var author: String? = null,
        var narrator: String? = null,
        var series: String? = null,
        var seriesIndex: String? = null,
        var year: String? = null,
    )

    private val I = RegexOption.IGNORE_CASE
    private val junk = Regex("""\s*[(\[](?:unabridged|abridged|audiobook|mp3|m4b|\d+\s*k(?:bps)?|retail)[)\]]""", I)
    private val year = Regex("""\s*[(\[]((?:19|20)\d\d)[)\]]""")
    private val bookNum = Regex("""^(?:book|vol(?:ume)?|part|#)\.?\s*(\d+(?:\.\d+)?)\s*(?:[-:._]\s*|\s+)(.+)$""", I)
    private val leadNum = Regex("""^(\d{1,3}(?:\.\d+)?)\s*(?:[-._:)]\s*|\s+-\s+)(.+)$|^(0\d)\s+(\D.*)$""")
    private val narrated = Regex("""\s*[(\[]?\s*(?:narrated|read)\s+by\s+([^)\]]+)[)\]]?\s*$""", I)
    private val seriesNum = Regex("""^(.*?)[\s,]*(?:book|vol(?:ume)?|#)?\s*(\d+(?:\.\d+)?)$""", I)
    private val bookWord = Regex("""(?:book|vol(?:ume)?|#)\.?\s*\d+(?:\.\d+)?""", I)
    private val dashes = Regex("""\s+[-–—]\s+""")
    private val seriesSuffix = Regex(
        """\s*[(\[]\s*([^()\[\]]+?)[,:]?\s+(?:book|volume|vol\.?|part|#)\s*(\d+(?:\.\d+)?)\s*[)\]]\s*$""", I,
    )
    private val genericDirs = Regex("""^(?:audiobooks?|books|library|media|new|unsorted|downloads?)$""", I)
    private val trackNum = Regex("""^\d{1,3}\s*[-._)]\s*(.+)$""")

    fun fmtIndex(s: String): String {
        val f = s.toDoubleOrNull() ?: return s
        return if (f == Math.floor(f)) f.toLong().toString() else f.toString()
    }

    private fun splitNumber(text: String): Pair<String, String>? {
        val m = bookNum.find(text) ?: leadNum.find(text) ?: return null
        val g = m.groupValues.drop(1).filter { it.isNotEmpty() }
        if (g.size < 2) return null
        return fmtIndex(g[0]) to g[1].trim()
    }

    fun parseName(raw: String): Info {
        val info = Info()
        var name = raw.replace("_", " ").trim()
        year.find(name)?.let { info.year = it.groupValues[1]; name = year.replace(name, "") }
        name = junk.replace(name, "")
        narrated.find(name)?.let { info.narrator = it.groupValues[1].trim(); name = name.substring(0, it.range.first) }
        name = name.replace(Regex("""\s+"""), " ").trim(' ', '-')

        splitNumber(name)?.let { (n, rest) -> info.seriesIndex = n; name = rest }

        val parts = name.split(dashes).map { it.trim() }.filter { it.isNotEmpty() }
        when {
            parts.size >= 3 -> {
                info.author = parts[0]
                val middle = parts.subList(1, parts.size - 1).joinToString(" - ")
                val sm = seriesNum.find(middle)
                val bm = bookNum.find("$middle x")
                when {
                    sm != null && sm.groupValues[1].isNotBlank() -> {
                        info.series = sm.groupValues[1].trim(' ', ',', '#')
                        info.seriesIndex = fmtIndex(sm.groupValues[2])
                    }
                    bm != null -> info.seriesIndex = fmtIndex(bm.groupValues[1])
                    else -> info.series = middle
                }
                info.title = parts.last()
            }
            parts.size == 2 -> {
                val (first, second) = parts
                if (bookWord.matches(second)) {
                    info.series = first
                    info.seriesIndex = fmtIndex(Regex("""\d+(?:\.\d+)?""").find(second)!!.value)
                    info.title = first
                } else {
                    info.author = first
                    info.title = second
                }
            }
            else -> info.title = name
        }
        info.title?.let { t ->
            val split = splitNumber(t)
            if (split != null && info.seriesIndex == null) {
                info.seriesIndex = split.first
                info.title = split.second
            }
        }
        return info
    }

    /** 'Title: Subtitle (Some Series, Book 9)' → ('Title: Subtitle', 'Some Series', '9'). */
    fun splitSeriesSuffix(title: String): Triple<String, String?, String?> {
        val m = seriesSuffix.find(title) ?: return Triple(title, null, null)
        if (m.range.first == 0) return Triple(title, null, null)
        return Triple(title.substring(0, m.range.first).trimEnd(' ', ':', '-'), m.groupValues[1].trim(), fmtIndex(m.groupValues[2]))
    }

    /** Name parsing plus the Author/Series/Book folder hierarchy. [relPath] is relative to the library root. */
    fun guessFromPath(relPath: String, isFile: Boolean): Info {
        val parts = relPath.split('/').filter { it.isNotEmpty() }
        val last = parts.lastOrNull() ?: return Info()
        val name = if (isFile) last.substringBeforeLast('.') else last
        val info = parseName(name)
        val parents = parts.dropLast(1).filterNot { genericDirs.matches(it) }
        if (parents.isNotEmpty()) {
            if (info.author == null) info.author = parents[0]
            if (parents.size >= 2 && info.series == null) info.series = parseName(parents[1]).title ?: parents[1]
        }
        return info
    }

    /** '01 - Intro' → 'Intro'; 'Part 3' stays 'Part 3'. */
    fun cleanTrackTitle(stem: String): String {
        val s = stem.replace("_", " ")
        return (trackNum.find(s)?.groupValues?.get(1) ?: s).trim()
    }

    /** Natural sort: "Part 2" before "Part 10". */
    val naturalOrder: Comparator<String> = Comparator { a, b ->
        val ra = Regex("""\d+|\D+""").findAll(a.lowercase()).map { it.value }.toList()
        val rb = Regex("""\d+|\D+""").findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]
            val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                x.toBigInteger().compareTo(y.toBigInteger())
            } else x.compareTo(y)
            if (c != 0) return@Comparator c
        }
        ra.size - rb.size
    }
}

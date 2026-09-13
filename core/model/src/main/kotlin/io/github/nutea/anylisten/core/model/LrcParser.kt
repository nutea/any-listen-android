package io.github.nutea.anylisten.core.model

object LrcParser {
    private val linePattern = Regex("""((?:\[\d{1,2}:\d{2}(?:\.\d{1,3})?])+)\s*(.*)""")
    private val timePattern = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?]""")

    fun parse(raw: String?): Lyrics {
        if (raw.isNullOrBlank()) return Lyrics(emptyList(), raw.orEmpty())
        val lines = raw.lineSequence().flatMap { line ->
            val match = linePattern.matchEntire(line.trim()) ?: return@flatMap emptySequence()
            val text = match.groupValues[2].trim()
            timePattern.findAll(match.groupValues[1]).mapNotNull { time ->
                val min = time.groupValues[1].toLong()
                val sec = time.groupValues[2].toLong()
                val frac = time.groupValues[3]
                val ms = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100
                    2 -> frac.toLong() * 10
                    else -> frac.take(3).toLong()
                }
                LyricLine((min * 60 + sec) * 1000 + ms, text)
            }
        }.sortedBy { it.timeMs }.toList()
        return Lyrics(lines, raw)
    }
}

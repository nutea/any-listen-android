package io.github.nutea.anylisten.core.model

object LrcParser {
    private val linePattern = Regex("""((?:\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?])+)\s*(.*)""")
    private val timePattern = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    // Any Listen AWLRC: [mm:ss.xxx]<offsetMs,durationMs>text. Offsets are relative to the line.
    private val wordTag = Regex("""<([^<>]*)>""")
    private val wordTime = Regex("""(\d+),(\d+)""")
    private val timingTag = Regex("""<[^<>\r\n]*,[^<>\r\n]*>""")

    fun parse(raw: String?, translationRaw: String? = null, romanizationRaw: String? = null, karaokeRaw: String? = null): Lyrics {
        val wordSource = karaokeRaw?.takeIf { it.isNotBlank() }
            ?: raw?.takeIf { timingTag.containsMatchIn(it) }.orEmpty()
        val source = raw?.takeIf { it.isNotBlank() } ?: wordSource.takeIf { it.isNotBlank() }
            ?: translationRaw?.takeIf { it.isNotBlank() } ?: romanizationRaw.orEmpty()
        val translation = translationRaw.orEmpty().takeUnless { it.trim() == source.trim() }.orEmpty()
        val romanization = romanizationRaw.orEmpty().takeUnless { it.trim() == source.trim() }.orEmpty()
        val translations = parseLines(translation).groupBy { it.timeMs }
        val romanizations = parseLines(romanization).groupBy { it.timeMs }
        fun extra(lines: List<LyricLine>?, text: String) = lines?.map { it.text }
            ?.filter { it.isNotBlank() && it != text }?.distinct()?.joinToString("\n")?.takeIf { it.isNotBlank() }
        fun attach(lines: List<LyricLine>) = lines.map { line ->
            line.copy(translation = extra(translations[line.timeMs], line.text), romanization = extra(romanizations[line.timeMs], line.text))
        }
        val karaoke = parseLines(wordSource, parseWords = true).takeIf { lines -> lines.any { it.words.isNotEmpty() } }.orEmpty()
        return Lyrics(attach(parseLines(source)), source, translation, romanization, wordSource, attach(karaoke))
    }

    private fun parseLines(raw: String, parseWords: Boolean = false): List<LyricLine> = raw.lineSequence().flatMap { line ->
        val match = linePattern.matchEntire(line.trim()) ?: return@flatMap emptySequence()
        val content = match.groupValues[2].trim()
        timePattern.findAll(match.groupValues[1]).map { time ->
            val min = time.groupValues[1].toLong()
            val sec = time.groupValues[2].toLong()
            val frac = time.groupValues[3].padEnd(3, '0')
            val start = (min * 60 + sec) * 1000 + frac.toLong()
            val words = if (parseWords) words(content, start) else emptyList()
            LyricLine(start, if (words.isNotEmpty()) words.joinToString("") { it.text }
                else timingTag.replace(content, "").trim(), words = words)
        }
    }.sortedBy { it.timeMs }.toList()

    private fun words(content: String, lineStart: Long): List<LyricWord> {
        val tags = wordTag.findAll(content).toList()
        if (tags.isEmpty() || tags.first().range.first != 0) return emptyList()
        val words = ArrayList<LyricWord>()
        var previous = -1L
        for ((index, tag) in tags.withIndex()) {
            val timing = wordTime.matchEntire(tag.groupValues[1]) ?: return emptyList()
            val offset = timing.groupValues[1].toLongOrNull() ?: return emptyList()
            val duration = timing.groupValues[2].toLongOrNull() ?: return emptyList()
            // Invalid timing must not break playback or overflow the absolute timestamp.
            if (offset < previous || offset > 86_400_000 || duration > 86_400_000) return emptyList()
            previous = offset
            var text = content.substring(tag.range.last + 1, tags.getOrNull(index + 1)?.range?.first ?: content.length)
            if (index == 0) text = text.trimStart()
            if (index == tags.lastIndex) text = text.trimEnd()
            if (text.isNotEmpty()) words += LyricWord(text, lineStart + offset, duration)
        }
        return words
    }
}

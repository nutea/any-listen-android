package io.github.nutea.anylisten.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {
    @Test
    fun parsesTimedLines() {
        val lyrics = LrcParser.parse("[00:01.00]Hello\n[00:02.50]World")
        assertEquals(2, lyrics.lines.size)
        assertEquals(1000L, lyrics.lines[0].timeMs)
        assertEquals("World", lyrics.lines[1].text)
        assertEquals(2500L, lyrics.lines[1].timeMs)
        assertEquals("Hello", lyrics.lineAt(1000L))
        assertEquals("World", lyrics.lineAt(3000L))
        assertEquals("[00:01.00]Hello\n[00:02.50]World", lyrics.sessionPayload())
        assertTrue(lyrics.toTimedLrc().contains("[00:01.00]Hello"))
    }

    @Test
    fun buildsTimedLrcWhenRawHasNoTags() {
        val lyrics = Lyrics(listOf(LyricLine(1500, "Hi")), raw = "Hi")
        assertEquals("[00:01.500]Hi", lyrics.toTimedLrc())
    }

    @Test fun translationsAlignByTimestampRatherThanLineNumber() {
        val result = LrcParser.parse("[00:01.00]One\n[00:02.00]Two\n[00:03.00]Three",
            "[00:01.000]一\n[00:03.00]三")
        assertEquals(listOf("一", null, "三"), result.lines.map { it.translation })
        assertEquals("One", result.lineAt(1000))
    }

    @Test fun translationOnlyRemainsReadableAndDuplicateTranslationIsHidden() {
        assertEquals("译文", LrcParser.parse(null, "[00:01.00]译文").lines.single().text)
        assertEquals(null, LrcParser.parse("[00:01.00]Same", "[00:01.00]Same").lines.single().translation)
    }

    @Test fun lyricTimingAndSeekUseInverseOffsets() {
        assertEquals(1000L, LyricTiming.position(1500, 500))
        assertEquals(1500L, LyricTiming.seek(1000, 500))
        assertEquals(500L, LyricTiming.seek(1000, -500))
        assertEquals(0L, LyricTiming.seek(100, -500))
        assertEquals(60_000L, LyricTiming.clamp(100_000))
    }
}

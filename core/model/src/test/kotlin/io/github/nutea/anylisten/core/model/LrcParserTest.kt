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
}

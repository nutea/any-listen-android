package io.github.nutea.anylisten.core.model

import org.junit.Assert.*
import org.junit.Test

class KaraokeLyricsTest {
    @Test fun parsesRelativeWordTimingAndAllThreeLyricTracks() {
        val lyrics = LrcParser.parse("[00:10.000]風が吹く\n[00:12.00]空",
            "[00:10.00]风在吹", "[00:10.00]kaze ga fuku\n[00:12.00]sora",
            "[00:10.00]<0,500>風<500,300>が<800,400>吹く\n[00:12.00]空")
        val line = lyrics.displayLines(true).first()
        assertEquals("風が吹く", line.text)
        assertEquals("风在吹", line.translation)
        assertEquals("kaze ga fuku", line.romanization)
        assertEquals(listOf(10_000L, 10_500L, 10_800L), line.words.map { it.startTimeMs })
        assertEquals(.5f, line.words.first().progressAt(10_250), .001f)
        assertTrue(lyrics.displayLines(false).all { it.words.isEmpty() })
        assertEquals("sora", lyrics.displayLines(true)[1].romanization)
        assertNull(lyrics.displayLines(true)[1].translation)
    }

    @Test fun karaokeOnlyAndRepeatedLineTimestampsRemainReadable() {
        val lyrics = LrcParser.parse(null, karaokeRaw = "[00:01.00][00:05.00]<0,300>one <400,500>two")
        assertEquals(listOf("one two", "one two"), lyrics.lines.map { it.text })
        assertEquals(5_400L, lyrics.karaokeLines[1].words[1].startTimeMs)
        assertTrue(lyrics.lines.none { '<' in it.text })
    }

    @Test fun missingMalformedOverflowAndUnorderedTimingFallBackToNormalLines() {
        for (raw in listOf("[00:01.00]<0,999999999999999999999999>safe",
            "[00:01.00]<500,10>a<0,10>b", "[00:01.00]<-10,100>safe", "[00:01.00]no timing")) {
            val lyrics = LrcParser.parse("[00:01.00]Original", karaokeRaw = raw)
            assertTrue(lyrics.karaokeLines.isEmpty())
            assertEquals("Original", lyrics.displayLines(true).single().text)
        }
        assertEquals("safe", LrcParser.parse(null, karaokeRaw = "[00:01.00]<-10,100>safe").lines.single().text)
        assertEquals("safe", LrcParser.parse(null, karaokeRaw = "[00:01.00]<bad,timing>safe").lines.single().text)
    }

    @Test fun progressHandlesGapsZeroDurationBackwardSeekAndOffset() {
        val word = LyricWord("word", 2000, 1000)
        assertEquals(0f, word.progressAt(1999), 0f)
        assertEquals(.5f, word.progressAt(LyricTiming.position(2700, 200)), .001f)
        assertEquals(1f, word.progressAt(4000), 0f)
        assertEquals(0f, word.progressAt(0), 0f)
        assertEquals(0f, LyricWord("!", 2000, 0).progressAt(1999), 0f)
        assertEquals(1f, LyricWord("!", 2000, 0).progressAt(2000), 0f)
    }

    @Test fun interpolationIsBoundedAndRespectsSpeedAndDuration() {
        assertEquals(1300L, LyricTiming.interpolate(1000, 100, 300, 1.5f, 10000))
        assertEquals(1500L, LyricTiming.interpolate(1000, 100, 10000, 1f, 10000))
        assertEquals(1000L, LyricTiming.interpolate(1000, 100, 50, 1f, 10000))
        assertEquals(1000L, LyricTiming.interpolate(1000, 0, 10000, 1f, 10000))
        assertEquals(1100L, LyricTiming.interpolate(1000, 100, 300, 1f, 1100))
    }
}

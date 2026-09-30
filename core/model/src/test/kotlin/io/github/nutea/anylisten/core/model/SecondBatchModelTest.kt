package io.github.nutea.anylisten.core.model

import org.junit.Assert.*
import org.junit.Test

class SecondBatchModelTest {
    private fun track(id: String, title: String = id) = Track(TrackIdentity("server", id), title, "莫文蔚", "呼吸有害", 120000)
    @Test fun pinyinInitialsTraditionalChineseAndOriginalTextShareIdentity() {
        val a = track("a", "呼吸有害")
        val b = track("b", "愛情").copy(album = "Album")
        val snapshot = LibrarySnapshot(listOf(Playlist("x", "X", "general", 2)), mapOf("x" to listOf(a, b)), 0, false)
        val index = LibrarySearch.index(snapshot)
        assertEquals(a.cacheKey, index.find("huxiyouhai").single().track.cacheKey)
        assertEquals(a.cacheKey, index.find("hxyh").single().track.cacheKey)
        assertEquals(a.cacheKey, index.find("有害 mww").single().track.cacheKey)
        assertEquals(b.cacheKey, index.find("aiqing").single().track.cacheKey)
        assertTrue(index.find("none").isEmpty())
    }
    @Test fun listeningIgnoresPauseAndCountsOnlyOncePerOccurrence() {
        val clock = ListeningAccumulator()
        assertNull(clock.sample(0, "a", true, 120000))
        assertEquals(ListeningDelta("a", 10000, 0), clock.sample(10000, "a", false))
        assertNull(clock.sample(20000, "a", true))
        assertEquals(ListeningDelta("a", 20000, 1), clock.sample(40000, "a", true))
        assertEquals(ListeningDelta("a", 10000, 0), clock.sample(50000, "a", false))
        assertNull(clock.sample(90000, "a", false))
    }
    @Test fun transitionsAttributeElapsedToOldSongAndRepeatStartsNewCount() {
        val clock = ListeningAccumulator()
        clock.sample(0, "a", true, 10000)
        assertEquals(ListeningDelta("a", 5000, 1), clock.sample(5000, "b", true, 10000))
        assertEquals(ListeningDelta("b", 5000, 1), clock.sample(10000, "b", true, 10000, newOccurrence = true))
        assertEquals(ListeningDelta("b", 5000, 1), clock.sample(15000, "b", false))
    }
    @Test fun lateDecoderDurationCountsShortSongsAtHalfLength() {
        val clock = ListeningAccumulator()
        clock.sample(0, "a", true)
        assertEquals(ListeningDelta("a", 5000, 1), clock.sample(5000, "a", false, 10000))
    }
    @Test fun bufferingAndClockRollbackNeverInventDuration() {
        val clock = ListeningAccumulator()
        clock.sample(100, "a", false)
        assertNull(clock.sample(10000, "a", true))
        assertNull(clock.sample(9999, "a", false))
        assertNull(clock.sample(20000, null, false))
    }
    @Test fun smartListsUseRecordedCountsAndAgeAndNeverBackfillRecentList() {
        val songs = listOf(track("a"), track("b"), track("c"))
        val now = 60L * 86400000
        val stats = listOf(ListeningStat(songs[0].cacheKey, 90000, 3, now - 31L * 86400000),
            ListeningStat(songs[1].cacheKey, 120000, 2, now - 1000))
        assertEquals(listOf("a", "b"), SmartTracks.select(songs, stats, SmartLibrary.FREQUENT, now).map { it.identity.remoteTrackId })
        assertEquals(listOf("a"), SmartTracks.select(songs, stats, SmartLibrary.REDISCOVER, now).map { it.identity.remoteTrackId })
        assertTrue(SmartTracks.select(songs, emptyList(), SmartLibrary.FREQUENT, now).isEmpty())
        assertEquals(3, SmartTracks.select(songs + songs, stats, SmartLibrary.RANDOM, now).size)
        assertEquals(SmartTracks.select(songs, stats, SmartLibrary.RANDOM, now, 7), SmartTracks.select(songs, stats, SmartLibrary.RANDOM, now, 7))
    }
    @Test fun effectsClampCorruptSettingsAndPlaylistOrderRemainsStable() {
        assertEquals(1f, AudioEffectsSettings(Float.NaN).sanitized().speed)
        assertEquals(2f, AudioEffectsSettings(100f).sanitized().speed)
        assertEquals("flat", AudioEffectsSettings(preset = "bogus").sanitized().preset)
        assertEquals(mapOf(60 to 1500), AudioEffectsSettings(gains = mapOf(-1 to 4, 60 to 99999)).sanitized().gains)
        val songs = listOf(track("z"), track("a"))
        assertEquals(songs, TrackSort.apply(songs, TrackSortField.SERVER_ORDER, true))
    }
}

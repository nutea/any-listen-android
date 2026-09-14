package io.github.nutea.anylisten.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayLaterTest {
    private val tracks = (1..4).map {
        Track(TrackIdentity("p", "$it"), "T$it", "A$it", "B$it", 1000L * it)
    }

    @Test
    fun insertKeepsCurrentAndAppendsToLaterSection() {
        val first = PlayLater.insert(tracks, tracks[1].cacheKey, emptyList(), listOf(tracks[3]))
        assertEquals(tracks[1].cacheKey, first.queue[1].cacheKey)
        assertEquals(listOf(tracks[3].cacheKey), first.laterKeys)
        val second = PlayLater.insert(first.queue, tracks[1].cacheKey, first.laterKeys, listOf(tracks[0]))
        assertEquals(tracks[1].cacheKey, second.queue.first().cacheKey)
        assertEquals(listOf(tracks[3].cacheKey, tracks[0].cacheKey), second.laterKeys)
        assertEquals(listOf(tracks[1].cacheKey, tracks[3].cacheKey, tracks[0].cacheKey, tracks[2].cacheKey),
            second.queue.map { it.cacheKey })
    }

    @Test
    fun emptyCurrentPutsLaterFirstWithoutInventingANowPlayingTrack() {
        val result = PlayLater.insert(emptyList(), null, emptyList(), listOf(tracks[2], tracks[0]))
        assertEquals(listOf(tracks[2].cacheKey, tracks[0].cacheKey), result.queue.map { it.cacheKey })
        assertEquals(result.queue.map { it.cacheKey }, result.laterKeys)
    }

    @Test
    fun queueSectionsSplitNowPlayingLaterAndRest() {
        val sections = QueueSections.from(tracks, tracks[0].cacheKey, listOf(tracks[2].cacheKey))
        assertEquals(tracks[0], sections.nowPlaying)
        assertEquals(listOf(tracks[2]), sections.later)
        assertEquals(listOf(tracks[1], tracks[3]), sections.rest)
    }

    @Test
    fun sortUsesExistingFieldsOnly() {
        val sorted = TrackSort.apply(tracks, TrackSortField.TITLE, false)
        assertEquals(listOf("T4", "T3", "T2", "T1"), sorted.map { it.title })
        assertTrue(TrackSort.apply(tracks, TrackSortField.ARTIST, true).first().artist == "A1")
        val recency = TrackSort.apply(tracks, TrackSortField.PLAY_TIME, false)
        assertEquals(tracks.map { it.title }, recency.map { it.title })
        assertEquals(tracks.reversed().map { it.title }, TrackSort.apply(tracks, TrackSortField.PLAY_TIME, true).map { it.title })
        assertTrue(TrackSort.defaultAscending(TrackSortField.TITLE))
        assertTrue(!TrackSort.defaultAscending(TrackSortField.PLAY_TIME))
    }
}

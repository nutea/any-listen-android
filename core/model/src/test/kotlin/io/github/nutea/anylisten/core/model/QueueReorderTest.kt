package io.github.nutea.anylisten.core.model

import org.junit.Assert.*
import org.junit.Test

class QueueReorderTest {
    private val tracks = (0..5).map { Track(TrackIdentity("fixture", "$it"), "Track $it", "", "", 1000) }
    private val keys = tracks.map { it.cacheKey }

    @Test fun ordinaryTracksMoveWithoutChangingCurrentOrPrioritySlots() {
        val moved = QueueReorder.move(tracks, keys[1], listOf(keys[2], keys[3]), keys[5], keys[0])!!
        assertEquals(listOf(5, 1, 2, 3, 0, 4).map(tracks::get), moved.queue)
        assertEquals(listOf(keys[2], keys[3]), moved.laterKeys)
        assertEquals(tracks.size, moved.queue.map { it.cacheKey }.distinct().size)
    }

    @Test fun priorityReorderChangesBothMembershipOrderAndPhysicalTimeline() {
        val moved = QueueReorder.move(tracks, keys[1], listOf(keys[2], keys[3]), keys[3], keys[2])!!
        assertEquals(listOf(0, 1, 3, 2, 4, 5).map(tracks::get), moved.queue)
        assertEquals(listOf(keys[3], keys[2]), moved.laterKeys)
    }

    @Test fun currentTrackAndSectionBoundariesCannotBeMoved() {
        for ((from, to) in listOf(keys[1] to keys[4], keys[4] to keys[1], keys[2] to keys[4],
                keys[4] to keys[2], "missing" to keys[4], keys[4] to keys[4]))
            assertNull(QueueReorder.move(tracks, keys[1], listOf(keys[2], keys[3]), from, to))
    }
}

package io.github.nutea.anylisten.core.model

import org.junit.Assert.*
import org.junit.Test

class LibrarySearchTest {
    private val one = Track(TrackIdentity("server", "1"), "Morning Light", "Alice", "Home", 120000)
    private val two = Track(TrackIdentity("server", "2"), "Morning Light", "Bob", "Away", 120000)
    private val snapshot = LibrarySnapshot(listOf(Playlist("love", "Favorites", "love", 1), Playlist("other", "Other", "user", 2)),
        mapOf("love" to listOf(one), "other" to listOf(one.copy(playlistId = "other"), two)), 1, false)

    @Test fun searchesAllPlaylistsAndMetadataIgnoringCase() {
        assertEquals(two, LibrarySearch.find(snapshot, "bOB away").single().track)
        assertEquals(listOf("other"), LibrarySearch.find(snapshot, "away").single().playlistIds)
    }
    @Test fun mergesIdentityWithOriginsButKeepsDistinctRecordings() {
        val result = LibrarySearch.find(snapshot, "Morning")
        assertEquals(2, result.size)
        assertEquals(listOf("love", "other"), result.first().playlistIds)
    }
    @Test fun emptyQueryHasNoResultsAndOfflineUsesSameSnapshot() {
        assertTrue(LibrarySearch.find(snapshot, " \n ").isEmpty())
        assertTrue(LibrarySearch.find(snapshot, "missing").isEmpty())
        assertEquals(LibrarySearch.find(snapshot, "  Alice  home "), LibrarySearch.find(snapshot.copy(offline = true), "Alice home"))
    }
}

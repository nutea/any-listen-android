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

    @Test fun playlistScopeExcludesOtherPlaylistsAndTheirOrigins() {
        val result = LibrarySearch.find(snapshot, "Morning", "love")
        assertEquals(listOf(one), result.map { it.track })
        assertEquals(listOf("love"), result.single().playlistIds)
        assertTrue(LibrarySearch.find(snapshot, "Bob", "love").isEmpty())
        assertEquals(2, LibrarySearch.find(snapshot, "Morning", "other").size)
    }

    @Test fun playlistScopeUsesItsOwnMetadataAndPinyinIndex() {
        val scoped = one.copy(title = "呼吸有害", artist = "莫文蔚", album = "专辑", playlistId = "other")
        val library = snapshot.copy(tracksByPlaylist = snapshot.tracksByPlaylist + ("other" to listOf(scoped)))
        assertEquals(scoped, LibrarySearch.find(library, "hxyh mowenwei", "other").single().track)
        assertTrue(LibrarySearch.find(library, "Alice", "other").isEmpty())
        assertEquals(one, LibrarySearch.find(library, "Alice").single().track)
    }

    @Test fun missingDeletedAndEmptyScopesNeverFallBackToGlobalSearch() {
        val empty = snapshot.copy(playlists = snapshot.playlists + Playlist("empty", "Empty", "user", 0))
        listOf("missing", "", "empty").forEach { id ->
            assertTrue(LibrarySearch.index(empty, id).all.isEmpty())
        }
        val deleted = snapshot.copy(playlists = snapshot.playlists.filterNot { it.id == "love" })
        assertTrue(LibrarySearch.find(deleted, "Morning", "love").isEmpty())
        assertEquals(2, LibrarySearch.find(deleted, "Morning").size)
        assertEquals(LibrarySearch.find(snapshot, "Morning", "love"),
            LibrarySearch.find(snapshot.copy(offline = true), "Morning", "love"))
    }
}

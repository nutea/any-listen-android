package io.github.nutea.anylisten.core.data

import io.github.nutea.anylisten.core.data.gateway.*
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class LibraryChangeTest {
    private fun parse(raw: String) = LibraryChange.from(ProtocolDtos.json.parseToJsonElement(raw).jsonObject).playlistIds
    @Test fun unchangedPersistedLibraryDoesNotTriggerWritesButOrderAndMetadataDo() {
        val track = io.github.nutea.anylisten.core.model.Track(
            io.github.nutea.anylisten.core.model.TrackIdentity("server", "1"), "Song", "Artist", "Album", null)
        val second = track.copy(identity = io.github.nutea.anylisten.core.model.TrackIdentity("server", "2"))
        val playlist = io.github.nutea.anylisten.core.model.Playlist("a", "List", "user", 2)
        val before = io.github.nutea.anylisten.core.model.LibrarySnapshot(listOf(playlist), mapOf("a" to listOf(track, second)), 1L, false)
        val remote = before.copy(refreshedAtEpochMs = 2L, playlists = listOf(playlist.copy(revision = "new")),
            tracksByPlaylist = mapOf("a" to listOf(track.copy(isLocalOnServer = true, playlistId = "a"), second)))
        assertTrue(io.github.nutea.anylisten.core.data.repo.sameLibraryContents(before, remote))
        assertFalse(io.github.nutea.anylisten.core.data.repo.sameLibraryContents(before,
            before.copy(tracksByPlaylist = mapOf("a" to listOf(second, track)))))
        assertFalse(io.github.nutea.anylisten.core.data.repo.sameLibraryContents(before,
            before.copy(tracksByPlaylist = mapOf("a" to listOf(track.copy(title = "Renamed"), second)))))
    }

    @Test fun songChangesIdentifyAllAffectedPlaylistsAndMalformedChangesFallBackToFull() {
        assertEquals(setOf("a"), parse("""{"action":"list_music_add","data":{"id":"a"}}"""))
        assertEquals(setOf("a", "b"), parse("""{"action":"list_music_move","data":{"fromId":"a","toId":"b"}}"""))
        assertEquals(setOf("a", "b"), parse("""{"action":"list_music_update","data":[{"id":"a"},{"id":"b"}]}"""))
        assertEquals(setOf("a"), parse("""{"action":"list_music_clear","data":["a"]}"""))
        assertEquals(emptySet<String>(), parse("""{"action":"list_remove","data":["a"]}"""))
        assertNull(parse("""{"action":"list_music_move","data":{"fromId":"a"}}"""))
        assertNull(parse("""{"action":"list_data_overwrite"}"""))
        assertNull(parse("""{"action":"list_future_action"}"""))
    }
    @Test fun conflatedNotificationsRetainIdsUntilDrained() {
        val pending = PendingLibraryChanges()
        pending.add(LibraryChange(setOf("a")))
        pending.add(LibraryChange(setOf("b")))
        assertEquals(setOf("a", "b"), pending.take()?.playlistIds)
        assertNull(pending.take())
        pending.add(LibraryChange(setOf("c")))
        pending.add(LibraryChange())
        assertNull(pending.take()!!.playlistIds)
    }
}

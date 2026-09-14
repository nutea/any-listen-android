package io.github.nutea.anylisten.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentlyPlayedTest {
    private fun track(id: String, title: String = id) =
        Track(TrackIdentity("p", id), title, "A", "B", 1000L)

    @Test
    fun skipsWhenSourceIsLastPlayed() {
        val mutation = RecentlyPlayed.mutation(listOf("a"), "b", ProtocolConstants.LIST_LAST_PLAYED)
        assertEquals(RecentlyPlayedMutation.None, mutation)
    }

    @Test
    fun insertsAtTopAndTrimsOldestWhenOverLimit() {
        val ids = listOf("keep", "mid", "drop")
        val mutation = RecentlyPlayed.mutation(ids, "new", "love", AddMusicLocationType.TOP, limit = 3)
        assertEquals(RecentlyPlayedMutation.Insert(AddMusicLocationType.TOP, "drop"), mutation)
        val applied = RecentlyPlayed.apply(ids.map(::track), track("new"), mutation)
        assertEquals(listOf("new", "keep", "mid"), applied.map { it.identity.remoteTrackId })
    }

    @Test
    fun insertsAtBottomAndTrimsHeadWhenAddTypeIsBottom() {
        val ids = listOf("oldest", "mid")
        val mutation = RecentlyPlayed.mutation(ids, "new", "default", AddMusicLocationType.BOTTOM, limit = 2)
        assertEquals(RecentlyPlayedMutation.Insert(AddMusicLocationType.BOTTOM, "oldest"), mutation)
        val applied = RecentlyPlayed.apply(ids.map(::track), track("new"), mutation)
        assertEquals(listOf("mid", "new"), applied.map { it.identity.remoteTrackId })
    }

    @Test
    fun movesExistingIdToTopOnReplay() {
        val ids = listOf("newer", "old")
        val mutation = RecentlyPlayed.mutation(ids, "old", "love", AddMusicLocationType.TOP)
        assertEquals(RecentlyPlayedMutation.Move("old", 0), mutation)
        val applied = RecentlyPlayed.apply(ids.map(::track), track("old"), mutation)
        assertEquals(listOf("old", "newer"), applied.map { it.identity.remoteTrackId })
    }

    @Test
    fun noopsWhenAlreadyMostRecent() {
        assertEquals(
            RecentlyPlayedMutation.None,
            RecentlyPlayed.mutation(listOf("a", "b"), "a", "love", AddMusicLocationType.TOP),
        )
        assertEquals(
            RecentlyPlayedMutation.None,
            RecentlyPlayed.mutation(listOf("a", "b"), "b", "love", AddMusicLocationType.BOTTOM),
        )
    }

    @Test
    fun newestFirstReversesBottomAddLists() {
        val tracks = listOf(track("old"), track("new"))
        assertEquals(listOf("old", "new"), RecentlyPlayed.newestFirst(tracks, AddMusicLocationType.TOP).map { it.identity.remoteTrackId })
        assertEquals(listOf("new", "old"), RecentlyPlayed.newestFirst(tracks, AddMusicLocationType.BOTTOM).map { it.identity.remoteTrackId })
    }

    @Test
    fun browseLastPlayedDefaultsToPlayTimeDescendingNotTitle() {
        val older = track("1", "AAA")
        val newer = track("2", "ZZZ")
        val playlist = Playlist(ProtocolConstants.LIST_LAST_PLAYED, "Recently played", "default", 2, canMutateOnline = false)
        val snapshot = LibrarySnapshot(
            playlists = listOf(playlist),
            tracksByPlaylist = mapOf(playlist.id to listOf(newer, older)),
            refreshedAtEpochMs = 1L,
            offline = false,
            addMusicLocationType = AddMusicLocationType.TOP,
        )
        val visible = LibraryBrowse.visibleTracks(snapshot, playlist, "", TrackSortField.PLAY_TIME, ascending = false)
        assertEquals(listOf("ZZZ", "AAA"), visible.map { it.title })
        val byTitle = LibraryBrowse.visibleTracks(snapshot, playlist, "", TrackSortField.TITLE, ascending = true)
        assertEquals(listOf("AAA", "ZZZ"), byTitle.map { it.title })
    }

    @Test
    fun browseEmptyLastPlayedStaysEmpty() {
        val playlist = Playlist(ProtocolConstants.LIST_LAST_PLAYED, "Recently played", "default", 0, canMutateOnline = false)
        val snapshot = LibrarySnapshot(listOf(playlist), mapOf(playlist.id to emptyList()), 1L, false)
        assertTrue(LibraryBrowse.visibleTracks(snapshot, playlist, "", TrackSortField.PLAY_TIME, false).isEmpty())
    }

    @Test
    fun sortForPlaylistUsesPlayTimeOnLastPlayedAndRestoresTitle() {
        val last = Playlist(ProtocolConstants.LIST_LAST_PLAYED, "r", "default", 0)
        val love = Playlist(ProtocolConstants.LIST_LOVE, "l", "default", 0)
        assertEquals(TrackSortField.PLAY_TIME to false, LibraryBrowse.sortForPlaylist(last, TrackSortField.TITLE, true))
        assertEquals(TrackSortField.TITLE to true, LibraryBrowse.sortForPlaylist(love, TrackSortField.PLAY_TIME, false))
        assertEquals(TrackSortField.ARTIST to false, LibraryBrowse.sortForPlaylist(love, TrackSortField.ARTIST, false))
    }
}

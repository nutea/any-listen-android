package io.github.nutea.anylisten.core.data.gateway

import io.github.nutea.anylisten.core.model.AddMusicLocationType
import io.github.nutea.anylisten.core.model.AppError
import io.github.nutea.anylisten.core.model.DemoCatalog
import io.github.nutea.anylisten.core.model.ErrorKind
import io.github.nutea.anylisten.core.model.LibrarySnapshot
import io.github.nutea.anylisten.core.model.LrcParser
import io.github.nutea.anylisten.core.model.Lyrics
import io.github.nutea.anylisten.core.model.MediaResource
import io.github.nutea.anylisten.core.model.Playlist
import io.github.nutea.anylisten.core.model.ProtocolConstants
import io.github.nutea.anylisten.core.model.RecentlyPlayed
import io.github.nutea.anylisten.core.model.RecentlyPlayedMutation
import io.github.nutea.anylisten.core.model.PlaylistEdit
import io.github.nutea.anylisten.core.model.canManage
import io.github.nutea.anylisten.core.model.validPlaylistName
import io.github.nutea.anylisten.core.model.Track

class MockAnyListenGateway : AnyListenGateway {
    /** Offline until a caller says otherwise, matching a gateway with no session yet. */
    private var online = false
    private var snapshot = DemoCatalog.library()

    fun setOnline(value: Boolean) {
        online = value
    }

    override fun isOnline(): Boolean = online

    override fun addMusicLocationType(): AddMusicLocationType = snapshot.addMusicLocationType

    override suspend fun refreshLibrary(): LibrarySnapshot {
        if (!online) throw AppError(ErrorKind.NETWORK_UNREACHABLE, "Offline", retryable = true)
        snapshot = snapshot.copy(refreshedAtEpochMs = System.currentTimeMillis(), offline = false)
        return snapshot
    }

    override suspend fun resolveMedia(track: Track, refresh: Boolean): MediaResource {
        if (!online) throw AppError(ErrorKind.NETWORK_UNREACHABLE, "Offline", retryable = true)
        return MediaResource(url = "https://example.invalid/mock/${track.identity.remoteTrackId}.mp3")
    }

    override suspend fun resolveCover(track: Track): String? = track.coverUrl

    override suspend fun resolveLyrics(track: Track): Lyrics =
        LrcParser.parse("[00:00.00]${track.title}\n[00:03.00]${track.artist}")

    override suspend fun editPlaylist(edit: PlaylistEdit) {
        if (!online) throw AppError(ErrorKind.OFFLINE_MUTATION, "Server edits are disabled offline")
        val lists = snapshot.playlists.toMutableList()
        when (edit) {
            is PlaylistEdit.Create -> {
                require(validPlaylistName(edit.name) && lists.none { it.id == edit.id })
                lists.add(Playlist(edit.id, edit.name.trim(), "general", 0))
            }
            is PlaylistEdit.Rename -> {
                val index = lists.indexOfFirst { it.id == edit.id && it.canManage }
                require(index >= 0 && validPlaylistName(edit.name))
                lists[index] = lists[index].copy(name = edit.name.trim())
            }
            is PlaylistEdit.Delete -> {
                require(lists.any { it.id == edit.id && it.canManage })
                lists.removeAll { it.id == edit.id }
            }
            is PlaylistEdit.Move -> {
                require(edit.delta == -1 || edit.delta == 1)
                val custom = lists.filter { it.id !in setOf("default", "love", "last_played") }
                val index = custom.indexOfFirst { it.id == edit.id && it.canManage }
                require(index >= 0)
                val target = (index + edit.delta).coerceIn(0, custom.lastIndex)
                val destination = lists.indexOf(custom[target])
                val item = lists.removeAt(lists.indexOf(custom[index]))
                lists.add(destination, item)
            }
        }
        snapshot = snapshot.copy(playlists = lists, tracksByPlaylist = lists.associate { it.id to snapshot.tracksByPlaylist[it.id].orEmpty() })
    }

    override suspend fun addToPlaylist(playlistId: String, track: Track) {
        if (!online) throw AppError(ErrorKind.OFFLINE_MUTATION, "Server edits are disabled offline")
        val current = snapshot.tracksByPlaylist[playlistId].orEmpty()
        snapshot = snapshot.copy(
            tracksByPlaylist = snapshot.tracksByPlaylist + (playlistId to (current + track)),
        )
    }

    override suspend fun removeFromPlaylist(playlistId: String, track: Track) {
        if (!online) throw AppError(ErrorKind.OFFLINE_MUTATION, "Server edits are disabled offline")
        val current = snapshot.tracksByPlaylist[playlistId].orEmpty()
        snapshot = snapshot.copy(
            tracksByPlaylist = snapshot.tracksByPlaylist + (playlistId to current.filterNot { it.identity == track.identity }),
        )
    }

    override suspend fun recordRecentlyPlayed(track: Track, sourceListId: String?): List<Track>? {
        if (!online) return null
        val current = snapshot.tracksByPlaylist[ProtocolConstants.LIST_LAST_PLAYED].orEmpty()
        val mutation = RecentlyPlayed.mutation(
            current.map { it.identity.remoteTrackId },
            track.identity.remoteTrackId,
            sourceListId,
            snapshot.addMusicLocationType,
        )
        if (mutation is RecentlyPlayedMutation.None) return null
        val updated = RecentlyPlayed.apply(current, track, mutation)
        val playlists = if (snapshot.playlists.any { it.id == ProtocolConstants.LIST_LAST_PLAYED }) {
            snapshot.playlists.map { playlist ->
                if (playlist.id == ProtocolConstants.LIST_LAST_PLAYED) playlist.copy(trackCount = updated.size) else playlist
            }
        } else {
            snapshot.playlists + Playlist(
                ProtocolConstants.LIST_LAST_PLAYED,
                ProtocolConstants.LIST_LAST_PLAYED,
                "default",
                updated.size,
                canMutateOnline = false,
            )
        }
        snapshot = snapshot.copy(
            playlists = playlists,
            tracksByPlaylist = snapshot.tracksByPlaylist + (ProtocolConstants.LIST_LAST_PLAYED to updated),
        )
        return updated
    }
}

package io.github.nutea.anylisten.core.data.repo

import io.github.nutea.anylisten.core.data.gateway.AnyListenGateway
import io.github.nutea.anylisten.core.data.local.LibraryDao
import io.github.nutea.anylisten.core.data.local.PlaylistEntity
import io.github.nutea.anylisten.core.data.local.PlaylistEntryEntity
import io.github.nutea.anylisten.core.data.local.TrackEntity
import io.github.nutea.anylisten.core.model.AppError
import io.github.nutea.anylisten.core.model.ErrorKind
import io.github.nutea.anylisten.core.model.LibrarySnapshot
import io.github.nutea.anylisten.core.model.Playlist
import io.github.nutea.anylisten.core.model.Track
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest

class LibraryRepository(
    private val dao: LibraryDao,
    private val gateway: AnyListenGateway,
) {
    private val refreshFlight = SingleFlight<LibrarySnapshot>()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeLibrary(): Flow<LibrarySnapshot> = dao.observePlaylists().mapLatest { entities ->
        val tracks = entities.associate { playlist ->
            playlist.id to dao.tracks(playlist.id).map { it.toModel() }
        }
        LibrarySnapshot(
            playlists = entities.map { it.toModel() },
            tracksByPlaylist = tracks,
            refreshedAtEpochMs = entities.maxOfOrNull { it.refreshedAtEpochMs } ?: 0L,
            offline = !gateway.isOnline(),
        )
    }

    suspend fun cached(): LibrarySnapshot {
        val playlists = dao.playlists()
        return LibrarySnapshot(
            playlists = playlists.map { it.toModel() },
            tracksByPlaylist = playlists.associate { it.id to dao.tracks(it.id).map(TrackEntity::toModel) },
            refreshedAtEpochMs = playlists.maxOfOrNull { it.refreshedAtEpochMs } ?: 0L,
            offline = !gateway.isOnline(),
        )
    }

    suspend fun refresh(): LibrarySnapshot = refreshFlight.join {
        val remote = gateway.refreshLibrary()
        persist(remote)
        remote
    }

    suspend fun search(playlistId: String, query: String): List<Track> {
        val q = query.trim().lowercase()
        val tracks = dao.tracks(playlistId).map { it.toModel() }
        if (q.isEmpty()) return tracks
        return tracks.filter {
            it.title.lowercase().contains(q) ||
                it.artist.lowercase().contains(q) ||
                it.album.lowercase().contains(q)
        }
    }

    suspend fun addToPlaylist(playlistId: String, track: Track) {
        if (!gateway.isOnline()) throw AppError(ErrorKind.OFFLINE_MUTATION, "Server edits are disabled offline")
        gateway.addToPlaylist(playlistId, track)
        refresh()
    }

    suspend fun removeFromPlaylist(playlistId: String, track: Track) {
        if (!gateway.isOnline()) throw AppError(ErrorKind.OFFLINE_MUTATION, "Server edits are disabled offline")
        gateway.removeFromPlaylist(playlistId, track)
        refresh()
    }

    suspend fun playlists(): List<Playlist> = dao.playlists().map { it.toModel() }

    private suspend fun persist(snapshot: LibrarySnapshot) {
        val playlists = snapshot.playlists.map { PlaylistEntity.from(it, snapshot.refreshedAtEpochMs) }
        val tracks = snapshot.tracksByPlaylist.values.flatten().map { TrackEntity.from(it) }
        val entries = snapshot.tracksByPlaylist.flatMap { (playlistId, items) ->
            items.mapIndexed { index, track ->
                PlaylistEntryEntity(playlistId, track.identity.remoteTrackId, track.cacheKey, index)
            }
        }
        dao.replaceLibrary(playlists, tracks, entries)
    }
}

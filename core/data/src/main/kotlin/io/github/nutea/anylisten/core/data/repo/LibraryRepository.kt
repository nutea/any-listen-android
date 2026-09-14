package io.github.nutea.anylisten.core.data.repo

import io.github.nutea.anylisten.core.data.gateway.AnyListenGateway
import io.github.nutea.anylisten.core.data.local.LibraryDao
import io.github.nutea.anylisten.core.data.local.PlaylistEntity
import io.github.nutea.anylisten.core.data.local.PlaylistEntryEntity
import io.github.nutea.anylisten.core.data.local.TrackEntity
import io.github.nutea.anylisten.core.model.AddMusicLocationType
import io.github.nutea.anylisten.core.model.AppError
import io.github.nutea.anylisten.core.model.ErrorKind
import io.github.nutea.anylisten.core.model.LibrarySnapshot
import io.github.nutea.anylisten.core.model.Playlist
import io.github.nutea.anylisten.core.model.ProtocolConstants
import io.github.nutea.anylisten.core.model.RecentlyPlayed
import io.github.nutea.anylisten.core.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

class LibraryRepository(
    private val dao: LibraryDao,
    private val gateway: AnyListenGateway,
) {
    private val refreshFlight = SingleFlight<LibrarySnapshot>()
    private val addMusicLocationType = MutableStateFlow(AddMusicLocationType.TOP)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeLibrary(): Flow<LibrarySnapshot> = dao.observePlaylists().mapLatest { entities ->
        snapshotFrom(entities, gateway.isOnline())
    }

    suspend fun cachedTrack(cacheKey: String): Track? = dao.track(cacheKey)?.toModel()

    suspend fun cached(): LibrarySnapshot = snapshotFrom(dao.playlists(), gateway.isOnline())

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

    /**
     * Record a play onto the server `last_played` list using the same mutations as the web player.
     * No-ops offline, when the queue was started from recently played, or when the id is already newest.
     */
    suspend fun recordPlay(track: Track, sourceListId: String?) {
        if (!gateway.isOnline()) return
        if (RecentlyPlayed.skipBecauseSourceIsRecent(sourceListId)) return
        val updated = gateway.recordRecentlyPlayed(track, sourceListId) ?: return
        addMusicLocationType.value = gateway.addMusicLocationType()
        persistLastPlayed(updated)
    }

    suspend fun playlists(): List<Playlist> = dao.playlists().map { it.toModel() }

    private suspend fun snapshotFrom(entities: List<PlaylistEntity>, online: Boolean): LibrarySnapshot {
        val tracks = entities.associate { playlist ->
            playlist.id to dao.tracks(playlist.id).map { it.toModel() }
        }
        return LibrarySnapshot(
            playlists = entities.map { it.toModel() },
            tracksByPlaylist = tracks,
            refreshedAtEpochMs = entities.maxOfOrNull { it.refreshedAtEpochMs } ?: 0L,
            offline = !online,
            addMusicLocationType = addMusicLocationType.value,
        )
    }

    private suspend fun persist(snapshot: LibrarySnapshot) {
        addMusicLocationType.value = snapshot.addMusicLocationType
        val playlists = snapshot.playlists.map { PlaylistEntity.from(it, snapshot.refreshedAtEpochMs) }
        val tracks = snapshot.tracksByPlaylist.values.flatten().map { TrackEntity.from(it) }
        val entries = snapshot.tracksByPlaylist.flatMap { (playlistId, items) ->
            items.mapIndexed { index, track ->
                PlaylistEntryEntity(playlistId, track.identity.remoteTrackId, track.cacheKey, index)
            }
        }
        dao.replaceLibrary(playlists, tracks, entries)
    }

    private suspend fun persistLastPlayed(tracks: List<Track>) {
        val now = System.currentTimeMillis()
        val existing = dao.playlists().find { it.id == ProtocolConstants.LIST_LAST_PLAYED }
        val playlist = PlaylistEntity(
            id = ProtocolConstants.LIST_LAST_PLAYED,
            name = existing?.name ?: ProtocolConstants.LIST_LAST_PLAYED,
            type = existing?.type ?: "default",
            trackCount = tracks.size,
            coverUrl = existing?.coverUrl,
            canMutateOnline = false,
            refreshedAtEpochMs = now,
        )
        dao.replacePlaylistTracks(
            ProtocolConstants.LIST_LAST_PLAYED,
            playlist,
            tracks.map { TrackEntity.from(it) },
            tracks.mapIndexed { index, track ->
                PlaylistEntryEntity(
                    ProtocolConstants.LIST_LAST_PLAYED,
                    track.identity.remoteTrackId,
                    track.cacheKey,
                    index,
                )
            },
        )
    }
}

/**
 * Dedupes consecutive reports of the same track so `play()` and ExoPlayer transitions
 * do not double-write `last_played`. Failures clear the stamp so the next attempt can retry.
 */
class RecentlyPlayedRecorder(private val library: LibraryRepository) {
    private val lastMusicId = AtomicReference<String?>(null)
    private val mutex = Mutex()

    suspend fun onTrackStarted(track: Track, sourceListId: String?) = mutex.withLock {
        if (RecentlyPlayed.skipBecauseSourceIsRecent(sourceListId)) return
        val musicId = track.identity.remoteTrackId
        if (musicId.isBlank() || lastMusicId.get() == musicId) return
        val previous = lastMusicId.get()
        lastMusicId.set(musicId)
        try {
            library.recordPlay(track, sourceListId)
        } catch (cancelled: CancellationException) {
            lastMusicId.compareAndSet(musicId, previous)
            throw cancelled
        } catch (_: Exception) {
            lastMusicId.compareAndSet(musicId, previous)
        }
    }
}

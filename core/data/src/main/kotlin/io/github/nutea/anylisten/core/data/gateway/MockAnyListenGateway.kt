package io.github.nutea.anylisten.core.data.gateway

import io.github.nutea.anylisten.core.model.AppError
import io.github.nutea.anylisten.core.model.DemoCatalog
import io.github.nutea.anylisten.core.model.ErrorKind
import io.github.nutea.anylisten.core.model.LibrarySnapshot
import io.github.nutea.anylisten.core.model.LrcParser
import io.github.nutea.anylisten.core.model.Lyrics
import io.github.nutea.anylisten.core.model.MediaResource
import io.github.nutea.anylisten.core.model.Track

class MockAnyListenGateway : AnyListenGateway {
    /** Offline until a caller says otherwise, matching a gateway with no session yet. */
    private var online = false
    private var snapshot = DemoCatalog.library()

    fun setOnline(value: Boolean) {
        online = value
    }

    override fun isOnline(): Boolean = online

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
}

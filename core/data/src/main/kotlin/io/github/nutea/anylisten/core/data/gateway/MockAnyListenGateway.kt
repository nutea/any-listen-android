package io.github.nutea.anylisten.core.data.gateway

import io.github.nutea.anylisten.core.model.AppError
import io.github.nutea.anylisten.core.model.DemoCatalog
import io.github.nutea.anylisten.core.model.ErrorKind
import io.github.nutea.anylisten.core.model.LibrarySnapshot
import io.github.nutea.anylisten.core.model.LrcParser
import io.github.nutea.anylisten.core.model.Lyrics
import io.github.nutea.anylisten.core.model.MediaResource
import io.github.nutea.anylisten.core.model.ProtocolConstants
import io.github.nutea.anylisten.core.model.ServerProfile
import io.github.nutea.anylisten.core.model.Track
import java.util.UUID

class MockAnyListenGateway : AnyListenGateway {
    private var signedIn = false
    private var online = true
    private var snapshot = DemoCatalog.library()
    var failNextRestore: Boolean = false

    fun setOnline(value: Boolean) {
        online = value
    }

    override fun isOnline(): Boolean = signedIn && online

    override suspend fun login(baseUrl: String, password: String): SessionInfo {
        UrlNormalizer.httpsBase(baseUrl)
        if (password == "wrong") throw AppError(ErrorKind.AUTH_FAILED, "Authentication failed")
        signedIn = true
        return SessionInfo(
            profile = ServerProfile(
                id = DemoCatalog.PROFILE_ID,
                baseUrl = baseUrl.trimEnd('/'),
                serverId = "demo-server",
                serverName = "Mock Any Listen",
                reportedVersion = ProtocolConstants.TARGET_SERVER_VERSION,
            ),
            token = "mock-${UUID.randomUUID()}",
        )
    }

    override suspend fun restore(profile: ServerProfile, token: String): SessionInfo {
        if (failNextRestore) {
            failNextRestore = false
            throw AppError(ErrorKind.SESSION_EXPIRED, "Session expired")
        }
        if (token.isBlank()) throw AppError(ErrorKind.SESSION_EXPIRED, "Missing session")
        signedIn = true
        return SessionInfo(profile, token)
    }

    override suspend fun logout() {
        signedIn = false
    }

    override suspend fun refreshLibrary(): LibrarySnapshot {
        if (!signedIn) throw AppError(ErrorKind.SESSION_EXPIRED, "Not signed in")
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

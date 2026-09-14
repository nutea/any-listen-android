package io.github.nutea.anylisten.core.data.gateway

import io.github.nutea.anylisten.core.model.AddMusicLocationType
import io.github.nutea.anylisten.core.model.LibrarySnapshot
import io.github.nutea.anylisten.core.model.Lyrics
import io.github.nutea.anylisten.core.model.MediaResource
import io.github.nutea.anylisten.core.model.ServerProfile
import io.github.nutea.anylisten.core.model.Track

data class SessionInfo(
    val profile: ServerProfile,
    val token: String,
)

/**
 * Server capabilities the app consumes. Signing in, reconnecting and socket ownership belong to
 * `SessionConnectionManager`; a gateway only knows how to ask a live session for data.
 */
interface AnyListenGateway {
    suspend fun refreshLibrary(): LibrarySnapshot
    suspend fun resolveMedia(track: Track, refresh: Boolean = false): MediaResource
    suspend fun resolveCover(track: Track): String?
    suspend fun resolveLyrics(track: Track): Lyrics
    suspend fun addToPlaylist(playlistId: String, track: Track)
    suspend fun removeFromPlaylist(playlistId: String, track: Track)
    /**
     * Apply the same `last_played` mutations the web-server player runs on `musicChanged`.
     * Returns the updated last-played tracks, or null when nothing changed or the client is offline.
     */
    suspend fun recordRecentlyPlayed(track: Track, sourceListId: String?): List<Track>?
    fun isOnline(): Boolean
    /** Last known `list.addMusicLocationType`; default `top` like web. */
    fun addMusicLocationType(): AddMusicLocationType = AddMusicLocationType.TOP
}

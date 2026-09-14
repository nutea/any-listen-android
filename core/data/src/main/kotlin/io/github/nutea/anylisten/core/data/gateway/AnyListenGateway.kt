package io.github.nutea.anylisten.core.data.gateway

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
    fun isOnline(): Boolean
}

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

interface AnyListenGateway {
    suspend fun login(baseUrl: String, password: String): SessionInfo
    suspend fun restore(profile: ServerProfile, token: String): SessionInfo
    suspend fun logout()
    suspend fun refreshLibrary(): LibrarySnapshot
    suspend fun resolveMedia(track: Track, refresh: Boolean = false): MediaResource
    suspend fun resolveCover(track: Track): String?
    suspend fun resolveLyrics(track: Track): Lyrics
    suspend fun addToPlaylist(playlistId: String, track: Track)
    suspend fun removeFromPlaylist(playlistId: String, track: Track)
    fun isOnline(): Boolean
}

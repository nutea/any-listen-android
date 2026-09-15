package io.github.nutea.anylisten.core.data.gateway

import io.github.nutea.anylisten.core.data.connection.SessionConnectionManager
import io.github.nutea.anylisten.core.model.AddMusicLocationType
import io.github.nutea.anylisten.core.model.AppError
import io.github.nutea.anylisten.core.model.ErrorKind
import io.github.nutea.anylisten.core.model.LibrarySnapshot
import io.github.nutea.anylisten.core.model.LrcParser
import io.github.nutea.anylisten.core.model.Lyrics
import io.github.nutea.anylisten.core.model.MediaResource
import io.github.nutea.anylisten.core.model.ProtocolConstants
import io.github.nutea.anylisten.core.model.RecentlyPlayed
import io.github.nutea.anylisten.core.model.RecentlyPlayedMutation
import io.github.nutea.anylisten.core.model.Track
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Server API expressed as IPC calls.
 *
 * The gateway no longer owns a socket, a session or a mutex: it borrows the live channel from
 * [SessionConnectionManager] for the duration of one call. A call issued while a reconnect is in
 * flight waits for the new socket and is retried once, instead of failing with "socket not
 * connected" the moment a handover starts.
 */
class ProtocolAnyListenGateway(
    private val connection: SessionConnectionManager,
) : AnyListenGateway {

    @Volatile private var lastAddMusicLocationType = AddMusicLocationType.TOP

    override fun isOnline(): Boolean = connection.isOnline

    override fun addMusicLocationType(): AddMusicLocationType = lastAddMusicLocationType

    private val baseUrl: String
        get() = connection.session.value?.profile?.baseUrl
            ?: throw AppError(ErrorKind.SESSION_EXPIRED, "Not signed in")

    override suspend fun refreshLibrary(): LibrarySnapshot = readLibrary(null, null)

    override suspend fun refreshLibrary(cached: LibrarySnapshot, changedPlaylistIds: Set<String>): LibrarySnapshot =
        readLibrary(cached, changedPlaylistIds)

    private suspend fun readLibrary(cached: LibrarySnapshot?, changedPlaylistIds: Set<String>?): LibrarySnapshot {
        val profileId = connection.session.value?.profile?.id
            ?: throw AppError(ErrorKind.SESSION_EXPIRED, "Not signed in")
        val listsEl = connection.withChannel { it.call(listOf("getAllUserLists")) }
        val playlists = ProtocolDtos.playlistsFrom(listsEl?.jsonObject ?: JsonObject(emptyMap()))
        val tracks = linkedMapOf<String, List<Track>>()
        val freshlyRead = mutableMapOf<String, Track>()
        for (playlist in playlists) {
            val retained = cached?.tracksByPlaylist?.get(playlist.id)
            if (changedPlaylistIds != null && playlist.id !in changedPlaylistIds && retained != null &&
                retained.all { it.identity.serverProfileId == profileId }) {
                tracks[playlist.id] = retained
                continue
            }
            val musics = connection.withChannel { it.call(listOf("getListMusics"), listOf(JsonPrimitive(playlist.id))) }
            tracks[playlist.id] = musics?.jsonArray?.map {
                val track = ProtocolDtos.trackFrom(profileId, playlist.id, it.jsonObject)
                track.copy(coverUrl = resolvePublicUrl(track.coverUrl) ?: track.coverUrl)
            }.orEmpty()
            tracks.getValue(playlist.id).forEach { freshlyRead[it.cacheKey] = it }
        }
        // A track may occur in several lists but shares one local database row. Do not let
        // retained metadata from another playlist overwrite a freshly read update.
        tracks.replaceAll { _, items -> items.map { freshlyRead[it.cacheKey] ?: it } }
        return LibrarySnapshot(
            playlists = playlists.map { item -> item.copy(trackCount = tracks[item.id]?.size ?: item.trackCount) },
            tracksByPlaylist = tracks,
            refreshedAtEpochMs = System.currentTimeMillis(),
            offline = false,
            addMusicLocationType = fetchAddMusicLocationType(),
        )
    }

    override suspend fun resolveMedia(track: Track, refresh: Boolean): MediaResource {
        val payload = Message2Call.obj(
            "musicInfo" to ProtocolDtos.trackToProtocol(track),
            "isRefresh" to JsonPrimitive(refresh),
        )
        val result = connection.withChannel { it.call(listOf("getMusicUrl"), listOf(payload)) }?.jsonObject
            ?: throw AppError(ErrorKind.TRACK_UNAVAILABLE, "No media URL")
        val url = result["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (url.isBlank()) throw AppError(ErrorKind.TRACK_UNAVAILABLE, "Empty media URL")
        val base = baseUrl
        val absolute = UrlNormalizer.resolve(base, url)
        UrlNormalizer.requireEncryptedOrLocal(absolute)
        return MediaResource(
            url = absolute,
            quality = result["quality"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            requiresSameOriginCookie = UrlNormalizer.sameHost(base, absolute),
        )
    }

    override suspend fun resolveCover(track: Track): String? {
        val payload = Message2Call.obj("musicInfo" to ProtocolDtos.trackToProtocol(track))
        val result = connection.withChannel { it.call(listOf("getMusicPic"), listOf(payload)) }?.jsonObject
        val url = result?.get("url")?.jsonPrimitive?.contentOrNull ?: return track.coverUrl
        return url.takeIf { it.isNotBlank() }?.let { resolvePublicUrl(it) } ?: track.coverUrl
    }

    override suspend fun resolveLyrics(track: Track): Lyrics {
        // Match web loadMusicLyric: omit isRefresh (server default false). isRefresh=true
        // skips edited DB lyrics and sidecar .lrc files for local tracks.
        val payload = Message2Call.obj("musicInfo" to ProtocolDtos.trackToProtocol(track))
        val result = connection.withChannel { it.call(listOf("getMusicLyric"), listOf(payload)) }?.jsonObject
        val info = result?.get("info")?.jsonObject
        val raw = listOf("lyric", "awlyric", "tlyric")
            .firstNotNullOfOrNull { key -> info?.get(key)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } }
        return LrcParser.parse(raw)
    }

    override suspend fun addToPlaylist(playlistId: String, track: Track) {
        if (existsInPlaylist(playlistId, track.identity.remoteTrackId)) return
        mutatePlaylist(playlistId, track.identity.remoteTrackId, expectPresent = true) {
            val data = Message2Call.obj(
                "id" to JsonPrimitive(playlistId),
                "musicInfos" to buildJsonArray { add(ProtocolDtos.trackToProtocol(track)) },
                "addMusicLocationType" to JsonPrimitive(ProtocolConstants.ADD_LOCATION_BOTTOM),
            )
            connection.withChannel {
                it.call(
                    listOf("listAction"),
                    listOf(
                        Message2Call.obj(
                            "action" to JsonPrimitive(ProtocolConstants.ACTION_MUSIC_ADD),
                            "data" to data,
                        ),
                    ),
                )
            }
        }
    }

    override suspend fun removeFromPlaylist(playlistId: String, track: Track) {
        mutatePlaylist(playlistId, track.identity.remoteTrackId, expectPresent = false) {
            val data = Message2Call.obj(
                "listId" to JsonPrimitive(playlistId),
                "ids" to buildJsonArray { add(JsonPrimitive(track.identity.remoteTrackId)) },
            )
            connection.withChannel {
                it.call(
                    listOf("listAction"),
                    listOf(
                        Message2Call.obj(
                            "action" to JsonPrimitive(ProtocolConstants.ACTION_MUSIC_REMOVE),
                            "data" to data,
                        ),
                    ),
                )
            }
        }
    }

    override suspend fun recordRecentlyPlayed(track: Track, sourceListId: String?): List<Track>? {
        if (!isOnline()) return null
        if (RecentlyPlayed.skipBecauseSourceIsRecent(sourceListId)) return null
        val musicId = track.identity.remoteTrackId
        if (musicId.isBlank()) return null
        val addType = fetchAddMusicLocationType()
        val before = currentIds(ProtocolConstants.LIST_LAST_PLAYED)
        when (val mutation = RecentlyPlayed.mutation(before, musicId, sourceListId, addType)) {
            RecentlyPlayedMutation.None -> return null
            is RecentlyPlayedMutation.Move -> listAction(
                ProtocolConstants.ACTION_MUSIC_UPDATE_POSITION,
                Message2Call.obj(
                    "listId" to JsonPrimitive(ProtocolConstants.LIST_LAST_PLAYED),
                    "position" to JsonPrimitive(mutation.position),
                    "ids" to buildJsonArray { add(JsonPrimitive(musicId)) },
                ),
            )
            is RecentlyPlayedMutation.Insert -> {
                val musicInfo = ProtocolDtos.withCreateTime(ProtocolDtos.trackToProtocol(track), System.currentTimeMillis())
                listAction(
                    ProtocolConstants.ACTION_MUSIC_ADD,
                    Message2Call.obj(
                        "id" to JsonPrimitive(ProtocolConstants.LIST_LAST_PLAYED),
                        "musicInfos" to buildJsonArray { add(musicInfo) },
                        "addMusicLocationType" to JsonPrimitive(mutation.addType.wire),
                    ),
                )
                mutation.trimId?.let { trimId ->
                    listAction(
                        ProtocolConstants.ACTION_MUSIC_REMOVE,
                        Message2Call.obj(
                            "listId" to JsonPrimitive(ProtocolConstants.LIST_LAST_PLAYED),
                            "ids" to buildJsonArray { add(JsonPrimitive(trimId)) },
                        ),
                    )
                }
            }
        }
        return lastPlayedTracks()
    }

    private suspend fun lastPlayedTracks(): List<Track> {
        val profileId = connection.session.value?.profile?.id
            ?: throw AppError(ErrorKind.SESSION_EXPIRED, "Not signed in")
        val musics = connection.withChannel {
            it.call(listOf("getListMusics"), listOf(JsonPrimitive(ProtocolConstants.LIST_LAST_PLAYED)))
        }
        return musics?.jsonArray?.map {
            val parsed = ProtocolDtos.trackFrom(profileId, ProtocolConstants.LIST_LAST_PLAYED, it.jsonObject)
            parsed.copy(coverUrl = resolvePublicUrl(parsed.coverUrl) ?: parsed.coverUrl)
        }.orEmpty()
    }

    private suspend fun fetchAddMusicLocationType(): AddMusicLocationType {
        val settings = runCatching {
            connection.withChannel { it.call(listOf("getSetting")) }?.jsonObject
        }.getOrNull() ?: return lastAddMusicLocationType
        lastAddMusicLocationType = ProtocolDtos.addMusicLocationTypeFrom(settings)
        return lastAddMusicLocationType
    }

    private suspend fun listAction(action: String, data: JsonObject) {
        connection.withChannel {
            it.call(
                listOf("listAction"),
                listOf(Message2Call.obj("action" to JsonPrimitive(action), "data" to data)),
            )
        }
    }

    private suspend fun mutatePlaylist(
        playlistId: String,
        trackId: String,
        expectPresent: Boolean,
        write: suspend () -> Unit,
    ) {
        if (!isOnline()) throw AppError(ErrorKind.OFFLINE_MUTATION, "Server edits are disabled offline")
        val before = currentIds(playlistId)
        try {
            write()
        } catch (error: Throwable) {
            val afterUnknown = runCatching { currentIds(playlistId) }.getOrNull()
            if (afterUnknown != null && afterUnknown != before) return
            throw if (error is AppError) error else AppError(ErrorKind.WRITE_UNCONFIRMED, error.message ?: "Write failed")
        }
        val after = currentIds(playlistId)
        if (after != before) return
        if (existsInPlaylist(playlistId, trackId) == expectPresent) return
        throw AppError(ErrorKind.WRITE_UNCONFIRMED, "Remote list did not change")
    }

    private suspend fun existsInPlaylist(playlistId: String, trackId: String): Boolean {
        val result = runCatching {
            connection.withChannel {
                it.call(
                    listOf("checkListExistMusic"),
                    listOf(JsonPrimitive(playlistId), JsonPrimitive(trackId)),
                )
            }
        }.getOrNull()
        result?.jsonPrimitive?.booleanOrNull?.let { return it }
        return currentIds(playlistId).contains(trackId)
    }

    private suspend fun currentIds(playlistId: String): List<String> {
        val musics = connection.withChannel { it.call(listOf("getListMusics"), listOf(JsonPrimitive(playlistId))) }
        return musics?.jsonArray?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.orEmpty()
    }

    private fun resolvePublicUrl(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val sessionBase = connection.session.value?.profile?.baseUrl ?: return null
        return runCatching { UrlNormalizer.resolveArtwork(sessionBase, value) }.getOrNull()
    }
}

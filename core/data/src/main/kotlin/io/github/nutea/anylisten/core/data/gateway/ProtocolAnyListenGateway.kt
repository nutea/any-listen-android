package io.github.nutea.anylisten.core.data.gateway

import io.github.nutea.anylisten.core.model.AppError
import io.github.nutea.anylisten.core.model.ErrorKind
import io.github.nutea.anylisten.core.model.LibrarySnapshot
import io.github.nutea.anylisten.core.model.LrcParser
import io.github.nutea.anylisten.core.model.Lyrics
import io.github.nutea.anylisten.core.model.MediaResource
import io.github.nutea.anylisten.core.model.ProtocolConstants
import io.github.nutea.anylisten.core.model.ServerProfile
import io.github.nutea.anylisten.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ProtocolAnyListenGateway(
    private val http: OkHttpClient,
    private val auth: IpcAuthClient,
) : AnyListenGateway {
    private val mutex = Mutex()
    private var session: SessionInfo? = null
    private var socket: WebSocket? = null
    private var ipc: Message2Call? = null
    private val connected = AtomicBoolean(false)

    override fun isOnline(): Boolean = connected.get()

    override suspend fun login(baseUrl: String, password: String): SessionInfo = mutex.withLock {
        disconnectLocked()
        val info = withContext(Dispatchers.IO) { auth.login(baseUrl, password) }
        connectLocked(info)
        session = info
        info
    }

    override suspend fun restore(profile: ServerProfile, token: String): SessionInfo = mutex.withLock {
        disconnectLocked()
        val info = withContext(Dispatchers.IO) { auth.restore(profile, token) }
        connectLocked(info)
        session = info
        info
    }

    override suspend fun logout() {
        mutex.withLock {
            socket?.close(ProtocolConstants.CLOSE_LOGOUT, "logout")
            disconnectLocked()
            session = null
        }
    }

    override suspend fun refreshLibrary(): LibrarySnapshot {
        val profile = session?.profile ?: throw AppError(ErrorKind.SESSION_EXPIRED, "Not signed in")
        val call = ipc ?: throw AppError(ErrorKind.NETWORK_UNREACHABLE, "Socket not connected", retryable = true)
        val listsEl = call.call(listOf("getAllUserLists"))
        val playlists = ProtocolDtos.playlistsFrom(listsEl?.jsonObject ?: JsonObject(emptyMap()))
        val tracks = linkedMapOf<String, List<Track>>()
        for (playlist in playlists) {
            val musics = call.call(listOf("getListMusics"), listOf(JsonPrimitive(playlist.id)))
            tracks[playlist.id] = musics?.jsonArray?.map {
                val track = ProtocolDtos.trackFrom(profile.id, playlist.id, it.jsonObject)
                track.copy(coverUrl = resolvePublicUrl(track.coverUrl) ?: track.coverUrl)
            }.orEmpty()
        }
        return LibrarySnapshot(
            playlists = playlists.map { item -> item.copy(trackCount = tracks[item.id]?.size ?: item.trackCount) },
            tracksByPlaylist = tracks,
            refreshedAtEpochMs = System.currentTimeMillis(),
            offline = false,
        )
    }

    override suspend fun resolveMedia(track: Track, refresh: Boolean): MediaResource {
        val call = requireIpc()
        val payload = Message2Call.obj(
            "musicInfo" to ProtocolDtos.trackToProtocol(track),
            "isRefresh" to JsonPrimitive(refresh),
        )
        val result = call.call(listOf("getMusicUrl"), listOf(payload))?.jsonObject
            ?: throw AppError(ErrorKind.TRACK_UNAVAILABLE, "No media URL")
        val url = result["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (url.isBlank()) throw AppError(ErrorKind.TRACK_UNAVAILABLE, "Empty media URL")
        val absolute = UrlNormalizer.resolve(session!!.profile.baseUrl, url)
        return MediaResource(
            url = absolute,
            quality = result["quality"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            requiresSameOriginCookie = UrlNormalizer.sameHost(session!!.profile.baseUrl, absolute),
        )
    }

    override suspend fun resolveCover(track: Track): String? {
        val call = requireIpc()
        val payload = Message2Call.obj("musicInfo" to ProtocolDtos.trackToProtocol(track))
        val result = runCatching { call.call(listOf("getMusicPic"), listOf(payload))?.jsonObject }.getOrNull()
        val url = result?.get("url")?.jsonPrimitive?.contentOrNull ?: return track.coverUrl
        return url.takeIf { it.isNotBlank() }?.let { resolvePublicUrl(it) } ?: track.coverUrl
    }

    override suspend fun resolveLyrics(track: Track): Lyrics {
        val call = requireIpc()
        val payload = Message2Call.obj("musicInfo" to ProtocolDtos.trackToProtocol(track))
        val result = runCatching { call.call(listOf("getMusicLyric"), listOf(payload))?.jsonObject }.getOrNull()
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
                "musicInfos" to kotlinx.serialization.json.buildJsonArray {
                    add(ProtocolDtos.trackToProtocol(track))
                },
                "addMusicLocationType" to JsonPrimitive(ProtocolConstants.ADD_LOCATION_BOTTOM),
            )
            requireIpc().call(
                listOf("listAction"),
                listOf(Message2Call.obj("action" to JsonPrimitive(ProtocolConstants.ACTION_MUSIC_ADD), "data" to data)),
            )
        }
    }

    override suspend fun removeFromPlaylist(playlistId: String, track: Track) {
        mutatePlaylist(playlistId, track.identity.remoteTrackId, expectPresent = false) {
            val data = Message2Call.obj(
                "listId" to JsonPrimitive(playlistId),
                "ids" to kotlinx.serialization.json.buildJsonArray {
                    add(JsonPrimitive(track.identity.remoteTrackId))
                },
            )
            requireIpc().call(
                listOf("listAction"),
                listOf(Message2Call.obj("action" to JsonPrimitive(ProtocolConstants.ACTION_MUSIC_REMOVE), "data" to data)),
            )
        }
    }

    private suspend fun mutatePlaylist(
        playlistId: String,
        trackId: String,
        expectPresent: Boolean,
        write: suspend () -> Unit,
    ) {
        if (!connected.get()) throw AppError(ErrorKind.OFFLINE_MUTATION, "Server edits are disabled offline")
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
            requireIpc().call(
                listOf("checkListExistMusic"),
                listOf(JsonPrimitive(playlistId), JsonPrimitive(trackId)),
            )
        }.getOrNull()
        result?.jsonPrimitive?.booleanOrNull?.let { return it }
        return currentIds(playlistId).contains(trackId)
    }

    private suspend fun currentIds(playlistId: String): List<String> {
        val musics = requireIpc().call(listOf("getListMusics"), listOf(JsonPrimitive(playlistId)))
        return musics?.jsonArray?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.orEmpty()
    }

    private fun requireIpc(): Message2Call =
        ipc ?: throw AppError(ErrorKind.NETWORK_UNREACHABLE, "Socket not connected", retryable = true)

    private fun resolvePublicUrl(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val sessionBase = session?.profile?.baseUrl ?: return null
        return runCatching { UrlNormalizer.resolve(sessionBase, value) }.getOrNull()
    }

    private suspend fun connectLocked(info: SessionInfo) {
        val token = java.net.URLEncoder.encode(info.token, Charsets.UTF_8)
        val wsUrl = UrlNormalizer.wsUrl(
            info.profile.baseUrl,
            "${ProtocolConstants.SOCKET_PATH}?m=$token&t=${ProtocolConstants.WIN_TYPE_MAIN}",
        )
        val client = Message2Call(ProtocolDtos.json) { text -> socket?.send(text) }
        ipc = client
        val request = Request.Builder().url(wsUrl).build()
        socket = suspendCancellableCoroutine { cont ->
            val ws = http.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        connected.set(true)
                        if (cont.isActive) cont.resume(webSocket)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        client.onMessage(text)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        connected.set(false)
                        client.destroy(t.message ?: "socket failed")
                        if (cont.isActive) {
                            val code = response?.code
                            cont.resumeWithException(
                                if (code == 401) AppError(ErrorKind.SESSION_EXPIRED, "WebSocket unauthorized")
                                else AppError.fromThrowable(t),
                            )
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        connected.set(false)
                        client.destroy("closed")
                    }
                },
            )
            cont.invokeOnCancellation { ws.cancel() }
        }
        runCatching { client.call(listOf("inited")) }
        runCatching {
            val version = client.call(listOf("getCurrentVersionInfo"))?.jsonObject
            val reported = version?.get("version")?.jsonPrimitive?.contentOrNull
            if (!reported.isNullOrBlank()) {
                session = info.copy(profile = info.profile.copy(reportedVersion = reported))
            }
        }
        runCatching {
            val tokenReq = Request.Builder()
                .url(UrlNormalizer.resolve(info.profile.baseUrl, "${ProtocolConstants.PROXY_TOKEN_PATH}?m=$token"))
                .get()
                .build()
            http.newCall(tokenReq).execute().close()
        }
    }

    private fun disconnectLocked() {
        connected.set(false)
        ipc?.destroy()
        ipc = null
        socket?.cancel()
        socket = null
    }
}

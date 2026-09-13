package io.github.nutea.anylisten.core.data.gateway

import io.github.nutea.anylisten.core.model.IntervalParser
import io.github.nutea.anylisten.core.model.Playlist
import io.github.nutea.anylisten.core.model.ProtocolConstants
import io.github.nutea.anylisten.core.model.Track
import io.github.nutea.anylisten.core.model.TrackIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ProtocolDtos {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    fun trackFrom(profileId: String, playlistId: String?, obj: JsonObject): Track {
        val id = obj.string("id").orEmpty()
        val meta = obj["meta"]?.jsonObject
        return Track(
            identity = TrackIdentity(profileId, id),
            title = obj.string("name").orEmpty(),
            artist = obj.string("singer").orEmpty(),
            album = meta?.string("albumName").orEmpty(),
            durationMs = IntervalParser.toMillis(obj.string("interval")),
            coverUrl = meta?.string("picUrl"),
            fingerprint = meta?.string("musicId"),
            isLocalOnServer = obj["isLocal"]?.jsonPrimitive?.booleanOrNull == true,
            playlistId = playlistId,
            rawJson = obj.toString(),
            sizeLabel = meta?.string("sizeStr"),
            extension = meta?.string("ext"),
        )
    }

    fun trackToProtocol(track: Track): JsonObject {
        track.rawJson?.let {
            return json.parseToJsonElement(it).jsonObject
        }
        return JsonObject(
            buildMap {
                put("id", JsonPrimitive(track.identity.remoteTrackId))
                put("name", JsonPrimitive(track.title))
                put("singer", JsonPrimitive(track.artist))
                put("interval", JsonNull)
                put("isLocal", JsonPrimitive(track.isLocalOnServer))
                put(
                    "meta",
                    JsonObject(
                        buildMap {
                            put("musicId", JsonPrimitive(track.fingerprint ?: track.identity.remoteTrackId))
                            put("albumName", JsonPrimitive(track.album))
                            track.coverUrl?.let { put("picUrl", JsonPrimitive(it)) }
                        },
                    ),
                )
            },
        )
    }

    fun playlistsFrom(root: JsonObject): List<Playlist> {
        val result = mutableListOf<Playlist>()
        root["defaultList"]?.jsonObject?.let { result += playlistFrom(it, ProtocolConstants.LIST_DEFAULT) }
        root["loveList"]?.jsonObject?.let { result += playlistFrom(it, ProtocolConstants.LIST_LOVE) }
        root["lastPlayList"]?.jsonObject?.let { result += playlistFrom(it, ProtocolConstants.LIST_LAST_PLAYED) }
        root["userList"]?.jsonArray?.forEach { el ->
            result += playlistFrom(el.jsonObject, el.jsonObject.string("id").orEmpty())
        }
        return result.filter { it.id.isNotBlank() }
    }

    private fun playlistFrom(obj: JsonObject, fallbackId: String): Playlist {
        val id = obj.string("id") ?: fallbackId
        val meta = obj["meta"]?.jsonObject
        return Playlist(
            id = id,
            name = obj.string("name") ?: id,
            type = obj.string("type") ?: "general",
            trackCount = meta?.get("songCount")?.jsonPrimitive?.intOrNull ?: 0,
            coverUrl = meta?.string("pic"),
            revision = meta?.get("updateTime")?.toString(),
            canMutateOnline = id != ProtocolConstants.LIST_LAST_PLAYED,
        )
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull
}

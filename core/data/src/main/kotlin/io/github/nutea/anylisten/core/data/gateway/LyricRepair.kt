package io.github.nutea.anylisten.core.data.gateway

import io.github.nutea.anylisten.core.model.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException

data class LyricSource(val id: String, val extensionId: String, val name: String)
data class LyricCandidate(val id: String, val title: String, val artist: String,
    val source: LyricSource, val embedded: Lyrics? = null)

fun lyricPayload(track: Track, lyrics: Lyrics) = buildJsonObject {
    put("lyric", lyrics.toTimedLrc()); put("tlyric", lyrics.translationRaw)
    put("rlyric", lyrics.romanizationRaw); put("awlyric", lyrics.karaokeRaw)
    put("name", track.title); put("singer", track.artist)
    put("interval", ProtocolDtos.trackToProtocol(track)["interval"] ?: JsonNull)
}

class LyricRepair(private val online: () -> Boolean,
    private val call: suspend (String, List<JsonElement>, Boolean) -> JsonElement?) {
    suspend fun sources(): List<LyricSource> {
        requireOnline()
        val root = call("getResourceList", emptyList(), true) as? JsonObject
        val resources = root?.get("resources") as? JsonObject
        return (resources?.get("lyricSearch") as? JsonArray).orEmpty().mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = obj.text("id"); val ext = obj.text("extensionId")
            if (id.isBlank() || ext.isBlank()) null else LyricSource(id, ext,
                obj.text("name").takeUnless { it.isBlank() || it.contains('{') || it.startsWith("t(") } ?: id)
        }.distinctBy { it.extensionId to it.id }
    }
    suspend fun search(source: LyricSource, name: String, artist: String): List<LyricCandidate> {
        requireOnline(); require(name.isNotBlank())
        val result = call("lyricSearch", listOf(buildJsonObject {
            put("extensionId", source.extensionId); put("source", source.id)
            put("name", name.trim()); put("artist", artist.trim())
        }), true) as? JsonArray
        return result.orEmpty().mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = obj.text("id")
            if (id.isBlank()) null else LyricCandidate(id, obj.text("name"), obj.text("artist"), source,
                (obj["lyric"] as? JsonObject)?.let(ProtocolDtos::lyricsFrom))
        }.distinctBy { it.id }
    }
    suspend fun detail(candidate: LyricCandidate): Lyrics {
        candidate.embedded?.let { return it }
        requireOnline()
        return ProtocolDtos.lyricsFrom(call("lyricDetail", listOf(buildJsonObject {
            put("extensionId", candidate.source.extensionId); put("source", candidate.source.id); put("id", candidate.id)
        }), true) as? JsonObject)
    }
    suspend fun save(track: Track, lyrics: Lyrics) {
        requireOnline()
        require(lyrics.raw.isNotBlank() || lyrics.lines.isNotEmpty())
        val payload = lyricPayload(track, lyrics)
        try { call("setMusicLyric", listOf(JsonPrimitive(track.identity.remoteTrackId), payload), false) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Read back after an ambiguous response. */ }
        val confirmed = try {
            val result = call("getMusicLyric", listOf(buildJsonObject { put("musicInfo", ProtocolDtos.trackToProtocol(track)) }), true) as? JsonObject
            val after = ProtocolDtos.lyricsFrom(result?.get("info") as? JsonObject)
            listOf(after.toTimedLrc(), after.translationRaw, after.romanizationRaw, after.karaokeRaw) ==
                listOf(lyrics.toTimedLrc(), lyrics.translationRaw, lyrics.romanizationRaw, lyrics.karaokeRaw)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
        if (!confirmed) throw AppError(ErrorKind.WRITE_UNCONFIRMED, "Lyric save could not be confirmed")
    }
    suspend fun remove(track: Track) {
        requireOnline()
        // Do not retry an ambiguous write. A fresh read will load the server's original resource.
        call("removeMusicLyric", listOf(JsonPrimitive(track.identity.remoteTrackId)), false)
    }
    private fun requireOnline() { if (!online()) throw AppError(ErrorKind.OFFLINE_MUTATION, "Offline") }
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}

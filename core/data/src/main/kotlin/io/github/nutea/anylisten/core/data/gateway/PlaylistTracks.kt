package io.github.nutea.anylisten.core.data.gateway

import io.github.nutea.anylisten.core.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Mutate only ordinary playlists and confirm the exact affected IDs; no whole-library overwrite. */
class PlaylistTracks(private val online: () -> Boolean, private val lists: suspend () -> List<Playlist>,
    private val read: suspend (String) -> List<JsonObject>,
    private val write: suspend (String, JsonElement) -> Unit) {
    private val mutex = Mutex()
    suspend fun setOrder(listId: String, original: List<String>, ordered: List<String>) = mutex.withLock {
        editable(listId)
        require(original.size == original.toSet().size && ordered.size == original.size && ordered.toSet() == original.toSet())
        val current = read(listId).map { it.id() }
        require(current == original) { "Playlist changed; reopen before sorting" }
        if (current == ordered) return@withLock
        confirmed("list_music_update_position", buildJsonObject {
            put("listId", listId); put("position", 0); put("ids", JsonArray(ordered.map(::JsonPrimitive)))
        }) { read(listId).map { it.id() } == ordered }
    }
    suspend fun reorder(listId: String, from: String, to: String) = mutex.withLock {
        editable(listId)
        val before = read(listId).map { it.id() }
        val index = before.indexOf(from); val target = before.indexOf(to)
        require(index >= 0 && target >= 0)
        if (index == target) return@withLock
        val expected = before.toMutableList().apply { removeAt(index); add(target, from) }
        confirmed("list_music_update_position", buildJsonObject {
            put("listId", listId); put("position", target); put("ids", JsonArray(listOf(JsonPrimitive(from))))
        }) { read(listId).map { it.id() } == expected }
    }
    suspend fun move(fromId: String, toId: String, ids: List<String>) = mutex.withLock {
        editable(fromId); editable(toId); require(fromId != toId && ids.isNotEmpty())
        val selected = ids.distinct()
        val before = read(fromId)
        require(selected.all { id -> before.any { it.id() == id } })
        val music = before.filter { it.id() in selected }
        confirmed("list_music_move", buildJsonObject {
            put("fromId", fromId); put("toId", toId); put("addMusicLocationType", "bottom")
            put("musicInfos", JsonArray(music))
        }) {
            val source = read(fromId).map { it.id() }; val target = read(toId).map { it.id() }
            selected.none { it in source } && selected.all { it in target }
        }
    }
    suspend fun append(listId: String, music: List<Track>) = mutex.withLock {
        editable(listId)
        val existing = read(listId).map { it.id() }.toSet()
        val added = music.distinctBy { it.identity.remoteTrackId }.filterNot { it.identity.remoteTrackId in existing }
        if (added.isEmpty()) return@withLock
        confirmed("list_music_add", buildJsonObject {
            put("id", listId); put("addMusicLocationType", "bottom")
            put("musicInfos", JsonArray(added.map(ProtocolDtos::trackToProtocol)))
        }) { val after = read(listId).map { it.id() }; added.all { it.identity.remoteTrackId in after } }
    }
    private suspend fun editable(id: String) {
        if (!online()) throw AppError(ErrorKind.OFFLINE_MUTATION, "Offline")
        require(lists().any { it.id == id && it.canManage && it.canMutateOnline }) { "Only ordinary playlists can be edited" }
    }
    private suspend fun confirmed(action: String, data: JsonElement, check: suspend () -> Boolean) {
        try { write(action, data) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* A response can be lost after a successful mutation; read before retrying. */ }
        val ok = try { check() } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
        if (!ok) throw AppError(ErrorKind.WRITE_UNCONFIRMED, "Playlist change could not be confirmed")
    }
    private fun JsonObject.id() = (get("id") as? JsonPrimitive)?.contentOrNull.orEmpty()
}

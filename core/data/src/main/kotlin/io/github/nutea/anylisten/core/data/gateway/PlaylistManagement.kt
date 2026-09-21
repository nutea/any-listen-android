package io.github.nutea.anylisten.core.data.gateway

import io.github.nutea.anylisten.core.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Read current metadata before edits, preserve unknown fields, then confirm the requested result. */
internal class PlaylistManagement(
    private val online: () -> Boolean,
    private val read: suspend () -> JsonObject,
    private val write: suspend (String, JsonElement) -> Unit,
) {
    private val mutex = Mutex()

    suspend fun apply(edit: PlaylistEdit) = mutex.withLock {
        if (!online()) throw AppError(ErrorKind.OFFLINE_MUTATION, "Server edits are disabled offline")
        val before = userLists(read())
        val id = when (edit) {
            is PlaylistEdit.Create -> edit.id
            is PlaylistEdit.Rename -> edit.id
            is PlaylistEdit.Delete -> edit.id
            is PlaylistEdit.Move -> edit.id
        }
        require(id.isNotBlank() && id !in setOf("default", "love", "last_played"))
        val current = before.find { it["id"]?.jsonPrimitive?.content == id }
        if (edit !is PlaylistEdit.Create) {
            if (edit is PlaylistEdit.Delete && current == null) return@withLock
            require(current?.get("type")?.jsonPrimitive?.content == "general") { "Only ordinary playlists can be edited" }
        }
        val action: String
        val data: JsonElement
        val confirmed: (List<JsonObject>) -> Boolean
        when (edit) {
            is PlaylistEdit.Create -> {
                require(validPlaylistName(edit.name))
                val name = edit.name.trim()
                confirmed = { lists -> lists.any { it["id"]?.jsonPrimitive?.content == id && it["name"]?.jsonPrimitive?.content == name } }
                if (current != null) {
                    require(confirmed(before)) { "Playlist ID already exists" }
                    return@withLock
                }
                val now = System.currentTimeMillis()
                val info = buildJsonObject {
                    put("id", id); put("name", name); put("type", "general"); put("parentId", JsonNull)
                    putJsonObject("meta") {
                        put("songCount", 0); put("playCount", 0); put("pic", ""); put("desc", "")
                        put("createTime", now); put("updateTime", now); put("posTime", now)
                    }
                }
                action = "list_create"
                data = buildJsonObject { put("position", before.size); put("listInfos", JsonArray(listOf(info))) }
            }
            is PlaylistEdit.Rename -> {
                require(validPlaylistName(edit.name))
                val name = edit.name.trim()
                val updated = JsonObject(current!!.toMutableMap().apply { put("name", JsonPrimitive(name)) })
                action = "list_update"
                data = buildJsonObject { put("lists", JsonArray(listOf(updated))) }
                confirmed = { lists -> lists.any { it["id"]?.jsonPrimitive?.content == id && it["name"]?.jsonPrimitive?.content == name } }
            }
            is PlaylistEdit.Delete -> {
                action = "list_remove"
                data = JsonArray(listOf(JsonPrimitive(id)))
                confirmed = { lists -> lists.none { it["id"]?.jsonPrimitive?.content == id } }
            }
            is PlaylistEdit.Move -> {
                require(edit.delta == -1 || edit.delta == 1)
                val parent = current!!["parentId"] ?: JsonNull
                val siblings = before.filter { (it["parentId"] ?: JsonNull) == parent }
                val ids = siblings.map { it.getValue("id").jsonPrimitive.content }
                val index = ids.indexOf(id)
                val target = (index + edit.delta).coerceIn(0, ids.lastIndex)
                if (index == target) return@withLock
                val expected = ids.toMutableList().apply { removeAt(index); add(target, id) }
                action = "list_update_position"
                data = buildJsonObject { put("ids", JsonArray(listOf(JsonPrimitive(id)))); put("position", target) }
                confirmed = { lists -> lists.filter { (it["parentId"] ?: JsonNull) == parent }.map { it.getValue("id").jsonPrimitive.content } == expected }
            }
        }
        try {
            write(action, data)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // A socket failure may follow a successful write. Never blindly issue a second mutation.
            val after = try { userLists(read()) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            if (after != null && confirmed(after)) return@withLock
            throw AppError(ErrorKind.WRITE_UNCONFIRMED, "Playlist change could not be confirmed")
        }
        if (!confirmed(userLists(read()))) throw AppError(ErrorKind.WRITE_UNCONFIRMED, "Playlist change could not be confirmed")
    }

    private fun userLists(root: JsonObject) = root["userList"]?.jsonArray?.map { it.jsonObject }.orEmpty()
}

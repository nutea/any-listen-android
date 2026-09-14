package io.github.nutea.anylisten.core.data.gateway

import kotlinx.serialization.json.*

/** null IDs mean a complete reconciliation; empty IDs mean playlist metadata only. */
data class LibraryChange(val playlistIds: Set<String>? = null) {
    fun merge(other: LibraryChange) = LibraryChange(
        if (playlistIds == null || other.playlistIds == null) null else playlistIds + other.playlistIds)

    companion object {
        fun from(action: JsonObject): LibraryChange {
            val name = (action["action"] as? JsonPrimitive)?.content
            val data = action["data"]
            fun id(value: JsonElement?) = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            fun ids(vararg keys: String): Set<String>? {
                val obj = data as? JsonObject ?: return null
                val values = keys.map { id(obj[it]) ?: return null }
                return values.toSet()
            }
            val affected = when (name) {
                "list_music_add" -> ids("id")
                "list_music_remove", "list_music_overwrite", "list_music_update_position" -> ids("listId")
                "list_music_move" -> ids("fromId", "toId")
                "list_music_clear" -> (data as? JsonArray)?.map { id(it) ?: return LibraryChange() }?.toSet()
                "list_music_update" -> (data as? JsonArray)?.map {
                    id((it as? JsonObject)?.get("id")) ?: return LibraryChange()
                }?.toSet()
                "list_create", "list_remove", "list_update", "list_move", "list_update_position" -> emptySet()
                else -> null
            }
            return LibraryChange(affected)
        }
    }
}

/** Bounded, lossless union even when StateFlow conflates several push notifications. */
class PendingLibraryChanges {
    private var pending: LibraryChange? = null
    @Synchronized fun add(change: LibraryChange) { pending = pending?.merge(change) ?: change }
    @Synchronized fun take(): LibraryChange? = pending.also { pending = null }
}

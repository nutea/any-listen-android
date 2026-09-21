package io.github.nutea.anylisten.core.model

/** Only ordinary user playlists are managed here; source-backed lists remain server-managed. */
sealed interface PlaylistEdit {
    data class Create(val id: String, val name: String) : PlaylistEdit
    data class Rename(val id: String, val name: String) : PlaylistEdit
    data class Delete(val id: String) : PlaylistEdit
    data class Move(val id: String, val delta: Int) : PlaylistEdit
}

val Playlist.canManage: Boolean
    get() = type == "general" && id !in setOf("default", "love", "last_played")

fun validPlaylistName(name: String): Boolean = name.trim().isNotEmpty() && name.trim().length <= 100

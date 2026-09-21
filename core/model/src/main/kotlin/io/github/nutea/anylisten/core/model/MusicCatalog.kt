package io.github.nutea.anylisten.core.model

import java.util.Locale
import kotlinx.serialization.json.*

/** Metadata grouping, not an online discography. Preserve composite artist credits verbatim. */
object MusicCatalog {
    data class ArtistKey(val serverId: String, val name: String)
    data class AlbumKey(val serverId: String, val artist: String, val name: String)
    data class Artist(val key: ArtistKey, val name: String, val tracks: List<Track>, val albums: List<Album>)
    data class Album(val key: AlbumKey, val name: String, val artist: String, val tracks: List<Track>)
    data class Index(val artists: List<Artist>, val albums: List<Album>) {
        fun artist(key: ArtistKey) = artists.firstOrNull { it.key == key }
        fun album(key: AlbumKey) = albums.firstOrNull { it.key == key }
    }

    private fun normalized(value: String) = value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    fun artistKey(track: Track): ArtistKey? = track.artist.takeIf { it.isNotBlank() }?.let {
        ArtistKey(track.identity.serverProfileId, normalized(it))
    }
    fun albumKey(track: Track): AlbumKey? = track.album.takeIf { it.isNotBlank() }?.let {
        AlbumKey(track.identity.serverProfileId, normalized(track.artist), normalized(it))
    }

    fun build(snapshot: LibrarySnapshot): Index {
        // Favorite/recent/other playlist copies are the same remote song, even after a fingerprint refresh.
        val unique = snapshot.tracksByPlaylist.values.flatten().distinctBy { it.identity }
        val positions = unique.associate { track ->
            val meta = runCatching { track.rawJson?.let { Json.parseToJsonElement(it).jsonObject["meta"] as? JsonObject } }.getOrNull()
            fun number(name: String) = (meta?.get(name) as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
            track.identity to ((number("discNo") ?: 1) to (number("trackNo") ?: Int.MAX_VALUE))
        }
        // Album metadata wins; a title fallback remains stable when the recent-play list changes.
        val tracks = unique.sortedWith(compareBy<Track>(
            { normalized(it.album) }, { positions.getValue(it.identity).first },
            { positions.getValue(it.identity).second }, { normalized(it.title) }, { it.identity.remoteTrackId },
        ))
        val albums = tracks.filter { albumKey(it) != null }.groupBy { albumKey(it)!! }.map { (key, songs) ->
            Album(key, songs.first().album.trim(), songs.first().artist.trim(), songs)
        }.sortedWith(compareBy({ normalized(it.name) }, { it.key.artist }))
        val albumsByArtist = albums.groupBy { ArtistKey(it.key.serverId, it.key.artist) }
        val artists = tracks.filter { artistKey(it) != null }.groupBy { artistKey(it)!! }.map { (key, songs) ->
            Artist(key, songs.first().artist.trim(), songs, albumsByArtist[key].orEmpty())
        }.sortedBy { normalized(it.name) }
        return Index(artists, albums)
    }
}

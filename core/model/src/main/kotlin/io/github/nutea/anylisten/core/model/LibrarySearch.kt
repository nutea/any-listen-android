package io.github.nutea.anylisten.core.model

data class LibrarySearchHit(val track: Track, val playlistIds: List<String>)

/** Searches only the synchronized library. Song identity, not title, determines duplicates. */
object LibrarySearch {
    fun find(snapshot: LibrarySnapshot, query: String): List<LibrarySearchHit> {
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptyList()
        val tracks = linkedMapOf<String, Track>()
        val origins = linkedMapOf<String, MutableSet<String>>()
        snapshot.playlists.forEach { playlist ->
            snapshot.tracksByPlaylist[playlist.id].orEmpty().forEach { track ->
                val haystack = "${track.title}\n${track.artist}\n${track.album}"
                if (terms.all { haystack.contains(it, ignoreCase = true) }) {
                    tracks.putIfAbsent(track.cacheKey, track)
                    origins.getOrPut(track.cacheKey) { linkedSetOf() }.add(playlist.id)
                }
            }
        }
        return tracks.map { (key, track) -> LibrarySearchHit(track, origins.getValue(key).toList()) }
    }
}

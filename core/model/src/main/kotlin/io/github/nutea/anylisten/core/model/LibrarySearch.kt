package io.github.nutea.anylisten.core.model

data class LibrarySearchHit(val track: Track, val playlistIds: List<String>)

/** Searches only the synchronized library. Song identity, not title, determines duplicates. */
object LibrarySearch {
    class Index internal constructor(private val rows: List<Pair<LibrarySearchHit, String>>) {
        val all: List<LibrarySearchHit> get() = rows.map { it.first }
        fun find(query: String): List<LibrarySearchHit> {
            val terms = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (terms.isEmpty()) return emptyList()
            return rows.filter { (_, text) -> terms.all { text.contains(it, ignoreCase = true) } }.map { it.first }
        }
    }
    fun index(snapshot: LibrarySnapshot): Index {
        val tracks = linkedMapOf<String, Track>()
        val origins = linkedMapOf<String, MutableSet<String>>()
        snapshot.playlists.forEach { playlist -> snapshot.tracksByPlaylist[playlist.id].orEmpty().forEach { track ->
            tracks.putIfAbsent(track.cacheKey, track)
            origins.getOrPut(track.cacheKey) { linkedSetOf() }.add(playlist.id)
        } }
        return Index(tracks.map { (key, track) ->
            val text = listOf(track.title, track.artist, track.album).joinToString("\n") { field ->
                val syllables = SearchPhonetics.syllables(field)
                field + "\n" + syllables.joinToString("") + "\n" + syllables.joinToString("") { it.take(1) }
            }
            LibrarySearchHit(track, origins.getValue(key).toList()) to text
        })
    }
    fun find(snapshot: LibrarySnapshot, query: String) = index(snapshot).find(query)
}

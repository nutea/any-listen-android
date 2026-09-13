package io.github.nutea.anylisten.core.model

enum class TrackSortField { TITLE, ARTIST, ALBUM }

object TrackSort {
    fun apply(tracks: List<Track>, field: TrackSortField, ascending: Boolean): List<Track> {
        val selected: (Track) -> String = when (field) {
            TrackSortField.TITLE -> { track -> track.title }
            TrackSortField.ARTIST -> { track -> track.artist }
            TrackSortField.ALBUM -> { track -> track.album }
        }
        return if (ascending) tracks.sortedBy { selected(it).lowercase() }
        else tracks.sortedByDescending { selected(it).lowercase() }
    }
}

data class QueueSections(
    val nowPlaying: Track?,
    val later: List<Track>,
    val rest: List<Track>,
) {
    companion object {
        fun from(queue: List<Track>, currentKey: String?, laterKeys: List<String>): QueueSections {
            val nowPlaying = currentKey?.let { key -> queue.firstOrNull { it.cacheKey == key } }
            val later = laterKeys.mapNotNull { key -> queue.firstOrNull { it.cacheKey == key } }
                .filter { it.cacheKey != nowPlaying?.cacheKey }
            val used = buildSet {
                nowPlaying?.cacheKey?.let(::add)
                later.forEach { add(it.cacheKey) }
            }
            return QueueSections(nowPlaying, later, queue.filter { it.cacheKey !in used })
        }
    }
}

/** Play-later is a priority section after the current track, not a strict "play next". */
object PlayLater {
    data class Result(val queue: List<Track>, val laterKeys: List<String>)

    fun insert(
        queue: List<Track>,
        currentKey: String?,
        laterKeys: List<String>,
        incoming: List<Track>,
    ): Result {
        val current = currentKey?.let { key -> queue.firstOrNull { it.cacheKey == key } }
        val incomingUnique = incoming.filter { it.cacheKey != current?.cacheKey }.distinctBy { it.cacheKey }
        val existingLater = laterKeys.mapNotNull { key -> queue.firstOrNull { it.cacheKey == key } }
            .filter { it.cacheKey != current?.cacheKey }
        val later = (existingLater + incomingUnique).distinctBy { it.cacheKey }
        val laterKeyList = later.map { it.cacheKey }
        if (current == null) {
            val rest = queue.filter { item -> item.cacheKey !in laterKeyList }
            return Result(later + rest, laterKeyList)
        }
        val laterSet = laterKeyList.toSet()
        val prefix = queue.takeWhile { it.cacheKey != current.cacheKey }.filter { it.cacheKey !in laterSet }
        val after = queue.dropWhile { it.cacheKey != current.cacheKey }.drop(1).filter { it.cacheKey !in laterSet }
        return Result(prefix + current + later + after, laterKeyList)
    }
}

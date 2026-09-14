package io.github.nutea.anylisten.core.model

/**
 * Where new songs land in a list. Matches web `list.addMusicLocationType`.
 * Default in any-listen@e4ef53a is `top`.
 */
enum class AddMusicLocationType(val wire: String) {
    TOP(ProtocolConstants.ADD_LOCATION_TOP),
    BOTTOM(ProtocolConstants.ADD_LOCATION_BOTTOM),
    ;

    companion object {
        fun fromWire(value: String?): AddMusicLocationType =
            if (value == ProtocolConstants.ADD_LOCATION_BOTTOM) BOTTOM else TOP
    }
}

/**
 * Mutations the web-server player applies to `last_played` on `musicChanged`
 * (`packages/web-server/src/app/modules/player/index.ts` `updateLatestPlayList`).
 */
sealed class RecentlyPlayedMutation {
    data object None : RecentlyPlayedMutation()
    data class Move(val musicId: String, val position: Int) : RecentlyPlayedMutation()
    data class Insert(
        val addType: AddMusicLocationType,
        val trimId: String?,
    ) : RecentlyPlayedMutation()
}

/**
 * Recently played is the built-in `last_played` list, not a client-only history.
 *
 * Web records a play when the current track changes, unless that track was started
 * from `last_played` itself. Recency is list position (default: index 0 = newest),
 * capped at [ProtocolConstants.LAST_PLAYED_LIMIT]. Re-plays move the existing id;
 * `meta.createTime` is set only on first insert and is not the recency key.
 */
object RecentlyPlayed {
    fun skipBecauseSourceIsRecent(sourceListId: String?): Boolean =
        sourceListId == ProtocolConstants.LIST_LAST_PLAYED

    fun mutation(
        currentIds: List<String>,
        musicId: String,
        sourceListId: String?,
        addType: AddMusicLocationType = AddMusicLocationType.TOP,
        limit: Int = ProtocolConstants.LAST_PLAYED_LIMIT,
    ): RecentlyPlayedMutation {
        if (skipBecauseSourceIsRecent(sourceListId) || musicId.isBlank()) {
            return RecentlyPlayedMutation.None
        }
        val recencyIndex = recencyIndex(currentIds, addType)
        if (musicId in currentIds) {
            if (currentIds.getOrNull(recencyIndex) == musicId) return RecentlyPlayedMutation.None
            return RecentlyPlayedMutation.Move(musicId, recencyIndex)
        }
        val trimId = if (currentIds.size + 1 > limit && currentIds.isNotEmpty()) {
            currentIds[oldestIndex(currentIds, addType)]
        } else {
            null
        }
        return RecentlyPlayedMutation.Insert(addType, trimId)
    }

    fun apply(
        tracks: List<Track>,
        incoming: Track,
        mutation: RecentlyPlayedMutation,
    ): List<Track> = when (mutation) {
        RecentlyPlayedMutation.None -> tracks
        is RecentlyPlayedMutation.Move -> {
            val item = tracks.firstOrNull { it.identity.remoteTrackId == mutation.musicId } ?: incoming
            val without = tracks.filterNot { it.identity.remoteTrackId == mutation.musicId }
            insertAtRecency(without, item, mutation.position == 0)
        }
        is RecentlyPlayedMutation.Insert -> {
            val placed = insertAtRecency(tracks, incoming, mutation.addType == AddMusicLocationType.TOP)
            if (mutation.trimId == null) placed
            else placed.filterNot { it.identity.remoteTrackId == mutation.trimId }
        }
    }

    /** Server order is newest-first when add type is top, newest-last when bottom. */
    fun newestFirst(tracks: List<Track>, addType: AddMusicLocationType): List<Track> =
        if (addType == AddMusicLocationType.TOP) tracks else tracks.reversed()

    private fun recencyIndex(currentIds: List<String>, addType: AddMusicLocationType): Int =
        if (addType == AddMusicLocationType.TOP) 0 else (currentIds.size - 1).coerceAtLeast(0)

    private fun oldestIndex(currentIds: List<String>, addType: AddMusicLocationType): Int =
        if (addType == AddMusicLocationType.TOP) currentIds.lastIndex else 0

    private fun insertAtRecency(tracks: List<Track>, item: Track, newestAtFront: Boolean): List<Track> =
        if (newestAtFront) listOf(item) + tracks else tracks + item
}

/** Visible tracks for a playlist, including last-played recency order. */
object LibraryBrowse {
    fun visibleTracks(
        snapshot: LibrarySnapshot,
        playlist: Playlist?,
        query: String,
        sort: TrackSortField,
        ascending: Boolean,
    ): List<Track> {
        val tracks = snapshot.tracksByPlaylist[playlist?.id].orEmpty()
        val q = query.trim().lowercase()
        val searched = if (q.isEmpty()) {
            tracks
        } else {
            tracks.filter {
                it.title.lowercase().contains(q) ||
                    it.artist.lowercase().contains(q) ||
                    it.album.lowercase().contains(q)
            }
        }
        val ordered = if (sort == TrackSortField.PLAY_TIME) {
            RecentlyPlayed.newestFirst(searched, snapshot.addMusicLocationType)
        } else {
            searched
        }
        return TrackSort.apply(ordered, sort, ascending)
    }

    fun sortForPlaylist(playlist: Playlist, current: TrackSortField, ascending: Boolean): Pair<TrackSortField, Boolean> =
        if (playlist.id == ProtocolConstants.LIST_LAST_PLAYED) {
            TrackSortField.PLAY_TIME to false
        } else if (current == TrackSortField.PLAY_TIME) {
            TrackSortField.TITLE to true
        } else {
            current to ascending
        }
}

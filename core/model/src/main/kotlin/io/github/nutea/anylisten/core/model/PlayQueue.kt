package io.github.nutea.anylisten.core.model

data class PlayQueue(
    val tracks: List<Track> = emptyList(),
    val currentIndex: Int = 0,
    val repeat: RepeatMode = RepeatMode.ALL,
    val shuffled: Boolean = false,
    val order: List<Int> = tracks.indices.toList(),
) {
    val current: Track?
        get() = order.getOrNull(currentIndex)?.let { tracks.getOrNull(it) }

    fun withTracks(newTracks: List<Track>, startIdentity: TrackIdentity? = null): PlayQueue {
        val start = startIdentity?.let { id -> newTracks.indexOfFirst { it.identity == id } }?.takeIf { it >= 0 } ?: 0
        val nextOrder = if (shuffled) shuffledOrder(newTracks.size, start) else newTracks.indices.toList()
        val index = if (shuffled) nextOrder.indexOf(start).coerceAtLeast(0) else start
        return copy(tracks = newTracks, order = nextOrder, currentIndex = index.coerceIn(0, (newTracks.size - 1).coerceAtLeast(0)))
    }

    fun toggleShuffle(): PlayQueue {
        if (tracks.isEmpty()) return copy(shuffled = !shuffled)
        val currentReal = order.getOrNull(currentIndex) ?: 0
        val nextShuffled = !shuffled
        val nextOrder = if (nextShuffled) shuffledOrder(tracks.size, currentReal) else tracks.indices.toList()
        val nextIndex = if (nextShuffled) 0 else currentReal
        return copy(shuffled = nextShuffled, order = nextOrder, currentIndex = nextIndex)
    }

    fun cycleRepeat(): PlayQueue = copy(
        repeat = when (repeat) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        },
    )

    fun next(userRequested: Boolean = false): PlayQueue {
        if (tracks.isEmpty()) return this
        if (repeat == RepeatMode.ONE && !userRequested) return this
        val nextIndex = currentIndex + 1
        return if (nextIndex < order.size) {
            copy(currentIndex = nextIndex)
        } else when (repeat) {
            RepeatMode.ALL -> copy(currentIndex = 0)
            RepeatMode.OFF, RepeatMode.ONE -> this
        }
    }

    fun previous(): PlayQueue {
        if (tracks.isEmpty()) return this
        val prev = currentIndex - 1
        return if (prev >= 0) copy(currentIndex = prev) else if (repeat == RepeatMode.ALL) copy(currentIndex = order.lastIndex) else this
    }

    fun jumpTo(identity: TrackIdentity): PlayQueue {
        val real = tracks.indexOfFirst { it.identity == identity }
        if (real < 0) return this
        val idx = order.indexOf(real).takeIf { it >= 0 } ?: real
        return copy(currentIndex = idx)
    }

    fun snapshot(positionMs: Long): PlaybackSnapshot = PlaybackSnapshot(
        queueIds = order.mapNotNull { tracks.getOrNull(it)?.identity?.remoteTrackId },
        currentIndex = currentIndex,
        positionMs = positionMs,
        repeat = repeat,
        shuffled = shuffled,
    )

    private fun shuffledOrder(size: Int, startReal: Int): List<Int> {
        if (size == 0) return emptyList()
        val rest = (0 until size).filter { it != startReal }.shuffled()
        return listOf(startReal) + rest
    }
}

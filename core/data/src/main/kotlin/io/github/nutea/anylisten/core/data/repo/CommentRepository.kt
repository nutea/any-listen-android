package io.github.nutea.anylisten.core.data.repo

import io.github.nutea.anylisten.core.data.gateway.CommentPage
import io.github.nutea.anylisten.core.data.gateway.CommentSource
import io.github.nutea.anylisten.core.data.gateway.MusicComments
import io.github.nutea.anylisten.core.model.Track
import kotlinx.serialization.json.JsonObject

/** Owns matched protocol metadata; UI only holds a read-only comment thread. */
class CommentRepository(private val gateway: MusicComments) {
    suspend fun sources() = gateway.sources()
    fun preferredSource(track: Track, sources: List<CommentSource>) = gateway.preferredSource(track, sources)
    suspend fun match(track: Track, source: CommentSource): CommentThread? =
        gateway.match(track, source)?.let { CommentThread(gateway, source, it) }
}

class CommentThread internal constructor(
    private val gateway: MusicComments,
    private val source: CommentSource,
    private val music: JsonObject,
) {
    suspend fun page(type: String, page: Int): CommentPage = gateway.page(source, music, type, page)
}

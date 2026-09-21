package io.github.nutea.anylisten.core.data.gateway

import io.github.nutea.anylisten.core.model.Track
import kotlinx.serialization.json.*

data class CommentSource(val id: String, val extensionId: String, val name: String)
data class MusicComment(
    val id: String, val userName: String, val text: String,
    val time: Long? = null, val location: String? = null, val likedCount: Long? = null,
    val replies: List<MusicComment> = emptyList(),
    val avatar: String? = null, val images: List<String> = emptyList(),
)
data class CommentPage(val list: List<MusicComment>, val total: Long, val page: Int, val limit: Int)

/** Read-only extension resource calls; retain the full online track returned by findMusic. */
class MusicComments(private val call: suspend (String, List<JsonElement>) -> JsonElement?) {
    suspend fun sources(): List<CommentSource> {
        val resources = call("getResourceList", emptyList())?.jsonObject?.get("resources")?.jsonObject
        val sources = (resources?.get("musicComment") as? JsonArray).orEmpty().map {
            val obj = it.jsonObject
            CommentSource(obj.string("id"), obj.string("extensionId"), obj.string("name"))
        }.filter { it.id.isNotBlank() && it.extensionId.isNotBlank() }.distinctBy { it.extensionId to it.id }
        if (sources.none { it.name.contains('{') || it.name.startsWith("t(") }) return sources
        val extensions = try { call("getExtensionList", emptyList()) as? JsonArray }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { null }
        val messages = extensions.orEmpty().associate { item ->
            val obj = item.jsonObject
            obj.string("id") to (obj["i18nMessages"] as? JsonObject)
        }
        return sources.map { source ->
            val template = if (source.name.startsWith("t(")) "{" + source.name.removePrefix("t(").removeSuffix(")") + "}" else source.name
            val name = Regex("\\{([\\w.-]+)\\}").replace(template) { match ->
                messages[source.extensionId]?.string(match.groupValues[1])?.takeIf { it.isNotBlank() } ?: source.id
            }
            source.copy(name = name.ifBlank { source.id })
        }
    }

    fun preferredSource(track: Track, sources: List<CommentSource>): CommentSource? {
        val id = (ProtocolDtos.trackToProtocol(track)["meta"] as? JsonObject)?.string("source")
        return sources.firstOrNull { it.id == id } ?: sources.firstOrNull()
    }

    suspend fun match(track: Track, source: CommentSource): JsonObject? {
        val music = ProtocolDtos.trackToProtocol(track)
        if (!track.isLocalOnServer && (music["meta"] as? JsonObject)?.string("source") == source.id) return music
        return call("findMusic", listOf(buildJsonObject {
            put("extensionId", source.extensionId); put("source", source.id)
            put("name", track.title); put("artist", track.artist); put("albumName", track.album)
            music["interval"]?.let { put("interval", it) }
            put("strict", false)
        })) as? JsonObject
    }

    suspend fun page(source: CommentSource, music: JsonObject, type: String, page: Int): CommentPage {
        require(type == "hot" || type == "new")
        require(page > 0)
        val result = call("musicComment", listOf(buildJsonObject {
            put("extensionId", source.extensionId); put("source", source.id)
            put("musicInfo", music); put("type", type); put("page", page); put("limit", 20)
        }))?.jsonObject ?: error("Missing comment response")
        return decodePage(result)
    }

    companion object {
        fun decodePage(result: JsonObject): CommentPage = CommentPage(
            (result["list"] as? JsonArray).orEmpty().map { decodeComment(it.jsonObject, 0) },
            result.number("total")?.coerceAtLeast(0) ?: 0,
            (result.number("page") ?: 1).coerceIn(1, Int.MAX_VALUE.toLong()).toInt(),
            (result.number("limit") ?: 20).coerceIn(1, Int.MAX_VALUE.toLong()).toInt(),
        )
        private fun decodeComment(obj: JsonObject, depth: Int): MusicComment = MusicComment(
            obj.string("id"), obj.string("userName"), obj.string("text"), obj.number("time"),
            obj.string("location").takeIf { it.isNotBlank() }, obj.number("likedCount"),
            if (depth >= 3) emptyList() else (obj["reply"] as? JsonArray).orEmpty().map { decodeComment(it.jsonObject, depth + 1) },
            obj.string("avatar").takeIf { it.startsWith("https://") },
            (obj["images"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { url -> url.startsWith("https://") } },
        )
        private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
        private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.longOrNull
    }
}

package io.github.nutea.anylisten.core.data.gateway

import io.github.nutea.anylisten.core.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class SecondBatchGatewayTest {
    private val track = Track(TrackIdentity("s", "song"), "Title", "Artist", "Album", 90000)
    private val lyrics = LrcParser.parse("[00:01.000]Hello", "[00:01.000]你好", "[00:01.000]ni hao", "[00:01.000]<0,1000>Hello")
    private val source = LyricSource("source", "extension", "Source")
    @Test fun searchAndDetailUseExtensionSourceAndEditedTitle() = runBlocking {
        val api = LyricRepair({ true }) { method, args, retry ->
            assertTrue(retry)
            when (method) {
                "getResourceList" -> Json.parseToJsonElement("""{"resources":{"lyricSearch":[{"id":"source","extensionId":"extension","name":"Source"}]}}""")
                "lyricSearch" -> {
                    val request = args.single().jsonObject
                    assertEquals("extension", request["extensionId"]!!.jsonPrimitive.content)
                    assertFalse(request.containsKey("interval"))
                    assertEquals("New", request["name"]!!.jsonPrimitive.content)
                    Json.parseToJsonElement("""[{"id":"candidate","name":"Title","artist":"Artist"}]""")
                }
                "lyricDetail" -> {
                    assertEquals("candidate", args.single().jsonObject["id"]!!.jsonPrimitive.content)
                    lyricPayload(track, lyrics)
                }
                else -> error("Unexpected call")
            }
        }
        assertEquals(source, api.sources().single())
        val found = api.search(source, " New ", "Artist").single()
        assertEquals(lyrics, api.detail(found))
    }
    @Test fun embeddedLyricsDoNotMakeDetailRequest() = runBlocking {
        val api = LyricRepair({ false }) { _, _, _ -> error("No request") }
        assertEquals(lyrics, api.detail(LyricCandidate("id", "Title", "Artist", source, lyrics)))
    }
    @Test fun serverSaveConfirmsAmbiguousAcknowledgementWithoutRetryAndKeepsAllLyricLayers() = runBlocking {
        var stored: JsonObject? = null
        var writes = 0
        val api = LyricRepair({ true }) { method, args, retry ->
            if (method == "setMusicLyric") {
                assertFalse(retry); writes++
                assertEquals("song", args[0].jsonPrimitive.content)
                stored = args[1].jsonObject
                throw java.io.IOException("Lost acknowledgement")
            } else buildJsonObject { put("info", stored!!) }
        }
        api.save(track, lyrics)
        assertEquals(1, writes)
        assertEquals(lyrics.karaokeRaw, stored!!["awlyric"]!!.jsonPrimitive.content)
    }
    @Test fun saveRejectsUnconfirmedReadAndPropagatesCancellation() = runBlocking {
        val api = LyricRepair({ true }) { _, _, _ -> buildJsonObject { put("info", lyricPayload(track, LrcParser.parse("[00:01]Other"))) } }
        try { api.save(track, lyrics); fail() } catch (e: AppError) { assertEquals(ErrorKind.WRITE_UNCONFIRMED, e.kind) }
        val cancelled = LyricRepair({ true }) { _, _, _ -> throw CancellationException() }
        try { cancelled.save(track, lyrics); fail() } catch (_: CancellationException) { }
    }
    @Test fun lyricImportReadsUtf8BomAndChineseEncodingAndRejectsUnboundedOrUntimedFiles() {
        assertEquals("Hello", LyricImport.read(ByteArrayInputStream("\uFEFF[00:01]Hello".toByteArray())).lines.single().text)
        assertEquals("你好", LyricImport.read(ByteArrayInputStream("[00:01]你好".toByteArray(charset("GB18030")))).lines.single().text)
        for (bytes in listOf("plain text".toByteArray(), ByteArray(LyricImport.MAX_BYTES + 1))) {
            try { LyricImport.read(ByteArrayInputStream(bytes)); fail() } catch (_: IllegalArgumentException) { }
        }
    }
    private fun music(id: String) = buildJsonObject { put("id", id); put("meta", buildJsonObject { put("future", 42) }) }
    private fun lists() = listOf(Playlist("a", "A", "general", 2), Playlist("b", "B", "general", 1), Playlist("remote", "R", "remote", 1))
    @Test fun setOrderUsesOneScopedActionAndConfirmsExactOrder() = runBlocking {
        var rows = listOf(music("one"), music("two"), music("three"))
        var writes = 0
        val api = PlaylistTracks({ true }, { lists() }, { rows }) { action, data ->
            writes++; assertEquals("list_music_update_position", action)
            val payload = data.jsonObject
            assertEquals("a", payload["listId"]!!.jsonPrimitive.content)
            assertEquals(0, payload["position"]!!.jsonPrimitive.int)
            val byId = rows.associateBy { it["id"]!!.jsonPrimitive.content }
            rows = payload["ids"]!!.jsonArray.map { byId.getValue(it.jsonPrimitive.content) }
            throw java.io.IOException("Acknowledgement lost")
        }
        api.setOrder("a", listOf("one", "two", "three"), listOf("three", "one", "two"))
        assertEquals(1, writes)
    }
    @Test fun staleOrderDuplicateIdsAndSourceBackedListsNeverWrite() = runBlocking {
        val api = PlaylistTracks({ true }, { lists() }, { listOf(music("one"), music("two")) }) { _, _ -> error("Must not write") }
        for ((id, original, target) in listOf(Triple("a", listOf("one"), listOf("one")),
            Triple("a", listOf("one", "two"), listOf("one", "one")), Triple("remote", listOf("one", "two"), listOf("two", "one")))) {
            try { api.setOrder(id, original, target); fail() } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun nativeMoveRetainsProtocolMetadataAndDedupesTargetWhileRemovingSource() = runBlocking {
        var a = listOf(music("one"), music("two")); var b = listOf(music("two"))
        val api = PlaylistTracks({ true }, { lists() }, { if (it == "a") a else b }) { action, data ->
            assertEquals("list_music_move", action)
            val payload = data.jsonObject
            assertEquals("a", payload["fromId"]!!.jsonPrimitive.content)
            assertEquals("b", payload["toId"]!!.jsonPrimitive.content)
            assertEquals("bottom", payload["addMusicLocationType"]!!.jsonPrimitive.content)
            val moved = payload["musicInfos"]!!.jsonArray.map { it.jsonObject }
            assertEquals(42, moved.first()["meta"]!!.jsonObject["future"]!!.jsonPrimitive.int)
            b = (b + moved).distinctBy { it["id"] }; a = a.filter { it !in moved }
        }
        api.move("a", "b", listOf("one", "two"))
        assertTrue(a.isEmpty()); assertEquals(2, b.size)
    }
    @Test fun unconfirmedMoveAndOfflineMutationAreReported() = runBlocking {
        val api = PlaylistTracks({ true }, { lists() }, { listOf(music("one")) }) { _, _ -> Unit }
        try { api.move("a", "b", listOf("one")); fail() } catch (e: AppError) { assertEquals(ErrorKind.WRITE_UNCONFIRMED, e.kind) }
        val offline = PlaylistTracks({ false }, { error("No read") }, { error("No read") }) { _, _ -> error("No write") }
        try { offline.move("a", "b", listOf("one")); fail() } catch (e: AppError) { assertEquals(ErrorKind.OFFLINE_MUTATION, e.kind) }
    }
}

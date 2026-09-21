package io.github.nutea.anylisten.core.data.gateway

import io.github.nutea.anylisten.core.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MusicCommentsTest {
    private val source = CommentSource("test", "extension", "Test")
    private val track = Track(TrackIdentity("server", "local"), "Title", "Artist", "Album", 123000, isLocalOnServer = true)
    private fun json(value: String) = Json.parseToJsonElement(value)

    @Test fun localMusicIsMatchedAndCompleteOnlineMetadataIsPassedToComments() = runBlocking {
        val online = json("""{"id":"online","meta":{"source":"test","token":"preserved"}}""").jsonObject
        val calls = mutableListOf<String>()
        val service = MusicComments { method, args ->
            calls += method
            val params = args.single().jsonObject
            assertEquals("extension", params["extensionId"]!!.jsonPrimitive.content)
            if (method == "findMusic") {
                assertEquals("Title", params["name"]!!.jsonPrimitive.content)
                assertEquals(false, params["strict"]!!.jsonPrimitive.boolean)
                online
            } else {
                assertEquals(online, params["musicInfo"])
                assertEquals(2, params["page"]!!.jsonPrimitive.int)
                assertEquals("new", params["type"]!!.jsonPrimitive.content)
                json("""{"list":[],"total":21,"page":2,"limit":20}""")
            }
        }
        val matched = service.match(track, source)!!
        assertEquals(2, service.page(source, matched, "new", 2).page)
        assertEquals(listOf("findMusic", "musicComment"), calls)
    }
    @Test fun sameSourceOnlineMusicDoesNotSearchAndNullMatchStaysUnavailable() = runBlocking {
        val service = MusicComments { _, _ -> JsonNull }
        assertNull(service.match(track, source))
        val online = track.copy(isLocalOnServer = false, rawJson = """{"id":"online","meta":{"source":"test"}}""")
        assertEquals("online", service.match(online, source)!!["id"]!!.jsonPrimitive.content)
    }
    @Test fun sourceDiscoveryPreservesExtensionIdentity() = runBlocking {
        val service = MusicComments { method, _ ->
            assertEquals("getResourceList", method)
            json("""{"resources":{"musicComment":[{"id":"a","extensionId":"one","name":"A"},{"id":"a","extensionId":"two","name":"B"}]}}""")
        }
        assertEquals(2, service.sources().size)
    }
    @Test fun extensionSourceNamesUseServerTranslations() = runBlocking {
        val service = MusicComments { method, _ ->
            if (method == "getResourceList") json("""{"resources":{"musicComment":[{"id":"kg","extensionId":"ext","name":"{kgName}"}]}}""")
            else json("""[{"id":"ext","i18nMessages":{"kgName":"酷狗音乐"}}]""")
        }
        assertEquals("酷狗音乐", service.sources().single().name)
    }
    @Test fun parsesRepliesAndOptionalFieldsWithoutInventingLikes() {
        val page = MusicComments.decodePage(json("""{"list":[{"id":"c","userName":"Listener","text":"Hello","time":1700000000000,"reply":[{"id":"r","userName":"Reply","text":"Thanks","likedCount":0}]}],"total":1,"page":1,"limit":20}""").jsonObject)
        assertNull(page.list.single().likedCount)
        assertEquals(0L, page.list.single().replies.single().likedCount)
        assertEquals(1700000000000L, page.list.single().time)
    }
}

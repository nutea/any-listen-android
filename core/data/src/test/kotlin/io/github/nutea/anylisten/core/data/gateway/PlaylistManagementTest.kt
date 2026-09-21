package io.github.nutea.anylisten.core.data.gateway

import io.github.nutea.anylisten.core.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PlaylistManagementTest {
    private fun info(id: String, type: String = "general") = Json.parseToJsonElement("""{"id":"$id","type":"$type","name":"Old","parentId":null,"meta":{"desc":"keep","songCount":3,"unknown":42},"future":true}""").jsonObject
    private fun root(lists: List<JsonObject>) = buildJsonObject { put("userList", JsonArray(lists)) }

    @Test fun renamePreservesMetadataAndUnknownFields() = runBlocking {
        var lists = listOf(info("a"))
        val original = lists.single()
        val service = PlaylistManagement({ true }, { root(lists) }) { action, data ->
            assertEquals("list_update", action)
            val updated = data.jsonObject.getValue("lists").jsonArray.single().jsonObject
            assertEquals(original["meta"], updated["meta"])
            assertEquals(original["future"], updated["future"])
            lists = listOf(updated)
        }
        service.apply(PlaylistEdit.Rename("a", " New "))
        assertEquals("New", lists.single()["name"]!!.jsonPrimitive.content)
    }

    @Test fun successfulCreateWithLostAcknowledgementIsConfirmedWithoutSecondWrite() = runBlocking {
        var lists = emptyList<JsonObject>()
        var writes = 0
        val service = PlaylistManagement({ true }, { root(lists) }) { action, data ->
            writes++
            assertEquals("list_create", action)
            lists = data.jsonObject.getValue("listInfos").jsonArray.map { it.jsonObject }
            throw java.io.IOException("connection lost after applying")
        }
        service.apply(PlaylistEdit.Create("new-id", "New"))
        service.apply(PlaylistEdit.Create("new-id", "New"))
        assertEquals(1, writes)
        assertEquals("general", lists.single()["type"]!!.jsonPrimitive.content)
    }

    @Test fun downMoveUsesPositionAfterRemoval() = runBlocking {
        var lists = listOf(info("a"), info("b"), info("c"))
        val service = PlaylistManagement({ true }, { root(lists) }) { action, data ->
            assertEquals("list_update_position", action)
            assertEquals(1, data.jsonObject.getValue("position").jsonPrimitive.int)
            assertEquals("a", data.jsonObject.getValue("ids").jsonArray.single().jsonPrimitive.content)
            lists = listOf(lists[1], lists[0], lists[2])
        }
        service.apply(PlaylistEdit.Move("a", 1))
    }

    @Test fun deletionUsesIdArrayAndRequiresReadback() = runBlocking {
        var lists = listOf(info("a"), info("b"))
        val service = PlaylistManagement({ true }, { root(lists) }) { action, data ->
            assertEquals("list_remove", action)
            assertEquals(JsonArray(listOf(JsonPrimitive("a"))), data)
            lists = lists.drop(1)
        }
        service.apply(PlaylistEdit.Delete("a"))
        assertEquals("b", lists.single()["id"]!!.jsonPrimitive.content)
        val unconfirmed = PlaylistManagement({ true }, { root(lists) }) { _, _ -> }
        try { unconfirmed.apply(PlaylistEdit.Delete("b")); fail("Expected unconfirmed write") }
        catch (error: AppError) { assertEquals(ErrorKind.WRITE_UNCONFIRMED, error.kind) }
    }

    @Test fun offlineBuiltinsAndSourceListsCannotBeEdited() = runBlocking {
        var writes = 0
        val service = PlaylistManagement({ true }, { root(listOf(info("source", "local"))) }) { _, _ -> writes++ }
        for (edit in listOf(PlaylistEdit.Delete("default"), PlaylistEdit.Rename("source", "x"), PlaylistEdit.Create("x", "  "))) {
            try { service.apply(edit); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
        }
        val offline = PlaylistManagement({ false }, { error("Must not read offline") }) { _, _ -> writes++ }
        try { offline.apply(PlaylistEdit.Create("x", "x")); fail("Expected offline rejection") }
        catch (error: AppError) { assertEquals(ErrorKind.OFFLINE_MUTATION, error.kind) }
        assertEquals(0, writes)
    }
}

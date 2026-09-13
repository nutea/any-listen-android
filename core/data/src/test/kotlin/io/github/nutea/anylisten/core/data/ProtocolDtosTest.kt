package io.github.nutea.anylisten.core.data

import io.github.nutea.anylisten.core.data.gateway.IpcAuthClient
import io.github.nutea.anylisten.core.data.gateway.ProtocolDtos
import io.github.nutea.anylisten.core.model.ProtocolConstants
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolDtosTest {
    @Test
    fun mapsTrackAndLists() {
        val trackJson = JsonObject(
            mapOf(
                "id" to JsonPrimitive("t1"),
                "name" to JsonPrimitive("Song"),
                "singer" to JsonPrimitive("Artist"),
                "interval" to JsonPrimitive("03:55"),
                "isLocal" to JsonPrimitive(true),
                "meta" to JsonObject(
                    mapOf(
                        "musicId" to JsonPrimitive("fp-1"),
                        "albumName" to JsonPrimitive("Album"),
                    ),
                ),
            ),
        )
        val track = ProtocolDtos.trackFrom("profile", "love", trackJson)
        assertEquals("t1", track.identity.remoteTrackId)
        assertEquals(235_000L, track.durationMs)
        assertEquals("fp-1", track.fingerprint)
        val lists = ProtocolDtos.playlistsFrom(
            JsonObject(
                mapOf(
                    "loveList" to JsonObject(
                        mapOf(
                            "id" to JsonPrimitive(ProtocolConstants.LIST_LOVE),
                            "name" to JsonPrimitive("Love"),
                            "type" to JsonPrimitive("default"),
                        ),
                    ),
                    "userList" to kotlinx.serialization.json.buildJsonArray { },
                ),
            ),
        )
        assertEquals(ProtocolConstants.LIST_LOVE, lists.single().id)
    }

    @Test
    fun sha256MatchesProbe() {
        assertEquals(64, IpcAuthClient.sha256Hex("secret123").length)
        assertTrue(IpcAuthClient.sha256Hex("a") != IpcAuthClient.sha256Hex("b"))
    }

    @Test
    fun helloAcceptsTrailingNewline() {
        val client = IpcAuthClient(okhttp3.OkHttpClient())
        assertTrue(client.isHello(ProtocolConstants.HELLO_MSG + "\n"))
        assertTrue(!client.isHello("nope"))
    }
}

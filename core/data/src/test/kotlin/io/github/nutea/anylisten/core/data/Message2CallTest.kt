package io.github.nutea.anylisten.core.data

import io.github.nutea.anylisten.core.data.gateway.Message2Call
import io.github.nutea.anylisten.core.data.gateway.ProtocolDtos
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Message2CallTest {
    @Test
    fun requestAndResponse() = runBlocking {
        val sent = CompletableDeferred<String>()
        val client = Message2Call(ProtocolDtos.json) { sent.complete(it) }
        coroutineScope {
            val job = async { client.call(listOf("getAllUserLists")) }
            val request = ProtocolDtos.json.parseToJsonElement(sent.await()) as JsonArray
            assertEquals(0, request[0].jsonPrimitive.content.toInt())
            assertEquals("getAllUserLists", (request[2] as JsonArray)[0].jsonPrimitive.content)
            val name = request[1].jsonPrimitive.content
            val response = buildJsonArray {
                add(JsonPrimitive(1))
                add(JsonPrimitive(name))
                add(JsonNull)
                add(JsonPrimitive("ok"))
            }
            client.onMessage(response.toString())
            assertEquals("ok", job.await()?.jsonPrimitive?.content)
        }
    }

    @Test
    fun failedSendFailsTheCallImmediately() = runBlocking {
        val client = Message2Call(ProtocolDtos.json) { error("send failed") }
        val error = runCatching { client.call(listOf("getMusicUrl")) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals("send failed", error?.message)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun unansweredCallTimesOutInsteadOfHanging() = runTest {
        val client = Message2Call(ProtocolDtos.json, callTimeoutMs = 1_000) { }
        val result = async { runCatching { client.call(listOf("getMusicUrl")) } }
        advanceTimeBy(1_000)
        assertTrue(result.await().isFailure)
    }

    @Test
    fun destroyWithMultiplePendingDoesNotThrow() = runBlocking {
        val client = Message2Call(ProtocolDtos.json) { }
        coroutineScope {
            val first = async { runCatching { client.call(listOf("a")) } }
            val second = async { runCatching { client.call(listOf("b")) } }
            yield()
            client.destroy("closed")
            assertTrue(first.await().isFailure)
            assertTrue(second.await().isFailure)
        }
    }

    @Test
    fun destroyRacingOnMessageDoesNotThrow() = runBlocking {
        val sent = CompletableDeferred<String>()
        val client = Message2Call(ProtocolDtos.json) { sent.complete(it) }
        coroutineScope {
            val job = async { runCatching { client.call(listOf("getAllUserLists")) } }
            val request = ProtocolDtos.json.parseToJsonElement(sent.await()) as JsonArray
            val name = request[1].jsonPrimitive.content
            val response = buildJsonArray {
                add(JsonPrimitive(1))
                add(JsonPrimitive(name))
                add(JsonNull)
                add(JsonPrimitive("ok"))
            }
            repeat(20) {
                client.onMessage(response.toString())
                client.destroy("closed")
            }
            job.await()
        }
    }
}

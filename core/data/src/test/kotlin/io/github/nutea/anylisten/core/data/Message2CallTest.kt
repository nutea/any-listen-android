package io.github.nutea.anylisten.core.data

import io.github.nutea.anylisten.core.data.gateway.IpcCallException
import io.github.nutea.anylisten.core.data.gateway.IpcClosedException
import io.github.nutea.anylisten.core.data.gateway.Message2Call
import io.github.nutea.anylisten.core.data.gateway.ProtocolDtos
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
            client.dispatch(reply(request[1].jsonPrimitive.content, "ok"))
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
        val error = result.await().exceptionOrNull()
        assertTrue(error is io.github.nutea.anylisten.core.data.gateway.IpcTimeoutException)
    }

    @Test
    fun serverSideErrorsAreDistinguishableFromADeadSocket() = runBlocking {
        val sent = CompletableDeferred<String>()
        val client = Message2Call(ProtocolDtos.json) { sent.complete(it) }
        coroutineScope {
            val job = async { runCatching { client.call(listOf("getMusicUrl")) } }
            val name = (ProtocolDtos.json.parseToJsonElement(sent.await()) as JsonArray)[1].jsonPrimitive.content
            client.dispatch(
                buildJsonArray {
                    add(JsonPrimitive(1))
                    add(JsonPrimitive(name))
                    add(Message2Call.obj("message" to JsonPrimitive("no such track")))
                }.toString(),
            )
            val error = job.await().exceptionOrNull()
            assertTrue(error is IpcCallException)
            assertEquals("no such track", error?.message)
        }
    }

    @Test
    fun failAllWithMultiplePendingCallsFailsEachOneSeparately() = runBlocking {
        val client = Message2Call(ProtocolDtos.json) { }
        coroutineScope {
            val results = List(8) { index -> async { runCatching { client.call(listOf("call$index")) } } }
            yield()
            client.failAll("closed")
            val errors = results.awaitAll().map { it.exceptionOrNull() }
            assertTrue(errors.all { it is IpcClosedException })
            // The same throwable instance must not be shared: a suppressed-exception cycle on a
            // reused instance is how this used to blow up on the OkHttp reader thread.
            assertEquals(errors.size, errors.distinct().size)
        }
    }

    @Test
    fun callsAfterFailAllFailFastInsteadOfWaitingForAGoneSocket() = runBlocking {
        val client = Message2Call(ProtocolDtos.json) { error("must not send") }
        client.failAll("closed")
        assertTrue(client.isClosed)
        val error = runCatching { client.call(listOf("getMusicUrl")) }.exceptionOrNull()
        assertTrue(error is IpcClosedException)
    }

    @Test
    fun duplicateResponsesForOneCallAreIgnored() = runBlocking {
        val sent = CompletableDeferred<String>()
        val client = Message2Call(ProtocolDtos.json) { sent.complete(it) }
        coroutineScope {
            val job = async { client.call(listOf("getAllUserLists")) }
            val name = (ProtocolDtos.json.parseToJsonElement(sent.await()) as JsonArray)[1].jsonPrimitive.content
            repeat(10) { client.dispatch(reply(name, "ok")) }
            assertEquals("ok", job.await()?.jsonPrimitive?.content)
        }
    }

    @Test
    fun malformedFramesNeverEscapeToTheReaderThread() {
        val client = Message2Call(ProtocolDtos.json) { }
        listOf("", "ping", "not json", "{}", "[]", "[1]", "[1,\"x\"]", "[\"a\",\"b\",null]")
            .forEach { client.dispatch(it) }
    }

    /**
     * The 0.1.1-beta.4 crash, reproduced as a race: an inbound response landing on the reader
     * thread at the same instant the socket is torn down. Completing a pending call is now
     * atomic, so neither side can resume it twice.
     */
    @Test
    fun failAllRacingDispatchOnAnotherThreadNeverThrows() {
        val reader = Executors.newSingleThreadExecutor()
        val closer = Executors.newSingleThreadExecutor()
        val failure = AtomicReference<Throwable?>(null)
        try {
            repeat(200) {
                runBlocking {
                    val sent = CompletableDeferred<String>()
                    val client = Message2Call(ProtocolDtos.json) { sent.complete(it) }
                    coroutineScope {
                        val job = async { runCatching { client.call(listOf("getMusicUrl")) } }
                        val name = (ProtocolDtos.json.parseToJsonElement(sent.await()) as JsonArray)[1]
                            .jsonPrimitive.content
                        val start = CountDownLatch(1)
                        val done = CountDownLatch(2)
                        reader.execute {
                            start.await()
                            runCatching { client.dispatch(reply(name, "ok")) }.onFailure(failure::set)
                            done.countDown()
                        }
                        closer.execute {
                            start.await()
                            runCatching { client.failAll("socket closed") }.onFailure(failure::set)
                            done.countDown()
                        }
                        start.countDown()
                        assertTrue(done.await(5, TimeUnit.SECONDS))
                        job.await()
                    }
                }
            }
        } finally {
            reader.shutdownNow()
            closer.shutdownNow()
        }
        assertEquals(null, failure.get())
    }

    @Test
    fun aCancelledCallLeavesNoPendingEntryBehind() = runBlocking {
        val sent = CompletableDeferred<String>()
        val client = Message2Call(ProtocolDtos.json) { sent.complete(it) }
        val job = async { client.call(listOf("getMusicUrl")) }
        sent.await()
        job.cancel()
        runCatching { job.await() }
        // A response for the abandoned call must be a no-op rather than an error.
        client.dispatch(reply("stale", "ok"))
        assertFalse(client.isClosed)
    }

    private fun reply(name: String, value: String) = buildJsonArray {
        add(JsonPrimitive(1))
        add(JsonPrimitive(name))
        add(JsonNull)
        add(JsonPrimitive(value))
    }.toString()
}

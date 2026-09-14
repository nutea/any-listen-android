package io.github.nutea.anylisten.core.data

import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HttpClientsTest {
    @Test
    fun webSocketsArePingedSoHalfOpenLinksFail() {
        val clients = HttpClients.create()
        assertEquals(HttpClients.WEBSOCKET_PING_INTERVAL_MS, clients.api.pingIntervalMillis.toLong())
        assertTrue(clients.api.retryOnConnectionFailure)
    }

    @Test
    fun mediaSharesTheConnectionPoolAndCookiesButNotTheDispatcher() {
        val clients = HttpClients.create()
        assertSame(clients.api.connectionPool, clients.media.connectionPool)
        assertSame(clients.api.cookieJar, clients.media.cookieJar)
        assertNotSame(clients.api.dispatcher, clients.media.dispatcher)
    }

    @Test
    fun mediaHasNoWholeCallDeadlineButStillTimesOutAStalledRead() {
        val clients = HttpClients.create()
        assertEquals(0, clients.media.callTimeoutMillis)
        assertEquals(
            HttpClients.MEDIA_READ_TIMEOUT_S,
            TimeUnit.MILLISECONDS.toSeconds(clients.media.readTimeoutMillis.toLong()),
        )
    }

    /**
     * The reason the clients are split: after a handover the API calls bound to the old
     * interface have to be failed fast, and doing that used to abort the audio stream too,
     * because both shared one dispatcher.
     */
    @Test
    fun cancellingApiCallsDoesNotTouchTheMediaStream() {
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                started.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return MockResponse().setBody("done")
            }
        }
        server.start()
        val clients = HttpClients.create()
        try {
            val apiFailed = CountDownLatch(1)
            val mediaFinished = CountDownLatch(1)
            clients.api.newCall(Request.Builder().url(server.loopbackUrl("/api")).build())
                .enqueue(finish(onFailure = { apiFailed.countDown() }))
            clients.media.newCall(Request.Builder().url(server.loopbackUrl("/media")).build())
                .enqueue(finish(onAny = { mediaFinished.countDown() }))
            assertTrue(started.await(10, TimeUnit.SECONDS))

            clients.cancelApiCalls()

            assertTrue("the API call must be cancelled", apiFailed.await(10, TimeUnit.SECONDS))
            assertFalse("the media stream must survive", mediaFinished.await(500, TimeUnit.MILLISECONDS))

            release.countDown()
            assertTrue(mediaFinished.await(10, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            clients.cancelMediaCalls()
            server.shutdown()
        }
    }

    @Test
    fun cancellingMediaCallsFailsThemSoExoPlayerCanRetry() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                started.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return MockResponse().setBody("done")
            }
        }
        server.start()
        val clients = HttpClients.create()
        try {
            val failed = CountDownLatch(1)
            clients.media.newCall(Request.Builder().url(server.loopbackUrl("/media")).build())
                .enqueue(finish(onFailure = { failed.countDown() }))
            assertTrue(started.await(10, TimeUnit.SECONDS))
            clients.cancelMediaCalls()
            assertTrue(failed.await(10, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            server.shutdown()
        }
    }

    private fun finish(
        onFailure: () -> Unit = {},
        onAny: () -> Unit = {},
    ) = object : okhttp3.Callback {
        override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
            onFailure()
            onAny()
        }

        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
            response.close()
            onAny()
        }
    }
}

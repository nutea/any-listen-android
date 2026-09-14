package io.github.nutea.anylisten.core.data

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NetworkFactoryTest {
    @Test
    fun clientPingsWebSocketsSoHalfOpenLinksFail() {
        val client = NetworkFactory.client()
        assertEquals(NetworkFactory.WEBSOCKET_PING_INTERVAL_MS, client.pingIntervalMillis.toLong())
        assertTrue(client.retryOnConnectionFailure)
    }

    @Test
    fun cancelInFlightCallsFailsHungRequests() {
        val requestStarted = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestStarted.countDown()
                check(releaseServer.await(5, TimeUnit.SECONDS))
                return MockResponse().setBody("slow")
            }
        }
        server.start()
        try {
            val client = OkHttpClient()
            val finished = CountDownLatch(1)
            client.newCall(Request.Builder().url(server.loopbackUrl("/slow")).build()).enqueue(
                object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                        finished.countDown()
                    }
                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        response.close()
                    }
                },
            )
            assertTrue(requestStarted.await(2, TimeUnit.SECONDS))
            client.cancelInFlightCalls()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
        } finally {
            releaseServer.countDown()
            server.shutdown()
        }
    }
}

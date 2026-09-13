package io.github.nutea.anylisten.core.data

import android.graphics.Bitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private fun picture(color: Int = android.graphics.Color.TRANSPARENT): MockResponse {
        val bytes = ByteArrayOutputStream()
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
            compress(Bitmap.CompressFormat.PNG, 100, bytes)
            recycle()
        }
        return MockResponse().setHeader("Cache-Control", "no-store, max-age=0")
            .setBody(Buffer().write(bytes.toByteArray()))
    }

    @Test fun unrelatedCoverRefreshDoesNotNotifyCurrentImage() = runTest {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(picture(if (it == 2) android.graphics.Color.BLUE else android.graphics.Color.RED)) }
            server.start()
            val store = ArtworkStore(temp.newFolder(), OkHttpClient())
            val a = server.loopbackUrl("/a"); val b = server.loopbackUrl("/b")
            store.get(a)
            val versions = mutableListOf<Long>()
            val observer = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { store.updatesFor(a).toList(versions) }
            store.get(b); store.get(b, force = true)
            assertEquals(listOf(1L), versions)
            observer.cancel()
        }
    }

    @Test fun sameUrlSurvivesOfflineRestart() = runTest {
        val directory = temp.newFolder()
        val server = MockWebServer()
        server.enqueue(picture())
        server.start()
        val url = server.loopbackUrl("/cover?v=1")
        val stored = ArtworkStore(directory, OkHttpClient()).get(url)
        stored.setLastModified(1L)
        assertEquals(1, server.requestCount)
        server.shutdown()
        val reloaded = ArtworkStore(directory, OkHttpClient(), { false }).get(url)
        assertEquals(stored, reloaded)
        assertEquals(1L, reloaded.lastModified())
        assertTrue(reloaded.length() > 0)
    }

    @Test fun sameUrlRefreshNotifiesImagesAndOfflineKeepsLastVersion() = runTest {
        MockWebServer().use { server ->
            server.enqueue(picture(android.graphics.Color.RED))
            server.enqueue(picture(android.graphics.Color.BLUE))
            server.start()
            var online = true
            val store = ArtworkStore(temp.newFolder(), OkHttpClient(), { online })
            val url = server.loopbackUrl("/cover")
            val file = store.get(url)
            val original = file.readBytes()
            val revision = store.updates.value
            assertEquals(file, store.get(url, force = true))
            assertFalse(original.contentEquals(file.readBytes()))
            assertTrue(store.updates.value > revision)
            val updated = file.readBytes()
            online = false
            assertArrayEquals(updated, store.get(url, force = true).readBytes())
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun concurrentRequestsShareFileButChangedUrlDownloadsAgain() = runTest {
        MockWebServer().use { server ->
            server.enqueue(picture())
            server.enqueue(picture())
            server.start()
            val store = ArtworkStore(temp.newFolder(), OkHttpClient())
            val first = server.loopbackUrl("/cover?v=1")
            val results = List(4) { async { store.get(first) } }.awaitAll()
            assertEquals(1, results.distinct().size)
            assertEquals(1, server.requestCount)
            assertNotEquals(results.first(), store.get(server.loopbackUrl("/cover?v=2")))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun invalidResponsesDoNotPoisonPermanentStorage() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("not an image"))
            server.enqueue(picture())
            server.start()
            val directory = temp.newFolder()
            val store = ArtworkStore(directory, OkHttpClient())
            val url = server.loopbackUrl("/cover")
            assertTrue(runCatching { store.get(url) }.isFailure)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            assertTrue(store.get(url).length() > 0)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun httpArtworkTriesHttpsThenFallsBackAndCachesSuccessfulUrl() = runTest {
        MockWebServer().use { server ->
            server.enqueue(picture())
            server.start()
            val httpUrl = "http://imge.example/cover.jpg"
            val store = ArtworkStore(temp.newFolder(), coverClient(server, httpsFails = true))
            val stored = store.get(httpUrl)
            assertTrue(stored.length() > 0)
            assertEquals(1, server.requestCount)
            assertEquals(stored, store.get(httpUrl))
            assertEquals(1, server.requestCount)
            assertEquals(sha256(httpUrl), stored.name)
        }
    }

    @Test fun httpArtworkReadsExistingHttpsCache() = runTest {
        val httpUrl = "http://imge.example/cover.jpg"
        val httpsUrl = "https://imge.example/cover.jpg"
        val directory = temp.newFolder()
        val planted = File(directory, sha256(httpsUrl))
        planted.writeBytes(pngBytes())
        val store = ArtworkStore(directory, OkHttpClient(), { false })
        assertEquals(planted, store.get(httpUrl))
    }

    private fun coverClient(server: MockWebServer, httpsFails: Boolean): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                if (httpsFails && request.url.scheme == "https") throw IOException("https unavailable")
                chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath)).build())
            }
            .build()

    private fun pngBytes(): ByteArray {
        val bytes = ByteArrayOutputStream()
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            compress(Bitmap.CompressFormat.PNG, 100, bytes)
            recycle()
        }
        return bytes.toByteArray()
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

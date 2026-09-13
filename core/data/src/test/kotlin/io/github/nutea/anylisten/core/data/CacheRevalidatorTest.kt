package io.github.nutea.anylisten.core.data

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CacheRevalidatorTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun validatorsAvoidTransfersAndRefreshChangedBytesAtSameUrl() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("version-one").setHeader("ETag","v1").setHeader("Last-Modified","Mon, 01 Jun 2026 00:00:00 GMT"))
            server.enqueue(MockResponse().setResponseCode(304))
            server.enqueue(MockResponse().setBody("version-two").setHeader("ETag","v2"))
            server.start()
            var now = 1_000_000L
            val cache = CacheRevalidator(OkHttpClient()) { now }
            val file = File(temp.newFolder(),"audio")
            val url = server.loopbackUrl("/same.mp3")
            assertTrue(cache.update(url,file)); server.takeRequest()
            assertFalse(cache.update(url,file)); assertEquals(1,server.requestCount)
            now += CACHE_CHECK_INTERVAL_MS
            assertFalse(cache.update(url,file))
            val check = server.takeRequest()
            assertEquals("v1",check.getHeader("If-None-Match"));assertNotNull(check.getHeader("If-Modified-Since"))
            assertEquals("version-one",file.readText())
            assertTrue(cache.update(url,file,force = true))
            assertEquals("version-two",file.readText())
            assertTrue(File(file.path + ".http.json").readText().contains("v2"))
        }
    }
    @Test fun missingValidatorsAndChangedUrlsDoNotKeepStaleBytes() = runBlocking {
        MockWebServer().use { server ->
            listOf("before","after!","after!","third!").forEach { server.enqueue(MockResponse().setBody(it)) };server.start()
            val cache = CacheRevalidator(OkHttpClient());val file = File(temp.newFolder(),"file")
            assertTrue(cache.update(server.loopbackUrl("/a"),file))
            assertTrue(cache.update(server.loopbackUrl("/a"),file,true));assertEquals("after!",file.readText())
            val modified = file.lastModified()
            assertFalse(cache.update(server.loopbackUrl("/a"),file,true));assertEquals(modified,file.lastModified())
            assertTrue(cache.update(server.loopbackUrl("/b"),file));assertEquals("third!",file.readText())
        }
    }
    @Test fun failuresInvalidBodiesAndInterruptedDownloadsPreserveOldCopy() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("valid audio").setHeader("ETag","old"))
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setBody("<html>login</html>").setHeader("Content-Type","text/html"))
            server.enqueue(MockResponse().setBody("x".repeat(65536)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            server.start()
            val cache = CacheRevalidator(OkHttpClient());val file = File(temp.newFolder(),"file");val url = server.loopbackUrl("/a")
            cache.update(url,file);val metadata = File(file.path + ".http.json").readText()
            repeat(3) {
                assertTrue(runCatching { cache.update(url,file,true) }.isFailure)
                assertEquals("valid audio",file.readText());assertEquals(metadata,File(file.path + ".http.json").readText())
            }
            assertFalse(file.parentFile!!.listFiles()!!.any { it.name.endsWith(".part") })
        }
    }
    @Test fun removedFileCannotBeResurrectedByRefresh() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("original"));server.enqueue(MockResponse().setBody("replacement"));server.start()
            val cache = CacheRevalidator(OkHttpClient());val file = File(temp.newFolder(),"file");val url = server.loopbackUrl("/a")
            cache.update(url,file)
            assertTrue(runCatching { cache.update(url,file,true,validate = { file.delete() }) }.isFailure)
            assertFalse(file.exists())
        }
    }
}

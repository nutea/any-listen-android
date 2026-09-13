package io.github.nutea.anylisten.core.data

import io.github.nutea.anylisten.core.data.download.FileDownloader
import io.github.nutea.anylisten.core.model.AppError
import io.github.nutea.anylisten.core.model.ErrorKind
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FileDownloaderTest {
    @Test
    fun range206ResumesWithoutRestart() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 3-5/6").setHeader("ETag","v1").setBody("def"))
            server.start()
            val dest = File.createTempFile("al-dl", ".bin").also { it.delete() }
            File(dest.parentFile, dest.name + ".part").writeText("abc")
            java.util.Properties().apply { setProperty("url",server.loopbackUrl("/track"));setProperty("validator","v1") }
                .also { props -> File(dest.path + ".part.validator").outputStream().use { props.store(it,null) } }
            val result = FileDownloader(OkHttpClient()).download(server.loopbackUrl("/track"), dest)
            assertEquals("abcdef", dest.readText())
            assertFalse(result.restarted)
            assertEquals("v1",server.takeRequest().getHeader("If-Range"))
            assertEquals(1,server.requestCount)
            dest.delete()
        }
    }

    @Test
    fun range200RedownloadsWholeFile() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("FULLFILE"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("FULLFILE"))
            server.start()
            val dest = File.createTempFile("al-dl", ".bin").also { it.delete() }
            File(dest.parentFile, dest.name + ".part").writeText("xxx")
            val result = FileDownloader(OkHttpClient()).download(server.loopbackUrl("/track"), dest)
            assertEquals("FULLFILE", dest.readText())
            assertTrue(result.restarted)
            dest.delete()
        }
    }

    @Test fun rejectsHtmlAndInvalidRangesWithoutReplacingExistingFile() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type","text/html").setBody("<html>login</html>"))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 3-5/6").setBody("def"))
            server.start()
            val dest = File.createTempFile("al-dl", ".bin").apply { writeText("good audio") }
            try {
                repeat(2) {
                    assertTrue(runCatching { FileDownloader(OkHttpClient()).download(server.loopbackUrl("/track"),dest) }.isFailure)
                    assertEquals("good audio",dest.readText())
                }
            } finally { dest.delete() }
        }
    }

    @Test
    fun rejectsNonLocalHttp() {
        val dest = File.createTempFile("al-dl", ".bin").also { it.delete() }
        try {
            FileDownloader(OkHttpClient()).download("http://cdn.example/a.mp3", dest)
            throw AssertionError("expected failure")
        } catch (error: AppError) {
            assertEquals(ErrorKind.CERT_INVALID, error.kind)
        } finally {
            dest.delete()
        }
    }
}

package io.github.nutea.anylisten.core.data

import io.github.nutea.anylisten.core.data.download.FileDownloader
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
            server.enqueue(MockResponse().setResponseCode(206).setBody(""))
            server.enqueue(MockResponse().setResponseCode(206).setBody("def"))
            server.start()
            val dest = File.createTempFile("al-dl", ".bin").also { it.delete() }
            File(dest.parentFile, dest.name + ".part").writeText("abc")
            val result = FileDownloader(OkHttpClient()).download(server.url("/track").toString(), dest)
            assertEquals("abcdef", dest.readText())
            assertFalse(result.restarted)
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
            val result = FileDownloader(OkHttpClient()).download(server.url("/track").toString(), dest)
            assertEquals("FULLFILE", dest.readText())
            assertTrue(result.restarted)
            dest.delete()
        }
    }
}

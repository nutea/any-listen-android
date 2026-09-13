package io.github.nutea.anylisten

import androidx.test.platform.app.InstrumentationRegistry
import io.github.nutea.anylisten.core.playback.ContainerHolder
import io.github.nutea.anylisten.core.model.DownloadStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicit opt-in maintenance check for a device whose existing downloads need repair. */
class DeviceRecoveryAcceptanceTest {
    @Test fun repairExistingDownloadsAndVerifySourceBytesAndLyrics() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("repair_existing_downloads") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as ContainerHolder).container
        assertNotNull(container.session.restore())
        assertTrue(container.gateway.isOnline())
        val records = container.downloads.observe().first()
        val pending = records.filter { it.status in setOf(DownloadStatus.FAILED,DownloadStatus.PAUSED,DownloadStatus.QUEUED,DownloadStatus.DOWNLOADING,DownloadStatus.VERIFYING) }
        for (record in pending) container.downloads.retry(record.cacheKey)
        withTimeout(240000) {
            while (container.downloads.observe().first().any { it.cacheKey in pending.map { p -> p.cacheKey } && it.status != DownloadStatus.COMPLETED }) delay(1000)
        }
        val completed = container.downloads.observe().first().filter { it.status == DownloadStatus.COMPLETED }
        assertEquals(completed.size,completed.map { it.filePath }.toSet().size)
        // Verify current records: the user may remove downloads while maintenance is running.
        // Never recreate removed songs or log credentials, raw URLs, or lyric text.
        val samples = completed.take(3)
        assertEquals(3,samples.size)
        for (sample in samples) {
            val track = checkNotNull(container.library.cachedTrack(sample.cacheKey))
            val file = checkNotNull(container.downloads.completedFile(sample.cacheKey))
            val media = container.gateway.resolveMedia(track,false)
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(media.url).header("Range","bytes=0-65535").build()
                container.http.newCall(request).execute().use { response ->
                    assertTrue(response.isSuccessful)
                    val remote = checkNotNull(response.body).byteStream().readNBytes(65536)
                    val local = file.inputStream().use { it.readNBytes(65536) }
                    assertArrayEquals("Downloaded bytes must match this exact track",remote,local)
                }
            }
            assertTrue("Server lyrics must be cached for offline playback",container.offlineAssets.lyrics(track).lines.isNotEmpty())
        }
        // Do not log out or delete the saved session.
        val summary = "downloads=${completed.size}, uniqueFiles=${completed.size}, verifiedAudio=3, cachedLyrics=3"
        File(context.getExternalFilesDir(null),"download-repair-verification.txt").writeText(summary)
    }
}

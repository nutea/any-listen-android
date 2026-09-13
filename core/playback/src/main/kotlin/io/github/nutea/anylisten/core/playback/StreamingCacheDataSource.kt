package io.github.nutea.anylisten.core.playback

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.OutputStream

/** Save the same sequential bytes the player reads. Partial/seeked responses stay in Media3's span cache. */
class StreamingCacheDataSource(
    private val source: DataSource,
    private val directory: File,
    private val enabled: () -> Boolean,
    private val completed: (DataSpec, File) -> Unit,
) : DataSource by source {
    private var spec: DataSpec? = null
    private var temporary: File? = null
    private var output: OutputStream? = null
    private var expected = C.LENGTH_UNSET.toLong()
    private var received = 0L
    private var eof = false

    override fun open(dataSpec: DataSpec): Long {
        val length = source.open(dataSpec)
        spec = dataSpec; expected = length; received = 0; eof = false
        val contentType = source.responseHeaders.entries.firstOrNull { it.key.equals("Content-Type", true) }
            ?.value?.joinToString().orEmpty().lowercase()
        if (!contentType.contains("text/html") && !contentType.contains("application/json") && enabled() && dataSpec.position == 0L && dataSpec.length == C.LENGTH_UNSET.toLong() && dataSpec.uri.scheme in listOf("http", "https")) {
            runCatching {
                directory.mkdirs()
                temporary = File.createTempFile("stream-", ".part", directory)
                output = temporary!!.outputStream().buffered()
            }.onFailure { discard() }
        }
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = try { source.read(buffer, offset, length) } catch (error: Exception) { discard(); throw error }
        if (count == C.RESULT_END_OF_INPUT) eof = true
        else if (count > 0) {
            received += count
            if (!enabled()) discard()
            else runCatching { output?.write(buffer, offset, count) }.onFailure { discard() }
        }
        return count
    }

    override fun close() {
        try { source.close() } finally {
            val file = temporary
            val flushed = runCatching { output?.close() }.isSuccess
            output = null; temporary = null
            if (file != null) {
                val full = received > 0 && (if (expected >= 0) received == expected else eof)
                if (flushed && full && enabled() && file.length() == received) {
                    try { completed(spec!!, file) } catch (_: Exception) { file.delete() }
                } else file.delete()
            }
            spec = null
        }
    }

    private fun discard() {
        runCatching { output?.close() }
        output = null; temporary?.delete(); temporary = null
    }
}

fun streamingLoadControl() = androidx.media3.exoplayer.DefaultLoadControl.Builder()
    .setBufferDurationsMs(15_000, 30_000, 1_000, 2_000).build()

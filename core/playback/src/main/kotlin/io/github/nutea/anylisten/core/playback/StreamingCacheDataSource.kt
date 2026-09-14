package io.github.nutea.anylisten.core.playback

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import io.github.nutea.anylisten.core.data.StreamCacheSink

/** Save the bytes the player reads. Completeness is assembled across seek/retry opens. */
class StreamingCacheDataSource(
    private val source: DataSource,
    private val enabled: () -> Boolean,
    private val openSink: (DataSpec, Long) -> StreamCacheSink?,
) : DataSource by source {
    private var sink: StreamCacheSink? = null
    private var writeAt = 0L
    private var openedAt = 0L
    private var openedLength = C.LENGTH_UNSET.toLong()
    private var eof = false

    override fun open(dataSpec: DataSpec): Long {
        closeSink(endOfInput = false)
        eof = false
        val length = source.open(dataSpec)
        openedAt = dataSpec.position
        openedLength = length
        writeAt = dataSpec.position
        val contentType = source.responseHeaders.entries.firstOrNull { it.key.equals("Content-Type", true) }
            ?.value?.joinToString().orEmpty().lowercase()
        if (!contentType.contains("text/html") && !contentType.contains("application/json") &&
            enabled() && dataSpec.uri.scheme in listOf("http", "https")) {
            sink = runCatching { openSink(dataSpec, length) }.getOrNull()
        }
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = try { source.read(buffer, offset, length) } catch (error: Exception) {
            closeSink(endOfInput = false)
            throw error
        }
        if (count == C.RESULT_END_OF_INPUT) eof = true
        else if (count > 0) {
            if (!enabled()) closeSink(endOfInput = false)
            else {
                val position = writeAt
                runCatching { sink?.write(position, buffer, offset, count) }.onFailure { closeSink(endOfInput = false) }
                writeAt = position + count
            }
        }
        return count
    }

    override fun close() {
        try {
            drainTail()
            source.close()
        } finally {
            closeSink(endOfInput = eof)
            eof = false
            writeAt = 0L
            openedLength = C.LENGTH_UNSET.toLong()
        }
    }

    /**
     * Extractors often stop a few KB before HTTP EOF (ID3 trailer, padding). Drain that tail
     * only after this open already consumed most of the body, so a mid-track skip does not
     * download the rest of the song.
     */
    private fun drainTail() {
        val current = sink ?: return
        if (!enabled() || openedLength < 0) return
        val readThisOpen = writeAt - openedAt
        val unread = openedLength - readThisOpen
        if (unread <= 0L || unread > MAX_DRAIN_BYTES) return
        if (readThisOpen * 10 < openedLength * 9) return
        val buffer = ByteArray(8192)
        while (true) {
            val count = try { source.read(buffer, 0, buffer.size) } catch (_: Exception) { break }
            if (count == C.RESULT_END_OF_INPUT) { eof = true; break }
            if (count <= 0) break
            val position = writeAt
            runCatching { current.write(position, buffer, 0, count) }.onFailure {
                closeSink(endOfInput = false)
                return
            }
            writeAt = position + count
        }
    }

    private fun closeSink(endOfInput: Boolean) {
        val current = sink ?: return
        sink = null
        runCatching { current.close(endOfInput) }
    }
}

fun streamingLoadControl() = androidx.media3.exoplayer.DefaultLoadControl.Builder()
    .setBufferDurationsMs(15_000, 30_000, 1_000, 2_000).build()

private const val MAX_DRAIN_BYTES = 512 * 1024L

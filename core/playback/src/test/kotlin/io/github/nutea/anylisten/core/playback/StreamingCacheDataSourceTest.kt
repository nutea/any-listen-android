package io.github.nutea.anylisten.core.playback

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import io.github.nutea.anylisten.core.data.StreamCacheSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StreamingCacheDataSourceTest {
    private val uri = android.net.Uri.parse("https://stream-fixture.invalid/audio.bin")

    @Test fun seekedReopenWritesAtFileOffsetsNotAsAFreshFile() {
        val body = ByteArray(12_000) { it.toByte() }
        val sink = RecordingSink()
        read(body, 0, 4096, sink)
        assertEquals(listOf(0L), sink.starts)
        assertFalse(sink.eof.last())
        read(body, 4096, body.size - 4096, sink, untilEnd = true)
        assertEquals(listOf(0L, 4096L), sink.starts)
        assertTrue(sink.eof.last())
        assertEquals(body.toList(), sink.assembled(body.size).toList())
    }

    @Test fun midTrackCloseDoesNotDrainTheRestOfTheBody() {
        val body = ByteArray(200_000) { 4 }
        val sink = RecordingSink()
        read(body, 0, 4096, sink)
        assertEquals(4096, sink.bytes)
        assertFalse(sink.eof.single())
    }

    @Test fun nearlyFinishedOpenDrainsASmallExtractorTail() {
        val body = ByteArray(20_000) { 7 }
        val sink = RecordingSink()
        read(body, 0, 19_000, sink)
        assertEquals(body.size, sink.bytes)
        assertTrue(sink.eof.single())
    }

    @Test fun htmlAndDisabledOpensNeverStartASink() {
        val body = ByteArray(1024)
        var opened = 0
        val html = StreamingCacheDataSource(ArraySource(body, mapOf("Content-Type" to listOf("text/html"))), { true }) { _, _ ->
            opened++; RecordingSink()
        }
        html.open(spec(0)); html.read(ByteArray(8), 0, 8); html.close()
        assertEquals(0, opened)

        var enabled = false
        val disabled = StreamingCacheDataSource(ArraySource(body), { enabled }) { _, _ -> opened++; RecordingSink() }
        disabled.open(spec(0)); disabled.read(ByteArray(8), 0, 8); disabled.close()
        assertEquals(0, opened)
    }

    @Test fun readErrorClosesTheSinkWithoutEof() {
        val sink = RecordingSink()
        val source = StreamingCacheDataSource(ArraySource(ByteArray(4096), failAfter = 8), { true }) { _, _ -> sink }
        source.open(spec(0))
        source.read(ByteArray(8), 0, 8)
        try { source.read(ByteArray(8), 0, 8); throw AssertionError("expected failure") }
        catch (_: IOException) {}
        source.close()
        assertFalse(sink.eof.single())
        assertNull(sink.eof.getOrNull(1))
    }

    private fun read(body: ByteArray, position: Long, count: Int, sink: RecordingSink, untilEnd: Boolean = false) {
        val source = StreamingCacheDataSource(ArraySource(body), { true }) { _, _ -> sink }
        source.open(spec(position))
        var remaining = count
        val buffer = ByteArray(1024)
        while (remaining > 0) {
            val n = source.read(buffer, 0, minOf(buffer.size, remaining))
            if (n < 0) break
            remaining -= n
        }
        if (untilEnd) {
            while (true) {
                val n = source.read(buffer, 0, buffer.size)
                if (n < 0) break
            }
        }
        source.close()
    }

    private fun spec(position: Long) = DataSpec.Builder().setUri(uri).setPosition(position).build()

    private class RecordingSink : StreamCacheSink {
        val starts = mutableListOf<Long>()
        val eof = mutableListOf<Boolean>()
        private val chunks = mutableListOf<Pair<Long, ByteArray>>()
        val bytes get() = chunks.sumOf { it.second.size }

        private var session = true
        override fun write(position: Long, buffer: ByteArray, offset: Int, count: Int) {
            if (session) {
                starts += position
                session = false
            }
            chunks += position to buffer.copyOfRange(offset, offset + count)
        }

        override fun close(endOfInput: Boolean) {
            eof += endOfInput
            session = true
        }

        fun assembled(size: Int): ByteArray {
            val out = ByteArray(size)
            chunks.forEach { (position, data) -> data.copyInto(out, position.toInt()) }
            return out
        }
    }

    private class ArraySource(
        private val data: ByteArray,
        private val headers: Map<String, List<String>> = emptyMap(),
        private val failAfter: Int = Int.MAX_VALUE,
    ) : DataSource {
        private var spec: DataSpec? = null
        private var pos = 0
        private var limit = 0
        private var transferred = 0
        override fun addTransferListener(listener: TransferListener) {}
        override fun open(dataSpec: DataSpec): Long {
            spec = dataSpec
            pos = dataSpec.position.toInt().coerceAtLeast(0)
            val remaining = (data.size - pos).coerceAtLeast(0)
            val length = if (dataSpec.length == C.LENGTH_UNSET.toLong()) remaining.toLong()
            else minOf(dataSpec.length, remaining.toLong())
            limit = pos + length.toInt()
            return length
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (transferred >= failAfter) throw IOException("read failed")
            if (pos >= limit || pos >= data.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, limit - pos, data.size - pos)
            System.arraycopy(data, pos, buffer, offset, n)
            pos += n
            transferred += n
            return n
        }
        override fun close() {}
        override fun getUri() = spec?.uri
        override fun getResponseHeaders() = headers
    }
}

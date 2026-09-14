package io.github.nutea.anylisten.core.data

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Bytes written by playback, keyed by file offset rather than a single sequential session. */
interface StreamCacheSink {
    fun write(position: Long, buffer: ByteArray, offset: Int, count: Int)
    fun close(endOfInput: Boolean)
}

/**
 * Builds one complete audio file from ExoPlayer's reads.
 *
 * Progressive playback does not keep one position-0 DataSource open until EOF. MP3 ID3 / MP4
 * `moov` seeks, buffer-pause reopen, and network retries all close and reopen at a new offset.
 * Completeness is therefore "byte ranges cover [0, size)", not "this session started at 0".
 */
internal class StreamCacheAssembler(
    private val part: File,
    private val meta: File,
    private val complete: File,
) {
    private var expected = -1L
    private var spans = mutableListOf<Span>()
    private var lastEnd = 0L

    init {
        loadMeta()
    }

    fun alreadyComplete(): Boolean = complete.isFile && complete.length() > 0L

    fun opened(position: Long, openedLength: Long) {
        if (alreadyComplete()) return
        lastEnd = position
        val total = if (openedLength >= 0) position + openedLength else -1L
        if (total < 0) return
        if (expected < 0) expected = total
        else if (position == 0L && expected != total) {
            reset()
            expected = total
            lastEnd = position
        }
    }

    fun write(position: Long, buffer: ByteArray, offset: Int, count: Int) {
        if (count <= 0 || alreadyComplete()) return
        part.parentFile?.mkdirs()
        RandomAccessFile(part, "rw").use { raf ->
            raf.seek(position)
            raf.write(buffer, offset, count)
        }
        addSpan(position, position + count)
        lastEnd = position + count
    }

    /** @return true if [complete] now holds the full resource. */
    fun finish(endOfInput: Boolean): Boolean {
        if (alreadyComplete()) {
            deleteWorking()
            return true
        }
        val writtenEnd = maxOf(lastEnd, spans.maxOfOrNull { it.end } ?: 0L)
        if (endOfInput) {
            if (expected < 0) expected = writtenEnd
        }
        persistMeta()
        val size = expected
        if (size <= 0L || !covers(0L, size)) return false
        RandomAccessFile(part, "rw").use { it.setLength(size) }
        complete.parentFile?.mkdirs()
        Files.move(part.toPath(), complete.toPath(), StandardCopyOption.REPLACE_EXISTING)
        deleteWorking()
        return complete.isFile && complete.length() == size
    }

    fun discard() {
        reset()
    }

    private fun addSpan(start: Long, end: Long) {
        if (end <= start) return
        spans.add(Span(start, end))
        spans = merge(spans).toMutableList()
    }

    private fun covers(start: Long, end: Long): Boolean {
        var at = start
        for (span in spans) {
            if (span.start > at) return false
            if (span.end > at) at = span.end
            if (at >= end) return true
        }
        return at >= end
    }

    private fun reset() {
        spans.clear()
        expected = -1L
        lastEnd = 0L
        part.delete()
        meta.delete()
    }

    private fun deleteWorking() {
        part.delete()
        meta.delete()
        spans.clear()
        expected = -1L
        lastEnd = 0L
    }

    private fun persistMeta() {
        val body = buildString {
            append("expected=").append(expected).append('\n')
            spans.forEach { append(it.start).append('-').append(it.end).append('\n') }
        }
        val temporary = File(meta.path + ".tmp")
        try {
            meta.parentFile?.mkdirs()
            temporary.writeText(body)
            Files.move(temporary.toPath(), meta.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    private fun loadMeta() {
        val raw = meta.takeIf { it.isFile }?.readText().orEmpty()
        if (raw.isBlank()) return
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("expected=") -> expected = trimmed.removePrefix("expected=").toLongOrNull() ?: -1L
                '-' in trimmed -> {
                    val start = trimmed.substringBefore('-').toLongOrNull()
                    val end = trimmed.substringAfter('-').toLongOrNull()
                    if (start != null && end != null && end > start) spans.add(Span(start, end))
                }
            }
        }
        spans = merge(spans).toMutableList()
        if (!part.isFile || spans.any { it.start < 0 || it.end > part.length() }) reset()
    }

    private data class Span(val start: Long, val end: Long)

    private companion object {
        fun merge(spans: List<Span>): List<Span> {
            if (spans.isEmpty()) return emptyList()
            val sorted = spans.sortedWith(compareBy<Span> { it.start }.thenBy { it.end })
            val out = ArrayList<Span>(sorted.size)
            var current = sorted.first()
            for (i in 1 until sorted.size) {
                val next = sorted[i]
                current = if (next.start <= current.end) Span(current.start, maxOf(current.end, next.end))
                else {
                    out.add(current)
                    next
                }
            }
            out.add(current)
            return out
        }
    }
}

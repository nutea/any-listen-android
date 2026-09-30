package io.github.nutea.anylisten.core.data.gateway

import io.github.nutea.anylisten.core.model.LrcParser
import io.github.nutea.anylisten.core.model.Lyrics
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset

/** Bounded text import. Documents are copied, so no permanent URI permission is needed. */
object LyricImport {
    const val MAX_BYTES = 1_048_576
    fun read(input: InputStream): Lyrics {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() <= MAX_BYTES) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - out.size()))
            if (count < 0) break
            out.write(buffer, 0, count)
        }
        require(out.size() <= MAX_BYTES) { "LRC exceeds 1 MiB" }
        val bytes = out.toByteArray()
        val raw = try { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString() }
            catch (_: java.nio.charset.CharacterCodingException) { Charset.forName("GB18030").newDecoder().decode(ByteBuffer.wrap(bytes)).toString() }
        val lyrics = LrcParser.parse(raw.removePrefix("\uFEFF"))
        require(lyrics.lines.any { it.text.isNotBlank() }) { "Missing timed lyrics" }
        return lyrics
    }
}

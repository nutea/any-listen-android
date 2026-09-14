package io.github.nutea.anylisten.core.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StreamCacheAssemblerTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun prematureEofDoesNotShrinkKnownResourceLength() {
        val files = files()
        val assembler = StreamCacheAssembler(files.part, files.meta, files.complete)
        assembler.opened(0, 1000)
        assembler.write(0, ByteArray(500), 0, 500)
        assertFalse(assembler.finish(endOfInput = true))
        assertFalse(files.complete.exists())
    }

    @Test fun missingPartialFileInvalidatesPersistedRanges() {
        val files = files()
        val first = StreamCacheAssembler(files.part, files.meta, files.complete)
        first.opened(0, 1000)
        first.write(0, ByteArray(500), 0, 500)
        first.finish(false)
        files.part.delete()
        val resumed = StreamCacheAssembler(files.part, files.meta, files.complete)
        resumed.opened(500, 500)
        resumed.write(500, ByteArray(500), 0, 500)
        assertFalse(resumed.finish(true))
        assertFalse(files.complete.exists())
    }

    @Test fun sequentialOpenToEofBecomesCompleteFile() {
        val body = "complete-audio-bytes".toByteArray()
        val files = files()
        val assembler = StreamCacheAssembler(files.part, files.meta, files.complete)
        assembler.opened(0, body.size.toLong())
        assembler.write(0, body, 0, body.size)
        assertTrue(assembler.finish(endOfInput = true))
        assertArrayEquals(body, files.complete.readBytes())
        assertFalse(files.part.exists())
        assertFalse(files.meta.exists())
    }

    @Test fun exactKnownLengthCompletesWithoutWaitingForEof() {
        val body = ByteArray(8000) { it.toByte() }
        val files = files()
        val assembler = StreamCacheAssembler(files.part, files.meta, files.complete)
        assembler.opened(0, body.size.toLong())
        assembler.write(0, body, 0, body.size)
        assertTrue(assembler.finish(endOfInput = false))
        assertArrayEquals(body, files.complete.readBytes())
    }

    @Test fun pausedThenResumedAtSameOffsetAssemblesOneFile() {
        val body = ByteArray(12_000) { it.toByte() }
        val files = files()
        val first = StreamCacheAssembler(files.part, files.meta, files.complete)
        first.opened(0, body.size.toLong())
        first.write(0, body, 0, 4096)
        assertFalse(first.finish(endOfInput = false))
        assertFalse(files.complete.exists())

        val second = StreamCacheAssembler(files.part, files.meta, files.complete)
        second.opened(4096, (body.size - 4096).toLong())
        second.write(4096, body, 4096, body.size - 4096)
        assertTrue(second.finish(endOfInput = true))
        assertArrayEquals(body, files.complete.readBytes())
    }

    @Test fun id3StyleSeekOverlapsPrefixThenReadsToEnd() {
        val body = ByteArray(5000) { it.toByte() }
        val files = files()
        val sniff = StreamCacheAssembler(files.part, files.meta, files.complete)
        sniff.opened(0, body.size.toLong())
        sniff.write(0, body, 0, 80)
        assertFalse(sniff.finish(endOfInput = false))

        val rest = StreamCacheAssembler(files.part, files.meta, files.complete)
        rest.opened(50, (body.size - 50).toLong())
        rest.write(50, body, 50, body.size - 50)
        assertTrue(rest.finish(endOfInput = true))
        assertArrayEquals(body, files.complete.readBytes())
    }

    @Test fun outOfOrderHeaderTailAndMiddleStillCompletes() {
        val body = ByteArray(1000) { it.toByte() }
        val files = files()
        val assembler = StreamCacheAssembler(files.part, files.meta, files.complete)
        assembler.opened(900, 100)
        assembler.write(900, body, 900, 100)
        assertFalse(assembler.finish(endOfInput = false))
        assembler.opened(0, 1000)
        assembler.write(0, body, 0, 100)
        assertFalse(assembler.finish(endOfInput = false))
        assembler.write(100, body, 100, 800)
        assertTrue(assembler.finish(endOfInput = false))
        assertArrayEquals(body, files.complete.readBytes())
    }

    @Test fun tailWriteDoesNotPublishSparseHolesAsComplete() {
        val files = files()
        val assembler = StreamCacheAssembler(files.part, files.meta, files.complete)
        assembler.opened(900, 100)
        assembler.write(900, ByteArray(100), 0, 100)
        assertFalse(assembler.finish(endOfInput = true))
        assertFalse(files.complete.exists())
        assertTrue(files.part.isFile)
    }

    @Test fun leavingASongBeforeTheBodyEndsKeepsWorkWithoutPublishing() {
        val body = ByteArray(20_000) { 1 }
        val files = files()
        val assembler = StreamCacheAssembler(files.part, files.meta, files.complete)
        assembler.opened(0, body.size.toLong())
        assembler.write(0, body, 0, 4096)
        assertFalse(assembler.finish(endOfInput = false))
        assertFalse(files.complete.exists())
        assertTrue(files.part.isFile)
        assertNull(files.complete.takeIf { it.isFile })
    }

    @Test fun newLengthFromPositionZeroReplacesStalePartial() {
        val old = ByteArray(4000) { 1 }
        val fresh = ByteArray(2500) { 2 }
        val files = files()
        val first = StreamCacheAssembler(files.part, files.meta, files.complete)
        first.opened(0, old.size.toLong())
        first.write(0, old, 0, 1000)
        assertFalse(first.finish(endOfInput = false))

        val second = StreamCacheAssembler(files.part, files.meta, files.complete)
        second.opened(0, fresh.size.toLong())
        second.write(0, fresh, 0, fresh.size)
        assertTrue(second.finish(endOfInput = true))
        assertArrayEquals(fresh, files.complete.readBytes())
    }

    private class Files(val part: File, val meta: File, val complete: File)

    private fun files(): Files {
        val dir = temp.newFolder()
        return Files(File(dir, "track.stream.part"), File(dir, "track.stream.ranges"), File(dir, "track.audio"))
    }
}

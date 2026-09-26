package io.github.nutea.anylisten.core.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioWaveMeterTest {
    @Test fun meteringPreservesPcmAndRespondsToSoundAndSilence() {
        AudioWaveSignal.acquire()
        val meter = AudioWaveMeter { 1000L }
        val tee = TeeAudioProcessor(meter)
        try {
            tee.configure(AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)); tee.flush()
            val input = ByteBuffer.allocateDirect(8192).order(ByteOrder.LITTLE_ENDIAN)
            repeat(2048) { input.putShort(0); input.putShort(12000) }
            input.flip()
            val expected = ByteArray(input.remaining()).also { input.duplicate().get(it) }
            meter.handleBuffer(input)
            assertEquals(0, input.position())
            assertTrue(AudioWaveSignal.current(1000).level > .1f)
            tee.queueInput(input)
            val output = tee.output
            val actual = ByteArray(output.remaining()).also { output.get(it) }
            assertArrayEquals(expected, actual)
            tee.queueInput(ByteBuffer.allocateDirect(2048))
            assertEquals(0f, AudioWaveSignal.current(1000).level)
            assertEquals(0f, AudioWaveSignal.current(1600).peak)
        } finally { tee.reset(); AudioWaveSignal.release() }
    }

    @Test fun inactiveMeterDoesNotPublishAndFloatInputIsBounded() {
        val meter = AudioWaveMeter { 1000L }
        meter.flush(48000, 1, C.ENCODING_PCM_FLOAT)
        val data = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(Float.NaN).putFloat(Float.POSITIVE_INFINITY).putFloat(-2f).putFloat(.5f).apply { flip() }
        meter.handleBuffer(data)
        assertEquals(0f, AudioWaveSignal.current(1000).level)
        AudioWaveSignal.acquire()
        try {
            meter.handleBuffer(data)
            assertTrue(AudioWaveSignal.current(1000).level in .1f..1f)
            assertEquals(0, data.position())
        } finally { AudioWaveSignal.release() }
        assertFalse(AudioWaveSignal.enabled)
        assertEquals(0f, AudioWaveSignal.current(1000).level)
    }
}

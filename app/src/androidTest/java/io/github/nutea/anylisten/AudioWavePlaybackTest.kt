package io.github.nutea.anylisten

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nutea.anylisten.core.playback.AudioWaveRenderersFactory
import io.github.nutea.anylisten.core.playback.AudioWaveSignal
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

internal fun waveAudioFixture(context: android.content.Context, seconds: Int = 3): File {
    val samples = 44100 * seconds
    val wave = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
    wave.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
        .putInt(16).putShort(1).putShort(1).putInt(44100).putInt(88200).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(samples * 2)
    repeat(samples) {
        val envelope = .4 + .6 * kotlin.math.abs(sin(it * 2.0 * Math.PI * 1.5 / 44100))
        wave.putShort((sin(it * 2.0 * Math.PI * 440 / 44100) * 12000 * envelope).toInt().toShort())
    }
    return File(context.cacheDir, "audio-wave-test.wav").apply { writeBytes(wave.array()) }
}

class AudioWavePlaybackTest {
    @Test fun decodedAudioDrivesMeterWithoutMicrophone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = waveAudioFixture(context)
        var player: ExoPlayer? = null
        AudioWaveSignal.acquire()
        try {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context, AudioWaveRenderersFactory(context)).build().apply {
                    volume = 0f
                    setMediaItem(MediaItem.fromUri(file.toURI().toString())); prepare(); play()
                }
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + 8000
            while (AudioWaveSignal.current().level < .1f && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
            assertTrue("Decoded PCM must drive the live meter", AudioWaveSignal.current().level > .1f)
            instrumentation.runOnMainSync { assertNull(player?.playerError); player?.pause() }
        } finally {
            instrumentation.runOnMainSync { player?.release() }
            AudioWaveSignal.release()
            file.delete()
        }
        assertFalse(AudioWaveSignal.enabled)
    }
}

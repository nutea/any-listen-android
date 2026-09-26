package io.github.nutea.anylisten.core.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

class AudioWaveRenderersFactory(context: android.content.Context) : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink =
        androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(TeeAudioProcessor(AudioWaveMeter())))
            .build()
}

/** A transient, read-only meter for the visible player; never records audio. */
object AudioWaveSignal {
    data class Sample(val level: Float = 0f, val peak: Float = 0f, val atMs: Long = 0L)
    @Volatile private var listeners = 0
    @Volatile private var latest = Sample()
    val enabled get() = listeners > 0
    @Synchronized fun acquire() { listeners++ }
    @Synchronized fun release() { listeners = (listeners - 1).coerceAtLeast(0); if (listeners == 0) reset() }
    fun reset() { latest = Sample() }
    internal fun publish(sample: Sample) { if (enabled) latest = sample }
    fun current(nowMs: Long = SystemClock.elapsedRealtime()): Sample = latest.let {
        if (nowMs - it.atMs in 0..500) it else Sample()
    }
}

/** TeeAudioProcessor owns passthrough; this observer only reads a duplicate of its buffer. */
internal class AudioWaveMeter(private val now: () -> Long = SystemClock::elapsedRealtime) : TeeAudioProcessor.AudioBufferSink {
    private var encoding = C.ENCODING_INVALID
    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.encoding = encoding
        AudioWaveSignal.reset()
    }
    override fun handleBuffer(buffer: ByteBuffer) {
        if (!AudioWaveSignal.enabled) return
        val bytes = when (encoding) { C.ENCODING_PCM_16BIT -> 2; C.ENCODING_PCM_FLOAT -> 4; else -> return }
        val data = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val count = data.remaining() / bytes
        if (count == 0) return
        // An odd stride avoids sampling only the silent side of a stereo stream.
        val stride = ((count + 511) / 512).coerceAtLeast(1) or 1
        var sum = 0.0; var peak = 0f; var measured = 0
        for (i in 0 until count step stride) {
            val offset = data.position() + i * bytes
            val value = if (bytes == 2) data.getShort(offset) / 32768f else data.getFloat(offset)
            if (!value.isFinite()) continue
            val magnitude = abs(value).coerceAtMost(1f)
            sum += magnitude * magnitude; peak = maxOf(peak, magnitude); measured++
        }
        if (measured > 0) AudioWaveSignal.publish(AudioWaveSignal.Sample(
            (sqrt(sum / measured) * 2.4).toFloat().coerceIn(0f, 1f), peak, now()))
    }
}

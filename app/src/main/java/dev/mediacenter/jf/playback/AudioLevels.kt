package dev.mediacenter.jf.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * How loud the bass, the middle and the treble of what's playing are, for the music visualizer:
 * measured from the decoded sound on its way to the output (no microphone permission, nothing
 * recorded), with three simple filters, a few dozen times a second. Nothing is measured for sound
 * passed through to a receiver, or high-resolution sound output as-is; [live] then reads false and
 * the visualizer drifts on its own.
 */
class AudioLevels : TeeAudioProcessor.AudioBufferSink {
    @Volatile var bass = 0f
        private set
    @Volatile var mid = 0f
        private set
    @Volatile var treble = 0f
        private set
    @Volatile private var updatedAt = 0L

    /** Sound has been measured within the last half second. */
    val live get() = SystemClock.elapsedRealtime() - updatedAt < 500

    private var channels = 2
    private var encoding = C.ENCODING_INVALID
    // One-pole low-pass filters at about 200 Hz and 3 kHz, their coefficients set for the sample rate.
    private var aLow = 0f
    private var aHigh = 0f
    private var low = 0f
    private var lowMid = 0f

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        channels = channelCount.coerceAtLeast(1)
        this.encoding = encoding
        aLow = 1f - exp(-2.0 * Math.PI * 200.0 / sampleRateHz).toFloat()
        aHigh = 1f - exp(-2.0 * Math.PI * 3000.0 / sampleRateHz).toFloat()
        low = 0f
        lowMid = 0f
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (encoding != C.ENCODING_PCM_16BIT) return
        val samples = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frames = samples.remaining() / channels
        if (frames == 0) return
        var sumLow = 0f
        var sumMid = 0f
        var sumHigh = 0f
        // Every second frame is plenty for levels, and halves the work on the audio thread.
        var i = 0
        while (i < frames) {
            var mono = 0f
            for (c in 0 until channels) mono += samples.get(i * channels + c)
            val x = mono / (channels * 32768f)
            low += aLow * (x - low)
            lowMid += aHigh * (x - lowMid)
            val m = lowMid - low
            val h = x - lowMid
            sumLow += low * low
            sumMid += m * m
            sumHigh += h * h
            i += 2
        }
        val n = (frames + 1) / 2f
        // Scaled so ordinary music sits around 0.3–0.8, then eased: quick to rise, slower to fall.
        bass = ease(bass, (sqrt(sumLow / n) * 3.2f).coerceAtMost(1f))
        mid = ease(mid, (sqrt(sumMid / n) * 5f).coerceAtMost(1f))
        treble = ease(treble, (sqrt(sumHigh / n) * 9f).coerceAtMost(1f))
        updatedAt = SystemClock.elapsedRealtime()
    }

    private fun ease(from: Float, to: Float) = if (to > from) from + (to - from) * 0.6f else from + (to - from) * 0.15f
}

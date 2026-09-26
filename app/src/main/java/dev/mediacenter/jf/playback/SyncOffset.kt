package dev.mediacenter.jf.playback

import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer

/**
 * Runs a renderer a little ahead of or behind the sound, for audio and subtitle sync: the picture
 * (or the subtitles) is shown for the playback position [offsetMs] later than the true one. The
 * offset is read on every frame, so a change takes effect at once, without restarting playback.
 */
internal class SyncOffset(renderer: Renderer, private val offsetMs: () -> Long) : ForwardingRenderer(renderer) {
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        super.render(positionUs + offsetMs() * 1000L, elapsedRealtimeUs)
    }
}

/**
 * Night mode: a gentle compressor with make-up gain and a limiter on the player's sound, so loud
 * scenes come down and quiet dialogue comes up. Android 9's DynamicsProcessing, on the player's
 * own audio session; it doesn't reach sound passed through to a receiver (that isn't decoded here).
 */
internal class NightMode {
    private var effect: android.media.audiofx.DynamicsProcessing? = null
    private var appliedTo = 0

    /** Puts night mode on [session] (with [channels] channels) when [on], and takes it off otherwise. */
    fun apply(on: Boolean, session: Int, channels: Int) {
        if (!on || session == 0 || android.os.Build.VERSION.SDK_INT < 28) return release()
        if (effect != null && appliedTo == session) return
        release()
        effect = runCatching {
            val count = channels.coerceIn(1, 8)
            val config = android.media.audiofx.DynamicsProcessing.Config.Builder(
                android.media.audiofx.DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                count, false, 0, true, 1, false, 0, true,
            ).build()
            android.media.audiofx.DynamicsProcessing(0, session, config).apply {
                // One band over everything: above -30 dB squeezed 3.5:1, then lifted 9 dB, so the whole range is closer together.
                val band = android.media.audiofx.DynamicsProcessing.MbcBand(true, 20_000f, 10f, 250f, 3.5f, -30f, 6f, -90f, 1f, 0f, 9f)
                setMbcAllChannelsTo(android.media.audiofx.DynamicsProcessing.Mbc(true, true, 1).apply { setBand(0, band) })
                // And nothing over -2 dB, however loud the scene.
                setLimiterAllChannelsTo(android.media.audiofx.DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -2f, 0f))
                enabled = true
            }
        }.onFailure { dev.mediacenter.jf.AppLog.w("Player", "Night mode isn't available on this TV: ${it.message}") }.getOrNull()
        appliedTo = session
    }

    fun release() {
        effect?.release()
        effect = null
        appliedTo = 0
    }
}

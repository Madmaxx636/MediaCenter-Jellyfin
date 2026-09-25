package dev.mediacenter.jf.playback

import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix

/**
 * Mixes any channel layout down to stereo with the standard (ITU-R BS.775) weights: centre and
 * surrounds at -3 dB into each side, the LFE left out, then scaled so the loudest possible sum
 * can't clip. Mono and stereo pass through untouched. Used for "compatible sound", when a TV
 * can't take the soundtrack's own channel layout.
 */
object StereoDownmix {
    private const val C = 0.7071f

    /**
     * Per input layout, each channel's weight into (left, right), in Android's channel order:
     * FL FR FC LFE BL BR (BC) SL SR, with the layouts Media3 uses for 3 to 8 channels.
     */
    private val layouts: Map<Int, List<Pair<Float, Float>>> = mapOf(
        3 to listOf(1f to 0f, 0f to 1f, C to C),                                    // FL FR FC
        4 to listOf(1f to 0f, 0f to 1f, C to 0f, 0f to C),                          // FL FR BL BR
        5 to listOf(1f to 0f, 0f to 1f, C to C, C to 0f, 0f to C),                  // FL FR FC BL BR
        6 to listOf(1f to 0f, 0f to 1f, C to C, 0f to 0f, C to 0f, 0f to C),        // FL FR FC LFE BL BR
        7 to listOf(1f to 0f, 0f to 1f, C to C, 0f to 0f, C to 0f, 0f to C, 0.5f to 0.5f), // … BC
        8 to listOf(1f to 0f, 0f to 1f, C to C, 0f to 0f, C to 0f, 0f to C, C to 0f, 0f to C), // … SL SR
    )

    fun create(): ChannelMixingAudioProcessor = ChannelMixingAudioProcessor().apply {
        putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(1, 1))
        putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(2, 2))
        for ((inputs, weights) in layouts) {
            val scale = 1f / maxOf(weights.sumOf { it.first.toDouble() }, weights.sumOf { it.second.toDouble() }).toFloat()
            // Coefficients run input by input: [in0→L, in0→R, in1→L, in1→R, ...].
            val coefficients = FloatArray(inputs * 2)
            weights.forEachIndexed { i, (l, r) ->
                coefficients[i * 2] = l * scale
                coefficients[i * 2 + 1] = r * scale
            }
            putChannelMixingMatrix(ChannelMixingMatrix(inputs, 2, coefficients))
        }
    }
}

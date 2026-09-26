package dev.mediacenter.jf.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos
import dev.mediacenter.jf.data.Lyrics
import dev.mediacenter.jf.playback.AudioLevels
import dev.mediacenter.jf.ui.components.WText
import dev.mediacenter.jf.ui.theme.Wmc
import dev.mediacenter.jf.ui.theme.WmcType
import kotlin.math.PI
import kotlin.math.sin

/**
 * The music visualizer behind now playing, after Media Center's: a pool of light that swells with
 * the bass, and ribbons of light across the screen that sway with the middle and the treble. Where
 * the sound isn't measured (passed through to a receiver) it drifts gently on its own. Only the
 * frame clock and the levels are read, while drawing: nothing recomposes as it moves.
 */
@Composable
internal fun MusicVisualizer(levels: AudioLevels, playing: Boolean, modifier: Modifier = Modifier) {
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        val from = withFrameNanos { it } - (time.floatValue * 1e9f).toLong()
        while (true) withFrameNanos { time.floatValue = (it - from) / 1e9f }
    }
    val fade by animateFloatAsState(if (playing) 1f else 0.45f, tween(900), label = "visualizer")
    Spacer(
        modifier.fillMaxSize().drawWithCache {
            val w = size.width
            val h = size.height
            val paths = List(3) { Path() }
            val tints = listOf(Color(0xFF7CC4FF), Color(0xFF9ED8FF), Color(0xFF5FB0F5))
            val thread = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round)
            val glowWidth = 26.dp.toPx()
            onDrawBehind {
                val t = time.floatValue
                val live = levels.live
                // Measured levels, or a slow breathing when there's nothing to measure.
                val bass = if (live) levels.bass else 0.3f + 0.08f * sin(t * 0.6f)
                val mid = if (live) levels.mid else 0.25f + 0.06f * sin(t * 0.9f + 1f)
                val treble = if (live) levels.treble else 0.2f + 0.05f * sin(t * 1.3f + 2f)
                // The pool of light, low on the right where Media Center's backdrop is brightest.
                drawRect(
                    Brush.radialGradient(
                        listOf(Color(0xFF48B4FF).copy(alpha = (0.18f + 0.45f * bass) * fade), Color.Transparent),
                        center = Offset(w * 0.74f, h * 0.92f), radius = w * (0.34f + 0.22f * bass),
                    )
                )
                val bands = floatArrayOf(bass, mid, treble)
                paths.forEachIndexed { k, path ->
                    val level = bands[k]
                    val amplitude = h * (0.025f + 0.11f * level)
                    val baseline = h * (0.78f - 0.07f * k)
                    val waves = 1.2f + 0.45f * k
                    val speed = 0.35f + 0.22f * k
                    path.reset()
                    val steps = 48
                    for (i in 0..steps) {
                        val x = w * i / steps
                        val y = baseline - h * 0.18f * (i.toFloat() / steps) + // rising left to right, as the ribbons do
                            amplitude * sin(2f * PI.toFloat() * waves * i / steps + t * speed * 2f * PI.toFloat() * 0.3f)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, tints[k], alpha = (0.05f + 0.16f * level) * fade, style = Stroke(glowWidth, cap = StrokeCap.Round))
                    drawPath(path, Color.White, alpha = (0.22f + 0.45f * level) * fade, style = thread)
                }
            }
        },
    )
}

/**
 * The song's lyrics on now playing: timed ones follow along, the line being sung bright and the
 * ones around it fading; untimed ones move through the song at an even pace. Nothing shows for
 * songs without lyrics.
 */
@Composable
internal fun LyricsView(lyrics: Lyrics, positionMs: Long, durationMs: Long, modifier: Modifier = Modifier) {
    val lines = lyrics.lyrics.takeIf { it.isNotEmpty() } ?: return
    val current = if (lyrics.timed) {
        lines.indexOfLast { (it.startMs ?: Long.MAX_VALUE) <= positionMs }.coerceAtLeast(0)
    } else {
        if (durationMs > 0) ((positionMs.toFloat() / durationMs) * lines.size).toInt().coerceIn(0, lines.lastIndex) else 0
    }
    // A window of lines around the one being sung.
    val from = (current - 2).coerceAtLeast(0)
    Column(modifier) {
        WText("lyrics", WmcType.Label, Modifier.padding(bottom = 6.dp), color = Wmc.Accent)
        for (i in from until (from + 5).coerceAtMost(lines.size)) {
            val line = lines[i].text.ifBlank { "•" }
            WText(
                line, if (i == current) WmcType.Heading else WmcType.Body,
                color = when {
                    i == current -> Wmc.Text
                    kotlin.math.abs(i - current) == 1 -> Wmc.TextDim
                    else -> Wmc.TextFaint
                },
                maxLines = 1, align = TextAlign.Start,
            )
        }
    }
}

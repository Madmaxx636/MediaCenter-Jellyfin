package dev.mediacenter.jf.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Line icons drawn in code, in a 24-unit box, so the APK carries no icon fonts or bitmaps. */
enum class Glyph {
    Movies, Tv, Music, Pictures, Video, Settings, Resume, NextUp, Recent, Playlist, NowPlaying, User, Exit, Guide,
    Folder, Info, Collections, LiveTv, Record, ListLines, Minus, Plus, Search, Grid, Sort,
    Play, Pause, Stop, Rewind, FastForward, SkipBack, SkipNext, Subtitles, Audio, Check, Star, Shuffle, Back,
    Server, Mic,
}

@Composable
fun GlyphIcon(glyph: Glyph, modifier: Modifier = Modifier, size: Dp = 24.dp, color: Color = Color.White) {
    Canvas(modifier.size(size)) { drawGlyph(glyph, color) }
}

fun DrawScope.drawGlyph(glyph: Glyph, color: Color) {
    val u = size.minDimension / 24f
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    val stroke = Stroke(width = 1.7f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, p(x1, y1), p(x2, y2), stroke.width, StrokeCap.Round)
    fun rect(x: Float, y: Float, w: Float, h: Float, r: Float = 1.5f, filled: Boolean = false) = drawRoundRect(
        color, p(x, y), Size(w * u, h * u), CornerRadius(r * u), style = if (filled) androidx.compose.ui.graphics.drawscope.Fill else stroke,
    )
    fun circle(x: Float, y: Float, r: Float, filled: Boolean = false) =
        drawCircle(color, r * u, p(x, y), style = if (filled) androidx.compose.ui.graphics.drawscope.Fill else stroke)
    fun poly(vararg pts: Float, filled: Boolean = true, close: Boolean = true) {
        val path = Path().apply {
            moveTo(pts[0] * u, pts[1] * u)
            for (i in 2 until pts.size step 2) lineTo(pts[i] * u, pts[i + 1] * u)
            if (close) close()
        }
        drawPath(path, color, style = if (filled) androidx.compose.ui.graphics.drawscope.Fill else stroke)
    }

    when (glyph) {
        Glyph.Movies -> {
            rect(3f, 6f, 18f, 13f)
            line(3f, 10f, 21f, 10f)
            for (x in listOf(6f, 10.5f, 15f)) line(x, 6f, x + 2.5f, 10f)
        }
        Glyph.Tv -> {
            rect(2.5f, 5f, 19f, 12.5f)
            line(9f, 21f, 15f, 21f)
            line(12f, 17.5f, 12f, 21f)
        }
        Glyph.Music -> {
            line(9f, 17f, 9f, 5f); line(9f, 5f, 19f, 3f); line(19f, 3f, 19f, 15f)
            circle(6.5f, 17.5f, 2.5f, filled = true); circle(16.5f, 15.5f, 2.5f, filled = true)
        }
        Glyph.Pictures -> {
            rect(3f, 5f, 18f, 14f)
            poly(5f, 17f, 10f, 10.5f, 13.5f, 14.5f, 15.5f, 12.5f, 19f, 17f)
            circle(16f, 8.8f, 1.6f, filled = true)
        }
        Glyph.Video -> {
            rect(2.5f, 6.5f, 13f, 11f)
            poly(15.5f, 10.5f, 21.5f, 7f, 21.5f, 17f, 15.5f, 13.5f, filled = false)
        }
        Glyph.Settings -> {
            circle(12f, 12f, 3f)
            for (i in 0 until 8) {
                val a = i * PI / 4
                line(12f + 6f * cos(a).toFloat(), 12f + 6f * sin(a).toFloat(), 12f + 8.5f * cos(a).toFloat(), 12f + 8.5f * sin(a).toFloat())
            }
            circle(12f, 12f, 6f)
        }
        Glyph.Resume -> {
            circle(12f, 12f, 9f)
            poly(10f, 8f, 16.5f, 12f, 10f, 16f)
        }
        Glyph.NextUp -> {
            poly(4f, 6f, 11f, 12f, 4f, 18f)
            poly(12f, 6f, 19f, 12f, 12f, 18f)
            line(21f, 6f, 21f, 18f)
        }
        Glyph.Recent -> {
            circle(12f, 12f, 9f)
            line(12f, 7f, 12f, 12f); line(12f, 12f, 15.5f, 14f)
        }
        Glyph.Playlist -> {
            line(3f, 6f, 15f, 6f); line(3f, 11f, 15f, 11f); line(3f, 16f, 10f, 16f)
            poly(14f, 14f, 21f, 17.5f, 14f, 21f)
        }
        Glyph.NowPlaying -> {
            for ((i, h) in listOf(8f, 14f, 10f, 16f, 6f).withIndex()) rect(3.5f + i * 3.8f, 20f - h, 2.4f, h, 0.8f, filled = true)
        }
        Glyph.User -> {
            circle(12f, 8.5f, 4f)
            val path = Path().apply {
                moveTo(4f * u, 21f * u)
                cubicTo(4f * u, 15f * u, 20f * u, 15f * u, 20f * u, 21f * u)
            }
            drawPath(path, color, style = stroke)
        }
        Glyph.Exit -> {
            val path = Path().apply { addArc(androidx.compose.ui.geometry.Rect(p(4f, 4f), Size(16f * u, 16f * u)), -60f, 300f) }
            drawPath(path, color, style = stroke)
            line(12f, 2.5f, 12f, 11f)
        }
        Glyph.Guide -> {
            rect(3f, 4f, 18f, 16f)
            line(3f, 9f, 21f, 9f); line(3f, 14.5f, 21f, 14.5f); line(9f, 4f, 9f, 20f); line(15f, 9f, 15f, 14.5f)
        }
        Glyph.Record -> circle(12f, 12f, 6f, filled = true)
        Glyph.Search -> { circle(10f, 10f, 6.5f); line(14.8f, 14.8f, 20.5f, 20.5f) }
        Glyph.Mic -> {
            rect(9f, 3f, 6f, 11f, 3f, filled = true)
            drawArc(color, 0f, 180f, false, p(6f, 7f), Size(12f * u, 10f * u), style = stroke)
            line(12f, 17f, 12f, 21f)
            line(8.5f, 21f, 15.5f, 21f)
        }
        Glyph.Grid -> for (r in 0..2) for (c in 0..2) rect(3.5f + c * 6f, 3.5f + r * 6f, 4.5f, 4.5f, 0.6f, filled = true)
        Glyph.Sort -> poly(4f, 17f, 20f, 17f, 12f, 7f)
        Glyph.ListLines -> { line(5f, 7f, 19f, 7f); line(5f, 12f, 19f, 12f); line(5f, 17f, 19f, 17f) }
        Glyph.Minus -> line(6f, 12f, 18f, 12f)
        Glyph.Plus -> { line(6f, 12f, 18f, 12f); line(12f, 6f, 12f, 18f) }
        Glyph.Server -> {
            // Two stacked server units, each with a status light and a vent line.
            rect(3.5f, 4f, 17f, 7f)
            rect(3.5f, 13f, 17f, 7f)
            circle(7f, 7.5f, 1.1f, filled = true)
            circle(7f, 16.5f, 1.1f, filled = true)
            line(11f, 7.5f, 17.5f, 7.5f)
            line(11f, 16.5f, 17.5f, 16.5f)
        }
        Glyph.LiveTv -> {
            rect(2.5f, 8f, 19f, 12.5f)
            line(8f, 3f, 12f, 8f); line(16f, 3f, 12f, 8f)
            circle(12f, 14.2f, 2.2f, filled = true)
        }
        Glyph.Folder -> poly(3f, 6f, 9.5f, 6f, 11.5f, 8.5f, 21f, 8.5f, 21f, 19f, 3f, 19f, filled = false)
        Glyph.Info -> {
            circle(12f, 12f, 9f)
            line(12f, 11f, 12f, 17f)
            circle(12f, 7.6f, 0.9f, filled = true)
        }
        Glyph.Collections -> {
            rect(7f, 3f, 13f, 16f)
            rect(4f, 6f, 13f, 16f)
        }
        Glyph.Play -> poly(7f, 4.5f, 19.5f, 12f, 7f, 19.5f)
        Glyph.Pause -> { rect(6f, 5f, 4f, 14f, 1f, true); rect(14f, 5f, 4f, 14f, 1f, true) }
        Glyph.Stop -> rect(6f, 6f, 12f, 12f, 1.5f, true)
        Glyph.Rewind -> { poly(11.5f, 6f, 3f, 12f, 11.5f, 18f); poly(20.5f, 6f, 12f, 12f, 20.5f, 18f) }
        Glyph.FastForward -> { poly(3.5f, 6f, 12f, 12f, 3.5f, 18f); poly(12.5f, 6f, 21f, 12f, 12.5f, 18f) }
        Glyph.SkipBack -> { rect(4f, 6f, 2.6f, 12f, 0.8f, true); poly(19.5f, 6f, 8f, 12f, 19.5f, 18f) }
        Glyph.SkipNext -> { poly(4.5f, 6f, 16f, 12f, 4.5f, 18f); rect(17.4f, 6f, 2.6f, 12f, 0.8f, true) }
        Glyph.Subtitles -> {
            rect(2.5f, 5f, 19f, 14f)
            line(6f, 11f, 10f, 11f); line(12f, 11f, 18f, 11f); line(6f, 15f, 14f, 15f); line(16f, 15f, 18f, 15f)
        }
        Glyph.Audio -> {
            poly(3f, 9.5f, 7f, 9.5f, 12f, 5f, 12f, 19f, 7f, 14.5f, 3f, 14.5f)
            val arc = Path().apply { addArc(androidx.compose.ui.geometry.Rect(p(10f, 7f), Size(10f * u, 10f * u)), -50f, 100f) }
            drawPath(arc, color, style = stroke)
        }
        Glyph.Check -> poly(4f, 12.5f, 9.5f, 18f, 20f, 6.5f, filled = false, close = false)
        Glyph.Star -> {
            val pts = FloatArray(20)
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) 9.5f else 4f
                val a = -PI / 2 + i * PI / 5
                pts[i * 2] = 12f + r * cos(a).toFloat(); pts[i * 2 + 1] = 12.5f + r * sin(a).toFloat()
            }
            poly(*pts)
        }
        Glyph.Shuffle -> {
            poly(3f, 7f, 8f, 7f, 16f, 17f, 20f, 17f, filled = false, close = false)
            poly(3f, 17f, 8f, 17f, 16f, 7f, 20f, 7f, filled = false, close = false)
            poly(18f, 4.5f, 21f, 7f, 18f, 9.5f, filled = false, close = false)
            poly(18f, 14.5f, 21f, 17f, 18f, 19.5f, filled = false, close = false)
        }
        Glyph.Back -> {
            line(7f, 12f, 18f, 12f)
            poly(12f, 6.5f, 6.5f, 12f, 12f, 17.5f, filled = false, close = false)
        }
    }
}

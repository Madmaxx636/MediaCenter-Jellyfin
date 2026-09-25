package dev.mediacenter.jf.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The pictures on the start menu's tiles, after Windows 7 Media Center's: soft, filled,
 * pale-blue pictograms rather than line icons (a star for favourites, a tuner bar for radio,
 * a shelf of cases for the movie library, a grid of bars for the guide), fading away to the
 * right as Media Center's did. Drawn in code, in a 184 x 102 box (the tile's own shape).
 */
enum class TileArt {
    MusicMosaic, Star, Radio, Search, Photos, Filmstrip, DvdCases, PosterWall, Guide, LiveTv, Extras,
    Collections, Folder, Resume, NextUp, Recent, People, Playlist, OnNow, NowPlaying, Queue, Settings,
    Power, User, Server, Books, Screen,
}

// Media Center's own light blue, a touch lighter at the top.
private val ArtTop = Color(0xFFBFE2FF)
private val ArtBottom = Color(0xFF6FB2F0)

/** A tile's picture, drawn at [alpha] (Media Center showed them fairly faint until focused). */
@Composable
fun TileArtwork(art: TileArt, modifier: Modifier = Modifier, alpha: () -> Float) {
    Canvas(
        modifier
            // Its own layer, so the fade to the right only cuts into the picture itself.
            .graphicsLayer { this.alpha = alpha(); compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.horizontalGradient(0.55f to Color.Black, 1f to Color.Black.copy(alpha = 0.2f)),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) { drawTileArt(art) }
}

fun DrawScope.drawTileArt(art: TileArt) {
    // Fit the 184 x 102 design box into the canvas, centred.
    val u = minOf(size.width / 184f, size.height / 102f)
    val ox = (size.width - 184f * u) / 2f
    val oy = (size.height - 102f * u) / 2f
    fun p(x: Float, y: Float) = Offset(ox + x * u, oy + y * u)
    val fill = Brush.verticalGradient(listOf(ArtTop, ArtBottom), startY = oy + 20f * u, endY = oy + 82f * u)
    fun box(x: Float, y: Float, w: Float, h: Float, r: Float = 1.5f, a: Float = 1f) =
        drawRoundRect(fill, p(x, y), Size(w * u, h * u), CornerRadius(r * u), alpha = a)
    fun disc(x: Float, y: Float, r: Float, a: Float = 1f) = drawCircle(fill, r * u, p(x, y), alpha = a)
    fun ring(x: Float, y: Float, r: Float, w: Float, a: Float = 1f) =
        drawCircle(fill, r * u, p(x, y), alpha = a, style = Stroke(w * u))
    fun bar(x1: Float, y1: Float, x2: Float, y2: Float, w: Float) =
        drawLine(fill, p(x1, y1), p(x2, y2), w * u, StrokeCap.Round)
    fun shape(vararg pts: Float, a: Float = 1f) {
        val path = Path().apply {
            moveTo(ox + pts[0] * u, oy + pts[1] * u)
            for (i in 2 until pts.size step 2) lineTo(ox + pts[i] * u, oy + pts[i + 1] * u)
            close()
        }
        drawPath(path, fill, alpha = a)
    }
    fun person(cx: Float, top: Float, s: Float, a: Float = 1f) {
        disc(cx, top + 8f * s, 7.5f * s, a)
        val path = Path().apply {
            moveTo(ox + (cx - 15f * s) * u, oy + (top + 38f * s) * u)
            cubicTo(ox + (cx - 15f * s) * u, oy + (top + 20f * s) * u, ox + (cx + 15f * s) * u, oy + (top + 20f * s) * u, ox + (cx + 15f * s) * u, oy + (top + 38f * s) * u)
            close()
        }
        drawPath(path, fill, alpha = a)
    }

    when (art) {
        TileArt.MusicMosaic -> for (r in 0..2) for (c in 0..5) box(49f + c * 15f, 29f + r * 15f, 13f, 13f, 1f, if ((r + c) % 3 == 0) 0.75f else 1f)
        TileArt.Star -> {
            val path = Path()
            for (i in 0 until 10) {
                val a = -PI.toFloat() / 2f + i * PI.toFloat() / 5f
                val r = if (i % 2 == 0) 20f else 8.5f
                val pt = p(92f + cos(a) * r, 52f + sin(a) * r)
                if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
            }
            path.close()
            drawPath(path, fill)
        }
        // A tuner's dial: a bar of segments, thickest in the middle.
        TileArt.Radio -> {
            val widths = floatArrayOf(7f, 9f, 11f, 14f, 22f, 14f, 11f, 9f, 7f)
            val heights = floatArrayOf(8f, 10f, 12f, 13f, 18f, 13f, 12f, 10f, 8f)
            var x = 92f - (widths.sum() + 2f * (widths.size - 1)) / 2f
            widths.forEachIndexed { i, w ->
                box(x, 51f - heights[i] / 2f, w, heights[i], 1.5f, 0.55f + 0.45f * (1f - kotlin.math.abs(i - 4) / 4f))
                x += w + 2f
            }
        }
        TileArt.Search -> { ring(87f, 46f, 12.5f, 5.5f); bar(96.5f, 55.5f, 108f, 67f, 7f) }
        TileArt.Photos -> {
            rotate(-9f, p(80f, 52f)) { box(62f, 36f, 38f, 28f, 1.5f, 0.6f) }
            rotate(7f, p(104f, 50f)) { box(86f, 34f, 38f, 28f, 1.5f, 0.75f) }
            box(71f, 42f, 42f, 30f, 1.5f)
            shape(75f, 68f, 86f, 54f, 94f, 63f, 99f, 58f, 109f, 68f, a = 0.35f)
        }
        // Media Center's recorded tv: a strip of pictures, the middle one larger.
        TileArt.Filmstrip -> {
            box(40f, 33f, 104f, 36f, 2f, 0.28f)
            box(46f, 41f, 30f, 20f, 1f, 0.8f)
            box(79f, 36f, 36f, 30f, 1f)
            box(118f, 41f, 22f, 20f, 1f, 0.7f)
        }
        // A shelf of cases, one leaning over.
        TileArt.DvdCases -> {
            var x = 52f
            for (i in 0 until 10) {
                if (i == 3) {
                    rotate(24f, p(x + 4f, 70f)) { box(x, 34f, 8f, 36f, 1f, 0.9f) }
                    x += 16f
                } else {
                    box(x, 34f + (i % 3), 8f, 36f - (i % 3), 1f, if (i % 2 == 0) 1f else 0.8f)
                    x += 10f
                }
            }
        }
        TileArt.PosterWall -> for (r in 0..2) for (c in 0..5) box(51f + c * 14f, 29f + r * 15.5f, 12f, 13.5f, 1f, if ((r * 2 + c) % 4 == 1) 0.7f else 1f)
        // The programme guide: rows of programmes.
        TileArt.Guide -> {
            val rows = listOf(floatArrayOf(26f, 44f), floatArrayOf(40f, 30f), floatArrayOf(18f, 24f, 28f), floatArrayOf(34f, 36f))
            rows.forEachIndexed { r, cells ->
                var x = 48f
                cells.forEach { w -> box(x, 30f + r * 11f, w, 8.5f, 1f); x += w + 2.5f }
            }
        }
        // Media Center's live tv: three discs of light running together.
        TileArt.LiveTv -> { disc(76f, 51f, 14f, 0.55f); disc(108f, 51f, 14f, 0.55f); disc(92f, 51f, 16f, 0.8f) }
        TileArt.Extras -> for (r in 0..1) for (c in 0..2) box(62f + c * 21f, 32f + r * 21f, 18f, 18f, 3f, if (r == 0 && c == 1) 1f else 0.75f)
        TileArt.Collections -> { box(76f, 26f, 30f, 42f, 1.5f, 0.5f); box(70f, 30f, 30f, 42f, 1.5f, 0.75f); box(64f, 34f, 30f, 42f, 1.5f) }
        TileArt.Folder -> shape(62f, 34f, 80f, 34f, 85f, 39f, 122f, 39f, 122f, 70f, 62f, 70f)
        TileArt.Books -> { box(66f, 32f, 12f, 40f, 1f); box(80f, 30f, 10f, 42f, 1f, 0.8f); rotate(14f, p(96f, 72f)) { box(92f, 33f, 11f, 39f, 1f, 0.9f) }; box(106f, 34f, 12f, 38f, 1f, 0.7f) }
        TileArt.Resume -> {
            box(64f, 30f, 56f, 34f, 2f, 0.55f)
            shape(86f, 38f, 100f, 47f, 86f, 56f)
            box(64f, 69f, 56f, 4f, 2f, 0.35f); box(64f, 69f, 34f, 4f, 2f)
        }
        TileArt.NextUp -> {
            box(58f, 36f, 34f, 26f, 1.5f, 0.45f)
            box(70f, 32f, 38f, 30f, 1.5f, 0.7f)
            shape(112f, 38f, 124f, 47f, 112f, 56f); box(125f, 38f, 3.5f, 18f, 1f)
        }
        TileArt.Recent -> {
            ring(92f, 50f, 17f, 5f)
            bar(92f, 50f, 92f, 40f, 4f); bar(92f, 50f, 100f, 54f, 4f)
        }
        TileArt.People -> { person(80f, 30f, 1f, 0.6f); person(102f, 32f, 1.05f) }
        TileArt.User -> person(92f, 28f, 1.15f)
        TileArt.Playlist -> {
            for (i in 0..2) box(62f, 34f + i * 10f, 34f, 5f, 2f, 1f - i * 0.2f)
            bar(112f, 34f, 112f, 62f, 4f); bar(112f, 34f, 122f, 38f, 4f)
            disc(106f, 63f, 6.5f)
        }
        TileArt.OnNow -> {
            box(66f, 36f, 52f, 34f, 2f)
            bar(84f, 36f, 76f, 26f, 3f); bar(100f, 36f, 108f, 26f, 3f)
        }
        // Now playing: the bars of a level meter.
        TileArt.NowPlaying -> {
            val h = floatArrayOf(14f, 26f, 36f, 22f, 30f, 16f, 10f)
            h.forEachIndexed { i, bh -> box(66f + i * 8f, 68f - bh, 6f, bh, 1f, 0.7f + 0.3f * (bh / 36f)) }
        }
        TileArt.Queue -> {
            for (i in 0..3) { disc(70f, 35f + i * 10f, 2.8f, 1f - i * 0.15f); box(77f, 32.5f + i * 10f, 40f, 5f, 2f, 1f - i * 0.15f) }
        }
        TileArt.Settings -> {
            val path = Path().apply { fillType = PathFillType.EvenOdd }
            val teeth = 8
            for (i in 0 until teeth * 2) {
                val a0 = i * PI.toFloat() / teeth
                val r = if (i % 2 == 0) 21f else 16f
                val a1 = a0 + PI.toFloat() / teeth
                val s = p(92f + cos(a0) * r, 51f + sin(a0) * r)
                if (i == 0) path.moveTo(s.x, s.y) else path.lineTo(s.x, s.y)
                val e = p(92f + cos(a1 - 0.05f) * r, 51f + sin(a1 - 0.05f) * r)
                path.lineTo(e.x, e.y)
            }
            path.close()
            val hole = p(92f, 51f)
            path.addOval(androidx.compose.ui.geometry.Rect(hole, 7.5f * u))
            drawPath(path, fill)
        }
        TileArt.Power -> {
            drawArc(fill, -60f, 300f, false, p(74f, 33f), Size(36f * u, 36f * u), style = Stroke(6f * u, cap = StrokeCap.Round))
            bar(92f, 28f, 92f, 48f, 6f)
        }
        // Media Center's video library: a plain screen.
        TileArt.Screen -> box(58f, 34f, 70f, 34f, 1.5f)
        TileArt.Server -> for (i in 0..2) {
            box(70f, 30f + i * 14f, 44f, 11f, 2.5f, 1f - i * 0.18f)
        }
    }
}

package dev.mediacenter.jf.ui.theme

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin

/** One light ribbon: where it sits, how it curves, how broad it is, and when it sweeps in during the intro. */
internal class Ribbon(val y: Float, val rise: Float, val wave: Float, val width: Float, val delay: Float, val tint: Color)

/** Media Center's ribbons of light, shared by the intro and the start menu behind it. */
internal val Ribbons = listOf(
    Ribbon(y = 0.78f, rise = -0.30f, wave = 0.07f, width = 1.0f, delay = 0.04f, tint = Color(0xFF7CC4FF)),
    Ribbon(y = 0.88f, rise = -0.22f, wave = 0.05f, width = 0.7f, delay = 0.12f, tint = Color(0xFF9ED8FF)),
    Ribbon(y = 0.64f, rise = -0.36f, wave = 0.09f, width = 0.55f, delay = 0.20f, tint = Color(0xFF5FB0F5)),
    Ribbon(y = 0.96f, rise = -0.16f, wave = 0.04f, width = 0.85f, delay = 0.28f, tint = Color(0xFFB8E4FF)),
)

/** A ribbon's curve across a [w]×[h] screen, from off the left edge to off the right; [bend] flexes it (px). */
internal fun ribbonPath(r: Ribbon, w: Float, h: Float, bend: Float = 0f, into: Path = Path()): Path = into.apply {
    reset()
    moveTo(-0.1f * w, r.y * h)
    cubicTo(
        0.30f * w, (r.y + r.wave) * h + bend,
        0.62f * w, (r.y + r.rise - r.wave) * h - bend,
        1.1f * w, (r.y + r.rise) * h + bend * 0.5f,
    )
}

/** The ribbon's broad soft glow: three stacked bands, widest and faintest outermost. */
internal fun DrawScope.drawRibbonBands(path: Path, r: Ribbon, alpha: Float) {
    val band = 110.dp.toPx() * r.width
    drawPath(path, r.tint, alpha = 0.05f * alpha, style = Stroke(band, cap = StrokeCap.Round))
    drawPath(path, r.tint, alpha = 0.08f * alpha, style = Stroke(band * 0.4f, cap = StrokeCap.Round))
    drawPath(path, r.tint, alpha = 0.12f * alpha, style = Stroke(band * 0.12f, cap = StrokeCap.Round))
}

/** Stroke styles by pixel width, kept rather than made afresh every frame (less garbage to collect). */
private val threadStrokes = HashMap<Float, Stroke>()

/** The bright thread through the middle of a ribbon. */
internal fun DrawScope.drawRibbonThread(path: Path, alpha: Float, width: Float = 1.6f) {
    val px = width.dp.toPx()
    val stroke = threadStrokes.getOrPut(px) { Stroke(px, cap = StrokeCap.Round) }
    drawPath(path, Color.White, alpha = 0.35f * alpha, style = stroke)
}

/** A glint of light riding along a ribbon. */
internal fun DrawScope.drawRibbonGlint(at: Offset, r: Ribbon, alpha: Float) {
    val g = 26.dp.toPx() * (0.6f + r.width * 0.6f)
    drawCircle(
        Brush.radialGradient(listOf(Color(0xCCFFFFFF), Color(0x3398D6FF), Color.Transparent), at, g),
        g, at, alpha = alpha,
    )
}

/** How long one ribbon's loop behind the start menu takes; it divides the clock's hour, so the loop never jumps. */
private const val Cycle = 16f
private const val SweepIn = 2.6f
private const val FadeOutAt = 13.4f
private const val FadeOutFor = 1.6f
private const val ShimmerAt = 6.8f
private const val ShimmerFor = 2.4f

/**
 * A ribbon made ready to draw every frame: its curve, and its glow drawn once at reduced size
 * ([scale]) covering only [left],[top] to [right],[bottom], the band the ribbon occupies, so each
 * frame fills that strip rather than the whole screen (small TV graphics chips are fill-bound).
 */
internal class RibbonArt(
    val ribbon: Ribbon, val path: Path, val bands: ImageBitmap, scale: Float,
    val left: Int, val top: Int, val right: Int, val bottom: Int,
) {
    val measure = PathMeasure().apply { setPath(path, false) }
    val length = measure.length
    val partial = Path()
    val dstOffset = androidx.compose.ui.unit.IntOffset(left, top)
    val dstSize = IntSize(right - left, bottom - top)
    val srcSize = IntSize(bands.width, bands.height)
    val shader = android.graphics.BitmapShader(bands.asAndroidBitmap(), android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        .apply {
            setLocalMatrix(android.graphics.Matrix().apply {
                setScale(1f / scale, 1f / scale)
                postTranslate(left.toFloat(), top.toFloat())
            })
        }
}

/**
 * Kept for the whole run, so coming back to the start menu doesn't redraw the glow:
 * that's the only slow part, done once per screen size.
 */
internal object RibbonCache {
    private var size = Size.Zero
    private var densityKey = 0f
    private var art: List<RibbonArt> = emptyList()

    /** Built once per screen size; usually on a background thread at launch (see Prewarm). */
    @Synchronized
    fun get(size: Size, density: Density): List<RibbonArt> {
        if (size == this.size && density.density == densityKey && art.isNotEmpty()) return art
        densityKey = density.density
        val w = size.width
        val h = size.height
        // Half size, and no more than 960 wide even on a TV that runs its interface at 4K: the glow
        // is soft through and through, so it scales back up cleanly and the bitmaps stay small.
        val s = minOf(0.5f, 960f / w)
        art = Ribbons.map { r ->
            val path = ribbonPath(r, w, h)
            // The strip the glow covers: the curve's bounds plus half the widest band, on screen,
            // snapped to the bitmap's pixel grid so it samples exactly as the full-screen version did.
            val pad = with(density) { 110.dp.toPx() } * r.width / 2f + 2f
            val b = path.getBounds()
            val grid = 1f / s
            val left = (kotlin.math.floor(maxOf(0f, b.left - pad) / grid) * grid).toInt()
            val top = (kotlin.math.floor(maxOf(0f, b.top - pad) / grid) * grid).toInt()
            val right = minOf(w, b.right + pad).let { kotlin.math.ceil(it / grid) * grid }.toInt()
            val bottom = minOf(h, b.bottom + pad).let { kotlin.math.ceil(it / grid) * grid }.toInt()
            val bw = ceil((right - left) * s).toInt().coerceAtLeast(1)
            val bh = ceil((bottom - top) * s).toInt().coerceAtLeast(1)
            val bitmap = ImageBitmap(bw, bh)
            CanvasDrawScope().draw(density, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bitmap), Size(bw.toFloat(), bh.toFloat())) {
                scale(s, s, pivot = Offset.Zero) { translate(-left.toFloat(), -top.toFloat()) { drawRibbonBands(path, r, 1f) } }
            }
            RibbonArt(r, path, bitmap, s, left, top, right, bottom)
        }
        this.size = size
        return art
    }

    /** Frees the glow bitmaps when memory is short; they're redrawn the next time they're needed. */
    @Synchronized
    fun clear() {
        art = emptyList()
        size = Size.Zero
    }
}

private fun easeOut(x: Float) = 1f - (1f - x) * (1f - x) * (1f - x)

/**
 * The intro's ribbons of light, carried on behind the start menu. Each one sweeps in
 * from the left with a glint at its head, sways gently, has a glint run along it now
 * and then, fades, and sweeps in again; they're staggered, so something is always
 * moving left to right. [fadeIn] (0..1) brings them in as the intro hands over.
 *
 * Cheap enough for every frame on a TV chip: each ribbon's glow is drawn once into
 * a half-size bitmap of just its strip, and after that only moved and faded. The thin bright thread and
 * the glints are the only vector drawing, and nothing recomposes: the clock is read
 * only while drawing.
 */
@Composable
fun RibbonBackdrop(modifier: Modifier = Modifier, fadeIn: () -> Float = { 1f }) {
    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        // Seconds within the hour: every period below divides 3600, so the wrap is seamless.
        while (true) withFrameNanos { clock.floatValue = (it / 1_000_000L % 3_600_000L) / 1000f }
    }
    Spacer(
        modifier.fillMaxSize().drawWithCache {
            val art = timed("Ribbon glows (main thread)") { RibbonCache.get(size, this) }
            val w = size.width
            val h = size.height
            val paint = android.graphics.Paint().apply { isFilterBitmap = true }
            onDrawBehind {
                val f = fadeIn()
                if (f <= 0f) return@onDrawBehind
                val t = clock.floatValue
                art.forEachIndexed { i, a ->
                    val r = a.ribbon
                    val p = ((t + i * (Cycle / 4f) + i * 0.9f) % Cycle + Cycle) % Cycle
                    val alpha = f * when {
                        p < SweepIn -> (p / 0.3f).coerceAtMost(1f)
                        p < FadeOutAt -> 1f
                        p < FadeOutAt + FadeOutFor -> 1f - (p - FadeOutAt) / FadeOutFor
                        else -> 0f
                    }
                    if (alpha <= 0f) return@forEachIndexed
                    val reveal = if (p < SweepIn) easeOut(p / SweepIn) else 1f
                    // A slow sway up and down; 24 s divides the hour.
                    val sway = sin((t / 24f + i * 0.27f) * 2f * PI.toFloat()) * r.wave * h * 0.22f
                    translate(top = sway) {
                        if (reveal < 1f) {
                            // Sweeping in: show the glow up to the head, fading out softly just behind it.
                            val head = a.measure.getPosition(a.length * reveal)
                            val edge = 180.dp.toPx()
                            val fade = android.graphics.LinearGradient(
                                head.x - edge, 0f, head.x, 0f,
                                android.graphics.Color.BLACK, android.graphics.Color.TRANSPARENT,
                                android.graphics.Shader.TileMode.CLAMP,
                            )
                            paint.shader = android.graphics.ComposeShader(a.shader, fade, android.graphics.PorterDuff.Mode.DST_IN)
                            paint.alpha = (alpha * 255).toInt()
                            // Only the ribbon's strip, and only up to the head: past it the glow is fully faded.
                            val until = minOf(a.right.toFloat(), head.x)
                            if (until > a.left) drawContext.canvas.nativeCanvas.drawRect(a.left.toFloat(), a.top.toFloat(), until, a.bottom.toFloat(), paint)
                            paint.shader = null
                            a.partial.reset()
                            a.measure.getSegment(0f, a.length * reveal, a.partial, true)
                            drawRibbonThread(a.partial, alpha)
                            drawRibbonGlint(head, r, alpha * (1f - reveal * 0.6f))
                        } else {
                            drawImage(a.bands, srcSize = a.srcSize, dstOffset = a.dstOffset, dstSize = a.dstSize, alpha = alpha)
                            drawRibbonThread(a.path, alpha)
                            // Now and then a glint runs along the ribbon, trailing a brighter stretch of thread.
                            val s = (p - ShimmerAt) / ShimmerFor
                            if (s in 0f..1f) {
                                val at = easeInOutSine(s)
                                val tail = (at - 0.14f).coerceAtLeast(0f)
                                a.partial.reset()
                                a.measure.getSegment(a.length * tail, a.length * at, a.partial, true)
                                val bright = alpha * sin(s * PI.toFloat())
                                drawRibbonThread(a.partial, bright * 1.8f, width = 2.4f)
                                drawRibbonGlint(a.measure.getPosition(a.length * at), r, bright * 0.85f)
                            }
                        }
                    }
                }
            }
        },
    )
}

private fun easeInOutSine(x: Float) = -(kotlin.math.cos(PI.toFloat() * x) - 1f) / 2f

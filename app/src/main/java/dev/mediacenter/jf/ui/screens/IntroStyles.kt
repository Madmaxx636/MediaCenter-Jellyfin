package dev.mediacenter.jf.ui.screens

import kotlinx.coroutines.flow.first
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.mediacenter.jf.ui.theme.Wmc
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * The startup animation's styles. Each draws its own build-up under the orb (and, for some,
 * a flash or sweep over it); the orb, the name, the flight to the corner and the hand-over to
 * the menu are shared (IntroScreen). [v] runs 0..1 over the whole intro.
 */
enum class IntroStyle(
    val key: String,
    val label: String,
    /** When the orb appears, and when the name slides in. */
    val orbFrom: Float,
    val orbTo: Float,
    val wordsFrom: Float,
) {
    Ribbons("classic", "ribbons", 0.02f, 0.34f, 0.32f),
    Swirl("swirl", "light swirl", 0.34f, 0.46f, 0.38f),
    Glass("glass", "glass", 0.34f, 0.46f, 0.38f),
    Aurora("aurora", "aurora", 0.34f, 0.46f, 0.38f),
    /** Media Center's own, beat for beat (see [WmcIntro]); it has its own timeline. */
    Wmc("wmc", "Media Center", 0f, 0f, 0f),
    ;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: Ribbons
    }
}

/**
 * Holds the intro on its opening black until the screen underneath has been built and drawn and
 * the chime has loaded (at most [maxMs] in all), so neither lands in the middle of the animation
 * on a slow TV, and the chime is never skipped for not being ready in time.
 */
internal suspend fun awaitIntroReady(app: dev.mediacenter.jf.AppState, maxMs: Long = 3_500) {
    val start = android.os.SystemClock.uptimeMillis()
    kotlinx.coroutines.delay(200)
    val menu = kotlinx.coroutines.withTimeoutOrNull(maxMs) {
        androidx.compose.runtime.snapshotFlow { app.introMenuReady }.first { it }
    } != null
    val left = maxMs - (android.os.SystemClock.uptimeMillis() - start)
    val chime = app.sounds.awaitIntro(left.coerceAtLeast(300))
    dev.mediacenter.jf.AppLog.i(
        "Intro", "Starting after ${android.os.SystemClock.uptimeMillis() - start} ms" +
            (if (menu) "" else " (menu not ready)") + (if (chime) "" else " (chime not loaded; playing without it)"),
    )
}

internal fun introPhase(t: Float, from: Float, to: Float) = ((t - from) / (to - from)).coerceIn(0f, 1f)
internal fun introEaseOut(x: Float) = 1f - (1f - x) * (1f - x) * (1f - x)
internal fun introEaseInOut(x: Float) = if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).let { it * it * it } / 2f

/** Overshoots a little and settles: a "snap" into place. */
internal fun easeOutBack(x: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val y = x - 1f
    return 1f + c3 * y * y * y + c1 * y * y
}

/** Rises fast to 1 at [peak], then falls away smoothly: a flash. */
private fun bell(x: Float, peak: Float = 0.22f): Float = when {
    x <= 0f || x >= 1f -> 0f
    x < peak -> x / peak
    else -> (1f - (x - peak) / (1f - peak)).let { it * it }
}

private val JellyPurple = Color(0xFFAA5CC3)
private val JellyBlue = Color(0xFF00A4DC)

/** Black first, then Media Center's blue swelling up behind everything. */
private fun DrawScope.introBackdrop(swell: Float, backdrop: Float) {
    drawRect(Color.Black, alpha = backdrop)
    if (swell > 0f) drawRect(Brush.verticalGradient(listOf(Wmc.BgTop, Wmc.BgMid, Wmc.BgBottom)), alpha = swell * backdrop)
}

// --- Light swirl ------------------------------------------------------------------------------

/** Four lights in the Jellyfin colours (and two companions). */
private val SwirlColors = listOf(JellyPurple, JellyBlue, Color(0xFF45E0C8), Color(0xFFE061B8))

/** Sparks thrown out when the lights fuse: angle, speed, size. */
private val Sparks = Random(7).let { r -> List(40) { Triple(r.nextFloat() * 2f * PI.toFloat(), 0.35f + r.nextFloat() * 0.65f, 1.2f + r.nextFloat() * 2.4f) } }

/**
 * Four glowing lights swirl in out of the dark on tightening spirals, each trailing a comet
 * tail, and fuse at the centre in a flash: rays, a ring and sparks burst out and the orb
 * appears where they met, like the Windows 7 boot screen.
 */
internal fun DrawScope.drawSwirlUnder(v: Float, c: Offset, backdrop: Float) {
    introBackdrop(introPhase(v, 0.30f, 0.56f), backdrop)
    val w = size.width
    val s = introPhase(v, 0.02f, 0.34f)
    val reach = hypot(w, size.height) * 0.55f
    fun pos(i: Int, at: Float): Offset {
        val k = at.pow(1.25f)
        val r = reach * (1f - k).pow(1.5f)
        val a = PI.toFloat() / 4f + i * PI.toFloat() / 2f + 2.4f * PI.toFloat() * k
        return Offset(c.x + cos(a) * r, c.y + sin(a) * r * 0.62f)
    }
    if (s < 1f) {
        val fadeIn = introPhase(v, 0.02f, 0.08f) * backdrop
        val headR = 30.dp.toPx() * (0.7f + 0.5f * s)
        SwirlColors.forEachIndexed { i, color ->
            // The tail: segments back along the path, thinning and fading.
            var prev = pos(i, s)
            val steps = 26
            for (k in 1..steps) {
                val at = s - k * 0.017f
                if (at < 0f) break
                val p = pos(i, at)
                val frac = 1f - k / steps.toFloat()
                drawLine(color, prev, p, strokeWidth = 9.dp.toPx() * frac + 1f, cap = StrokeCap.Round, alpha = 0.55f * frac * frac * fadeIn)
                prev = p
            }
            val head = pos(i, s)
            drawCircle(
                Brush.radialGradient(listOf(Color.White, color, color.copy(alpha = 0.35f), Color.Transparent), head, headR),
                headR, head, alpha = fadeIn,
            )
        }
    }
    // Rays, turning slowly as they fade.
    val rays = bell(introPhase(v, 0.33f, 0.66f), 0.15f) * backdrop
    if (rays > 0f) {
        val len = w * 0.45f * (0.6f + 0.4f * introPhase(v, 0.33f, 0.66f))
        for (j in 0 until 14) {
            val a = j * 2f * PI.toFloat() / 14f + v * 0.8f
            val end = Offset(c.x + cos(a) * len, c.y + sin(a) * len)
            drawLine(
                Brush.linearGradient(listOf(Color(0x99DFF3FF), Color.Transparent), c, end),
                c, end, strokeWidth = 3.dp.toPx() * (1.6f - (j % 3) * 0.45f), alpha = rays,
            )
        }
    }
    // The shock ring.
    val ring = introPhase(v, 0.34f, 0.62f)
    if (ring in 0.001f..0.999f) {
        drawCircle(
            Color(0xFFBFE6FF), radius = size.height * (0.06f + 0.6f * introEaseOut(ring)), center = c,
            alpha = (1f - ring) * 0.55f * backdrop, style = Stroke(width = 4f + 26f * (1f - ring)),
        )
    }
    // Sparks.
    val t = introPhase(v, 0.34f, 0.72f)
    if (t in 0.001f..0.999f) {
        val colors = SwirlColors
        Sparks.forEachIndexed { i, (a, speed, r) ->
            val d = w * 0.34f * speed * introEaseOut(t)
            drawCircle(colors[i % colors.size].copy(alpha = 1f), r.dp.toPx(), Offset(c.x + cos(a) * d, c.y + sin(a) * d * 0.8f), alpha = (1f - t) * backdrop)
        }
    }
}

/** The flash where the lights fuse, washing over the orb as it appears. */
internal fun DrawScope.drawSwirlOver(v: Float, c: Offset) {
    val x = introPhase(v, 0.31f, 0.54f)
    val f = bell(x, 0.18f)
    if (f <= 0f) return
    val r = size.width * 0.5f * (0.35f + 0.65f * x)
    drawCircle(
        Brush.radialGradient(listOf(Color.White, Color(0xCC9FD8FF), Color(0x3350A8F0), Color.Transparent), c, r),
        r, c, alpha = f,
    )
}

// --- Glass ------------------------------------------------------------------------------------

/** One shard of the glass tile: its outline, where it flies in from, and its spin and timing. */
internal class Shard(val path: Path, val centroid: Offset, val from: Offset, val spin: Float, val delay: Float)

/** The glass tile the shards assemble into, around the orb at [center], with its brushes made once. */
internal class GlassTile(center: Offset, density: Density) {
    val rect: Rect
    val radius: CornerRadius
    val outline: Path
    val shards: List<Shard>
    private val base = Color(0xD00A2350)
    private val body: Brush
    private val gloss: Path
    private val glossBrush: Brush
    private val bottomGlow: Brush
    private val streaks: Brush

    init {
        val side = with(density) { 250.dp.toPx() }
        rect = Rect(center.x - side / 2f, center.y - side / 2f, center.x + side / 2f, center.y + side / 2f)
        radius = CornerRadius(with(density) { 30.dp.toPx() })
        outline = Path().apply { addRoundRect(RoundRect(rect, radius)) }
        body = Brush.verticalGradient(0f to Color(0x664FA8F0), 0.5f to Color(0x2230A0FF), 1f to Color(0x5560B8FF), startY = rect.top, endY = rect.bottom)
        gloss = Path().apply {
            moveTo(rect.left, rect.top); lineTo(rect.right, rect.top); lineTo(rect.right, rect.top + side * 0.40f)
            quadraticTo(rect.center.x, rect.top + side * 0.56f, rect.left, rect.top + side * 0.40f); close()
        }
        glossBrush = Brush.verticalGradient(listOf(Color(0x66FFFFFF), Color(0x10FFFFFF)), startY = rect.top, endY = rect.top + side * 0.5f)
        bottomGlow = Brush.radialGradient(listOf(Color(0x6660B8FF), Color.Transparent), Offset(rect.center.x, rect.bottom), side * 0.7f)
        streaks = Brush.linearGradient(
            0f to Color.Transparent, 0.32f to Color.Transparent, 0.36f to Color(0x16FFFFFF), 0.42f to Color.Transparent,
            0.55f to Color.Transparent, 0.58f to Color(0x10FFFFFF), 0.61f to Color.Transparent, 1f to Color.Transparent,
            start = rect.topLeft, end = Offset(rect.right, rect.bottom + side * 0.4f),
        )

        // Break the tile into wedges from a point just off centre, cut at jittered spots around the edge.
        val r = Random(11)
        val hub = Offset(rect.left + side * 0.44f, rect.top + side * 0.47f)
        val count = 10
        val cuts = List(count) { (it + 0.2f + r.nextFloat() * 0.6f) * 4f / count }
        fun edge(u: Float): Offset {
            val side4 = ((u % 4f) + 4f) % 4f
            val f = side4 - side4.toInt()
            return when (side4.toInt()) {
                0 -> Offset(rect.left + f * side, rect.top)
                1 -> Offset(rect.right, rect.top + f * side)
                2 -> Offset(rect.right - f * side, rect.bottom)
                else -> Offset(rect.left, rect.bottom - f * side)
            }
        }
        val travel = with(density) { 900.dp.toPx() }
        shards = List(count) { i ->
            val u0 = cuts[i]
            val u1 = if (i + 1 < count) cuts[i + 1] else cuts[0] + 4f
            val wedge = Path().apply {
                moveTo(hub.x, hub.y)
                val a = edge(u0); lineTo(a.x, a.y)
                // The tile's corners that fall between this wedge's two cuts.
                var k = kotlin.math.floor(u0).toInt() + 1
                while (k < u1) { val p = edge(k.toFloat()); lineTo(p.x, p.y); k++ }
                val b = edge(u1); lineTo(b.x, b.y)
                close()
            }
            val piece = Path().apply { op(wedge, outline, PathOperation.Intersect) }
            val mid = edge((u0 + u1) / 2f)
            val centroid = Offset((hub.x + edge(u0).x + edge(u1).x + mid.x) / 4f, (hub.y + edge(u0).y + edge(u1).y + mid.y) / 4f)
            val angle = atan2(centroid.y - hub.y, centroid.x - hub.x)
            Shard(
                piece, centroid,
                from = Offset(cos(angle) * travel, sin(angle) * travel * 0.7f),
                spin = (if (i % 2 == 0) 1f else -1f) * (30f + r.nextFloat() * 50f),
                delay = 0.03f + (i % 5) * 0.028f + (i / 5) * 0.012f,
            )
        }
    }

    /** The whole tile's glass, drawn inside whatever clip is active. */
    fun DrawScope.fill() {
        drawRoundRect(base, rect.topLeft, rect.size, radius)
        drawRoundRect(body, rect.topLeft, rect.size, radius)
        drawRect(bottomGlow, rect.topLeft, rect.size)
        drawRect(streaks, rect.topLeft, rect.size)
        drawPath(gloss, glossBrush)
    }
}

/**
 * Aero glass shards fly in from every side, spinning, and snap together into a glass tile
 * (the seams flash and vanish); the orb appears inside and a band of light sweeps across
 * the glass. The tile then melts away as the orb flies to its corner.
 */
internal fun DrawScope.drawGlassUnder(v: Float, tile: GlassTile, backdrop: Float) {
    introBackdrop(introPhase(v, 0.0f, 0.34f), backdrop)
    val fade = (1f - introPhase(v, 0.62f, 0.74f)) * backdrop
    if (fade <= 0f) return
    val shrink = 1f - 0.15f * introPhase(v, 0.62f, 0.74f)
    val c = tile.rect.center
    // A soft pool of light under the tile, like the glow a WMC tile casts.
    drawCircle(
        Brush.radialGradient(listOf(Color(0x5530A0FF), Color.Transparent), c, tile.rect.width * 1.1f),
        tile.rect.width * 1.1f, c, alpha = introPhase(v, 0.2f, 0.4f) * fade,
    )
    val seams = 1f - introPhase(v, 0.37f, 0.50f)
    withTransform({ scale(shrink, shrink, c) }) {
        for (shard in tile.shards) {
            val t = introPhase(v, shard.delay, shard.delay + 0.20f)
            if (t <= 0f) continue
            val e = easeOutBack(t)
            val away = 1f - e
            val alpha = introPhase(v, shard.delay, shard.delay + 0.05f) * fade
            withTransform({
                translate(shard.from.x * away, shard.from.y * away)
                rotate(shard.spin * away, shard.centroid)
                scale(1f + 0.3f * away.coerceAtLeast(0f), 1f + 0.3f * away.coerceAtLeast(0f), shard.centroid)
            }) {
                clipPath(shard.path) { with(tile) { fill() } }
                if (seams > 0f) drawPath(shard.path, Color.White, alpha = 0.6f * seams * alpha, style = Stroke(1.4.dp.toPx()))
            }
        }
        if (v > 0.33f) {
            // The two-tone Aero edge once assembled, and the flash as it snaps together.
            val edge = introPhase(v, 0.33f, 0.40f) * fade
            drawRoundRect(Color(0xB0000814), tile.rect.topLeft, tile.rect.size, tile.radius, style = Stroke(1.5.dp.toPx()), alpha = edge)
            val inset = 1.5.dp.toPx()
            drawRoundRect(
                Color(0x99E6F4FF), Offset(tile.rect.left + inset, tile.rect.top + inset),
                Size(tile.rect.width - inset * 2, tile.rect.height - inset * 2),
                CornerRadius(tile.radius.x - inset), style = Stroke(1.2.dp.toPx()), alpha = edge,
            )
            val snap = bell(introPhase(v, 0.33f, 0.46f), 0.15f)
            if (snap > 0f) drawPath(tile.outline, Color.White, alpha = 0.55f * snap * fade)
        }
    }
}

/** The band of light sweeping across the glass (and the orb in it). */
internal fun DrawScope.drawGlassOver(v: Float, tile: GlassTile, backdrop: Float) {
    val p = introPhase(v, 0.42f, 0.62f)
    if (p <= 0f || p >= 1f) return
    val r = tile.rect
    val x = r.left - r.width * 0.6f + r.width * 2.2f * introEaseInOut(p)
    val band = r.width * 0.28f
    clipPath(tile.outline) {
        drawRect(
            Brush.linearGradient(
                listOf(Color.Transparent, Color(0x66FFFFFF), Color.Transparent),
                start = Offset(x - band, r.top), end = Offset(x + band, r.bottom),
            ),
            r.topLeft, r.size, alpha = backdrop,
        )
    }
}

// --- Aurora -----------------------------------------------------------------------------------

/** One curtain of the aurora: height, sway, length, colours, pace. */
private class Curtain(val y: Float, val amp: Float, val freq: Float, val len: Float, val speed: Float, val phase: Float, val top: Color, val low: Color, val delay: Float)

private val Curtains = listOf(
    Curtain(0.12f, 0.05f, 1.3f, 0.30f, 0.9f, 0.0f, Color(0xFF3DF5B0), Color(0xFF20C8E0), 0.00f),
    Curtain(0.20f, 0.045f, 1.8f, 0.24f, -1.2f, 1.7f, Color(0xFF5A9CFF), JellyPurple, 0.05f),
    Curtain(0.07f, 0.04f, 2.4f, 0.22f, 1.5f, 3.1f, Color(0xFF45E0C8), JellyBlue, 0.10f),
)

/** Stars: position, size, twinkle phase. */
private val Stars = Random(3).let { r -> List(90) { floatArrayOf(r.nextFloat(), r.nextFloat() * 0.75f, 0.6f + r.nextFloat() * 1.4f, r.nextFloat() * 6.28f) } }

/**
 * Curtains of aurora light (green, teal, blue and violet) ripple across a starry night sky;
 * they brighten, a sunrise of light blooms at the centre with a lens-flare streak, and the orb
 * rises out of it. The sky then gives way to the menu.
 */
internal fun DrawScope.drawAuroraUnder(v: Float, c: Offset, backdrop: Float) {
    val w = size.width
    val h = size.height
    drawRect(Color.Black, alpha = backdrop)
    val sky = introPhase(v, 0f, 0.2f) * backdrop
    drawRect(Brush.verticalGradient(listOf(Color(0xFF01040E), Color(0xFF041634), Color(0xFF082A5A))), alpha = sky)
    // Stars, fading as the light grows.
    val starAlpha = sky * (1f - 0.7f * introPhase(v, 0.30f, 0.45f))
    if (starAlpha > 0f) for (s in Stars) {
        val tw = 0.55f + 0.45f * sin(v * 18f + s[3])
        drawCircle(Color.White, s[2].dp.toPx() * 0.6f, Offset(s[0] * w, s[1] * h), alpha = starAlpha * tw * 0.8f)
    }
    val time = v * 5f
    val boost = 1f + 0.6f * introPhase(v, 0.22f, 0.34f)
    val band = Path()
    for (cur in Curtains) {
        val a = introPhase(v, cur.delay, cur.delay + 0.26f) * boost * (1f - introPhase(v, 0.66f, 0.92f)) * backdrop
        if (a <= 0f) continue
        band.reset()
        val n = 48
        val tops = FloatArray(n + 1)
        for (k in 0..n) {
            val x = -0.1f * w + 1.2f * w * k / n
            val fx = x / w
            tops[k] = h * (cur.y + cur.amp * sin(fx * cur.freq * 6.283f + time * cur.speed + cur.phase) + 0.02f * sin(fx * 9f - time * 1.7f))
            if (k == 0) band.moveTo(x, tops[k]) else band.lineTo(x, tops[k])
        }
        for (k in n downTo 0) {
            val x = -0.1f * w + 1.2f * w * k / n
            val fx = x / w
            val len = h * (cur.len + 0.08f * sin(fx * 5.1f + time * 0.8f + cur.phase))
            band.lineTo(x, tops[k] + len)
        }
        band.close()
        val top = h * (cur.y - cur.amp)
        drawPath(
            band,
            Brush.verticalGradient(
                0f to cur.top.copy(alpha = 0f), 0.02f to cur.top.copy(alpha = 0.50f), 0.22f to cur.low.copy(alpha = 0.20f),
                0.6f to cur.low.copy(alpha = 0.05f), 1f to Color.Transparent,
                startY = top, endY = top + h * (cur.len + cur.amp * 2f + 0.06f),
            ),
            alpha = (a * 0.75f).coerceAtMost(1f),
        )
        // The vertical ray structure: three faint sets of stripes at unrelated spacings, drifting at
        // different speeds, so they add up to irregular rays rather than an even pattern.
        for ((period, drift) in listOf(19f to 26f, 31f to -15f, 53f to 9f)) {
            val shift = time * drift * cur.speed
            drawPath(
                band,
                Brush.linearGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.045f), Color.Transparent),
                    start = Offset(shift, 0f), end = Offset(shift + period.dp.toPx(), 0f), tileMode = TileMode.Repeated,
                ),
                alpha = (a * 0.8f).coerceAtMost(1f),
            )
        }
    }
    // The lens-flare streak across the screen as the light blooms.
    val flare = bell(introPhase(v, 0.30f, 0.54f), 0.25f) * backdrop
    if (flare > 0f) {
        val streak = Brush.horizontalGradient(listOf(Color.Transparent, Color(0x88BFF4FF), Color.White, Color(0x88BFF4FF), Color.Transparent), startX = c.x - w * 0.55f, endX = c.x + w * 0.55f)
        drawRect(streak, Offset(0f, c.y - 1.5.dp.toPx()), Size(w, 3.dp.toPx()), alpha = flare)
        drawRect(streak, Offset(0f, c.y - 22.dp.toPx()), Size(w, 44.dp.toPx()), alpha = flare * 0.14f)
    }
}

/** The sunrise of light the orb rises out of. */
internal fun DrawScope.drawAuroraOver(v: Float, c: Offset) {
    val x = introPhase(v, 0.30f, 0.58f)
    val b = bell(x, 0.3f)
    if (b <= 0f) return
    val r = size.width * 0.38f * (0.45f + 0.55f * x)
    drawCircle(
        Brush.radialGradient(listOf(Color.White, Color(0xAA9FFFE0), Color(0x3345C8E0), Color.Transparent), c, r),
        r, c, alpha = b * 0.9f,
    )
}

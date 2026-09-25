package dev.mediacenter.jf.ui.screens

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mediacenter.jf.LocalAppState
import dev.mediacenter.jf.ui.components.LogoOrb
import dev.mediacenter.jf.ui.components.WText
import dev.mediacenter.jf.ui.components.tryFocus
import dev.mediacenter.jf.ui.theme.Wmc
import dev.mediacenter.jf.ui.theme.WmcType
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** The whole intro, in seconds, as Windows 7 Media Center's own: the menu has settled by 2.95 s. */
private const val WmcLength = 2.95f

/** When the intro starts handing over to the menu underneath. */
private const val HandoffAt = 2.48f

/**
 * The startup animation after Windows 7 Media Center's own, beat for beat, with the Jellyfin
 * orb in place of Windows': black while the chime starts, then a cut to the blue with the
 * logo huge and out of focus, zooming back into focus with diagonal streaks of light behind
 * it; it holds as a glint travels over the orb and a lens flare's ghost drifts past, then
 * dissolves where it stands while the start menu settles in from slightly too large and the
 * corner logo fades in. The chime carries on over the menu, as it did in Media Center.
 *
 * Built to run smoothly on a small TV chip: it composes once, and each frame only moves layers
 * and redraws the few things that change (the streaks, the glint, the flare). The blue is the
 * app's own cached backdrop showing through, with the menu above it not drawn until the hand-over;
 * the blur and zoom work on a layer just large enough for the logo and name.
 */
@Composable
fun WmcIntro(onDone: () -> Unit) {
    val app = LocalAppState.current
    val t = remember { Animatable(0f) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        app.introZoomMenu = true
        focus.tryFocus()
        // Black, as Media Center began, until the menu underneath is built and the chime has loaded.
        awaitIntroReady(app)
        app.introCovers = true
        app.sounds.intro()
        t.animateTo(WmcLength, tween((WmcLength * 1000).toInt(), easing = LinearEasing)) {
            if (!app.introShown) {
                app.introCovers = value < HandoffAt
                app.introHandoff = introPhase(value, HandoffAt, WmcLength)
                app.introLogoAlpha = introPhase(value, 2.70f, WmcLength)
            }
        }
        onDone()
    }

    BoxWithConstraints(Modifier.fillMaxSize().focusRequester(focus).focusable()) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        // Sized to the screen as Media Center's was: the orb a fifth of its width, the name beneath.
        val orbCenter = Offset(w / 2f, h * 0.45f)
        val orbPx = w * 0.2f
        val orbDp = with(density) { orbPx.toDp() }
        val namePx = h * 0.085f
        // The logo group's layer: the orb's glow, the orb and the name, with room around them for the blur.
        val group = androidx.compose.ui.geometry.Rect(w * 0.17f, h * 0.07f, w * 0.83f, h * 0.83f)
        val local = orbCenter - group.topLeft

        // For the hand-over, the menu's own blue (cached) laid over the menu, fading away to reveal it.
        // Until then the same blue shows through from the app's backdrop, and nothing is drawn here.
        dev.mediacenter.jf.ui.theme.CachedBackdrop(
            Modifier.graphicsLayer {
                val s = t.value
                alpha = if (s < HandoffAt) 0f else 1f - introPhase(s, HandoffAt, WmcLength)
                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
            },
        )
        // Black while the chime starts, then straight to the blue, as Media Center cut.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - introPhase(t.value, 0.28f, 0.34f) }.background(Color.Black))
        // The streaks of light across the blue and the lens flare's ghost. Their gradients and the flare's
        // ring are made once, on the opening black; each frame only moves them into place.
        androidx.compose.foundation.layout.Spacer(
            Modifier.fillMaxSize().drawWithCache {
                val art = IntroArt(size, this)
                onDrawBehind {
                    val s = t.value
                    val appear = introPhase(s, 0.28f, 0.34f)
                    if (appear <= 0f || s >= 2.6f) return@onDrawBehind
                    val out = 1f - introPhase(s, HandoffAt, WmcLength)
                    drawStreaks(art, s, appear * out * (1f - introPhase(s, 1.9f, 2.6f)))
                    drawFlareRing(art, s, appear)
                }
            },
        )
        // The logo and name, as one group so they zoom, blur and dissolve together.
        Box(
            Modifier
                .offset { IntOffset(group.left.roundToInt(), group.top.roundToInt()) }
                .size(with(density) { group.width.toDp() }, with(density) { group.height.toDp() })
                .graphicsLayer {
                    val s = t.value
                    // Cut in at 0.3 s at twice its size, zooming back into focus by about 0.85 s (steadily,
                    // easing only at the end, as Media Center's does), then drifting a quarter smaller.
                    val appear = introPhase(s, 0.28f, 0.34f)
                    val zoom = 1f - Math.pow((1f - introPhase(s, 0.30f, 0.86f)).toDouble(), 1.35).toFloat()
                    val scale = (2.05f - 1.05f * zoom) * (1f - 0.165f * (s - 0.95f).coerceAtLeast(0f))
                    val blur = 18f * (1f - introPhase(s, 0.30f, 0.76f))
                    val dissolve = 1f - introPhase(s, 2.12f, 2.55f).let { it * it }
                    // Drawn all but invisibly for a moment while the screen is still black, so the blur and
                    // text are ready (not prepared on the spot, which stutters on TV chips) when the logo cuts in.
                    alpha = if (s in 0.10f..0.20f) 0.004f else appear * dissolve
                    scaleX = scale; scaleY = scale
                    transformOrigin = TransformOrigin((w * 0.5f - group.left) / group.width, (h * 0.52f - group.top) / group.height)
                    renderEffect = if (Build.VERSION.SDK_INT >= 31 && blur > 0.5f && alpha > 0f) BlurEffect(blur.dp.toPx(), blur.dp.toPx(), TileMode.Decal) else null
                },
        ) {
            Canvas(Modifier.fillMaxSize()) { drawOrbHalo(local, orbPx) }
            LogoOrb(
                Modifier.offset { IntOffset((local.x - orbPx / 2f).roundToInt(), (local.y - orbPx / 2f).roundToInt()) },
                size = orbDp,
            )
            androidx.compose.foundation.layout.Spacer(
                Modifier.fillMaxSize().drawWithCache {
                    val glow = 16.dp.toPx()
                    val brush = Brush.radialGradient(listOf(Color.White, Color(0x88CDEBFF), Color.Transparent), Offset.Zero, glow)
                    onDrawBehind { drawGlint(t.value, local, orbPx / 2f, brush, glow) }
                },
            )
            Row(
                Modifier.align(Alignment.TopCenter).offset { IntOffset(0, (h * 0.715f - namePx * 0.75f - group.top).roundToInt()) },
                verticalAlignment = Alignment.Bottom,
            ) {
                val size = with(density) { namePx.toSp() }
                WText("Media Center", WmcType.Hero.copy(fontSize = size))
                WText(" for Jellyfin", WmcType.Hero.copy(fontSize = size * 0.72f, fontWeight = androidx.compose.ui.text.font.FontWeight.Light), color = Wmc.TextDim)
            }
        }
    }
}

/** Streaks: horizontal position, width, brightness, drift. */
private val Streaks = Random(21).let { r -> List(9) { floatArrayOf(0.25f + r.nextFloat() * 0.85f, 0.6f + r.nextFloat() * 2.2f, 0.10f + r.nextFloat() * 0.22f, (r.nextFloat() - 0.5f) * 0.06f) } }

/**
 * What the streaks and the flare need, made once when the intro is first drawn (on its opening
 * black, before anything moves): each streak's gradient laid out from its own top, so a frame
 * only has to slide it into place, the wisp's glow, and the flare's ring drawn into a picture.
 */
private class IntroArt(size: Size, density: androidx.compose.ui.unit.Density) {
    val w = size.width
    val h = size.height
    val streakTop = Offset(0f, -h * 0.05f)
    val streakBottom = Offset(-h * 0.42f, h * 1.05f)
    val streakBrush = Brush.linearGradient(
        0f to Color.Transparent, 0.25f to Color(0xFFBFE4FF), 0.7f to Color(0xFF8FC8FF), 1f to Color.Transparent,
        start = streakTop, end = streakBottom,
    )
    val streakWidths = Streaks.map { with(density) { it[1].dp.toPx() } }
    val wispAt = Offset(w * 0.18f, h * 0.95f)
    val wisp = Brush.radialGradient(listOf(Color(0x3380C8FF), Color.Transparent), wispAt, w * 0.35f)
    val ringRadius = h * 0.22f
    val ringHalf = ringRadius * 1.1f
    val ring = dev.mediacenter.jf.ui.theme.renderBitmap(Size(ringHalf * 2f, ringHalf * 2f), density) { drawRing(Offset(ringHalf, ringHalf), ringRadius) }
}

/** The fine diagonal streaks of light across the blue, drifting, as behind Media Center's logo. */
private fun DrawScope.drawStreaks(art: IntroArt, s: Float, alpha: Float) {
    if (alpha <= 0f) return
    Streaks.forEachIndexed { i, st ->
        val pulse = 0.75f + 0.25f * sin(s * 2.2f + st[0] * 9f)
        translate(left = art.w * (st[0] + st[3] * s)) {
            // A soft glow either side, then the fine bright line.
            drawLine(art.streakBrush, art.streakTop, art.streakBottom, strokeWidth = art.streakWidths[i] * 7f, alpha = alpha * st[2] * 0.18f * pulse, cap = StrokeCap.Round)
            drawLine(art.streakBrush, art.streakTop, art.streakBottom, strokeWidth = art.streakWidths[i], alpha = alpha * st[2] * pulse, cap = StrokeCap.Round)
        }
    }
    // Soft wisps of light low on the left, as in Media Center's opening.
    drawCircle(art.wisp, art.w * 0.35f, art.wispAt, alpha = alpha)
}

/** A soft white-blue glow around the orb. */
private fun DrawScope.drawOrbHalo(c: Offset, orbPx: Float) {
    val r = orbPx * 0.95f
    drawCircle(Brush.radialGradient(listOf(Color(0x5590D0FF), Color(0x1860A8F0), Color.Transparent), c, r), r, c)
}

/** The glint of light that travels over the top of the orb while it holds. */
private fun DrawScope.drawGlint(s: Float, c: Offset, radius: Float, glowBrush: Brush, glow: Float) {
    val p = introPhase(s, 0.95f, 2.2f)
    if (p <= 0f || p >= 1f) return
    val a = (sin(p * PI.toFloat()) * 1.6f).coerceAtMost(1f)
    val angle = (-105f + 55f * introEaseInOut(p)) * PI.toFloat() / 180f
    translate(c.x + cos(angle) * radius * 0.93f, c.y + sin(angle) * radius * 0.93f) {
        drawCircle(glowBrush, glow, Offset.Zero, alpha = a)
        // The four-pointed sparkle.
        val arm = 13.dp.toPx() * (0.6f + 0.4f * a)
        drawLine(Color.White, Offset(-arm, 0f), Offset(arm, 0f), strokeWidth = 1.2.dp.toPx(), alpha = a * 0.9f, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(0f, -arm), Offset(0f, arm), strokeWidth = 1.2.dp.toPx(), alpha = a * 0.9f, cap = StrokeCap.Round)
    }
}

/**
 * The lens flare's ghost, as in Media Center's opening: a soft ring of rainbow light (red on
 * the outside, violet within) left of the logo while the glint crosses it, brightest on its
 * lower left and fading out round to the right, drifting up a little as it fades.
 */
private fun DrawScope.drawFlareRing(art: IntroArt, s: Float, alpha: Float) {
    val p = introPhase(s, 0.95f, 1.78f)
    val ring = art.ring
    if (p <= 0f || p >= 1f || alpha <= 0f || ring == null) return
    val a = alpha * sin(p * PI.toFloat()).let { it * (2f - it) }
    val c = Offset(size.width * (0.2f - 0.012f * p), size.height * (0.58f - 0.11f * p))
    drawImage(ring, Offset(c.x - art.ringHalf, c.y - art.ringHalf), alpha = a)
}

/** The ring itself, centred on [c]: drawn once into a picture, faded round to the right, as Media Center's was. */
private fun DrawScope.drawRing(c: Offset, r: Float) {
    val ring = Brush.radialGradient(
        0f to Color.Transparent,
        0.80f to Color.Transparent,
        0.86f to Color(0x307A5CFF),
        0.90f to Color(0x503C8CFF),
        0.93f to Color(0x4838D8C8),
        0.955f to Color(0x44D8E870),
        0.975f to Color(0x40FF9A5A),
        0.99f to Color(0x20FF6A6A),
        1f to Color.Transparent,
        center = c, radius = r * 1.08f,
    )
    val bounds = androidx.compose.ui.geometry.Rect(c, r * 1.1f)
    drawContext.canvas.saveLayer(bounds, androidx.compose.ui.graphics.Paint())
    drawCircle(ring, r * 1.08f, c)
    // A faint body of light across the ring, whiter than its edges.
    drawCircle(Color(0xFFCFE6FF), r * 0.93f, c, alpha = 0.05f, style = Stroke(r * 0.08f))
    drawRect(
        Brush.linearGradient(
            0f to Color.Black, 0.45f to Color.Black.copy(alpha = 0.8f), 0.8f to Color.Transparent,
            start = Offset(c.x - r, c.y + r * 0.55f), end = Offset(c.x + r * 0.7f, c.y - r * 0.6f),
        ),
        topLeft = bounds.topLeft, size = bounds.size, blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
    )
    drawContext.canvas.restore()
}

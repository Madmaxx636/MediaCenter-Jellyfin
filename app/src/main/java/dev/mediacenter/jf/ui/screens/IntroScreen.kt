package dev.mediacenter.jf.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mediacenter.jf.LocalAppState
import dev.mediacenter.jf.ui.components.LogoOrb
import dev.mediacenter.jf.ui.components.ScreenPadH
import dev.mediacenter.jf.ui.components.ScreenPadTop
import dev.mediacenter.jf.ui.components.WText
import dev.mediacenter.jf.ui.components.tryFocus
import dev.mediacenter.jf.ui.theme.Ribbons
import dev.mediacenter.jf.ui.theme.Wmc
import dev.mediacenter.jf.ui.theme.drawRibbonBands
import dev.mediacenter.jf.ui.theme.drawRibbonGlint
import dev.mediacenter.jf.ui.theme.drawRibbonThread
import dev.mediacenter.jf.ui.theme.ribbonPath
import dev.mediacenter.jf.ui.theme.WmcType
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

private fun phase(t: Float, from: Float, to: Float) = ((t - from) / (to - from)).coerceIn(0f, 1f)
private fun easeOut(x: Float) = 1f - (1f - x) * (1f - x) * (1f - x)
private fun easeInOut(x: Float) = if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).let { it * it * it } / 2f
private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** The intro orb's size while it's centre stage. */
internal val OrbSize = 170.dp

/**
 * The startup animation, after Media Center's. The blue swells out of the dark with
 * ribbons of light sweeping across it, the orb bursts in with a chime and the name
 * beside it, then the orb glides up into the corner logo while the start menu fades
 * in underneath and slides in from the right. It plays over the app, so the menu is
 * already loaded by the time it's revealed. Any key skips it.
 */
@Composable
fun IntroScreen(onDone: () -> Unit) {
    val app = LocalAppState.current
    if (remember { IntroStyle.of(app.settings.introStyle.value) } == IntroStyle.Wmc) {
        WmcIntro(onDone)
        return
    }
    val t = remember { Animatable(0f) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focus.tryFocus()
        awaitIntroReady(app)
        app.sounds.intro()
        // Stretched over the chime (about 5.4 s).
        t.animateTo(1f, tween(5200, easing = LinearEasing)) {
            // Once skipped, a last frame of this animation mustn't hide the menu again.
            if (!app.introShown) app.introHandoff = phase(value, 0.72f, 1f)
        }
        onDone()
    }
    val v = t.value
    val style = remember { IntroStyle.of(app.settings.introStyle.value) }
    val orbT = phase(v, style.orbFrom, style.orbTo)
    // The new styles pop the orb in with a little overshoot; the classic one swells it in.
    val orbIn = if (style == IntroStyle.Ribbons) easeOut(orbT) else easeOutBack(orbT)
    val orbAlpha = if (style == IntroStyle.Ribbons) orbIn else phase(v, style.orbFrom, style.orbFrom + 0.05f)
    val burst = phase(v, 0.10f, 0.58f)
    val sweep = phase(v, 0.26f, 0.66f)
    val wordsIn = easeOut(phase(v, style.wordsFrom, style.wordsFrom + 0.24f))
    val words = wordsIn * (1f - phase(v, 0.60f, 0.68f))
    val fly = easeInOut(phase(v, 0.64f, 0.88f))
    // The intro's own backdrop fades away to reveal the menu underneath as the orb flies.
    val backdrop = 1f - phase(v, 0.68f, 0.90f)
    val ribbonsOut = 1f - phase(v, 0.76f, 1f)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable(),
    ) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val orbPx = with(density) { OrbSize.toPx() }
        // Centre stage: the orb left of centre with the name beside it.
        val start = Offset(w / 2f - with(density) { 200.dp.toPx() }, h / 2f)
        // The corner logo on the screen underneath (or where it normally sits).
        val logo = app.logoBounds
        val end = logo?.center ?: with(density) { Offset((ScreenPadH - 12.dp + 20.dp).toPx(), (ScreenPadTop + 20.dp).toPx()) }
        val endSize = logo?.width ?: with(density) { 40.dp.toPx() }
        val orbCenter = Offset(lerp(start.x, end.x, fly), lerp(start.y, end.y, fly))
        val orbScale = lerp(1f, endSize / orbPx, fly)

        val glass = remember(w, h) { if (style == IntroStyle.Glass) GlassTile(start, density) else null }
        if (style != IntroStyle.Ribbons) {
            Canvas(Modifier.fillMaxSize()) {
                when (style) {
                    IntroStyle.Swirl -> drawSwirlUnder(v, start, backdrop)
                    IntroStyle.Glass -> drawGlassUnder(v, glass!!, backdrop)
                    else -> drawAuroraUnder(v, start, backdrop)
                }
            }
        } else Canvas(Modifier.fillMaxSize()) {
            // Black, then deep blue swelling up out of it.
            drawRect(Color.Black, alpha = backdrop)
            drawRect(Brush.verticalGradient(listOf(Wmc.BgTop, Wmc.BgMid, Wmc.BgBottom)), alpha = phase(v, 0f, 0.5f) * backdrop)
            drawRibbons(v, ribbonsOut)
            // A soft bloom around the orb as it bursts in; it travels with the orb and fades as it lands.
            val bloom = size.width * (0.2f + 0.5f * burst) * lerp(1f, 0.25f, fly)
            drawCircle(
                Brush.radialGradient(listOf(Color(0x9060B8FF), Color(0x2030A0FF), Color.Transparent), orbCenter, bloom),
                bloom, orbCenter, alpha = ((1f - burst) * 0.9f + 0.25f) * (1f - fly * 0.85f) * phase(v, 0.02f, 0.12f),
            )
            // An expanding ring of light behind the orb.
            if (burst in 0.01f..0.99f) {
                drawCircle(
                    Color(0xFFBFE6FF), radius = size.height * (0.08f + 0.55f * burst), center = start,
                    alpha = (1f - burst) * 0.5f,
                    style = Stroke(width = 6f + 30f * (1f - burst)),
                )
            }
            // A diagonal glint that crosses the screen.
            if (sweep in 0.01f..0.99f) {
                val x = -size.width * 0.3f + size.width * 1.6f * sweep
                drawRect(
                    Brush.linearGradient(
                        listOf(Color.Transparent, Color(0x55DDF1FF), Color.Transparent),
                        start = Offset(x - 160f, 0f), end = Offset(x + 160f, size.height * 0.4f),
                    ),
                    alpha = backdrop,
                )
            }
        }
        LogoOrb(
            Modifier
                .offset { IntOffset((orbCenter.x - orbPx / 2f).roundToInt(), (orbCenter.y - orbPx / 2f).roundToInt()) }
                .graphicsLayer {
                    val s = (if (style == IntroStyle.Ribbons) 0.5f + 0.5f * orbIn else 0.3f + 0.7f * orbIn) * orbScale
                    scaleX = s; scaleY = s
                    alpha = orbAlpha
                },
            size = OrbSize,
        )
        // Flashes and sweeps that pass over the orb.
        if (style != IntroStyle.Ribbons) Canvas(Modifier.fillMaxSize()) {
            when (style) {
                IntroStyle.Swirl -> drawSwirlOver(v, start)
                IntroStyle.Glass -> drawGlassOver(v, glass!!, backdrop)
                else -> drawAuroraOver(v, start)
            }
        }
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset((start.x + orbPx / 2f + 34.dp.toPx()).roundToInt(), 0) }
                .graphicsLayer { alpha = words; translationX = (1f - wordsIn) * 60f },
        ) {
            WText("Media Center", WmcType.Hero.copy(fontSize = 64.sp))
            WText("for Jellyfin", WmcType.Heading, color = Wmc.TextDim)
        }
    }
}

/**
 * Media Center's flowing ribbons of light: broad soft bands with a bright thread
 * through each, sweeping in from the left across the lower screen and drifting.
 * The same ribbons carry on behind the start menu afterwards ([RibbonBackdrop]).
 */
private fun DrawScope.drawRibbons(v: Float, fade: Float) {
    if (fade <= 0f) return
    val w = size.width
    val h = size.height
    val measure = PathMeasure()
    val path = Path()
    val part = Path()
    for (r in Ribbons) {
        val reveal = easeOut(phase(v, r.delay, r.delay + 0.46f))
        if (reveal <= 0f) continue
        val drift = sin((v * 1.6f + r.delay * 5f) * PI.toFloat()) * r.wave * h
        ribbonPath(r, w, h, drift, path)
        measure.setPath(path, false)
        val length = measure.length
        part.reset()
        measure.getSegment(0f, length * reveal, part, true)
        val a = fade * phase(v, r.delay, r.delay + 0.12f)
        drawRibbonBands(part, r, a)
        drawRibbonThread(part, a)
        // A glint riding the leading edge while the ribbon sweeps in.
        if (reveal < 1f) drawRibbonGlint(measure.getPosition(length * reveal), r, a * (1f - reveal * 0.6f))
    }
}

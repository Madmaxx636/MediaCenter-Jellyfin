package dev.mediacenter.jf.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The backdrop behind every screen. It is static on purpose: a full-screen
 * animation would redraw every frame, which TV chips can't afford.
 *
 * It's drawn by the graphics chip once into a cached layer that's then reused every frame
 * (it never changes, so the layer is never redrawn). Drawing it in software instead took a
 * quarter to half a second on the main thread at start-up. The layer is never larger than
 * 1080p, even where the interface runs at 4K: it's all soft gradients, which scale up without
 * a visible difference, and the layer stays 8 MB rather than 33.
 */
@Composable
fun WmcBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    androidx.compose.foundation.layout.Box(modifier.fillMaxSize()) {
        CachedBackdrop()
        content()
    }
}

/** The backdrop on its own, drawn once into a cached layer (see [WmcBackground]). */
@Composable
fun CachedBackdrop(modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val fullW = with(density) { maxWidth.toPx() }
        val scale = if (fullW > 1920f) 1920f / fullW else 1f
        androidx.compose.foundation.layout.Spacer(
            Modifier
                .requiredSize(maxWidth * scale, maxHeight * scale)
                .align(androidx.compose.ui.Alignment.TopStart)
                .graphicsLayer {
                    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                    scaleX = 1f / scale
                    scaleY = 1f / scale
                }
                .drawBehind { drawBackdrop(0.15f) },
        )
    }
}

/**
 * Windows 7 Media Center's backdrop: navy in the top-left, brightening towards a
 * soft pool of light low on the right, with a faint haze that drifts slowly.
 */
fun DrawScope.drawBackdrop(phase: Float) {
    val w = size.width
    val h = size.height
    drawRect(Brush.linearGradient(listOf(Wmc.BgTop, Wmc.BgMid, Wmc.BgBottom), start = Offset(0f, 0f), end = Offset(w, h)))
    val t = phase * 2 * PI.toFloat()
    drawRect(
        Brush.radialGradient(
            listOf(Color(0x8048B4FF), Color(0x2830A0FF), Color.Transparent),
            center = Offset(w * (0.72f + 0.03f * sin(t)), h * (0.95f + 0.02f * cos(t))),
            radius = w * 0.55f,
        )
    )
    drawRect(
        Brush.radialGradient(
            listOf(Color(0x3868C8FF), Color.Transparent),
            center = Offset(w * (0.62f + 0.04f * cos(t * 0.7f)), h * 0.62f),
            radius = w * 0.3f,
        )
    )
    drawRect(
        Brush.radialGradient(
            listOf(Color(0x30000818), Color.Transparent),
            center = Offset(0f, 0f),
            radius = w * 0.6f,
        )
    )
    // A faint band of haze across the lower third.
    drawRect(
        Brush.verticalGradient(
            0f to Color.Transparent, 0.72f to Color.Transparent, 0.86f to Color(0x1890D4FF), 1f to Color(0x0890D4FF),
        )
    )
}

/** Draws [block] into a new bitmap of [size]; null if the size is empty. */
fun androidx.compose.ui.draw.CacheDrawScope.rasterize(
    size: androidx.compose.ui.geometry.Size,
    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
    block: DrawScope.() -> Unit,
): androidx.compose.ui.graphics.ImageBitmap? {
    val w = kotlin.math.ceil(size.width).toInt()
    val h = kotlin.math.ceil(size.height).toInt()
    if (w <= 0 || h <= 0) return null
    val bitmap = androidx.compose.ui.graphics.ImageBitmap(w, h)
    androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
        this, layoutDirection, androidx.compose.ui.graphics.Canvas(bitmap), size, block,
    )
    return bitmap
}

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

/**
 * The backdrop on its own, drawn once into a cached layer (see [WmcBackground]): Media Center's blue,
 * or the server's own picture where its Media Center plugin has one, filling the screen.
 */
@Composable
fun CachedBackdrop(modifier: Modifier = Modifier) {
    val picture = dev.mediacenter.jf.LocalAppState.current.serverControl.backdrop
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
                .drawBehind { if (picture != null) drawPicture(picture) else drawBackdrop() },
        )
    }
}

/** A backdrop picture, cropped to fill, under a light shade of blue so white type still reads over it. */
private fun DrawScope.drawPicture(picture: androidx.compose.ui.graphics.ImageBitmap) {
    val scale = maxOf(size.width / picture.width, size.height / picture.height)
    val w = size.width / scale
    val h = size.height / scale
    drawImage(
        picture,
        srcOffset = androidx.compose.ui.unit.IntOffset(((picture.width - w) / 2).toInt(), ((picture.height - h) / 2).toInt()),
        srcSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt()),
        dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
        filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
    )
    drawRect(Brush.linearGradient(listOf(Color(0x99020A1F), Color(0x40061F4F)), start = Offset.Zero, end = Offset(size.width, size.height)))
}

/** The chosen wallpaper (settings › general): Media Center's blue glow unless another is picked. */
fun DrawScope.drawBackdrop() = drawWallpaper(Wmc.palette, Wmc.wallpaperStyle)

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

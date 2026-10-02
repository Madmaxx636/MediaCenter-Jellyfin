package dev.mediacenter.jf.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mediacenter.jf.ui.theme.Wmc
import dev.mediacenter.jf.ui.theme.timed

/**
 * Windows 7 Aero Glass, drawn without a real blur (too costly on TV chips):
 * a tinted translucent body over a dark base for legibility, a curved gloss on
 * the top half, faint diagonal light streaks, and the two-tone Aero edge (dark
 * outside, bright inside). Everything is built once per size and cached.
 *
 * [strong] makes the base darker, for panels that sit over busy video.
 */
fun Modifier.aeroGlass(
    corner: Dp = 6.dp,
    strong: Boolean = false,
    tint: Color = Wmc.themed(Color(0xFF3F8FD8)),
    streaks: Boolean = true,
): Modifier = composed {
    // Bumped when this panel's glass bitmap has been drawn in the background, so it's swapped in.
    val ready = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val lastSize = remember { arrayOf(Size.Zero) }
    // The dark base under the tint, in the wallpaper's colour too.
    val base = Wmc.themed(Color(if (strong) 0xC0041430 else 0x80051838))
    glassModifier(corner, tint, base, streaks, ready, lastSize)
}

private fun Modifier.glassModifier(
    corner: Dp,
    tint: Color,
    base: Color,
    streaks: Boolean,
    ready: androidx.compose.runtime.MutableIntState,
    lastSize: Array<Size>,
): Modifier = drawWithCache {
    ready.intValue // read, so a finished background drawing rebuilds this
    val r = corner.toPx()
    val radius = CornerRadius(r)
    val w = size.width
    val h = size.height
    val inner = 1.dp.toPx()

    val paint = glassPaint(w, h, r, tint, base, streaks)

    // The glass under the content is drawn once per size into a bitmap, so each frame costs one
    // texture copy instead of four gradient fills and a path clip; the bitmaps are kept across
    // screens (GlassCache). Drawing that bitmap is slow in software on TV processors, so it's done
    // on a background thread once the panel's size has settled; until then the graphics chip draws
    // the same glass directly. The main thread never waits on it.
    val key = GlassKey(size.width.toInt(), size.height.toInt(), r, tint.value, base.value, streaks)
    val glass = GlassCache.get(key)
    if (glass == null) {
        // Captured now: "size" on this scope always reads the panel's current size.
        val requested = size
        lastSize[0] = requested
        GlassPainter.request(key, requested, this, glassPaint(w, h, r, tint, base, streaks), stillWanted = { lastSize[0] == requested }) {
            // Only swap in if the panel is still that size (it may have been mid-animation).
            if (lastSize[0] == requested) ready.intValue++
        }
    }

    onDrawWithContent {
        if (glass != null) drawImage(glass) else paint()
        drawContent()
        // Two-tone edge: a dark hairline outside and a bright one just inside.
        drawRoundRect(Color(0xB0000814), cornerRadius = radius, style = Stroke(inner))
        drawRoundRect(
            Color(0x66E6F4FF),
            topLeft = Offset(inner, inner),
            size = Size(w - inner * 2, h - inner * 2),
            cornerRadius = CornerRadius((r - inner).coerceAtLeast(0f)),
            style = Stroke(inner),
        )
    }
}

/**
 * The glass under the content, as drawing commands. Built separately for the screen and for the
 * background thread: gradient brushes keep internal state, so each thread gets its own.
 */
private fun glassPaint(w: Float, h: Float, r: Float, tint: Color, base: Color, streaks: Boolean): androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit {
    val radius = CornerRadius(r)
    val outline = Path().apply { addRoundRect(RoundRect(0f, 0f, w, h, radius)) }

    val body = Brush.verticalGradient(
        0f to tint.copy(alpha = 0.34f),
        0.5f to tint.copy(alpha = 0.16f),
        1f to tint.copy(alpha = 0.30f),
    )
    // The gloss is an ellipse-bottomed band over the top ~45%, like the Aero title bar.
    val gloss = Path().apply {
        moveTo(0f, 0f)
        lineTo(w, 0f)
        lineTo(w, h * 0.38f)
        quadraticTo(w * 0.5f, h * 0.52f, 0f, h * 0.38f)
        close()
    }
    val glossBrush = Brush.verticalGradient(listOf(Color(0x4DFFFFFF), Color(0x0FFFFFFF)), startY = 0f, endY = h * 0.5f)
    val streakBrush = Brush.linearGradient(
        0f to Color.Transparent,
        0.30f to Color.Transparent, 0.34f to Color(0x12FFFFFF), 0.40f to Color.Transparent,
        0.52f to Color.Transparent, 0.55f to Color(0x0CFFFFFF), 0.58f to Color.Transparent,
        0.70f to Color.Transparent, 0.72f to Color(0x0AFFFFFF), 0.75f to Color.Transparent,
        1f to Color.Transparent,
        start = Offset(0f, 0f), end = Offset(w, h * 1.4f),
    )
    val bottomGlow = Brush.radialGradient(
        listOf(tint.copy(alpha = 0.35f), Color.Transparent),
        center = Offset(w * 0.5f, h * 1.05f), radius = maxOf(w, h) * 0.6f,
    )
    return {
        drawRoundRect(base, cornerRadius = radius)
        drawRoundRect(body, cornerRadius = radius)
        clipPath(outline) {
            drawRect(bottomGlow)
            if (streaks) drawRect(streakBrush)
            drawPath(gloss, glossBrush)
        }
    }
}

/** What makes two glass panels look the same: size, corners, tint, base (its strength) and streaks. */
private data class GlassKey(val w: Int, val h: Int, val corner: Float, val tint: ULong, val base: ULong, val streaks: Boolean)

/**
 * Glass bitmaps kept between screens, up to a memory budget that suits the TV (less on 1 GB
 * boxes), least recently used dropped first. Main thread only, like drawing.
 */
private object GlassCache {
    private val budget = if (dev.mediacenter.jf.Hardware.lowRam) 12L shl 20 else 32L shl 20
    private var used = 0L
    private val map = LinkedHashMap<GlassKey, androidx.compose.ui.graphics.ImageBitmap>(16, 0.75f, true)

    fun get(key: GlassKey) = map[key]

    fun put(key: GlassKey, bitmap: androidx.compose.ui.graphics.ImageBitmap) {
        val bytes = bitmap.width.toLong() * bitmap.height * 4
        if (bytes > budget / 2) return
        map.put(key, bitmap)?.let { used -= it.width.toLong() * it.height * 4 }
        used += bytes
        val it = map.entries.iterator()
        while (used > budget && it.hasNext()) {
            val e = it.next()
            if (e.key == key) continue
            used -= e.value.width.toLong() * e.value.height * 4
            it.remove()
        }
    }

    fun clear() {
        map.clear()
        used = 0
    }
}

/**
 * Draws glass bitmaps on one background thread. A request waits briefly first, and is dropped
 * if the panel has changed size meanwhile, so a growing or shrinking panel isn't drawn at every
 * step of its animation, only at the size it ends at.
 */
private object GlassPainter {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "glass").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val pending = HashSet<GlassKey>()

    fun request(
        key: GlassKey,
        size: Size,
        density: androidx.compose.ui.unit.Density,
        paint: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit,
        stillWanted: () -> Boolean,
        onReady: () -> Unit,
    ) {
        if (!pending.add(key)) return
        val d = androidx.compose.ui.unit.Density(density.density, density.fontScale)
        main.postDelayed({
            // The panel moved on to another size (it's animating): skip this one.
            if (!stillWanted()) {
                pending.remove(key)
                return@postDelayed
            }
            worker.execute {
                val bitmap = runCatching { timed("Glass ${key.w}x${key.h} (background)") { dev.mediacenter.jf.ui.theme.renderBitmap(size, d, paint) } }.getOrNull()
                main.post {
                    pending.remove(key)
                    if (bitmap != null) {
                        GlassCache.put(key, bitmap)
                        onReady()
                    }
                }
            }
        }, 160)
    }
}

/** Frees the kept glass bitmaps when memory is short; they're drawn again when next shown. */
fun clearGlassCache() = GlassCache.clear()

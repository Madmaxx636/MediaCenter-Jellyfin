package dev.mediacenter.jf.ui.theme

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.mediacenter.jf.AppLog
import kotlin.math.ceil

/** Times a block and notes it in the log when it's slow enough to cost frames on a TV chip. */
internal inline fun <T> timed(label: String, block: () -> T): T {
    val start = SystemClock.elapsedRealtimeNanos()
    val result = block()
    val ms = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
    if (ms >= 8) AppLog.i("Perf", "$label took $ms ms")
    return result
}

/** Draws [block] into a new bitmap of [size] (software), on any thread. */
internal fun renderBitmap(size: Size, density: Density, block: DrawScope.() -> Unit): ImageBitmap? {
    val w = ceil(size.width).toInt()
    val h = ceil(size.height).toInt()
    if (w <= 0 || h <= 0) return null
    val bitmap = ImageBitmap(w, h)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bitmap), size, block)
    return bitmap
}

/**
 * The start menu's ribbon glows are drawn once into bitmaps, in software, which is slow on TV
 * processors; at launch that's done on a background thread while the intro plays, instead of
 * on the main thread when the start menu first draws. The pictures are exactly the same.
 */
object Prewarm {
    fun start(context: Context, uiScale: Float, fontScale: Float) {
        val metrics = context.resources.displayMetrics
        // A TV app fills the whole screen; take the real display size (landscape).
        val real = android.util.DisplayMetrics().also {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(it)
        }
        val screen = Size(maxOf(real.widthPixels, real.heightPixels).toFloat(), minOf(real.widthPixels, real.heightPixels).toFloat())
        val density = Density(metrics.density * uiScale, fontScale)
        Thread({
            runCatching {
                timed("Ribbon glows (background thread)") { RibbonCache.get(screen, density) }
            }
        }, "prewarm").apply { priority = Thread.NORM_PRIORITY - 1 }.start()
    }
}

/**
 * A see-through layer that stays cached while it's see-through: Android otherwise redraws a
 * faded layer off-screen on every frame anything else on screen moves (the start menu's
 * ribbons move every frame), which is costly on TV graphics chips. The cached layer holds the
 * same pixels and only redraws when its own content changes. Fully opaque, it's a plain layer.
 */
internal fun Modifier.fadeLayer(alpha: () -> Float): Modifier = graphicsLayer {
    val a = alpha()
    this.alpha = a
    // Fully clear, Android skips drawing it at all; only in between is a cached layer worth it.
    compositingStrategy = if (a > 0f && a < 1f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
}

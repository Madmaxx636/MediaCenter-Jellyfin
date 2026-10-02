package dev.mediacenter.jf.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import kotlin.random.Random

/**
 * A wallpaper colour: the backdrop's gradient from top-left to bottom-right, the colour its light
 * takes, and the focus colour that goes with it (null keeps Media Center's blue).
 */
class Palette(
    val key: String,
    val label: String,
    val top: Color,
    val mid: Color,
    val bottom: Color,
    val light: Color,
    val accent: Color?,
)

object Palettes {
    val all = listOf(
        Palette("blue", "Media Center blue", Color(0xFF020A1F), Color(0xFF06214F), Color(0xFF0A3576), Color(0xFF48B4FF), null),
        Palette("midnight", "midnight", Color(0xFF010205), Color(0xFF050A15), Color(0xFF0B1527), Color(0xFF3D5F94), null),
        Palette("teal", "teal", Color(0xFF01131A), Color(0xFF043844), Color(0xFF0A5E6A), Color(0xFF4FE0E6), Color(0xFF2BB5C2)),
        Palette("emerald", "emerald", Color(0xFF01110B), Color(0xFF053423), Color(0xFF0A583A), Color(0xFF46E0A0), Color(0xFF2FBF7F)),
        Palette("violet", "violet", Color(0xFF0A041F), Color(0xFF22104D), Color(0xFF3A1C78), Color(0xFFA57CFF), Color(0xFF8A5CF0)),
        Palette("crimson", "crimson", Color(0xFF150307), Color(0xFF430B16), Color(0xFF6E1626), Color(0xFFFF6A7A), Color(0xFFD9364A)),
        Palette("amber", "sunset amber", Color(0xFF130803), Color(0xFF481D06), Color(0xFF77390C), Color(0xFFFFB45A), Color(0xFFE68A2E)),
        Palette("graphite", "graphite", Color(0xFF07080A), Color(0xFF1A1D23), Color(0xFF2F343C), Color(0xFF9DAABD), Color(0xFF7E93AE)),
    )

    fun of(key: String?) = all.firstOrNull { it.key == key } ?: all.first()
}

/** The wallpaper patterns: the light and shapes laid over the colour. */
object WallpaperStyles {
    val all = listOf("glow" to "Media Center glow", "aurora" to "aurora", "horizon" to "horizon", "bokeh" to "bokeh", "plain" to "plain")
}

/**
 * Draws the wallpaper: [palette]'s gradient with [style]'s light over it. All soft shapes, drawn once
 * into a cached layer (see CachedBackdrop), so the pattern costs nothing per frame.
 */
fun DrawScope.drawWallpaper(palette: Palette, style: String) {
    val w = size.width
    val h = size.height
    val light = palette.light
    drawRect(Brush.linearGradient(listOf(palette.top, palette.mid, palette.bottom), start = Offset.Zero, end = Offset(w, h)))
    when (style) {
        "aurora" -> {
            // Curtains of light falling from the top, leaning across the screen.
            listOf(0.18f to 0.55f, 0.46f to 0.75f, 0.74f to 0.45f).forEachIndexed { i, (x, strength) ->
                scale(0.35f, 1.6f, pivot = Offset(w * x, 0f)) {
                    drawCircle(
                        Brush.radialGradient(
                            listOf(lerp(light, Color.White, 0.15f).copy(alpha = 0.30f * strength), light.copy(alpha = 0.10f * strength), Color.Transparent),
                            center = Offset(w * x, h * (0.12f + 0.08f * i)), radius = h * 0.55f,
                        ),
                        radius = h * 0.55f, center = Offset(w * x, h * (0.12f + 0.08f * i)),
                    )
                }
            }
            drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.7f to Color.Transparent, 1f to palette.top.copy(alpha = 0.55f)))
        }
        "horizon" -> {
            // A dusk sky: dark above, a band of light low across the screen, and its faint glow beneath.
            val y = h * 0.7f
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent, (y / h - 0.22f) to light.copy(alpha = 0.06f), (y / h) to lerp(light, Color.White, 0.25f).copy(alpha = 0.42f),
                    (y / h + 0.03f) to light.copy(alpha = 0.16f), 1f to palette.top.copy(alpha = 0.5f),
                )
            )
            // The brightest part of the horizon: a wide, flat glow that fades out well inside the screen.
            val glow = Offset(w * 0.62f, y)
            scale(3f, 1f, pivot = glow) {
                drawCircle(
                    Brush.radialGradient(listOf(lerp(light, Color.White, 0.3f).copy(alpha = 0.35f), Color.Transparent), center = glow, radius = h * 0.2f),
                    radius = h * 0.2f, center = glow,
                )
            }
        }
        "bokeh" -> {
            // Out-of-focus lights, the same every time (a fixed seed), larger and fainter towards the back.
            val random = Random(7)
            repeat(26) {
                val r = h * (0.03f + random.nextFloat() * 0.11f)
                val c = Offset(w * random.nextFloat(), h * (0.15f + random.nextFloat() * 0.85f))
                val a = 0.05f + random.nextFloat() * 0.13f
                drawCircle(
                    Brush.radialGradient(listOf(lerp(light, Color.White, 0.3f).copy(alpha = a), light.copy(alpha = a * 0.6f), Color.Transparent), center = c, radius = r),
                    radius = r, center = c,
                )
            }
            drawRect(Brush.radialGradient(listOf(light.copy(alpha = 0.18f), Color.Transparent), center = Offset(w * 0.72f, h * 0.95f), radius = w * 0.5f))
        }
        "plain" -> {
            drawRect(Brush.radialGradient(listOf(Color.Transparent, palette.top.copy(alpha = 0.5f)), center = Offset(w / 2, h / 2), radius = maxOf(w, h) * 0.75f))
        }
        else -> {
            // Media Center's own: a soft pool of light low on the right, a lesser one above it, and a band of haze.
            drawRect(
                Brush.radialGradient(
                    listOf(light.copy(alpha = 0.5f), light.copy(alpha = 0.16f), Color.Transparent),
                    center = Offset(w * 0.72f, h * 0.95f), radius = w * 0.55f,
                )
            )
            drawRect(Brush.radialGradient(listOf(lerp(light, Color.White, 0.2f).copy(alpha = 0.22f), Color.Transparent), center = Offset(w * 0.62f, h * 0.62f), radius = w * 0.3f))
            drawRect(Brush.radialGradient(listOf(Color(0x30000818), Color.Transparent), center = Offset.Zero, radius = w * 0.6f))
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent, 0.72f to Color.Transparent, 0.86f to lerp(light, Color.White, 0.3f).copy(alpha = 0.09f), 1f to light.copy(alpha = 0.03f),
                )
            )
        }
    }
}

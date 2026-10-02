package dev.mediacenter.jf.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The Windows 7 Media Center palette: deep blue glass, white type, cyan glow. */
object Wmc {
    val Text = Color.White
    // Secondary text in Media Center is a pale sky blue rather than grey (in the wallpaper's colour, if it has one).
    val TextDim get() = themed().textDim
    val TextFaint get() = themed().textFaint
    val TextGhost get() = themed().textGhost

    /**
     * The server's accent colour (the Media Center plugin's branding), or null for Media Center's
     * blue. The focus glow, focus fill and highlights follow it.
     */
    var accent by mutableStateOf<Color?>(null)

    /** Which wallpaper colour and pattern are chosen (settings › general); read while drawing, so a change shows at once. */
    var paletteKey: () -> String = { "blue" }
    var wallpaperKey: () -> String = { "glow" }
    val palette: Palette get() = Palettes.of(paletteKey())
    val wallpaperStyle: String get() = wallpaperKey()

    /** The focus colour: the server's, else the wallpaper colour's, else Media Center's blue (null). */
    private val focusColour: Color? get() = accent ?: palette.accent

    val Glow get() = focusColour?.let { lerp(it, Color.White, 0.15f) } ?: Color(0xFF55B8FF)
    val FocusTop get() = focusColour?.let { lerp(it, Color.White, 0.1f) } ?: Color(0xFF4EA6F2)
    val FocusBottom get() = focusColour?.let { lerp(it, Color.Black, 0.45f) } ?: Color(0xFF1760BE)
    val Accent get() = focusColour?.let { lerp(it, Color.White, 0.45f) } ?: Color(0xFF8FD0FF)

    /** The Aero focus fill: light above, a crisp band across the middle, deeper below; in the accent if there is one. */
    val FocusFill: Brush
        get() {
            val a = focusColour ?: return Brush.verticalGradient(0f to Color(0xFF6CBBF7), 0.48f to Color(0xFF2F7FD6), 0.52f to Color(0xFF1F68C4), 1f to Color(0xFF3A8FE0))
            return Brush.verticalGradient(0f to lerp(a, Color.White, 0.35f), 0.48f to lerp(a, Color.Black, 0.12f), 0.52f to lerp(a, Color.Black, 0.25f), 1f to lerp(a, Color.White, 0.08f))
        }

    val BgTop get() = palette.top
    val BgMid get() = palette.mid
    val BgBottom get() = palette.bottom

    val Glass get() = themed().glass
    val GlassEdge get() = themed().glassEdge

    /**
     * One of Media Center's blues in the wallpaper colour: its hue, the blue's lightness and a
     * share of its saturation. The blue palettes keep the blue as it is.
     */
    fun themed(blue: Color): Color {
        val a = palette.accent ?: return blue
        val (hue, saturation) = hueAndSaturation(a)
        val (_, blueSaturation, value) = hsv(blue)
        return Color.hsv(hue, (blueSaturation * saturation / ReferenceSaturation).coerceIn(0f, 1f), value, blue.alpha)
    }

    /** The themed colours, worked out once per wallpaper colour; reading it follows the setting. */
    internal fun themed(): Themed {
        val key = paletteKey()
        return themedCache?.takeIf { it.key == key } ?: Themed(key).also { themedCache = it }
    }

    private var themedCache: Themed? = null

    // The saturation of the glass blue (0xFF3F8FD8), the colour the others are measured against.
    private const val ReferenceSaturation = 0.708f

    private fun hueAndSaturation(c: Color) = hsv(c).let { it[0] to it[1] }

    private fun hsv(c: Color): FloatArray {
        val max = maxOf(c.red, c.green, c.blue)
        val min = minOf(c.red, c.green, c.blue)
        val d = max - min
        val hue = when {
            d == 0f -> 0f
            max == c.red -> 60f * (((c.green - c.blue) / d).mod(6f))
            max == c.green -> 60f * ((c.blue - c.red) / d + 2f)
            else -> 60f * ((c.red - c.green) / d + 4f)
        }
        return floatArrayOf(hue, if (max == 0f) 0f else d / max, max)
    }

    val Green = Color(0xFF4DBB36)
    val GreenDark = Color(0xFF1E6E12)
    val Warning = Color(0xFFFFC66B)
}

/** The colours that follow the wallpaper colour, for one wallpaper colour. */
internal class Themed(val key: String) {
    val textDim = Wmc.themed(Color(0xFF8FCBF2))
    val textFaint = Wmc.themed(Color(0xB06FAAD8))
    val textGhost = Wmc.themed(Color(0x40A9D4F5))
    val glass = Wmc.themed(Color(0x2A9CCBFF))
    val glassEdge = Wmc.themed(Color(0x55B8DCFF))
    val body = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, color = textDim)
    val caption = TextStyle(fontSize = 14.sp, color = textFaint)
    val pageTitle = TextStyle(fontSize = 62.sp, fontWeight = FontWeight.Light, color = Wmc.themed(Color(0x5578C2F0)), letterSpacing = (-1).sp)
}

private val TextGlow = Shadow(Color(0xAA000510), Offset(0f, 2f), 4f)
private val SmallGlow = Shadow(Color(0x88000510), Offset(0f, 1.5f), 2f)

object WmcType {
    val Hero = TextStyle(fontSize = 46.sp, fontWeight = FontWeight.Light, color = Wmc.Text, shadow = TextGlow, letterSpacing = (-0.5).sp)
    val Title = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Light, color = Wmc.Text, shadow = TextGlow)
    val Heading = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Light, color = Wmc.Text, shadow = TextGlow)
    val Pivot = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Normal, color = Wmc.Text, shadow = SmallGlow)
    val PageTitle get() = Wmc.themed().pageTitle
    val ItemTitle = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Medium, color = Wmc.Text, shadow = TextGlow)
    val Label = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Normal, color = Wmc.Text, shadow = SmallGlow)
    val Body get() = Wmc.themed().body
    val Caption get() = Wmc.themed().caption
    val Clock = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Light, color = Wmc.Text, shadow = TextGlow)
}

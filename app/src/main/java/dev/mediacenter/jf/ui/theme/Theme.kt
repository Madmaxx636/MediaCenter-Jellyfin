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
    // Secondary text in Media Center is a pale sky blue rather than grey.
    val TextDim = Color(0xFF8FCBF2)
    val TextFaint = Color(0xB06FAAD8)
    val TextGhost = Color(0x40A9D4F5)

    /**
     * The server's accent colour (the Media Center plugin's branding), or null for Media Center's
     * blue. The focus glow, focus fill and highlights follow it.
     */
    var accent by mutableStateOf<Color?>(null)

    val Glow get() = accent?.let { lerp(it, Color.White, 0.15f) } ?: Color(0xFF55B8FF)
    val FocusTop get() = accent?.let { lerp(it, Color.White, 0.1f) } ?: Color(0xFF4EA6F2)
    val FocusBottom get() = accent?.let { lerp(it, Color.Black, 0.45f) } ?: Color(0xFF1760BE)
    val Accent get() = accent?.let { lerp(it, Color.White, 0.45f) } ?: Color(0xFF8FD0FF)

    /** The Aero focus fill: light above, a crisp band across the middle, deeper below; in the accent if there is one. */
    val FocusFill: Brush
        get() {
            val a = accent ?: return Brush.verticalGradient(0f to Color(0xFF6CBBF7), 0.48f to Color(0xFF2F7FD6), 0.52f to Color(0xFF1F68C4), 1f to Color(0xFF3A8FE0))
            return Brush.verticalGradient(0f to lerp(a, Color.White, 0.35f), 0.48f to lerp(a, Color.Black, 0.12f), 0.52f to lerp(a, Color.Black, 0.25f), 1f to lerp(a, Color.White, 0.08f))
        }

    val BgTop = Color(0xFF020A1F)
    val BgMid = Color(0xFF06214F)
    val BgBottom = Color(0xFF0A3576)

    val Glass = Color(0x2A9CCBFF)
    val GlassEdge = Color(0x55B8DCFF)
    val Panel = Color(0x66061A40)

    val Green = Color(0xFF4DBB36)
    val GreenDark = Color(0xFF1E6E12)
    val Warning = Color(0xFFFFC66B)
}

private val TextGlow = Shadow(Color(0xAA000510), Offset(0f, 2f), 4f)
private val SmallGlow = Shadow(Color(0x88000510), Offset(0f, 1.5f), 2f)

object WmcType {
    val Hero = TextStyle(fontSize = 46.sp, fontWeight = FontWeight.Light, color = Wmc.Text, shadow = TextGlow, letterSpacing = (-0.5).sp)
    val Title = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Light, color = Wmc.Text, shadow = TextGlow)
    val Heading = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Light, color = Wmc.Text, shadow = TextGlow)
    val Pivot = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Normal, color = Wmc.Text, shadow = SmallGlow)
    val PageTitle = TextStyle(fontSize = 62.sp, fontWeight = FontWeight.Light, color = Color(0x5578C2F0), letterSpacing = (-1).sp)
    val ItemTitle = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Medium, color = Wmc.Text, shadow = TextGlow)
    val Label = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Normal, color = Wmc.Text, shadow = SmallGlow)
    val Body = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, color = Wmc.TextDim)
    val Caption = TextStyle(fontSize = 14.sp, color = Wmc.TextFaint)
    val Clock = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Light, color = Wmc.Text, shadow = TextGlow)
}

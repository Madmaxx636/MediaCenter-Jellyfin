package dev.mediacenter.jf.ui.components

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.mediacenter.jf.ui.theme.Wmc
import dev.mediacenter.jf.ui.theme.WmcType

/** Horizontal safe-area margin for TV overscan. */
val ScreenPadH = 48.dp
val ScreenPadTop = 22.dp

/** The glossy grey-white back button in the top-left corner. */
@Composable
fun BackButton(modifier: Modifier = Modifier) {
    Canvas(modifier.size(30.dp)) {
        val r = size.minDimension / 2
        drawCircle(Brush.verticalGradient(listOf(Color(0xFFE9EEF5), Color(0xFF9AA8BA), Color(0xFF5E6C80))), r * 0.94f)
        drawCircle(Color(0xAAFFFFFF), r * 0.94f, style = Stroke(1f))
        drawOval(
            Brush.verticalGradient(listOf(Color(0xCCFFFFFF), Color(0x00FFFFFF)), startY = center.y - r * 0.9f, endY = center.y),
            topLeft = Offset(center.x - r * 0.6f, center.y - r * 0.86f),
            size = androidx.compose.ui.geometry.Size(r * 1.2f, r * 0.8f),
        )
        val u = r / 12f
        val c = center
        val arrow = Color(0xFF34506F)
        val w = 2.6f * u
        drawLine(arrow, Offset(c.x - 5 * u, c.y), Offset(c.x + 5.5f * u, c.y), w, StrokeCap.Round)
        drawLine(arrow, Offset(c.x - 5 * u, c.y), Offset(c.x - 0.5f * u, c.y - 4.5f * u), w, StrokeCap.Round)
        drawLine(arrow, Offset(c.x - 5 * u, c.y), Offset(c.x - 0.5f * u, c.y + 4.5f * u), w, StrokeCap.Round)
    }
}

/** The app's orb: a glossy sphere in Jellyfin's colours carrying the Jellyfin mark, where Media Center had its logo. */
@Composable
fun LogoOrb(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 40.dp) {
    // The server's own logo, where its Media Center plugin has one.
    val serverLogo = dev.mediacenter.jf.LocalAppState.current.serverControl.logo
    if (serverLogo != null) {
        androidx.compose.foundation.Image(serverLogo, contentDescription = "Logo", modifier = modifier.size(size))
        return
    }
    androidx.compose.foundation.Image(
        androidx.compose.ui.res.painterResource(dev.mediacenter.jf.R.drawable.jellyfin_orb),
        contentDescription = "Jellyfin",
        modifier = modifier.size(size),
    )
}

/** The strip along the top of every screen: back button and orb on the left, the clock in the middle. */
@Composable
fun TopChrome(modifier: Modifier = Modifier, showBack: Boolean = true, showClock: Boolean = true, trailing: String? = null) {
    val app = dev.mediacenter.jf.LocalAppState.current
    Box(modifier.fillMaxWidth().padding(start = ScreenPadH - 12.dp, end = ScreenPadH, top = ScreenPadTop)) {
        Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            if (showBack) BackButton(Modifier.padding(end = 10.dp))
            LogoOrb(
                Modifier
                    // Hidden while the intro's orb flies in to land exactly here (or fading in, in Media Center's intro).
                    .graphicsLayer { alpha = if (app.introShown || !app.settings.intro.value) 1f else if (app.introZoomMenu) app.introLogoAlpha else 0f }
                    .onGloballyPositioned { app.logoBounds = it.boundsInRoot() },
            )
        }
        // Always centred along the top, clear of the page titles on the right.
        if (showClock && app.settings.showClock.value) WText(rememberClock(), WmcType.Clock, Modifier.align(Alignment.Center))
        if (trailing != null) WText(trailing, WmcType.Caption, Modifier.align(Alignment.CenterEnd))
    }
}

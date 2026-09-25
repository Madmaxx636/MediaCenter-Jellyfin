package dev.mediacenter.jf.ui.screens

import dev.mediacenter.jf.ui.components.aeroGlass
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.ui.compose.ContentFrame
import dev.mediacenter.jf.LocalAppState
import dev.mediacenter.jf.data.posterUrl
import dev.mediacenter.jf.ui.components.Artwork
import dev.mediacenter.jf.ui.components.FocusBox
import dev.mediacenter.jf.ui.components.Glyph
import dev.mediacenter.jf.ui.components.ProgressBar
import dev.mediacenter.jf.ui.components.ScreenPadH
import dev.mediacenter.jf.ui.components.WText
import dev.mediacenter.jf.ui.theme.WmcType
import kotlinx.coroutines.delay

val InsetWidth = 240.dp

/** How much bottom-left room screens should leave for the inset. */
val LocalInsetPadding = compositionLocalOf<Dp> { 0.dp }

/**
 * The little "now playing" window in the bottom-left corner, which keeps video
 * or music going while you browse. Select it to go back to full screen.
 */
@Composable
fun NowPlayingInset(modifier: Modifier = Modifier) {
    val app = LocalAppState.current
    val np by app.playback.nowPlaying.collectAsState()
    val current = np ?: return
    val repo = app.repository ?: return
    val fraction by produceState(0f, current) {
        while (true) {
            val p = app.playback.player
            value = if (p.duration > 0) p.currentPosition.toFloat() / p.duration else 0f
            delay(1_000)
        }
    }

    FocusBox(
        onClick = { app.navigator.showPlayer() },
        scale = 1.05f,
        modifier = modifier.padding(start = ScreenPadH - 8.dp, bottom = 20.dp).size(InsetWidth, InsetWidth * 9 / 16),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (current.isVideo) {
                ContentFrame(player = app.playback.player, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                Artwork(repo.posterUrl(current.item, 300), current.item.album, Modifier.fillMaxSize(), glyph = Glyph.Music)
            }
            Column(
                Modifier.fillMaxWidth().align(Alignment.BottomStart)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000814))))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                WText(current.item.name ?: "", WmcType.Caption, color = Color.White)
                ProgressBar(fraction, thickness = 3.dp)
            }
        }
    }
}

/** While video plays behind the menus, this small button returns to it full screen. */
@Composable
fun NowPlayingPill(modifier: Modifier = Modifier) {
    val app = LocalAppState.current
    val np by app.playback.nowPlaying.collectAsState()
    val current = np ?: return
    FocusBox(
        onClick = { app.navigator.showPlayer() },
        fill = true,
        scale = 1.04f,
        modifier = modifier.padding(start = ScreenPadH - 8.dp, bottom = 20.dp),
    ) { focused ->
        androidx.compose.foundation.layout.Row(
            Modifier
                .then(if (focused) Modifier else Modifier.aeroGlass(corner = 5.dp, strong = true))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            dev.mediacenter.jf.ui.components.GlyphIcon(Glyph.Play, size = 16.dp)
            WText("now playing  \u00b7  ${current.item.name ?: ""}", WmcType.Label, Modifier.padding(start = 10.dp).widthIn(max = 320.dp))
        }
    }
}

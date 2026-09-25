package dev.mediacenter.jf.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.mediacenter.jf.ui.components.aeroGlass
import dev.mediacenter.jf.ui.components.GlyphIcon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.mediacenter.jf.LocalAppState
import dev.mediacenter.jf.data.ImageKind
import dev.mediacenter.jf.ui.PhotoDest
import dev.mediacenter.jf.ui.components.Artwork
import dev.mediacenter.jf.ui.components.Glyph
import dev.mediacenter.jf.ui.components.ScreenPadH
import dev.mediacenter.jf.ui.components.WText
import dev.mediacenter.jf.ui.components.tryFocus
import dev.mediacenter.jf.ui.theme.Wmc
import dev.mediacenter.jf.ui.theme.WmcType
import kotlinx.coroutines.delay

/**
 * Full-screen pictures with Media Center's slide show: duration, order,
 * transition, captions and "now playing" song info follow Settings \u203a pictures.
 * Left/right step through pictures; OK plays or pauses the slide show.
 */
@Composable
fun PhotoScreen(dest: PhotoDest) {
    val app = LocalAppState.current
    val repo = app.repository ?: return
    val s = app.settings
    val photos = dest.photos
    var slideshow by remember { mutableStateOf(dest.slideshow) }
    var shownAt by remember { mutableLongStateOf(System.nanoTime()) }
    var controlsVisible by remember { mutableStateOf(true) }
    val focus = remember { FocusRequester() }
    val np by app.playback.nowPlaying.collectAsState()
    val transition = s.slideTransition.value

    LaunchedEffect(Unit) { focus.tryFocus() }
    LaunchedEffect(slideshow, dest.index) {
        if (slideshow && photos.size > 1) {
            delay(s.slideSeconds.value * 1000L)
            dest.index = (dest.index + 1) % photos.size
        }
    }
    LaunchedEffect(shownAt) {
        controlsVisible = true
        delay(3_500)
        controlsVisible = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionRight -> dest.index = (dest.index + 1) % photos.size
                    Key.DirectionLeft -> dest.index = (dest.index - 1 + photos.size) % photos.size
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> slideshow = !slideshow
                    Key.DirectionUp, Key.DirectionDown -> Unit
                    else -> return@onPreviewKeyEvent false
                }
                app.sounds.focus()
                shownAt = System.nanoTime()
                true
            },
    ) {
        val fade = when (transition) { "cut" -> 0; else -> 900 }
        Crossfade(dest.index, animationSpec = tween(fade), label = "photo") { i ->
            val photo = photos[i]
            val zoom = remember(i) { Animatable(1f) }
            LaunchedEffect(i, slideshow, transition) {
                if (slideshow && transition == "animated") {
                    zoom.animateTo(1.12f, tween(s.slideSeconds.value * 1000 + fade, easing = LinearEasing))
                } else zoom.snapTo(1f)
            }
            Artwork(
                repo.imageUrl(photo.id, ImageKind.Primary, photo.imageTags["Primary"], 1080),
                photo.name,
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom.value; scaleY = zoom.value
                    translationX = (zoom.value - 1f) * size.width * if (i % 2 == 0) 0.25f else -0.25f
                },
                glyph = Glyph.Pictures,
                corner = 0.dp,
                contentScale = ContentScale.Fit,
            )
        }

        // Song information while music plays, per Settings \u203a pictures.
        val track = np?.takeIf { !it.isVideo }?.item
        if (track != null && s.slideSongInfo.value != "never") {
            var songVisible by remember { mutableStateOf(true) }
            LaunchedEffect(track.id, s.slideSongInfo.value) {
                songVisible = true
                if (s.slideSongInfo.value == "edges") { delay(6_000); songVisible = false }
            }
            AnimatedVisibility(songVisible, Modifier.align(Alignment.TopEnd), enter = fadeIn(), exit = fadeOut()) {
                Row(
                    Modifier.padding(top = 30.dp, end = ScreenPadH).aeroGlass(corner = 6.dp, strong = true).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlyphIcon(Glyph.Music, size = 18.dp)
                    Column(Modifier.padding(start = 12.dp)) {
                        WText(track.name ?: "", WmcType.Label)
                        WText(listOfNotNull(track.albumArtist, track.album).joinToString("  \u00b7  "), WmcType.Caption, color = Wmc.TextDim)
                    }
                }
            }
        }

        val showCaption = controlsVisible || (slideshow && s.slideCaptions.value)
        AnimatedVisibility(showCaption, Modifier.align(Alignment.BottomStart), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000814))))
                    .padding(horizontal = ScreenPadH, vertical = 28.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(Modifier.weight(1f)) {
                    WText(photos[dest.index].name ?: "", WmcType.Heading)
                    WText("${dest.index + 1} of ${photos.size}", WmcType.Caption, color = Wmc.TextDim)
                }
                // Transport indicators: left/right step, OK plays or pauses.
                if (controlsVisible) {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlyphIcon(Glyph.SkipBack, size = 22.dp, color = Wmc.TextDim)
                        GlyphIcon(if (slideshow) Glyph.Pause else Glyph.Play, size = 30.dp)
                        GlyphIcon(Glyph.SkipNext, size = 22.dp, color = Wmc.TextDim)
                    }
                }
            }
        }
    }
}

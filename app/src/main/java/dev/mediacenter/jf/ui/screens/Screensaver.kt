package dev.mediacenter.jf.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import dev.mediacenter.jf.AppState
import dev.mediacenter.jf.data.BaseItem
import dev.mediacenter.jf.data.ImageKind
import dev.mediacenter.jf.data.ItemQuery
import dev.mediacenter.jf.data.MediaRepository
import dev.mediacenter.jf.data.backdropUrl
import dev.mediacenter.jf.ui.components.Glyph
import dev.mediacenter.jf.ui.components.GlyphIcon
import dev.mediacenter.jf.ui.components.WText
import dev.mediacenter.jf.ui.components.formatTime
import dev.mediacenter.jf.ui.theme.RibbonBackdrop
import dev.mediacenter.jf.ui.theme.Wmc
import dev.mediacenter.jf.ui.theme.WmcType
import kotlinx.coroutines.delay
import java.util.Date

/** One picture on the screensaver: the artwork, and what it's from (a title's logo, or its name). */
private class ScreensaverPicture(val image: String, val logo: String?, val title: String)

/**
 * The screensaver: the server's artwork, full screen, one picture after another. Each slowly zooms
 * while it's up and cross-fades into the next (fetched before it's due, so there's never a gap);
 * a film's or show's logo, or its name, sits low on the left; the time, top right, over a soft
 * vignette. It's drawn over the app, so music keeps playing and a paused video stays where it was.
 * Without a server's artwork (the demo, or the TV's own screensaver before signing in) it shows
 * Media Center's ribbons. Also the TV's own screensaver (MediaCenterDream).
 */
@Composable
fun Screensaver(app: AppState) {
    val repo = app.repository
    val context = LocalContext.current
    val seconds = app.settings.screensaverSeconds.value
    var current by remember { mutableStateOf<ScreensaverPicture?>(null) }
    var empty by remember { mutableStateOf(false) }
    LaunchedEffect(repo) {
        val list = repo?.let { runCatching { pictures(it, app.settings.screensaverShows.value) }.getOrDefault(emptyList()) }.orEmpty()
        if (list.isEmpty()) { empty = true; return@LaunchedEffect }
        var i = 0
        while (true) {
            val next = list[i % list.size]
            // Loaded before it's shown, so the cross-fade goes from picture to picture, never to black.
            runCatching { context.imageLoader.execute(ImageRequest.Builder(context).data(next.image).build()) }
            current = next
            delay(seconds * 1000L)
            i++
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (empty) {
            dev.mediacenter.jf.ui.theme.CachedBackdrop()
            RibbonBackdrop(Modifier.fillMaxSize())
        }
        Crossfade(current, animationSpec = tween(2_000), label = "screensaver") { picture ->
            if (picture == null) return@Crossfade
            val zoom = remember(picture) { Animatable(1f) }
            LaunchedEffect(picture) { zoom.animateTo(1.1f, tween((seconds + 2) * 1000, easing = LinearEasing)) }
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    picture.image, null, Modifier.fillMaxSize().graphicsLayer { scaleX = zoom.value; scaleY = zoom.value },
                    contentScale = ContentScale.Crop,
                )
                TitleMark(picture, Modifier.align(Alignment.BottomStart).padding(start = 64.dp, bottom = 56.dp))
            }
        }
        // A vignette, and a shade at the top for the time.
        Box(
            Modifier.fillMaxSize().drawWithCache {
                val vignette = Brush.radialGradient(
                    0f to Color.Transparent, 0.7f to Color.Transparent, 1f to Color(0x99000000),
                    center = Offset(size.width / 2, size.height / 2), radius = maxOf(size.width, size.height) * 0.72f,
                )
                val top = Brush.verticalGradient(0f to Color(0x88000000), 0.22f to Color.Transparent)
                val bottom = Brush.verticalGradient(0.65f to Color.Transparent, 1f to Color(0x99000000))
                onDrawBehind { drawRect(vignette); drawRect(top); drawRect(bottom) }
            },
        )
        ClockAndSong(app, Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 64.dp))
    }
}

/** A title's logo art, or its name in Media Center's type where it has no logo. */
@Composable
private fun TitleMark(picture: ScreensaverPicture, modifier: Modifier) {
    var logoFailed by remember(picture) { mutableStateOf(picture.logo == null) }
    if (!logoFailed) {
        AsyncImage(
            picture.logo, picture.title, modifier.size(420.dp, 150.dp),
            alignment = Alignment.BottomStart, contentScale = ContentScale.Fit,
            onError = { logoFailed = true },
        )
    } else if (picture.title.isNotEmpty()) {
        WText(picture.title, WmcType.Hero.copy(fontWeight = FontWeight.Light), modifier.fillMaxWidth(0.55f), color = Wmc.Text, maxLines = 2)
    }
}

/** The time and date, and the song while music plays. */
@Composable
private fun ClockAndSong(app: AppState, modifier: Modifier) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    val np by app.playback.nowPlaying.collectAsState()
    val song = np?.takeIf { !it.isVideo }?.item
    Column(modifier, horizontalAlignment = Alignment.End) {
        WText(formatTime(Date(now), context), WmcType.Hero.copy(fontWeight = FontWeight.Light), color = Wmc.Text)
        WText(java.text.DateFormat.getDateInstance(java.text.DateFormat.FULL).format(Date(now)).lowercase(), WmcType.Label, color = Wmc.TextDim)
        song?.let {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.Music, size = 16.dp, color = Wmc.Accent)
                WText(listOfNotNull(it.name, it.albumArtist).joinToString("  ·  "), WmcType.Label, Modifier.padding(start = 8.dp), color = Wmc.TextDim)
            }
        }
    }
}

/** What the screensaver shows, shuffled: films and shows with backdrops (and their logos), pictures, or both. */
private suspend fun pictures(repo: MediaRepository, shows: String): List<ScreensaverPicture> {
    val titles = if (shows == "pictures") emptyList() else {
        repo.items(ItemQuery(includeItemTypes = listOf("Movie", "Series"), hasImages = "Backdrop", sortBy = "Random", limit = 60))
            .mapNotNull { item ->
                repo.backdropUrl(item, 1080)?.let { ScreensaverPicture(it, logoUrl(repo, item), item.name.orEmpty()) }
            }
    }
    val photos = if (shows == "titles") emptyList() else {
        repo.items(ItemQuery(includeItemTypes = listOf("Photo"), sortBy = "Random", limit = 60)).mapNotNull { photo ->
            repo.imageUrl(photo.id, ImageKind.Primary, photo.imageTags["Primary"], 1080)?.let { ScreensaverPicture(it, null, "") }
        }
    }
    return (titles + photos).shuffled()
}

private fun logoUrl(repo: MediaRepository, item: BaseItem): String? =
    item.imageTags["Logo"]?.let { repo.imageUrl(item.id, ImageKind.Logo, it, 300) }

package dev.mediacenter.jf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.background
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.media3.ui.compose.ContentFrame
import androidx.compose.ui.unit.dp
import dev.mediacenter.jf.LocalAppState
import dev.mediacenter.jf.ui.screens.AlbumScreen
import dev.mediacenter.jf.ui.screens.DetailsScreen
import dev.mediacenter.jf.ui.screens.GuideScreen
import dev.mediacenter.jf.ui.screens.ProgramScreen
import dev.mediacenter.jf.ui.screens.InsetWidth
import dev.mediacenter.jf.ui.screens.IntroScreen
import dev.mediacenter.jf.ui.screens.LibraryScreen
import dev.mediacenter.jf.ui.screens.LocalInsetPadding
import dev.mediacenter.jf.ui.screens.LoginScreen
import dev.mediacenter.jf.ui.screens.NowPlayingInset
import dev.mediacenter.jf.ui.screens.NowPlayingPill
import dev.mediacenter.jf.ui.screens.PhotoScreen
import dev.mediacenter.jf.ui.screens.PlayerScreen
import dev.mediacenter.jf.ui.screens.SeriesScreen
import dev.mediacenter.jf.ui.screens.SettingsScreen
import dev.mediacenter.jf.ui.screens.StartScreen

@Composable
fun AppHost() {
    val app = LocalAppState.current
    val intro = !app.introShown && app.settings.intro.value
    Box(
        Modifier
            .fillMaxSize()
            // Any key skips the intro, and none reach the menu underneath while it plays.
            .onPreviewKeyEvent {
                if (!intro) return@onPreviewKeyEvent false
                if (it.type == KeyEventType.KeyDown) app.finishIntro()
                true
            },
    ) {
        // The screen underneath loads while the intro plays, and is revealed as it hands over. While the
        // intro covers the whole screen, it isn't drawn at all (a clear layer is skipped outright).
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (app.introCovers) 0f else 1f }) {
            if (app.repository == null) LoginScreen() else Screens()
        }
        // Signing in has nothing to load first; the intro needn't wait for it.
        if (app.repository == null) LaunchedEffect(Unit) { app.introMenuReady = true }
        if (intro) IntroScreen { app.finishIntro() }
        // The screensaver, over everything, after the idle time set in settings (not while a video plays).
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(15_000)
                val minutes = app.settings.screensaver.value
                val np = app.playback.nowPlaying.value
                val watching = np?.isVideo == true && app.playback.player.isPlaying
                val idle = android.os.SystemClock.elapsedRealtime() - app.lastInputAt
                if (minutes > 0 && !app.screensaver && app.repository != null && !watching && idle >= minutes * 60_000L) app.screensaver = true
                val playing = np != null && app.playback.player.isPlaying
                app.idleLong = idle >= 4 * 3_600_000L && !playing
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            app.screensaver,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(1_500)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(400)),
        ) { dev.mediacenter.jf.ui.screens.Screensaver(app) }
    }
}

@Composable
private fun Screens() {
    val app = LocalAppState.current
    val nav = app.navigator
    val top = nav.current
    val nowPlaying by app.playback.nowPlaying.collectAsState()
    val browsing = nowPlaying != null && top !is PlayerDest && top !is PhotoDest && top !is QueueDest
    // Video carries on full-screen behind the menus (as in Media Center); music gets the corner window.
    val videoBehind = browsing && nowPlaying?.isVideo == true && app.settings.backgroundVideo.value
    val showInset = browsing && !videoBehind
    val videoActive = nowPlaying?.isVideo == true
    if (dev.mediacenter.jf.ui.theme.Motion.videoActive != videoActive) dev.mediacenter.jf.ui.theme.Motion.videoActive = videoActive

    // How long each screen takes to show its first frame, noted in the log when slow (for tuning on TV chips).
    androidx.compose.runtime.LaunchedEffect(top.key) {
        val start = android.os.SystemClock.elapsedRealtime()
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        val ms = android.os.SystemClock.elapsedRealtime() - start
        if (ms > 50) dev.mediacenter.jf.AppLog.i("Perf", "${top::class.simpleName} first frame $ms ms")
    }

    BackHandler(enabled = nav.stack.size > 1) {
        app.sounds.back()
        nav.pop()
    }

    val holder = rememberSaveableStateHolder()
    CompositionLocalProvider(LocalInsetPadding provides if (showInset || videoBehind) InsetWidth + 24.dp else 0.dp) {
        Box(Modifier.fillMaxSize()) {
            if (videoBehind) {
                ContentFrame(player = app.playback.player, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color(0xD9020B22), Color(0xA6041A44), Color(0xC0020B22)))
                    )
                )
            }
            CompositionLocalProvider(LocalVideoBehind provides videoBehind) {
            AnimatedContent(
                targetState = top,
                contentKey = { it.key },
                transitionSpec = {
                    if (dev.mediacenter.jf.ui.theme.Motion.videoActive) fadeIn(tween(120)) togetherWith fadeOut(tween(90))
                    else (fadeIn(tween(260, delayMillis = 60)) + scaleIn(tween(300), initialScale = 0.965f)) togetherWith fadeOut(tween(140))
                },
                label = "screens",
            ) { dest ->
                holder.SaveableStateProvider(dest.key) {
                    when (dest) {
                        is StartDest -> StartScreen(dest)
                        is LibraryDest -> LibraryScreen(dest)
                        is DetailsDest -> DetailsScreen(dest)
                        is SeriesDest -> SeriesScreen(dest)
                        is AlbumDest -> AlbumScreen(dest)
                        is PhotoDest -> PhotoScreen(dest)
                        is PlayerDest -> PlayerScreen()
                        is SettingsDest -> SettingsScreen()
                        is OptimizeDest -> dev.mediacenter.jf.ui.screens.OptimizeScreen(dest)
                        is ServerSetupDest -> LoginScreen(dest)
                        is GuideDest -> GuideScreen(dest)
                        is ProgramDest -> ProgramScreen(dest)
                        is PersonDest -> dev.mediacenter.jf.ui.screens.PersonScreen(dest)
                        is QueueDest -> dev.mediacenter.jf.ui.screens.QueueScreen(dest)
                        is CatalogDest -> dev.mediacenter.jf.ui.screens.CatalogScreen(dest)
                        is SearchDest -> dev.mediacenter.jf.ui.screens.SearchScreen(dest)
                    }
                }
            }
            }
            if (showInset) NowPlayingInset(Modifier.align(Alignment.BottomStart))
            if (videoBehind) NowPlayingPill(Modifier.align(Alignment.BottomStart))
            dev.mediacenter.jf.ui.screens.ToastHost()
            dev.mediacenter.jf.ui.screens.ItemMenuHost()
        }
    }
}

/** True while a video plays behind the menus; screens skip their own full-screen backdrops then. */
val LocalVideoBehind = compositionLocalOf { false }

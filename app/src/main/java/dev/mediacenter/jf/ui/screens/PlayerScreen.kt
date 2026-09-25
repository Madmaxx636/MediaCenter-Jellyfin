package dev.mediacenter.jf.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.draw.clip
import dev.mediacenter.jf.playback.TrickplayFrames
import dev.mediacenter.jf.playback.UpNext
import dev.mediacenter.jf.ui.components.aeroGlass
import dev.mediacenter.jf.data.thumbUrl
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.ui.compose.ContentFrame
import dev.mediacenter.jf.LocalAppState
import dev.mediacenter.jf.data.posterUrl
import dev.mediacenter.jf.playback.NowPlaying
import dev.mediacenter.jf.playback.PlaybackManager
import dev.mediacenter.jf.playback.TrackOption
import dev.mediacenter.jf.ui.PlayerDest
import dev.mediacenter.jf.ui.components.ActionButton
import dev.mediacenter.jf.ui.components.Artwork
import dev.mediacenter.jf.ui.components.BusyIndicator
import dev.mediacenter.jf.ui.components.FocusBox
import dev.mediacenter.jf.ui.components.GlassPanel
import dev.mediacenter.jf.ui.components.Glyph
import dev.mediacenter.jf.ui.components.GlyphIcon
import dev.mediacenter.jf.ui.components.ProgressBar
import dev.mediacenter.jf.ui.components.Reflected
import dev.mediacenter.jf.ui.components.ScreenPadH
import dev.mediacenter.jf.ui.components.TopChrome
import dev.mediacenter.jf.ui.components.WText
import dev.mediacenter.jf.ui.components.formatDuration
import dev.mediacenter.jf.ui.components.formatTime
import dev.mediacenter.jf.ui.components.focusWhenReady
import dev.mediacenter.jf.ui.components.tryFocus
import dev.mediacenter.jf.ui.theme.Wmc
import dev.mediacenter.jf.ui.theme.WmcType
import kotlinx.coroutines.delay
import java.util.Date

private class Progress {
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var buffered by mutableFloatStateOf(0f)
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    val fraction get() = if (duration > 0) position.toFloat() / duration else 0f
}

/**
 * Full-screen playback. Video gets Media Center's transport overlay, which hides
 * itself after a few seconds; music gets the "now playing" page with the queue.
 * With the overlay hidden (each of these can be turned off in settings): OK pauses and plays,
 * holding it brings up the controls; left and right replay 7 seconds and skip 30; down can
 * open the subtitles; and pausing shows what's playing and when it'll end.
 */
@Composable
fun PlayerScreen() {
    val app = LocalAppState.current
    val pm = app.playback
    val np by pm.nowPlaying.collectAsState()
    val error by pm.error.collectAsState()

    LaunchedEffect(np == null) {
        if (np == null && app.navigator.current is PlayerDest) app.navigator.pop()
    }
    val current = np ?: return

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val progress = remember { Progress() }
    LaunchedEffect(Unit) {
        while (true) {
            val p = pm.player
            progress.position = p.currentPosition
            progress.duration = p.duration.takeIf { it > 0 } ?: 0L
            progress.buffered = if (p.duration > 0) p.bufferedPosition.toFloat() / p.duration else 0f
            progress.playing = p.isPlaying
            progress.buffering = p.playbackState == Player.STATE_BUFFERING
            delay(250)
        }
    }

    val isVideo = current.isVideo
    var osd by remember { mutableStateOf(!isVideo || app.settings.controlsAtStart.value) }
    var touched by remember { mutableLongStateOf(0L) }
    var flash by remember { mutableLongStateOf(0L) }
    var trackMenu by remember { mutableStateOf<Int?>(null) }
    val segment by pm.segment.collectAsState()
    val upNext by pm.upNext.collectAsState()
    val trickplay by pm.trickplay.collectAsState()
    // Scrubbing: left/right move a target position with previews; the jump happens when you stop pressing.
    var scrubTarget by remember { mutableStateOf<Long?>(null) }
    // Where the scrub started, so a replay can bring subtitles on for what it goes back over.
    var scrubFrom by remember { mutableLongStateOf(0L) }
    fun commitScrub(target: Long) {
        pm.player.seekTo(target)
        if (target < scrubFrom && app.settings.replaySubtitles.value) pm.subtitlesForReplay(until = scrubFrom)
        scrubTarget = null
    }
    LaunchedEffect(scrubTarget) {
        val target = scrubTarget ?: return@LaunchedEffect
        delay(650)
        commitScrub(target)
    }
    // OK pressed with the controls hidden: a tap pauses or plays when it's let go; held, it brings up the controls.
    var okHeld by remember { mutableStateOf(false) }
    val root = remember { FocusRequester() }
    val playButton = remember { FocusRequester() }
    // Starting without the controls: the remote goes straight to the picture.
    LaunchedEffect(Unit) { if (!osd) root.focusWhenReady() }

    fun hideOsd() {
        root.tryFocus()
        osd = false
    }

    LaunchedEffect(osd, touched, progress.playing, trackMenu, isVideo) {
        if (isVideo && osd && progress.playing && trackMenu == null) {
            delay(5_000)
            hideOsd()
        }
    }
    LaunchedEffect(osd) {
        if (osd) {
            playButton.focusWhenReady()
        }
    }

    BackHandler(enabled = error != null || trackMenu != null || upNext != null || scrubTarget != null || (isVideo && osd)) {
        app.sounds.back()
        when {
            upNext != null -> pm.cancelUpNext()
            scrubTarget != null -> scrubTarget = null
            error != null -> {
                pm.clearError()
                if (pm.player.playbackState == Player.STATE_IDLE) pm.stop()
            }
            trackMenu != null -> trackMenu = null
            else -> hideOsd()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .then(if (isVideo) Modifier.background(Color.Black) else Modifier)
            .focusRequester(root)
            .focusable()
            .onPreviewKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                // A tap of OK acts when it's let go, so holding it can bring up the controls instead.
                if (e.type == KeyEventType.KeyUp) {
                    if (!ok || !okHeld) return@onPreviewKeyEvent false
                    okHeld = false
                    pm.togglePlayPause()
                    return@onPreviewKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                touched = System.nanoTime()
                if (osd || error != null || trackMenu != null || upNext != null) return@onPreviewKeyEvent false
                val settings = app.settings
                fun scrub(direction: Int) {
                    val stepSec = if (direction < 0) settings.replaySeconds.value else settings.skipSeconds.value
                    // Holding the button speeds up the scrub.
                    val boost = if (e.nativeKeyEvent.repeatCount > 8) 4 else if (e.nativeKeyEvent.repeatCount > 2) 2 else 1
                    val duration = pm.player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                    if (scrubTarget == null) scrubFrom = pm.player.currentPosition
                    val from = scrubTarget ?: pm.player.currentPosition
                    scrubTarget = (from + direction * stepSec * 1000L * boost).coerceIn(0L, duration)
                    flash = System.nanoTime()
                }
                when (e.key) {
                    Key.DirectionUp -> if (current.isLive) pm.channel(1) else osd = true
                    Key.DirectionDown -> when {
                        current.isLive -> pm.channel(-1)
                        settings.downForTracks.value -> trackMenu = C.TRACK_TYPE_TEXT
                        else -> osd = true
                    }
                    Key.DirectionLeft -> if (settings.arrowSkip.value && !current.isLive) scrub(-1) else osd = true
                    Key.DirectionRight -> if (settings.arrowSkip.value && !current.isLive) scrub(1) else osd = true
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> when {
                        scrubTarget != null -> commitScrub(scrubTarget!!)
                        segment != null -> pm.skipCurrentSegment()
                        !settings.okPauses.value || current.isLive -> osd = true
                        // Held down: the controls, and the tap no longer counts.
                        e.nativeKeyEvent.repeatCount > 0 -> if (okHeld) { okHeld = false; osd = true }
                        else -> okHeld = true
                    }
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        if (isVideo) {
            ContentFrame(player = pm.player, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            SubtitleOverlay(pm, raised = osd)
            if (progress.buffering) BusyIndicator(Modifier.align(Alignment.Center), 64.dp)
            AnimatedVisibility(osd, enter = fadeIn(tween(120)), exit = fadeOut(tween(160))) {
                // The overlay has its own size setting, smaller than menus by default, so it covers less of the picture.
                val base = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density * app.settings.playerScale.value, base.fontScale),
                ) {
                    VideoOverlay(current, pm, progress, playButton) { trackMenu = it }
                }
            }
            ScrubPreview(scrubTarget, progress, trickplay, Modifier.align(Alignment.BottomCenter))
            PauseInfo(
                current, progress,
                visible = app.settings.pauseInfo.value && !osd && !progress.playing && !progress.buffering &&
                    scrubTarget == null && error == null && trackMenu == null && upNext == null && !current.isLive,
            )
            segment?.let { seg ->
                if (upNext == null) SkipPill(seg.label, osd, Modifier.align(Alignment.BottomEnd)) { pm.skipCurrentSegment() }
            }
            upNext?.let { UpNextCard(it, pm, Modifier.align(Alignment.BottomEnd)) }
            if (current.isLive && app.settings.channelBanner.value) ChannelBanner(current.item, osd, Modifier.align(Alignment.TopStart))
        } else {
            NowPlayingPage(current, pm, progress, playButton)
        }

        trackMenu?.let { type ->
            TrackMenu(pm, type, Modifier.align(Alignment.CenterEnd)) { trackMenu = null }
        }
        error?.let { message ->
            ErrorPanel(message, Modifier.align(Alignment.Center)) {
                pm.clearError()
                if (pm.player.playbackState == Player.STATE_IDLE) pm.stop()
            }
        }
    }
}

/**
 * Windows 7 Media Center's playback overlay: the back button and orb top-left,
 * what's playing bottom-left, and the compact transport strip bottom-right.
 */
@Composable
private fun VideoOverlay(np: NowPlaying, pm: PlaybackManager, progress: Progress, playButton: FocusRequester, onTracks: (Int) -> Unit) {
    val context = LocalContext.current
    val item = np.item
    val program = if (np.isLive) rememberCurrentProgram(item) else null
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxWidth().height(110.dp).align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent)))
        )
        TopChrome()
        Box(
            Modifier.fillMaxWidth().fillMaxHeight(0.42f).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x9902060F), Color(0xE0010307))))
        )
        Row(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = ScreenPadH).padding(bottom = 26.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f).padding(end = 24.dp)) {
                if (np.isLive) {
                    LiveInfo(item, program, context)
                } else {
                    WText(if (item.type == "Episode") item.seriesName ?: "" else item.name ?: "", WmcType.Heading.copy(fontWeight = FontWeight.Normal))
                    val sub = if (item.type == "Episode") listOfNotNull(episodeCode(item), item.name).joinToString("  \u00b7  ") else metaLine(item)
                    if (sub.isNotEmpty()) WText(sub, WmcType.Label, color = Wmc.TextDim)
                    ProgressBar(progress.fraction, Modifier.padding(top = 10.dp).fillMaxWidth(0.9f), progress.buffered, thickness = 4.dp)
                    val remaining = progress.duration - progress.position
                    WText(
                        "${formatDuration(progress.position)} / ${formatDuration(progress.duration)}   \u00b7   ends at ${formatTime(Date(System.currentTimeMillis() + remaining), context)}",
                        WmcType.Caption, color = Wmc.TextDim,
                    )
                }
            }
            TransportStrip(np, pm, progress.playing, playButton, onTracks)
        }
    }
}

/** The row of small transport buttons with the glossy play/pause orb in the middle. */
@Composable
private fun TransportStrip(
    np: NowPlaying,
    pm: PlaybackManager,
    playing: Boolean,
    playButton: FocusRequester,
    onTracks: ((Int) -> Unit)? = null,
) {
    val app = LocalAppState.current
    val scope = rememberCoroutineScope()

    // Flat icons straight over the video, as in Windows 7 Media Center; no bar behind them.
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (np.isLive) {
            val program = rememberCurrentProgram(np.item)
            var recording by remember(program?.id) { mutableStateOf(program?.timerId != null) }
            StripButton(Glyph.Record, tint = if (recording) Color(0xFFFF5A3C) else Color(0xFFE0453A)) {
                val p = program ?: return@StripButton
                scope.launch {
                    val repo = app.repository ?: return@launch
                    runCatching { if (recording) repo.cancelRecording(p, false) else repo.record(p, false) }
                        .onSuccess { recording = !recording }
                }
            }
            StripButton(Glyph.Info) { program?.let { app.navigator.push(dev.mediacenter.jf.ui.ProgramDest(it.id)) } }
            StripButton(Glyph.ListLines) { app.navigator.push(dev.mediacenter.jf.ui.GuideDest()) }
            StripButton(Glyph.Minus) { pm.channel(-1) }
            StripButton(Glyph.Plus) { pm.channel(1) }
            StripDivider()
        }
        StripButton(Glyph.Stop) { pm.stop() }
        StripDivider()
        StripButton(Glyph.Rewind) { pm.replay() }
        StripButton(Glyph.SkipBack) { if (np.isLive) pm.channel(-1) else pm.previous() }
        PlayOrb(playing, Modifier.focusRequester(playButton)) { pm.togglePlayPause() }
        StripButton(Glyph.SkipNext) { if (np.isLive) pm.channel(1) else pm.next() }
        StripButton(Glyph.FastForward) { pm.skip() }
        StripDivider()
        // Mute the app's own audio: TV boxes usually have fixed-volume HDMI, so system volume keys do nothing.
        var muted by remember { mutableStateOf(pm.player.volume == 0f) }
        StripButton(Glyph.Audio, tint = if (muted) Color(0xFFFF8A6A) else Color(0xFFD6E4F2)) {
            muted = !muted
            pm.player.volume = if (muted) 0f else 1f
        }
        if (onTracks != null) {
            StripDivider()
            StripButton(Glyph.Subtitles) { onTracks(C.TRACK_TYPE_TEXT) }
            StripButton(Glyph.Collections) { onTracks(C.TRACK_TYPE_AUDIO) }
        }
    }
}

@Composable
private fun StripDivider() =
    Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(20.dp).background(Color(0x66B8D4F0)))

/** A small flat transport icon that lights up with a cyan halo when focused. */
@Composable
private fun StripButton(glyph: Glyph, tint: Color = Color(0xFFD6E4F2), onClick: () -> Unit) {
    val sounds = LocalAppState.current.sounds
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(38.dp)
            .onFocusChanged { if (it.isFocused != focused) { focused = it.isFocused; if (focused) sounds.focus() } }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { sounds.select(); onClick() }
            .drawBehind {
                if (focused) {
                    drawCircle(Brush.radialGradient(listOf(Color(0x9955B8FF), Color.Transparent)), size.minDimension * 0.62f)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, size = if (focused) 22.dp else 18.dp, color = if (focused) Color.White else tint)
    }
}

/** The glossy blue play/pause orb. */
@Composable
private fun PlayOrb(playing: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val sounds = LocalAppState.current.sounds
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .padding(horizontal = 6.dp)
            .size(50.dp)
            .onFocusChanged { if (it.isFocused != focused) { focused = it.isFocused; if (focused) sounds.focus() } }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { sounds.select(); onClick() }
            .drawBehind {
                val r = size.minDimension / 2
                if (focused) drawCircle(Brush.radialGradient(listOf(Color(0xAA6CC8FF), Color.Transparent), center, r * 1.5f), r * 1.5f)
                drawCircle(Brush.radialGradient(listOf(Color(0xFF7CC6FF), Color(0xFF1F6FD0), Color(0xFF0A3478)), center.copy(y = center.y + r * 0.35f), r * 1.2f), r)
                drawCircle(Color(0xCCBFE3FF), r, style = androidx.compose.ui.graphics.drawscope.Stroke(1.2f))
                drawOval(
                    Brush.verticalGradient(listOf(Color(0xC8FFFFFF), Color(0x10FFFFFF)), startY = center.y - r * 0.92f, endY = center.y),
                    topLeft = androidx.compose.ui.geometry.Offset(center.x - r * 0.62f, center.y - r * 0.9f),
                    size = androidx.compose.ui.geometry.Size(r * 1.24f, r * 0.82f),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(if (playing) Glyph.Pause else Glyph.Play, size = 22.dp)
    }
}

/** Draws the current subtitles over the video, sized and styled by the subtitle settings. */
@Composable
private fun SubtitleOverlay(pm: PlaybackManager, raised: Boolean) {
    val settings = LocalAppState.current.settings
    val cues by pm.cues.collectAsState()
    if (cues.isEmpty()) return
    val scale = settings.subtitleSize.value
    val band = settings.subtitleBackground.value
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val bitmaps = cues.filter { it.bitmap != null }
        bitmaps.forEach { cue ->
            val bmp = cue.bitmap!!
            val w = maxWidth * (if (cue.size != androidx.media3.common.text.Cue.DIMEN_UNSET) cue.size else 0.5f)
            val h = if (cue.bitmapHeight != androidx.media3.common.text.Cue.DIMEN_UNSET) maxHeight * cue.bitmapHeight else w * (bmp.height.toFloat() / bmp.width)
            val x = maxWidth * (if (cue.position != androidx.media3.common.text.Cue.DIMEN_UNSET) cue.position else 0.5f) -
                if (cue.positionAnchor == androidx.media3.common.text.Cue.ANCHOR_TYPE_MIDDLE) w / 2 else 0.dp
            val y = maxHeight * (if (cue.line != androidx.media3.common.text.Cue.DIMEN_UNSET) cue.line else 0.85f)
            androidx.compose.foundation.Image(
                bmp.asImageBitmap(), null,
                Modifier.offset(x = x, y = y).size(w, h),
            )
        }
        val text = cues.mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        if (text.isNotEmpty()) {
            val style = androidx.compose.ui.text.TextStyle(
                fontSize = 30.sp * scale,
                lineHeight = 38.sp * scale,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                textAlign = TextAlign.Center,
                shadow = androidx.compose.ui.graphics.Shadow(Color.Black, androidx.compose.ui.geometry.Offset(2f, 2f), 4f),
            )
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = if (raised) 180.dp else 48.dp, start = 80.dp, end = 80.dp)
                    .then(if (band) Modifier.background(Color(0xB0000000), RoundedCornerShape(4.dp)).padding(horizontal = 14.dp, vertical = 4.dp) else Modifier),
            ) {
                androidx.compose.foundation.text.BasicText(text, style = style)
            }
        }
    }
}

/** While scrubbing: where you'll land, with a trickplay frame when the server has them. */
/**
 * Paused with the controls hidden: what's playing, how far in, the time and when it'll end, over
 * a soft shade at the bottom, with the pause sign, as Media Center showed a paused film.
 */
@Composable
private fun PauseInfo(np: NowPlaying, progress: Progress, visible: Boolean) {
    val context = LocalContext.current
    AnimatedVisibility(visible, enter = fadeIn(tween(220, delayMillis = 120)), exit = fadeOut(tween(160))) {
        // "Ends at" moves on while paused; keep it current.
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { delay(5_000); now = System.currentTimeMillis() } }
        val item = np.item
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxWidth().height(110.dp).align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(Color(0x88000000), Color.Transparent)))
            )
            TopChrome(showBack = false)
            Box(
                Modifier.fillMaxWidth().fillMaxHeight(0.36f).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x9902060F), Color(0xE0010307))))
            )
            Row(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = ScreenPadH).padding(bottom = 30.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(Modifier.weight(1f).padding(end = 32.dp)) {
                    WText(if (item.type == "Episode") item.seriesName ?: "" else item.name ?: "", WmcType.Heading.copy(fontWeight = FontWeight.Normal))
                    val sub = if (item.type == "Episode") listOfNotNull(episodeCode(item), item.name).joinToString("  \u00b7  ") else metaLine(item)
                    if (sub.isNotEmpty()) WText(sub, WmcType.Label, color = Wmc.TextDim)
                    ProgressBar(progress.fraction, Modifier.padding(top = 10.dp).fillMaxWidth(0.9f), progress.buffered, thickness = 4.dp)
                    val remaining = progress.duration - progress.position
                    WText(
                        "${formatDuration(progress.position)} / ${formatDuration(progress.duration)}   \u00b7   ends at ${formatTime(Date(now + remaining), context)}",
                        WmcType.Caption, color = Wmc.TextDim,
                    )
                }
                // The pause sign in Media Center's glossy orb.
                Box(
                    Modifier.size(64.dp).background(
                        Brush.radialGradient(listOf(Color(0xFF7CC4FF), Color(0xFF2A74C8), Color(0xFF0E3A78))), androidx.compose.foundation.shape.CircleShape,
                    ).border(1.5.dp, Color(0xAAE6F4FF), androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center,
                ) { GlyphIcon(Glyph.Pause, size = 30.dp, color = Color.White) }
            }
        }
    }
}

@Composable
private fun ScrubPreview(target: Long?, progress: Progress, trickplay: TrickplayFrames?, modifier: Modifier) {
    AnimatedVisibility(target != null, modifier, enter = fadeIn(tween(120)), exit = fadeOut(tween(250))) {
        val pos = target ?: progress.position
        val fraction = if (progress.duration > 0) pos.toFloat() / progress.duration else 0f
        BoxWithConstraints(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC010307))))
                .padding(horizontal = ScreenPadH, vertical = 26.dp)
        ) {
            val barWidth = maxWidth
            Column {
                if (trickplay != null) {
                    val thumbW = 240.dp
                    val thumbH = thumbW * trickplay.info.height / trickplay.info.width.coerceAtLeast(1)
                    val x = (barWidth * fraction - thumbW / 2).coerceIn(0.dp, barWidth - thumbW)
                    TrickplayFrame(trickplay, pos, Modifier.offset(x = x).padding(bottom = 10.dp).size(thumbW, thumbH))
                }
                ProgressBar(fraction, buffered = progress.buffered, thickness = 5.dp)
                Row(Modifier.fillMaxWidth()) {
                    WText(formatDuration(pos), WmcType.Label, color = Wmc.Text)
                    Box(Modifier.weight(1f))
                    WText(formatDuration(progress.duration), WmcType.Label, color = Wmc.TextDim)
                }
            }
        }
    }
}

/**
 * The trickplay frame for [positionMs], cut from its sheet as it's needed. The frame before stays up
 * while the next one decodes (a few milliseconds, or a moment longer while a sheet is still arriving).
 */
@Composable
private fun TrickplayFrame(trickplay: TrickplayFrames, positionMs: Long, modifier: Modifier) {
    val index = trickplay.trickplay.frameIndex(positionMs)
    var frame by remember(trickplay) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(trickplay, index) {
        trickplay.prefetch(positionMs)
        trickplay.frame(positionMs)?.let { frame = it }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(3.dp))
            .border(2.dp, Color(0xE6EAF4FF), RoundedCornerShape(3.dp))
            .background(Color.Black)
    ) {
        frame?.let { androidx.compose.foundation.Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
    }
}

/** The glass "skip intro" button. With the overlay hidden, OK presses it. */
@Composable
private fun SkipPill(label: String, osd: Boolean, modifier: Modifier, onSkip: () -> Unit) {
    Box(modifier.padding(end = ScreenPadH, bottom = if (osd) 120.dp else 44.dp)) {
        FocusBox(onClick = onSkip, fill = true, scale = 1.04f, corner = 6.dp) { focused ->
            Row(
                Modifier.aeroGlass(corner = 6.dp, strong = !focused).padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphIcon(Glyph.SkipNext, size = 18.dp)
                WText(label, WmcType.Label, Modifier.padding(start = 10.dp))
                if (!osd) WText("   OK", WmcType.Caption, color = Wmc.TextDim)
            }
        }
    }
}

/** "Up next": the next episode, a countdown, and play now / cancel. */
@Composable
private fun UpNextCard(upNext: UpNext, pm: PlaybackManager, modifier: Modifier) {
    val app = LocalAppState.current
    val repo = app.repository ?: return
    val playNow = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        playNow.focusWhenReady()
    }
    val next = upNext.item
    Row(
        modifier.padding(end = ScreenPadH, bottom = 44.dp).width(620.dp).aeroGlass(corner = 8.dp, strong = true).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(repo.thumbUrl(next, 240), next.name, Modifier.size(208.dp, 117.dp), glyph = Glyph.Tv)
        Column(Modifier.weight(1f).padding(start = 18.dp)) {
            WText("up next  \u00b7  starts in ${upNext.secondsLeft}", WmcType.Label, color = Wmc.Accent)
            WText(next.name ?: "", WmcType.Heading, maxLines = 1)
            WText(listOfNotNull(next.seriesName, episodeCode(next)).joinToString("  \u00b7  "), WmcType.Caption, color = Wmc.TextDim)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("play now", { pm.playUpNextNow() }, Modifier.width(150.dp).focusRequester(playNow), Glyph.Play, height = 38.dp)
                ActionButton("cancel", { pm.cancelUpNext() }, Modifier.width(130.dp), height = 38.dp)
            }
        }
    }
}

@Composable
private fun NowPlayingPage(np: NowPlaying, pm: PlaybackManager, progress: Progress, playButton: FocusRequester) {
    val app = LocalAppState.current
    val repo = app.repository ?: return
    val item = np.item
    Column(Modifier.fillMaxSize()) {
        TopChrome()
        Row(Modifier.fillMaxSize().padding(horizontal = ScreenPadH).padding(top = 18.dp)) {
            Reflected(300.dp) {
                Artwork(repo.posterUrl(item, 600), item.album ?: item.name, Modifier.size(300.dp), glyph = Glyph.Music)
            }
            Column(Modifier.weight(1f).padding(start = 44.dp)) {
                WText("now playing", WmcType.Label, color = Wmc.Accent)
                WText(item.name ?: "", WmcType.Title, Modifier.padding(top = 4.dp), maxLines = 2)
                WText(item.albumArtist ?: item.artists.joinToString(", "), WmcType.Heading, color = Wmc.TextDim)
                item.album?.let { WText(it, WmcType.Label, color = Wmc.TextFaint) }
                ProgressBar(progress.fraction, Modifier.padding(top = 20.dp), progress.buffered)
                Row(Modifier.fillMaxWidth()) {
                    WText(formatDuration(progress.position), WmcType.Caption)
                    Box(Modifier.weight(1f))
                    WText(formatDuration(progress.duration), WmcType.Caption)
                }
                Box(Modifier.padding(top = 14.dp)) { TransportStrip(np, pm, progress.playing, playButton) }
                // Media Center's Now Playing actions.
                val scope = rememberCoroutineScope()
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("view queue", { app.navigator.push(dev.mediacenter.jf.ui.QueueDest()) }, Modifier.width(190.dp), Glyph.ListLines, height = 38.dp)
                    ActionButton("shuffle", { pm.toggleShuffle() }, Modifier.width(170.dp), Glyph.Shuffle, detail = if (np.shuffle) "on" else "off", height = 38.dp)
                    ActionButton(
                        "repeat", { pm.cycleRepeat() }, Modifier.width(190.dp), Glyph.Resume, height = 38.dp,
                        detail = when (np.repeat) { dev.mediacenter.jf.playback.Repeat.Off -> "off"; dev.mediacenter.jf.playback.Repeat.All -> "all"; dev.mediacenter.jf.playback.Repeat.One -> "one" },
                    )
                    ActionButton("play slide show", {
                        scope.launch {
                            val photos = runCatching {
                                val views = repo.views().filter { it.collectionType == "photos" || it.collectionType == "homevideos" }
                                views.flatMap { v -> repo.items(dev.mediacenter.jf.data.ItemQuery(v.id, listOf("Photo"), sortBy = "Random", limit = 300)) }
                            }.getOrDefault(emptyList())
                            if (photos.isNotEmpty()) app.navigator.push(dev.mediacenter.jf.ui.PhotoDest(photos.shuffled(), 0, slideshow = true))
                        }
                    }, Modifier.width(230.dp), Glyph.Pictures, height = 38.dp)
                }
                val upNext = np.queue.drop(np.index + 1).take(4)
                if (upNext.isNotEmpty()) {
                    WText("up next", WmcType.Label, Modifier.padding(top = 22.dp, bottom = 4.dp), color = Wmc.Accent)
                    upNext.forEachIndexed { i, track ->
                        WText(
                            "${track.name ?: ""}  ·  ${track.albumArtist ?: ""}",
                            WmcType.Body,
                            color = if (i == 0) Wmc.TextDim else Wmc.TextFaint,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackMenu(pm: PlaybackManager, type: Int, modifier: Modifier, onClose: () -> Unit) {
    val options = remember(type) { pm.tracks(type) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        first.focusWhenReady()
    }
    GlassPanel(modifier.fillMaxHeight().width(380.dp).padding(vertical = 40.dp).padding(end = ScreenPadH)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            WText(if (type == C.TRACK_TYPE_AUDIO) "audio" else "subtitles", WmcType.Heading, Modifier.padding(bottom = 10.dp))
            if (options.isEmpty()) WText("None available", WmcType.Body)
            val selected = options.indexOfFirst(TrackOption::selected).coerceAtLeast(0)
            options.forEachIndexed { i, option ->
                ActionButton(
                    option.label,
                    onClick = { pm.selectTrack(type, option); onClose() },
                    modifier = if (i == selected) Modifier.focusRequester(first) else Modifier,
                    glyph = if (option.selected) Glyph.Check else null,
                )
            }
        }
    }
}

@Composable
private fun ErrorPanel(message: String, modifier: Modifier, onDismiss: () -> Unit) {
    val ok = remember { FocusRequester() }
    val app = LocalAppState.current
    LaunchedEffect(message) {
        app.sounds.error()
        ok.focusWhenReady()
    }
    val scope = rememberCoroutineScope()
    var sendState by remember { mutableStateOf<String?>(null) }
    GlassPanel(modifier.width(620.dp).background(Color(0xE6061A40))) {
        Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            WText("Can't play this item", WmcType.Heading)
            WText(message, WmcType.Body, maxLines = 6)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton("ok", onDismiss, Modifier.width(140.dp).focusRequester(ok))
                if (app.repository?.isDemo == false) {
                    ActionButton("send log to server", {
                        if (sendState == "sending\u2026") return@ActionButton
                        sendState = "sending\u2026"
                        scope.launch {
                            sendState = runCatching { "Sent. Saved on the server as ${app.sendLog()}" }
                                .getOrElse { "Couldn't send the log: ${it.message}. Check that client log upload is allowed in Jellyfin (Dashboard \u203a General)." }
                        }
                    }, Modifier.width(260.dp), glyph = dev.mediacenter.jf.ui.components.Glyph.Info)
                }
            }
            sendState?.let { WText(it, WmcType.Caption, color = Wmc.TextDim, maxLines = 3) }
        }
    }
}

/** The program on [channel] right now, refreshed when the current one ends. */
@Composable
private fun rememberCurrentProgram(channel: dev.mediacenter.jf.data.BaseItem): dev.mediacenter.jf.data.BaseItem? {
    val app = LocalAppState.current
    val repo = app.repository ?: return channel.currentProgram
    val program by produceState(channel.currentProgram, channel.id) {
        while (true) {
            val now = java.time.Instant.now()
            val end = value?.end
            if (end == null || end <= now) {
                value = runCatching { repo.programs(now, now.plusSeconds(1)).firstOrNull { it.channelId == channel.id } }.getOrNull() ?: value
            }
            delay(30_000)
        }
    }
    return program
}

@Composable
private fun LiveInfo(channel: dev.mediacenter.jf.data.BaseItem, program: dev.mediacenter.jf.data.BaseItem?, context: android.content.Context) {
    val fmt = android.text.format.DateFormat.getTimeFormat(context)
    WText(program?.name ?: channel.name ?: "", WmcType.Title)
    WText(
        listOfNotNull(
            program?.episodeTitle,
            listOfNotNull(channel.channelNumber, channel.name).joinToString(" "),
            program?.start?.let { s -> program.end?.let { e -> "${fmt.format(Date.from(s))} \u2013 ${fmt.format(Date.from(e))}" } },
        ).joinToString("  \u00b7  "),
        WmcType.Label, color = Wmc.TextDim,
    )
    val start = program?.start
    val end = program?.end
    val now = System.currentTimeMillis()
    val fraction = if (start != null && end != null) ((now - start.toEpochMilli()).toFloat() / (end.toEpochMilli() - start.toEpochMilli())) else 0f
    ProgressBar(fraction, Modifier.padding(top = 14.dp))
    Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        WText("live", WmcType.Caption, color = Wmc.Accent)
        Box(Modifier.weight(1f))
        if (end != null) WText("ends at ${fmt.format(Date.from(end))}  \u00b7  up/down change channel", WmcType.Caption, color = Wmc.TextDim)
    }
}

/** The channel number and program that appear for a few seconds after tuning. */
@Composable
private fun ChannelBanner(channel: dev.mediacenter.jf.data.BaseItem, osd: Boolean, modifier: Modifier) {
    var visible by remember { mutableStateOf(false) }
    val program = rememberCurrentProgram(channel)
    LaunchedEffect(channel.id) {
        visible = true
        delay(3_500)
        visible = false
    }
    AnimatedVisibility(visible && !osd, modifier, enter = fadeIn(), exit = fadeOut()) {
        Row(
            Modifier.padding(ScreenPadH, 36.dp)
                .aeroGlass(corner = 8.dp, strong = true)
                .padding(horizontal = 22.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WText(channel.channelNumber ?: "", WmcType.Hero, Modifier.padding(end = 18.dp))
            Column {
                WText(channel.name ?: "", WmcType.Heading)
                program?.name?.let { WText(it, WmcType.Label, color = Wmc.TextDim) }
            }
        }
    }
}

package dev.mediacenter.jf.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.mediacenter.jf.LocalAppState
import dev.mediacenter.jf.data.BaseItem
import dev.mediacenter.jf.data.channelLogoUrl
import dev.mediacenter.jf.playback.NowPlaying
import dev.mediacenter.jf.playback.PlaybackManager
import dev.mediacenter.jf.ui.ProgramDest
import dev.mediacenter.jf.ui.components.Artwork
import dev.mediacenter.jf.ui.components.FocusBox
import dev.mediacenter.jf.ui.components.GlassPanel
import dev.mediacenter.jf.ui.components.Glyph
import dev.mediacenter.jf.ui.components.GlyphIcon
import dev.mediacenter.jf.ui.components.ProgressBar
import dev.mediacenter.jf.ui.components.ScreenPadH
import dev.mediacenter.jf.ui.components.WText
import dev.mediacenter.jf.ui.components.aeroGlass
import dev.mediacenter.jf.ui.components.formatDuration
import dev.mediacenter.jf.ui.components.formatTime
import dev.mediacenter.jf.ui.components.focusWhenReady
import dev.mediacenter.jf.ui.theme.Wmc
import dev.mediacenter.jf.ui.theme.WmcType
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import kotlinx.coroutines.delay
import java.time.Instant
import java.util.Date

/**
 * The quick info bar (up, with the controls hidden): what's playing, the time and when it'll end,
 * and what the picture and sound are, in a slim band along the top. It goes by itself.
 */
@Composable
internal fun InfoBar(np: NowPlaying, positionMs: Long, durationMs: Long, visible: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    AnimatedVisibility(
        visible, modifier,
        enter = fadeIn(tween(160)) + slideInVertically(tween(220)) { -it / 2 },
        exit = fadeOut(tween(200)) + slideOutVertically(tween(220)) { -it / 2 },
    ) {
        val item = np.item
        val stream = np.stream
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
        Box(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xE6010307), Color(0x99010307), Color.Transparent)))
                .padding(horizontal = ScreenPadH).padding(top = 28.dp, bottom = 40.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    WText(if (item.type == "Episode") item.seriesName ?: "" else item.name ?: "", WmcType.Heading.copy(fontWeight = FontWeight.Normal), maxLines = 1)
                    val sub = if (item.type == "Episode") listOfNotNull(episodeCode(item), item.name).joinToString("  ·  ") else null
                    sub?.let { WText(it, WmcType.Label, color = Wmc.TextDim, maxLines = 1) }
                    val facts = listOfNotNull(
                        stream?.videoLabel,
                        stream?.audioLabel,
                        stream?.bitrate?.takeIf { it > 0 }?.let { "%.1f Mbps".format(it / 1_000_000.0) },
                        when {
                            stream == null -> null
                            stream.isTranscode -> "converted by the server"
                            else -> "playing as it is"
                        },
                    )
                    if (facts.isNotEmpty()) WText(facts.joinToString("  ·  "), WmcType.Caption, Modifier.padding(top = 6.dp), color = Wmc.Accent, maxLines = 1)
                }
                Column(horizontalAlignment = Alignment.End) {
                    WText(formatTime(Date(now), context), WmcType.Heading)
                    if (durationMs > 0) {
                        WText(
                            "${formatDuration(positionMs)} / ${formatDuration(durationMs)}  ·  ends at ${formatTime(Date(now + durationMs - positionMs), context)}",
                            WmcType.Caption, color = Wmc.TextDim,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The playback panel: sound and subtitle sync, speed and night mode. Left and right adjust the
 * focused row; the changes apply at once. Opened from the controls.
 */
@Composable
internal fun PlaybackPanel(pm: PlaybackManager, modifier: Modifier, onClose: () -> Unit) {
    val settings = LocalAppState.current.settings
    val audioDelay by pm.audioDelay.collectAsState()
    val subtitleDelay by pm.subtitleDelay.collectAsState()
    val speed by pm.speed.collectAsState()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.focusWhenReady() }
    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    fun ms(v: Long) = when {
        v == 0L -> "in sync"
        else -> (if (v > 0) "+" else "−") + "${kotlin.math.abs(v)} ms"
    }
    GlassPanel(modifier.fillMaxHeight().width(460.dp).padding(vertical = 40.dp).padding(end = ScreenPadH)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            WText("playback", WmcType.Heading, Modifier.padding(bottom = 10.dp))
            AdjustRow(
                "sound", ms(audioDelay), Modifier.focusRequester(first),
                hint = "‹ earlier  ·  later ›",
                onStep = { pm.setAudioDelay(audioDelay + it * 50) },
            )
            AdjustRow("subtitles", ms(subtitleDelay), hint = "‹ earlier  ·  later ›", onStep = { pm.setSubtitleDelay(subtitleDelay + it * 250) })
            AdjustRow(
                "speed", if (speed == 1f) "normal" else "${speed.toString().removeSuffix(".0")}×",
                hint = "not with sound passed to a receiver",
                onStep = { step -> speeds.getOrNull(speeds.indexOf(speed) + step)?.let(pm::setSpeed) },
            )
            AdjustRow(
                "night mode", if (android.os.Build.VERSION.SDK_INT < 28) "needs Android 9" else if (settings.nightMode.value) "on" else "off",
                hint = "evens out loud and quiet",
                onStep = { pm.setNightMode(!settings.nightMode.value) },
                onClick = { pm.setNightMode(!settings.nightMode.value) },
            )
            Box(Modifier.height(8.dp))
            AdjustRow("reset", "", hint = "sound and subtitles back in sync, normal speed", onStep = {}, onClick = {
                pm.setAudioDelay(0); pm.setSubtitleDelay(0); pm.setSpeed(1f)
            })
            AdjustRow("done", "", onStep = {}, onClick = onClose)
        }
    }
}

/** A row that left and right adjust (‹ value ›), and OK can act on. */
@Composable
private fun AdjustRow(label: String, value: String, modifier: Modifier = Modifier, hint: String? = null, onStep: (Int) -> Unit, onClick: () -> Unit = {}) {
    val sounds = LocalAppState.current.sounds
    FocusBox(
        onClick = onClick, fill = true, scale = 1.02f, corner = 4.dp,
        modifier = modifier.fillMaxWidth().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.DirectionLeft -> { onStep(-1); sounds.focus(); true }
                Key.DirectionRight -> { onStep(1); sounds.focus(); true }
                else -> false
            }
        },
    ) { f ->
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WText(label, WmcType.Label, Modifier.weight(1f), color = if (f) Wmc.Text else Wmc.TextDim)
                if (value.isNotEmpty()) WText(if (f) "‹  $value  ›" else value, WmcType.Label, color = if (f) Wmc.Text else Wmc.TextDim)
            }
            if (f && hint != null) WText(hint, WmcType.Caption, color = Wmc.TextFaint)
        }
    }
}

/**
 * Media Center's mini guide over live TV: a band along the bottom with a channel's program now and
 * next. Up and down look through the channels (the picture stays on the one you're watching),
 * left and right move between now and next, OK tunes in (or, on what's next, shows its details).
 */
@Composable
internal fun MiniGuide(np: NowPlaying, pm: PlaybackManager, modifier: Modifier, onClose: () -> Unit) {
    val app = LocalAppState.current
    val repo = app.repository ?: return
    val context = LocalContext.current
    val channels = np.queue
    var browse by remember { mutableIntStateOf(np.index) }
    var slot by remember { mutableIntStateOf(0) }
    var touched by remember { mutableLongStateOf(System.nanoTime()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.focusWhenReady() }
    // Goes by itself after a while without a button.
    LaunchedEffect(touched) { delay(10_000); onClose() }
    // Every channel's programs for the next few hours, fetched once when the guide opens.
    val programs by produceState<Map<String, List<BaseItem>>>(emptyMap()) {
        val now = Instant.now()
        value = runCatching { repo.programs(now, now.plusSeconds(6 * 3600)) }.getOrDefault(emptyList())
            .groupBy { it.channelId ?: "" }.mapValues { (_, list) -> list.sortedBy { it.start } }
    }
    val channel = channels.getOrNull(browse) ?: return
    val now = Instant.now()
    val list = programs[channel.id].orEmpty().filter { (it.end ?: now) > now }
    val onNow = list.firstOrNull { (it.start ?: now) <= now } ?: channel.currentProgram
    val next = list.firstOrNull { it.id != onNow?.id && (it.start ?: now) > now }
    val fmt = android.text.format.DateFormat.getTimeFormat(context)
    fun span(p: BaseItem?) = p?.start?.let { s -> p.end?.let { e -> "${fmt.format(Date.from(s))} – ${fmt.format(Date.from(e))}" } }

    Box(
        modifier.fillMaxWidth().focusRequester(focus).focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                touched = System.nanoTime()
                when (e.key) {
                    Key.DirectionUp -> { browse = (browse + 1) % channels.size; slot = 0; app.sounds.focus() }
                    Key.DirectionDown -> { browse = (browse - 1 + channels.size) % channels.size; slot = 0; app.sounds.focus() }
                    Key.DirectionLeft -> if (slot > 0) { slot = 0; app.sounds.focus() }
                    Key.DirectionRight -> if (slot < 1 && next != null) { slot = 1; app.sounds.focus() }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (e.nativeKeyEvent.repeatCount > 0) return@onPreviewKeyEvent true
                        app.sounds.select()
                        if (slot == 1 && next != null) {
                            onClose(); app.navigator.push(ProgramDest(next.id))
                        } else {
                            if (browse != np.index) pm.channel(browse - np.index)
                            onClose()
                        }
                    }
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC010307), Color(0xF0010307))))
            .padding(horizontal = ScreenPadH).padding(top = 60.dp, bottom = 30.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The channel: logo (or its number), number and name, with arrows to show up and down browse.
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(200.dp)) {
                WText("▲", WmcType.Caption, color = Wmc.TextFaint)
                Artwork(repo.channelLogoUrl(channel, 120), channel.name, Modifier.size(150.dp, 84.dp), glyph = Glyph.LiveTv, showTitle = false, contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                WText(listOfNotNull(channel.channelNumber, channel.name).joinToString("  "), WmcType.Label, maxLines = 1)
                WText("▼", WmcType.Caption, color = Wmc.TextFaint)
            }
            GuideCell(
                "now", onNow?.name ?: "No listing", span(onNow), selected = slot == 0, Modifier.weight(1.2f).padding(start = 18.dp),
                progress = onNow?.let { p ->
                    val s = p.start?.toEpochMilli(); val e = p.end?.toEpochMilli()
                    if (s != null && e != null && e > s) ((now.toEpochMilli() - s).toFloat() / (e - s)) else null
                },
                watching = browse == np.index,
            )
            GuideCell("next", next?.name ?: "—", span(next), selected = slot == 1, Modifier.weight(1f).padding(start = 12.dp))
        }
    }
}

@Composable
private fun GuideCell(
    heading: String, title: String, time: String?, selected: Boolean, modifier: Modifier,
    progress: Float? = null, watching: Boolean = false,
) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
    Column(
        modifier.then(
            // The chosen cell glows as Media Center's focus does; the other stays plain glass.
            if (selected) Modifier.background(Brush.verticalGradient(listOf(Wmc.FocusTop, Wmc.FocusBottom)), shape).border(1.5.dp, Color(0xCCE6F4FF), shape)
            else Modifier.aeroGlass(corner = 6.dp)
        ).padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WText(heading, WmcType.Caption, color = if (selected) Wmc.Text else Wmc.Accent)
            if (watching) {
                GlyphIcon(Glyph.NowPlaying, Modifier.padding(start = 8.dp), size = 14.dp, color = Wmc.Accent)
                WText("watching", WmcType.Caption, Modifier.padding(start = 4.dp), color = Wmc.TextDim)
            }
        }
        WText(title, WmcType.Heading, maxLines = 1)
        time?.let { WText(it, WmcType.Caption, color = if (selected) Wmc.Text else Wmc.TextDim) }
        progress?.let { ProgressBar(it.coerceIn(0f, 1f), Modifier.padding(top = 6.dp), thickness = 3.dp) }
    }
}

/**
 * Styled (ASS/SSA) subtitles: libass's own layer, placed exactly over the picture (letterboxing and
 * all), where it draws each frame's subtitles with their fonts, colours, positions and animations.
 */
@Composable
internal fun AssLayer(handler: io.github.peerless2012.ass.media.AssHandler, player: androidx.media3.common.Player) {
    val presentation = androidx.media3.ui.compose.state.rememberPresentationState(player)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.runtime.key(handler) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { io.github.peerless2012.ass.media.widget.AssSubtitleView(it, handler) },
                modifier = Modifier.resizeWithContentScale(androidx.compose.ui.layout.ContentScale.Fit, presentation.videoSizeDp),
            )
        }
    }
}

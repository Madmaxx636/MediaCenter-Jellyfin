package dev.mediacenter.jf.ui.screens

import dev.mediacenter.jf.ui.components.aeroGlass
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.mediacenter.jf.LocalAppState
import dev.mediacenter.jf.data.BaseItem
import dev.mediacenter.jf.data.ImageKind
import dev.mediacenter.jf.data.MediaRepository
import dev.mediacenter.jf.data.channelLogoUrl
import dev.mediacenter.jf.ui.GuideDest
import dev.mediacenter.jf.ui.ProgramDest
import dev.mediacenter.jf.ui.components.Artwork
import dev.mediacenter.jf.ui.components.CenteredBusy
import dev.mediacenter.jf.ui.components.CenteredMessage
import dev.mediacenter.jf.ui.components.Glyph
import dev.mediacenter.jf.ui.components.ScreenPadH
import dev.mediacenter.jf.ui.components.TopChrome
import dev.mediacenter.jf.ui.components.WText
import dev.mediacenter.jf.ui.components.focusWhenReady
import dev.mediacenter.jf.ui.components.tryFocus
import dev.mediacenter.jf.ui.theme.Wmc
import dev.mediacenter.jf.ui.theme.WmcType
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

private val Slot: Duration = Duration.ofMinutes(30)
private const val VisibleSlots = 4 // two hours across the screen
private val RowH = 40.dp
private val RowGap = 3.dp
private val ChannelColW = 200.dp
private val LoadChunk: Duration = Duration.ofHours(6)

private fun floorToSlot(t: Instant): Instant = Instant.ofEpochSecond(t.epochSecond - t.epochSecond % Slot.seconds)

/** The part of the guide a focused cell covers: a program, or an empty half-hour when there's no listing. */
private data class Span(val start: Instant, val end: Instant, val program: BaseItem?)

/**
 * Media Center's TV guide: channels down the left, half-hour columns across,
 * and details of the focused program along the bottom. Up/down change channel,
 * left/right move through the schedule, OK watches (or opens a future program
 * so you can record it).
 */
@Composable
fun GuideScreen(dest: GuideDest) {
    val app = LocalAppState.current
    val repo = app.repository ?: return
    val context = LocalContext.current
    val now by produceState(Instant.now()) {
        while (true) {
            delay(30_000)
            value = Instant.now()
        }
    }
    val earliest = floorToSlot(now)

    var channels by remember { mutableStateOf<List<BaseItem>?>(null) }
    val programs = remember { mutableStateMapOf<String, List<BaseItem>>() }
    var loadedUntil by remember { mutableStateOf<Instant?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var windowStart by remember { mutableStateOf(earliest) }
    var firstRow by remember { mutableIntStateOf(0) }
    var rowsOnScreenState by remember { mutableIntStateOf(6) }
    val focus = remember { FocusRequester() }
    val ok = remember { OkPress() }

    suspend fun load(from: Instant, to: Instant) {
        val list = repo.programs(from, to)
        list.groupBy { it.channelId }.forEach { (channelId, items) ->
            if (channelId == null) return@forEach
            programs[channelId] = (programs[channelId].orEmpty() + items).distinctBy { it.id }.sortedBy { it.start }
        }
        loadedUntil = to
    }

    LaunchedEffect(Unit) {
        try {
            val list = repo.channels()
            channels = list
            if (dest.channel !in list.indices) {
                dest.channel = list.indexOfFirst { it.id == app.store.lastChannelId }.coerceAtLeast(0)
            }
            if (dest.anchorEpochSec == 0L) dest.anchorEpochSec = Instant.now().epochSecond
            val anchor = Instant.ofEpochSecond(dest.anchorEpochSec)
            windowStart = maxOf(earliest, floorToSlot(anchor).minus(Slot))
            load(earliest, earliest.plus(LoadChunk))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.message ?: "Couldn't load the guide."
        }
    }
    // Fetch more listings as the guide scrolls towards the end of what's loaded.
    LaunchedEffect(windowStart, loadedUntil) {
        val until = loadedUntil ?: return@LaunchedEffect
        if (windowStart.plus(Slot.multipliedBy(VisibleSlots * 2L)) > until) {
            runCatching { load(until, until.plus(LoadChunk)) }
        }
    }
    LaunchedEffect(channels != null) {
        if (channels == null) return@LaunchedEffect
        app.sounds.quiet()
        focus.focusWhenReady()
    }

    val list = channels
    val windowEnd = windowStart.plus(Slot.multipliedBy(VisibleSlots.toLong()))
    val anchor = Instant.ofEpochSecond(dest.anchorEpochSec.coerceAtLeast(1))

    fun spanAt(channel: BaseItem, t: Instant): Span {
        val p = programs[channel.id]?.firstOrNull { it.start!! <= t && it.end!! > t }
        if (p != null) return Span(p.start!!, p.end!!, p)
        val s = floorToSlot(t)
        return Span(s, s.plus(Slot), null)
    }

    val focusedSpan = list?.getOrNull(dest.channel)?.let { spanAt(it, anchor) }

    fun setAnchor(t: Instant) {
        dest.anchorEpochSec = t.epochSecond
        while (t >= windowStart.plus(Slot.multipliedBy(VisibleSlots.toLong()))) windowStart = windowStart.plus(Slot)
        if (t < windowStart) windowStart = maxOf(earliest, floorToSlot(t))
    }

    Box(Modifier.fillMaxSize()) {
        WText("guide", WmcType.PageTitle, Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = ScreenPadH))
        Column(Modifier.fillMaxSize()) {
            TopChrome()
            when {
                error != null -> CenteredMessage("The guide isn't available", error)
                list == null -> CenteredBusy()
                list.isEmpty() -> CenteredMessage("No channels", "Set up a tuner and guide data in Jellyfin's Live TV settings.")
                else -> BoxWithConstraints(
                    Modifier
                        .fillMaxSize()
                        .focusRequester(focus)
                        .focusable()
                        .onPreviewKeyEvent { e ->
                            if (isOkKey(e)) {
                                val span = focusedSpan ?: return@onPreviewKeyEvent false
                                return@onPreviewKeyEvent ok.handle(
                                    e,
                                    tap = {
                                        app.sounds.select()
                                        val airing = span.start <= now && span.end > now
                                        val program = span.program
                                        if (airing || program == null) app.watchLiveTv(list, list[dest.channel])
                                        else app.navigator.push(ProgramDest(program.id))
                                    },
                                    // Held: what's on (record, watch, more info), or the channel when there's no listing.
                                    hold = { app.showItemMenu(span.program ?: list[dest.channel], list) },
                                )
                            }
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val span = focusedSpan ?: return@onPreviewKeyEvent false
                            val rowsOnScreen = rowsOnScreenState
                            fun moveChannel(to: Int) {
                                val target = to.coerceIn(0, list.lastIndex)
                                if (target == dest.channel) return
                                dest.channel = target
                                dest.anchorEpochSec = maxOf(span.start, windowStart).epochSecond
                                if (target < firstRow) firstRow = target
                                if (target >= firstRow + rowsOnScreen) firstRow = target - rowsOnScreen + 1
                                app.sounds.focus()
                            }
                            when (e.key) {
                                Key.DirectionUp -> moveChannel(dest.channel - 1)
                                Key.DirectionDown -> moveChannel(dest.channel + 1)
                                Key.PageUp, Key.ChannelUp -> moveChannel(dest.channel - rowsOnScreen)
                                Key.PageDown, Key.ChannelDown -> moveChannel(dest.channel + rowsOnScreen)
                                Key.DirectionRight -> { setAnchor(span.end); app.sounds.focus() }
                                Key.DirectionLeft -> {
                                    if (span.start > earliest) {
                                        val prev = spanAt(list[dest.channel], span.start.minusSeconds(1))
                                        setAnchor(maxOf(prev.start, earliest))
                                        app.sounds.focus()
                                    }
                                }
                                else -> return@onPreviewKeyEvent false
                            }
                            true
                        },
                ) {
                    val rowsOnScreen = visibleRowsFor(maxHeight)
                    rowsOnScreenState = rowsOnScreen
                    if (dest.channel < firstRow || dest.channel >= firstRow + rowsOnScreen) {
                        firstRow = (dest.channel - rowsOnScreen / 2).coerceIn(0, (list.size - rowsOnScreen).coerceAtLeast(0))
                    }
                    val gridW = maxWidth - (ScreenPadH - 8.dp) - ChannelColW - RowGap - ScreenPadH
                    val slotW = gridW / VisibleSlots
                    Column(Modifier.padding(start = ScreenPadH - 8.dp, top = 34.dp)) {
                        TimeHeader(windowStart, slotW, context)
                        for (r in firstRow until minOf(firstRow + rowsOnScreen, list.size)) {
                            val channel = list[r]
                            Row(Modifier.padding(bottom = RowGap).height(RowH)) {
                                ChannelCell(repo, channel)
                                Box(Modifier.padding(start = RowGap).width(gridW).fillMaxHeight()) {
                                    ProgramRow(
                                        programs[channel.id].orEmpty(), windowStart, windowEnd, slotW,
                                        focused = focusedSpan.takeIf { r == dest.channel },
                                    )
                                }
                            }
                        }
                    }
                    GuideDetails(
                        repo, list.getOrNull(dest.channel), focusedSpan, context,
                        Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = ScreenPadH, end = ScreenPadH, bottom = 18.dp),
                    )
                }
            }
        }
    }
}

private fun visibleRowsFor(height: Dp): Int = ((height - 64.dp - 150.dp) / (RowH + RowGap)).toInt().coerceAtLeast(3)

private fun timeText(t: Instant, context: android.content.Context) =
    android.text.format.DateFormat.getTimeFormat(context).format(Date.from(t))

@Composable
private fun TimeHeader(windowStart: Instant, slotW: Dp, context: android.content.Context) {
    val day = remember(windowStart) { DateTimeFormatter.ofPattern("EEE, MMM d").withZone(ZoneId.systemDefault()).format(windowStart) }
    Row(Modifier.height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        WText(day, WmcType.Label, Modifier.width(ChannelColW + RowGap).padding(start = 12.dp))
        for (i in 0 until VisibleSlots) {
            WText(timeText(windowStart.plus(Slot.multipliedBy(i.toLong())), context), WmcType.Label, Modifier.width(slotW).padding(start = 10.dp))
        }
    }
}

@Composable
private fun ChannelCell(repo: MediaRepository, channel: BaseItem) {
    Row(
        Modifier.width(ChannelColW).fillMaxHeight().aeroGlass(corner = 2.dp, strong = true, tint = Wmc.themed(Color(0xFF12356A)), streaks = false).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WText(channel.channelNumber ?: "", WmcType.Label.copy(fontSize = 20.sp), Modifier.width(62.dp), color = Wmc.TextDim)
        val logo = repo.channelLogoUrl(channel, 80)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (logo != null) {
                AsyncImage(model = logo, contentDescription = channel.name, contentScale = ContentScale.Fit, modifier = Modifier.height(26.dp).width(110.dp))
            } else {
                WText(channel.name ?: "", WmcType.Caption.copy(fontWeight = FontWeight.Medium), color = Wmc.Text)
            }
        }
    }
}

@Composable
private fun ProgramRow(programs: List<BaseItem>, windowStart: Instant, windowEnd: Instant, slotW: Dp, focused: Span?) {
    val slotSec = Slot.seconds.toFloat()
    fun x(t: Instant) = slotW * ((maxOf(t, windowStart).epochSecond - windowStart.epochSecond) / slotSec)
    fun w(s: Instant, e: Instant) = slotW * ((minOf(e, windowEnd).epochSecond - maxOf(s, windowStart).epochSecond) / slotSec)

    val visible = programs.filter { it.end!! > windowStart && it.start!! < windowEnd }
    if (visible.isEmpty()) {
        for (i in 0 until VisibleSlots) {
            val s = windowStart.plus(Slot.multipliedBy(i.toLong()))
            GuideCell("No program information", null, x(s), slotW, focused?.program == null && focused?.start == s, false, false)
        }
    } else {
        visible.forEach { p ->
            GuideCell(
                p.name ?: "", p, x(p.start!!), w(p.start!!, p.end!!), focused?.program?.id == p.id,
                clippedStart = p.start!! < windowStart, clippedEnd = p.end!! > windowEnd,
            )
        }
        // A gap in the listings that the user has moved into.
        if (focused != null && focused.program == null && focused.end > windowStart && focused.start < windowEnd) {
            GuideCell("No program information", null, x(focused.start), w(focused.start, focused.end), true, false, false)
        }
    }
}

@Composable
private fun GuideCell(title: String, program: BaseItem?, x: Dp, width: Dp, focused: Boolean, clippedStart: Boolean, clippedEnd: Boolean) {
    val shape = RoundedCornerShape(2.dp)
    Box(
        Modifier
            .offset(x = x)
            .width((width - RowGap).coerceAtLeast(4.dp))
            .fillMaxHeight()
            .then(
                if (focused) Modifier
                    .aeroGlass(corner = 3.dp, tint = Wmc.themed(Color(0xFF63B2F5)), streaks = false)
                    .border(2.dp, Color(0xDDE8F3FF), shape)
                else Modifier.aeroGlass(corner = 2.dp, tint = Wmc.themed(Color(0xFF1C4C8E)), streaks = false)
            )
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (clippedStart) WText("‹", WmcType.Label, color = Wmc.TextDim)
            WText(
                title,
                WmcType.Label.copy(fontSize = 19.sp, fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal),
                Modifier.weight(1f, fill = false),
                color = if (program == null) Wmc.TextFaint else Wmc.Text,
            )
            if (program != null) Badges(program, small = true)
            if (clippedEnd) WText("›", WmcType.Label, color = Wmc.TextDim)
        }
    }
}

/** "HD" tag and the red recording dot (two dots for a series recording), as in the Media Center guide. */
@Composable
private fun Badges(program: BaseItem, small: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (program.seriesTimerId != null || program.timerId != null) RecordDot(series = program.seriesTimerId != null, size = if (small) 14.dp else 18.dp)
        if (program.isHD == true) {
            WText(
                "HD",
                WmcType.Caption.copy(fontSize = if (small) 11.sp else 13.sp, fontWeight = FontWeight.Bold),
                Modifier.border(1.dp, Wmc.TextDim, RoundedCornerShape(2.dp)).padding(horizontal = 3.dp),
                color = Wmc.TextDim,
            )
        }
    }
}

@Composable
fun RecordDot(series: Boolean, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Canvas(modifier.size(if (series) size * 1.5f else size, size)) {
        val r = this.size.height / 2
        fun dot(cx: Float) {
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFF8A6A), Color(0xFFD8321C), Color(0xFF8E1508)), Offset(cx - r * 0.3f, r * 0.7f), r * 1.3f), r, Offset(cx, r))
            drawCircle(Color(0x66FFFFFF), r * 0.35f, Offset(cx - r * 0.3f, r * 0.6f))
        }
        if (series) dot(r * 2f)
        dot(r)
    }
}

@Composable
private fun GuideDetails(repo: MediaRepository, channel: BaseItem?, span: Span?, context: android.content.Context, modifier: Modifier) {
    val program = span?.program
    Row(modifier.height(140.dp), verticalAlignment = Alignment.Bottom) {
        val image = program?.imageTags?.get("Primary")?.let { repo.imageUrl(program.id, ImageKind.Primary, it, 240) }
        Artwork(image ?: channel?.let { repo.channelLogoUrl(it, 240) }, channel?.name, Modifier.size(214.dp, 120.dp), glyph = Glyph.Tv)
        Column(Modifier.weight(1f).padding(start = 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WText(program?.name ?: "No program information", WmcType.ItemTitle)
                if (program != null) Badges(program, small = false)
            }
            program?.episodeTitle?.let { WText(it, WmcType.Label, color = Wmc.Text) }
            if (span != null) {
                WText(
                    "${timeText(span.start, context)} – ${timeText(span.end, context)}  ·  ${channel?.channelNumber ?: ""} ${channel?.name ?: ""}",
                    WmcType.Label, color = Wmc.TextDim,
                )
            }
            program?.overview?.let { WText(it, WmcType.Body, color = Wmc.TextDim, maxLines = 2) }
            recordingStatus(program)?.let { WText(it, WmcType.Label, color = Wmc.Text) }
        }
    }
}

fun recordingStatus(program: BaseItem?): String? = when {
    program == null -> null
    program.seriesTimerId != null -> "This will record as part of a series request"
    program.timerId != null -> "This will record"
    else -> null
}

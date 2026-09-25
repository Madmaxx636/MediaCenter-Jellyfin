package dev.mediacenter.jf.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.mediacenter.jf.LocalAppState
import dev.mediacenter.jf.data.BaseItem
import dev.mediacenter.jf.data.ImageKind
import dev.mediacenter.jf.data.channelLogoUrl
import dev.mediacenter.jf.ui.ProgramDest
import dev.mediacenter.jf.ui.components.ActionButton
import dev.mediacenter.jf.ui.components.Artwork
import dev.mediacenter.jf.ui.components.CenteredBusy
import dev.mediacenter.jf.ui.components.CenteredMessage
import dev.mediacenter.jf.ui.components.Glyph
import dev.mediacenter.jf.ui.components.Reflected
import dev.mediacenter.jf.ui.components.ScreenPadH
import dev.mediacenter.jf.ui.components.TopChrome
import dev.mediacenter.jf.ui.components.WText
import dev.mediacenter.jf.ui.components.focusWhenReady
import dev.mediacenter.jf.ui.components.tryFocus
import dev.mediacenter.jf.ui.theme.Wmc
import dev.mediacenter.jf.ui.theme.WmcType
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

/** A guide listing: when and where it's on, and buttons to watch it or set a recording. */
@Composable
fun ProgramScreen(dest: ProgramDest) {
    val app = LocalAppState.current
    val repo = app.repository ?: return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var program by remember { mutableStateOf<BaseItem?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }

    LaunchedEffect(version) {
        runCatching { repo.program(dest.programId) }
            .onSuccess { program = it }
            .onFailure { if (program == null) error = it.message }
    }
    LaunchedEffect(program != null) {
        if (program == null) return@LaunchedEffect
        app.sounds.quiet()
        first.focusWhenReady()
    }

    fun change(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                app.sounds.error()
                error = e.message
            } finally {
                busy = false
                version++
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopChrome()
        val p = program
        when {
            p == null && error != null -> CenteredMessage("Couldn't load this program", error)
            p == null -> CenteredBusy()
            else -> Column(Modifier.padding(horizontal = ScreenPadH).padding(top = 4.dp)) {
                val fmt = android.text.format.DateFormat.getTimeFormat(context)
                val day = p.start?.let { DateTimeFormatter.ofPattern("EEEE").withZone(ZoneId.systemDefault()).format(it) }
                val now = Instant.now()
                val airing = p.start != null && p.end != null && p.start!! <= now && p.end!! > now
                WText(p.name ?: "", WmcType.Hero)
                WText(
                    listOfNotNull(
                        p.episodeTitle,
                        if (airing) "on now" else day,
                        p.start?.let { s -> p.end?.let { e -> "${fmt.format(Date.from(s))} – ${fmt.format(Date.from(e))}" } },
                        listOfNotNull(p.channelNumber, p.channelName).joinToString(" ").ifEmpty { null },
                    ).joinToString("  ·  "),
                    WmcType.Label, color = Wmc.TextDim,
                )
                Row(Modifier.padding(top = 26.dp), horizontalArrangement = Arrangement.spacedBy(34.dp)) {
                    Column(Modifier.width(270.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        var firstUsed = false
                        fun Modifier.firstButton(): Modifier = if (!firstUsed) { firstUsed = true; focusRequester(first) } else this
                        if (airing) {
                            ActionButton("watch", {
                                scope.launch {
                                    val channels = runCatching { repo.channels() }.getOrDefault(emptyList())
                                    app.watchLiveTv(channels, channels.firstOrNull { it.id == p.channelId })
                                }
                            }, Modifier.firstButton(), Glyph.Play)
                        }
                        if (p.timerId == null) {
                            ActionButton("record", { change { repo.record(p, series = false) } }, Modifier.firstButton(), Glyph.Guide)
                        } else if (p.seriesTimerId == null) {
                            ActionButton("don't record", { change { repo.cancelRecording(p, series = false) } }, Modifier.firstButton(), Glyph.Stop)
                        }
                        if (p.isSeries == true) {
                            if (p.seriesTimerId == null) {
                                ActionButton("record series", { change { repo.record(p, series = true) } }, Modifier.firstButton(), Glyph.Collections)
                            } else {
                                ActionButton("cancel series", { change { repo.cancelRecording(p, series = true) } }, Modifier.firstButton(), Glyph.Stop)
                            }
                        }
                        // Recording options for this program (Media Center's "Record Settings").
                        p.timerId?.let { timer ->
                            val padding by androidx.compose.runtime.produceState<Pair<Int, Int>?>(null, timer, version) {
                                value = runCatching { repo.recordingPadding(timer) }.getOrNull()
                            }
                            padding?.let { (early, late) ->
                                val earlySteps = listOf(0, 1, 2, 5, 10)
                                val lateSteps = listOf(0, 1, 5, 10, 30, 60, 120, 180)
                                ActionButton(
                                    "start", { change { repo.setRecordingPadding(timer, earlySteps[(earlySteps.indexOf(early) + 1) % earlySteps.size], late) } },
                                    glyph = Glyph.Record, detail = if (early == 0) "on time" else "$early min early",
                                )
                                ActionButton(
                                    "stop", { change { repo.setRecordingPadding(timer, early, lateSteps[(lateSteps.indexOf(late) + 1) % lateSteps.size]) } },
                                    glyph = Glyph.Stop, detail = when { late == 0 -> "on time"; late >= 60 -> "${late / 60} hr late"; else -> "$late min late" },
                                )
                            }
                        }
                    }
                    Reflected(180.dp) {
                        val image = p.imageTags["Primary"]?.let { repo.imageUrl(p.id, ImageKind.Primary, it, 480) } ?: repo.channelLogoUrl(p, 240)
                        Artwork(image, p.channelName, Modifier.size(320.dp, 180.dp), glyph = Glyph.Tv)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        recordingStatus(p)?.let {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RecordDot(series = p.seriesTimerId != null, Modifier.padding(end = 10.dp), size = 18.dp)
                                WText(it, WmcType.Label, color = Wmc.Text)
                            }
                        }
                        p.overview?.let { WText(it, WmcType.Body, maxLines = 8) }
                        val kinds = listOfNotNull(
                            "movie".takeIf { p.isMovie == true }, "news".takeIf { p.isNews == true },
                            "sports".takeIf { p.isSports == true }, "kids".takeIf { p.isKids == true },
                            "HD".takeIf { p.isHD == true }, "live".takeIf { p.isLive == true },
                            "premiere".takeIf { p.isPremiere == true },
                        )
                        if (kinds.isNotEmpty()) WText(kinds.joinToString("  ·  "), WmcType.Caption, color = Wmc.Accent)
                        error?.let { WText(it, WmcType.Label, color = Wmc.Warning, maxLines = 3) }
                    }
                }
            }
        }
    }
}

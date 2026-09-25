package dev.mediacenter.jf.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import dev.mediacenter.jf.Hardware
import dev.mediacenter.jf.LocalAppState
import dev.mediacenter.jf.data.Setting
import dev.mediacenter.jf.playback.AutoTune
import dev.mediacenter.jf.playback.DeviceCodecs
import dev.mediacenter.jf.ui.OptimizeDest
import dev.mediacenter.jf.ui.SettingsDest
import dev.mediacenter.jf.ui.components.ActionButton
import dev.mediacenter.jf.ui.components.BusyIndicator
import dev.mediacenter.jf.ui.components.Glyph
import dev.mediacenter.jf.ui.components.GlyphIcon
import dev.mediacenter.jf.ui.components.ScreenPadH
import dev.mediacenter.jf.ui.components.TopChrome
import dev.mediacenter.jf.ui.components.WText
import dev.mediacenter.jf.ui.components.aeroGlass
import dev.mediacenter.jf.ui.components.focusWhenReady
import dev.mediacenter.jf.ui.theme.Wmc
import dev.mediacenter.jf.ui.theme.WmcType
import kotlinx.coroutines.delay

/** One line of the check list: what's being checked and, once done, what was found. */
private class Check(val title: String, val glyph: Glyph) {
    var result by mutableStateOf<String?>(null)
}

/**
 * "Optimize for this TV", after Media Center's setup screens: checks the screen, video
 * decoders, sound and connection one by one, then shows what playback was set to. Shown
 * once after signing in to a new server, and any time from settings.
 */
@Composable
fun OptimizeScreen(dest: OptimizeDest) {
    val app = LocalAppState.current
    val repo = app.repository ?: return
    val serverKey = app.currentServer()?.id
    val checks = remember {
        listOf(
            Check("screen", Glyph.Tv),
            Check("video", Glyph.Video),
            Check("sound", Glyph.Audio),
            Check(if (repo.isDemo) "connection" else "connection to ${repo.serverName}", Glyph.Server),
            Check("memory", Glyph.Settings),
        )
    }
    var current by remember { mutableIntStateOf(0) }
    val done = current >= checks.size
    val doneButton = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        app.sounds.quiet()
        // Short pauses between the quick checks, so the list reads as it fills in.
        delay(450)
        val scan = AutoTune.rescanDevice()
        val codecs = DeviceCodecs.current
        checks[0].result = buildString {
            append(AutoTune.heightLabel(AutoTune.autoMaxHeight()).let { if (it == "no limit") "unknown size" else "$it screen" })
            val hdr = codecs.screenHdr.map { if (it == "DOVI") "Dolby Vision" else it }
            append(if (hdr.isEmpty()) ", no HDR" else ", " + hdr.joinToString(", "))
        }
        current = 1
        delay(400)
        val hw = codecs.hardwareVideo.keys.map(DeviceCodecs::nice)
        val sw = (codecs.softwareVideo.keys - codecs.hardwareVideo.keys).map(DeviceCodecs::nice)
        checks[1].result = (if (hw.isEmpty()) "software decoding" else hw.joinToString(", ") + " in hardware") +
            if (sw.isNotEmpty()) "; " + sw.joinToString(", ") + " in software" else ""
        current = 2
        delay(400)
        checks[2].result = buildString {
            append(scan.outputName)
            if (scan.outputChannels > 0) append(", up to ${scan.outputChannels} channels")
            if (codecs.passthroughAudio.isNotEmpty()) append("; passes " + codecs.passthroughAudio.map(DeviceCodecs::nice).joinToString(", ") + " through")
            append(if (scan.surround) " — surround" else " — stereo")
        }
        current = 3
        checks[3].result = if (repo.isDemo || serverKey == null) {
            delay(300)
            "not needed for the demo"
        } else {
            val bps = runCatching { AutoTune.measure(repo, serverKey) }.getOrNull()
            if (bps == null) "couldn't measure; quality isn't limited" else "about ${AutoTune.speedLabel(bps)}"
        }
        current = 4
        delay(300)
        checks[4].result = "${(Hardware.totalRamBytes shr 20).let { if (it >= 1000) "%.1f GB".format(it / 1024.0) else "$it MB" }}; " +
            "buffers up to ${Hardware.videoBufferBytes shr 20} MB of video ahead"
        current = 5
        serverKey?.let { AutoTune.markTuned(it) }
        app.sounds.select()
        doneButton.focusWhenReady()
    }

    Box(Modifier.fillMaxSize()) {
        WText("optimize", WmcType.PageTitle, Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = ScreenPadH))
        Column(Modifier.fillMaxSize()) {
            TopChrome(showBack = !dest.firstRun || done)
            Column(
                Modifier.padding(start = ScreenPadH + 40.dp, end = ScreenPadH, top = 28.dp).fillMaxWidth()
                    .aeroGlass(corner = 10.dp, strong = true).padding(horizontal = 28.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WText(if (done) "this TV is ready" else "optimizing for this TV", WmcType.Hero)
                WText(
                    if (done) "Playback is set to suit this TV. Settings left on automatic follow these results; anything you've set yourself stays as it is."
                    else "Checking this TV's screen, decoders, sound and connection, and setting playback to match.",
                    WmcType.Body, maxLines = 2,
                )
                Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    // The checks on the left; what was chosen, and the buttons, appear on the right when done.
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        checks.forEachIndexed { i, check -> CheckRow(check, state = if (i < current) 2 else if (i == current) 1 else 0) }
                    }
                    AnimatedVisibility(
                        done, Modifier.width(360.dp).padding(start = 28.dp),
                        enter = fadeIn(tween(300)) + slideInHorizontally(tween(320)) { it / 8 },
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            WText("chosen for playback", WmcType.Label, color = Wmc.TextDim)
                            val s = app.settings
                            ChosenRow("movies + tv quality", s.maxBitrate)
                            ChosenRow("movies + tv resolution", s.maxResolution)
                            ChosenRow("live tv quality", s.liveBitrate)
                            ChosenRow("audio output", s.surround)
                            Box(Modifier.height(4.dp))
                            ActionButton("done", { app.navigator.pop() }, Modifier.focusRequester(doneButton), Glyph.Check)
                            ActionButton("settings", { app.navigator.pop(); app.navigator.push(SettingsDest()) }, glyph = Glyph.Settings)
                        }
                    }
                }
            }
        }
    }
}

/** A check: waiting (dim), in progress (spinner), or done (tick, with what was found underneath). */
@Composable
private fun CheckRow(check: Check, state: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(28.dp).padding(top = 2.dp), contentAlignment = Alignment.Center) {
            when (state) {
                2 -> GlyphIcon(Glyph.Check, size = 22.dp, color = Wmc.Accent)
                1 -> BusyIndicator(size = 22.dp)
                else -> Unit
            }
        }
        GlyphIcon(check.glyph, Modifier.padding(start = 10.dp, end = 12.dp, top = 2.dp), size = 22.dp, color = if (state > 0) Wmc.Text else Wmc.TextFaint)
        Column(Modifier.weight(1f)) {
            WText(check.title, WmcType.Label, color = if (state > 0) Wmc.Text else Wmc.TextFaint, maxLines = 1)
            WText(
                check.result ?: if (state == 1) "checking\u2026" else " ",
                WmcType.Caption, color = if (check.result != null) Wmc.TextDim else Wmc.TextFaint, maxLines = 2,
            )
        }
    }
}

/** What a setting is now: its automatic result, or the user's own choice, kept. */
@Composable
private fun ChosenRow(title: String, setting: Setting<*>) {
    val auto = setting.label.startsWith("automatic")
    Column {
        WText(title, WmcType.Caption, color = Wmc.TextDim, maxLines = 1)
        WText(
            if (auto) setting.label.removePrefix("automatic (").removeSuffix(")").ifEmpty { "automatic" } + "  \u00b7  automatic"
            else "${setting.label}  \u00b7  your choice, kept",
            WmcType.Label, color = Wmc.Text, maxLines = 1,
        )
    }
}

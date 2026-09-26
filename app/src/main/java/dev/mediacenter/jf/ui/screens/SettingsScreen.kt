package dev.mediacenter.jf.ui.screens

import dev.mediacenter.jf.ui.components.aeroGlass
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusable
import androidx.compose.ui.unit.dp
import dev.mediacenter.jf.AppState
import dev.mediacenter.jf.BuildConfig
import dev.mediacenter.jf.LocalAppState
import dev.mediacenter.jf.data.Setting
import dev.mediacenter.jf.ui.components.ActionButton
import dev.mediacenter.jf.ui.components.SettingRow
import androidx.compose.foundation.layout.height
import dev.mediacenter.jf.ui.components.Glyph
import dev.mediacenter.jf.ui.components.ScreenPadH
import dev.mediacenter.jf.ui.components.TopChrome
import dev.mediacenter.jf.ui.components.WText
import dev.mediacenter.jf.ui.components.focusWhenReady
import dev.mediacenter.jf.ui.components.tryFocus
import dev.mediacenter.jf.ui.theme.Wmc
import dev.mediacenter.jf.ui.theme.WmcType
import kotlinx.coroutines.launch

private class Section(val title: String, val glyph: Glyph, val settings: List<Setting<*>> = emptyList(), val custom: Boolean = false)

/**
 * Media Center-style settings: sections down the left, the chosen section's
 * options on the right. OK steps an option to its next value; the line at the
 * bottom explains whatever is focused.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen() {
    val app = LocalAppState.current
    val s = app.settings
    val sections = remember {
        listOf(
            Section("playback", Glyph.Play, s.playback),
            Section("player controls", Glyph.FastForward, s.playerControls),
            Section("skipping", Glyph.SkipNext, s.skipping),
            Section("audio + subtitles", Glyph.Subtitles, s.audioSubtitles),
            Section("codecs", Glyph.Video, custom = true),
            Section("live tv", Glyph.LiveTv, s.liveTv),
            Section("recording", Glyph.Record, s.recording),
            Section("pictures", Glyph.Pictures, s.pictures),
            Section("general", Glyph.Settings, s.interfaceSettings),
            Section("servers", Glyph.Server, custom = true),
            Section("account", Glyph.User, custom = true),
            Section("about", Glyph.Info, custom = true),
        )
    }
    // Saved with the screen, so coming back (e.g. from signing in more people) reopens the same section.
    var selected by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    var help by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        app.sounds.quiet()
        first.focusWhenReady()
    }

    Box(Modifier.fillMaxSize()) {
        WText("settings", WmcType.PageTitle, Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = ScreenPadH))
        Column(Modifier.fillMaxSize()) {
            TopChrome()
            Row(Modifier.weight(1f).padding(start = 150.dp, end = ScreenPadH, top = 70.dp)) {
                // Scrolls, so every section can be reached at larger interface sizes too.
                Column(Modifier.width(260.dp).focusRestorer().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    sections.forEachIndexed { i, section ->
                        ActionButton(
                            section.title, { selected = i },
                            if (i == selected) Modifier.focusRequester(first) else Modifier,
                            section.glyph,
                            onFocus = { selected = i; help = "" },
                        )
                    }
                }
                Column(
                    Modifier.weight(1f).padding(start = 40.dp).aeroGlass(corner = 8.dp, strong = true).padding(12.dp)
                        // Down past the last row stays put, rather than dropping into the section list
                        // (which would switch section); left still goes back to the sections.
                        .focusProperties { onExit = { if (requestedFocusDirection == androidx.compose.ui.focus.FocusDirection.Down) cancelFocusChange() } }
                        .focusRestorer().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val section = sections[selected]
                    WText(section.title, WmcType.Heading, Modifier.padding(start = 14.dp, bottom = 8.dp), color = Wmc.TextDim)
                    when (section.title) {
                        "codecs" -> CodecsSection(app)
                        "servers" -> ServersSection(app)
                        "account" -> AccountSection(app) { help = it }
                        "about" -> AboutSection { help = it }
                        else -> {
                            if (section.title == "general") OptimizeRow(app)
                            section.settings.forEach { setting -> SettingRowFor(setting) }
                            if (section.title == "general") {
                                SettingRow(
                                    "show the screensaver now", null,
                                    "Starts the screensaver straight away, to see how it looks. Any button brings you back.",
                                    { app.screensaver = true }, glyph = Glyph.Pictures,
                                )
                            }
                        }
                    }
                }
            }
            Box(Modifier.height(28.dp))
        }
    }
}

/** Runs "optimize for this TV" again, e.g. after moving the box to another TV or adding a receiver. */
@Composable
private fun OptimizeRow(app: AppState) {
    val key = app.currentServer()?.id
    val at = key?.let { dev.mediacenter.jf.playback.AutoTune.measuredAt(it) } ?: 0L
    SettingRow(
        "optimize for this TV",
        if (at > 0) "checked " + android.text.format.DateUtils.getRelativeTimeSpanString(at).toString().lowercase() else null,
        "Checks this TV's screen, video decoders, sound and connection to the server, and sets playback to match. " +
            "Run it again after moving the box to another TV or connecting a receiver. Only settings left on automatic change.",
        { app.navigator.push(dev.mediacenter.jf.ui.OptimizeDest()) }, glyph = Glyph.Tv,
    )
}

/** A setting's row: a switch for on/off settings (one press flips it), a drop-down for the rest. */
@Composable
private fun SettingRowFor(setting: Setting<*>, modifier: Modifier = Modifier) {
    val isSwitch = setting.choices.size == 2 && setting.choices.all { it.value is Boolean }
    // Locked by the server's Media Center plugin: shown as it's set, but not changeable here.
    val locked = setting.lockedByServer
    SettingRow(
        setting.title, if (isSwitch) null else setting.label, setting.help,
        onClick = { if (!locked) setting.cycle() },
        modifier = modifier,
        footnote = if (locked) "set by your server" else "default: ${setting.defaultLabel}",
        options = if (isSwitch || locked) null else setting.choices.map { setting.choiceLabel(it) },
        selected = setting.selectedIndex,
        onSelect = { if (!locked) setting.selectIndex(it) },
        toggle = if (isSwitch) setting.value as Boolean else null,
    )
}

/**
 * Codecs: what this TV was found to decode (video, HDR, audio, and what goes to a receiver),
 * with a switch per format. Anything switched off or not supported is converted by the server.
 */
@Composable
private fun CodecsSection(app: AppState) {
    val s = app.settings
    val caps = remember { dev.mediacenter.jf.playback.DeviceCodecs.current }
    fun nice(codecs: Collection<String>) = codecs.map { dev.mediacenter.jf.playback.DeviceCodecs.nice(it) }.distinct()

    val mode = s.videoDecoding.value
    SettingRow(
        "video this TV decodes", "${caps.video(mode).size} formats",
        caps.videoLines(mode).joinToString(" · ") + ". Other formats, and anything larger, are converted by the server.",
        {}, glyph = Glyph.Video,
    )
    SettingRowFor(s.videoDecoding)
    SettingRow(
        "HDR on this screen",
        caps.screenHdr.ifEmpty { setOf("SDR only") }.joinToString(", ") { if (it == "DOVI") "Dolby Vision" else it },
        if (caps.screenHdr.isEmpty()) "This screen doesn't report HDR, so the server converts HDR video to SDR with tone mapping."
        else "HDR video in these formats plays as HDR; other kinds are converted by the server." +
            if (caps.dolbyVisionDecoder) " This TV has a Dolby Vision decoder." else "",
        {}, glyph = Glyph.Pictures,
    )
    val builtIn = nice(caps.platformAudio - "pcm")
    val inApp = nice(caps.ffmpegAudio - caps.platformAudio)
    SettingRow(
        "audio this TV plays", "${(builtIn + inApp).distinct().size} formats",
        buildString {
            append("Built in: ").append(builtIn.joinToString(", ")).append(". ")
            if (inApp.isNotEmpty()) append("Decoded by the app: ").append(inApp.joinToString(", ")).append(". ")
            append(
                if (caps.passthroughAudio.isEmpty()) "Nothing is passed through to a receiver on this output."
                else "Sent untouched to your receiver: " + nice(caps.passthroughAudio).joinToString(", ") + "."
            )
            append(
                if (s.losslessConversions.value) " APE, WavPack and DSD are converted to FLAC by the server, with nothing lost."
                else " APE, WavPack and DSD are converted by the server."
            )
        },
        {}, glyph = Glyph.Audio,
    )

    fun note(supported: Boolean, what: String) =
        if (supported) " This TV decodes $what." else " This TV has no $what decoder, so the server converts it either way."
    val video = caps.video(mode)
    CodecSwitch(s.allowHevc, note("hevc" in video, "HEVC"))
    CodecSwitch(s.allowVp9, note("vp9" in video, "VP9"))
    CodecSwitch(s.allowAv1, note("av1" in video, "AV1"))
    CodecSwitch(s.allowHdr, if (caps.screenHdr.any { it != "DOVI" }) "" else " This screen doesn't report HDR10 or HLG.")
    CodecSwitch(
        s.allowDolbyVision,
        if (caps.dolbyVisionDecoder && "DOVI" in caps.screenHdr) " This TV and screen support it."
        else " This TV or screen doesn't support it, so the HDR10 base is played where there is one.",
    )
    CodecSwitch(
        s.softwareAudio,
        if (caps.ffmpegAudio.isEmpty()) " The app's decoder isn't available on this TV." else "",
        onChange = { app.playback.rebuildPlayer() },
    )
    CodecSwitch(
        s.passthrough,
        if (caps.passthroughAudio.isEmpty()) " Nothing connected here takes Dolby or DTS as-is right now." else "",
        onChange = { app.playback.rebuildPlayer() },
    )
    CodecSwitch(s.hiResAudio, "", onChange = { app.playback.rebuildPlayer() })
    CodecSwitch(s.losslessConversions, "")
}

/** An on/off codec setting, with a note on what this TV supports added to its help. */
@Composable
private fun CodecSwitch(setting: Setting<Boolean>, note: String, onChange: () -> Unit = {}) {
    val locked = setting.lockedByServer
    SettingRow(
        setting.title, null, setting.help + note,
        onClick = { if (!locked) { setting.cycle(); onChange() } },
        footnote = if (locked) "set by your server" else "default: ${setting.defaultLabel}",
        toggle = setting.value,
    )
}

/**
 * Manage servers: every saved server with who's signed in on it. Open one to rename it,
 * change its address, sign people in or out, switch to it or remove it.
 */
@Composable
private fun ServersSection(app: AppState) {
    val store = app.store
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var openId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val top = remember(openId) { FocusRequester() }
    // When the focused row goes away (opening a server, removing someone), Android would hand
    // focus to the first thing on screen, which is the "playback" section, and switch to it.
    // So focus waits on an anchor in this panel meanwhile, then lands on the first row.
    val anchor = remember { FocusRequester() }
    val anchorOn = remember { booleanArrayOf(false) }
    var refocus by remember { mutableIntStateOf(0) }
    LaunchedEffect(openId, refocus) {
        if (refocus == 0) return@LaunchedEffect
        top.focusWhenReady()
        anchorOn[0] = false
    }
    fun keepFocus(change: () -> Unit) {
        anchorOn[0] = true
        anchor.tryFocus()
        change()
        refocus++
    }
    fun go(id: String?) = keepFocus { openId = id }
    Box(Modifier.focusRequester(anchor).focusProperties { canFocus = anchorOn[0] }.focusable())

    val open = openId?.let { store.server(it) }
    if (open == null) {
        SettingRowFor(app.settings.tasksSwitchServer, Modifier.focusRequester(top))
        val current = app.currentServer()?.id
        store.servers.forEach { server ->
            SettingRow(
                server.label,
                if (server.id == current) "in use" else "${server.users.size} saved",
                "${server.url}. Rename it, change its address, sign people in or out, or remove it.",
                { go(server.id) }, glyph = Glyph.Server,
            )
        }
        SettingRow(
            "add a server", null,
            "Find a Jellyfin server on your network or type its address, then sign in everyone who uses this TV. You stay signed in here.",
            { app.navigator.push(dev.mediacenter.jf.ui.ServerSetupDest()) }, glyph = Glyph.Plus,
        )
        return
    }

    var editing by remember(openId) { mutableStateOf<String?>(null) }
    var text by remember(openId) { mutableStateOf("") }
    var busy by remember(openId) { mutableStateOf(false) }
    var problem by remember(openId) { mutableStateOf<String?>(null) }
    // Removing and signing out take a second press, so neither happens by accident.
    var confirm by remember(openId) { mutableStateOf<String?>(null) }
    val inUse = app.currentServer()?.id == open.id
    val you = if (inUse) store.load()?.userId else null

    SettingRow("all servers", null, "Back to the list of servers.", { go(null) }, Modifier.focusRequester(top), glyph = Glyph.Back)
    SettingRow(
        "name", open.label,
        if (open.nickname != null) "Shown as ${open.nickname} here; the server calls itself ${open.name}. Clear the name to use the server's own."
        else "What to call this server on this TV. It's ${open.name} until you change it.",
        { editing = "name"; text = open.nickname ?: open.name; problem = null },
    )
    SettingRow(
        "address", open.url.removePrefix("http://").removePrefix("https://"),
        "Where to reach this server. Change it if the server moved; everyone stays signed in.",
        { editing = "address"; text = open.url; problem = null },
    )
    editing?.let { field ->
        val save: () -> Unit = {
            if (field == "name") {
                store.rename(open.id, text)
                editing = null
            } else if (!busy) {
                busy = true
                problem = null
                scope.launch {
                    try {
                        val (url, info) = app.api.findServer(text)
                        if (info.id != null && info.id != open.id) {
                            problem = "That's a different server (${info.serverName ?: url}). Add it as a new server instead."
                        } else {
                            app.setServerAddress(open, url)
                            editing = null
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        problem = friendlyError(e)
                    } finally {
                        busy = false
                    }
                }
            }
        }
        Row(Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            dev.mediacenter.jf.ui.components.WmcTextField(
                text, { text = it }, if (field == "name") "server name" else "server address", Modifier.width(420.dp),
                keyboardType = if (field == "name") androidx.compose.ui.text.input.KeyboardType.Text else androidx.compose.ui.text.input.KeyboardType.Uri,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done, onDone = save,
            )
            ActionButton(if (busy) "checking…" else "save", save, Modifier.width(150.dp), Glyph.Check)
            ActionButton("cancel", { editing = null; problem = null }, Modifier.width(150.dp))
        }
        problem?.let { WText(it, WmcType.Label, Modifier.padding(start = 14.dp), color = Wmc.Warning, maxLines = 2) }
    }
    open.users.forEach { user ->
        val key = "user:${user.id}"
        SettingRow(
            user.name,
            when {
                confirm == key -> "press OK again to sign out"
                user.id == you -> "watching now"
                else -> "signed in"
            },
            "Signs ${user.name} out on this TV; next time they'll need their password or Quick Connect." +
                if (user.id == you) " That's who's watching now, so you'll go back to \"who's watching\"." else "",
            { if (confirm == key) { confirm = null; keepFocus { app.forgetUser(open, user) } } else confirm = key },
            glyph = Glyph.User,
        )
    }
    SettingRow(
        "sign in more people", null,
        "Sign in everyone else who uses this TV on ${open.label}, so they're one press away. You stay signed in.",
        { app.navigator.push(dev.mediacenter.jf.ui.ServerSetupDest(open.id)) }, glyph = Glyph.Plus,
    )
    if (!inUse) {
        SettingRow(
            "switch to this server", null, "Go to ${open.label}'s \"who's watching\" list.",
            { app.openServer(open) }, glyph = Glyph.Server,
        )
    }
    SettingRow(
        "remove this server", if (confirm == "remove") "press OK again to remove" else null,
        "Forgets ${open.label} and everyone signed in on it, on this TV only. Nothing changes on the server." +
            if (inUse) " It's the server in use, so you'll go back to the server list." else "",
        {
            if (confirm == "remove") {
                confirm = null
                keepFocus {
                    openId = null
                    app.forgetServer(open)
                }
            } else confirm = "remove"
        },
        glyph = Glyph.Exit,
    )
}

@Composable
private fun AccountSection(app: AppState, onHelp: (String) -> Unit) {
    val repo = app.repository ?: return
    SettingRow("server", repo.serverName, "The Jellyfin server this device is connected to.", {})
    SettingRow("signed in as", repo.userName, "The Jellyfin user whose libraries, watched state and favorites are shown.", {})
    SettingRow(
        if (repo.isDemo) "connect a server" else "switch user or server", null,
        if (repo.isDemo) "Leave the demo library and connect to a Jellyfin server." else "Sign out of this server. Settings on this device are kept.",
        { app.switchUser() }, glyph = Glyph.User,
    )
}

@Composable
private fun AboutSection(onHelp: (String) -> Unit) {
    SettingRow(
        "version", BuildConfig.VERSION_NAME,
        "Media Center for Jellyfin, an Android TV client. Free software under the GNU GPL, version 3. " +
            "Source, releases and issues: github.com/madmaxx636/MediaCenter-Jellyfin",
        {},
    )
    val app = LocalAppState.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val updater = app.updater
    if (updater.enabled) {
        val update = updater.available
        SettingRow(
            if (update != null) "install ${update.version}" else "check for updates",
            updater.status ?: if (update != null) "new version" else null,
            "Downloads the new version from github.com/madmaxx636/MediaCenter-Jellyfin, checks it, and installs it over this one " +
                "(your settings and servers stay). Android asks you to confirm; the first time, it may ask you to allow " +
                "Media Center to install apps.",
            {
                when {
                    updater.busy -> {}
                    update != null -> scope.launch { updater.install(update) }
                    else -> scope.launch { updater.check(force = true) }
                }
            },
            glyph = Glyph.Resume,
        )
    }
    var sendState by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    SettingRow(
        "send log to server", sendState ?: if (dev.mediacenter.jf.AppLog.hasCrash) "crash report waiting" else null,
        "Uploads the recent app log (playback, stream formats, errors and any saved crash) to your Jellyfin server's log folder, " +
            "so problems can be diagnosed. Access tokens are removed. Requires client log upload to be allowed on the server.",
        {
            if (app.repository?.isDemo != false) {
                sendState = "not connected to a server"
            } else {
                sendState = "sending\u2026"
                scope.launch { sendState = runCatching { "sent as ${app.sendLog()}" }.getOrElse { "failed: ${it.message}" } }
            }
        },
        glyph = Glyph.Info,
    )
    SettingRow(
        "credits", null,
        "A Windows Media Center–style client for Jellyfin; not affiliated with Microsoft or the Jellyfin project. " +
            "Jellyfin logo © Jellyfin contributors, CC BY-SA 4.0. Built with AndroidX Media3, Jetpack Compose, Ktor and Coil " +
            "(Apache 2.0), Jellyfin's FFmpeg decoder and NextLib (GPL 3.0) and libass-android (MIT). The screensaver follows " +
            "Wholphin's. The interface sounds are the app's own.",
        {},
    )
}

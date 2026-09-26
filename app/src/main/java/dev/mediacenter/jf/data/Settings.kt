package dev.mediacenter.jf.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/** One choice in a setting: what's stored, and what the user sees. */
data class Choice<T>(val value: T, val label: String)

/**
 * A persisted setting with a fixed list of choices. [value] is Compose state,
 * so screens update the moment it changes.
 */
class Setting<T>(
    private val prefs: android.content.SharedPreferences,
    val key: String,
    val title: String,
    val help: String,
    val choices: List<Choice<T>>,
    private val default: T,
) {
    val defaultLabel: String get() = choices.firstOrNull { it.value == default }?.label ?: default.toString()

    /** The default as stored text (what the server's settings page offers as the starting value). */
    val defaultText: String get() = default.toString()

    /** Set and locked by the server (the Media Center plugin): shown, but not changeable here. */
    var lockedByServer by mutableStateOf(false)

    /** Sets it from stored text (a choice's value, as the server sends it); false if no choice matches. */
    fun setFromText(text: String): Boolean {
        val choice = choices.firstOrNull { it.value.toString() == text } ?: return false
        if (choice.value != state.value) set(choice.value)
        return true
    }

    private val state = mutableStateOf(load(default))
    val value: T get() = state.value

    /** For settings with an "automatic" choice: what it works out to on this TV right now. */
    var autoDetail: (() -> String?)? = null

    /** A choice's label, with what "automatic" currently means added, e.g. "automatic (4K)". */
    fun choiceLabel(choice: Choice<*>): String {
        val detail = if (choice.label == "automatic") autoDetail?.invoke() else null
        return if (detail != null) "${choice.label} ($detail)" else choice.label
    }

    val label: String get() = choices.firstOrNull { it.value == state.value }?.let(::choiceLabel) ?: state.value.toString()

    @Suppress("UNCHECKED_CAST")
    private fun load(default: T): T {
        val stored = prefs.getString(key, null) ?: return default
        return choices.firstOrNull { it.value.toString() == stored }?.value ?: default
    }

    fun set(v: T) {
        state.value = v
        prefs.edit { putString(key, v.toString()) }
    }

    val selectedIndex: Int get() = choices.indexOfFirst { it.value == state.value }

    fun selectIndex(i: Int) = choices.getOrNull(i)?.let { set(it.value) }

    /** Steps to the next choice, wrapping around. */
    fun cycle(step: Int = 1) {
        val i = choices.indexOfFirst { it.value == state.value }.coerceAtLeast(0)
        set(choices[((i + step) % choices.size + choices.size) % choices.size].value)
    }
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun onOff(key: String, title: String, help: String, default: Boolean) =
        Setting(prefs, key, title, help, listOf(Choice(true, "on"), Choice(false, "off")), default)

    private val languages = listOf(
        Choice("", "automatic"), Choice("eng", "English"), Choice("spa", "Spanish"), Choice("fre", "French"),
        Choice("ger", "German"), Choice("ita", "Italian"), Choice("por", "Portuguese"), Choice("jpn", "Japanese"),
        Choice("kor", "Korean"), Choice("chi", "Chinese"), Choice("rus", "Russian"), Choice("dut", "Dutch"),
    )

    // Playback
    // -1 is "automatic" (fits the measured connection); 0 is no limit.
    private val bitrateChoices = listOf(
            Choice(-1, "automatic"), Choice(0, "maximum"), Choice(80_000_000, "80 Mbps"), Choice(60_000_000, "60 Mbps"), Choice(40_000_000, "40 Mbps"),
            Choice(20_000_000, "20 Mbps"), Choice(15_000_000, "15 Mbps"), Choice(10_000_000, "10 Mbps"), Choice(8_000_000, "8 Mbps"),
            Choice(6_000_000, "6 Mbps"), Choice(4_000_000, "4 Mbps"), Choice(3_000_000, "3 Mbps"), Choice(2_000_000, "2 Mbps"),
            Choice(1_000_000, "1 Mbps"),
        )
    // -1 is "automatic" (fits the screen); 0 is no limit.
    private val resolutionChoices = listOf(
        Choice(-1, "automatic"), Choice(0, "no limit"), Choice(2160, "UHD"), Choice(1080, "Full HD"), Choice(720, "HD"), Choice(480, "SD"),
    )

    val maxBitrate = Setting(
        prefs, "max_bitrate", "movies + tv quality",
        "Maximum bitrate for movies and TV shows. Streams above this are transcoded by the server to fit. " +
            "Automatic fits the measured connection to the server (settings \u203a general \u203a optimize for this TV); maximum allows up to 120 Mbps.",
        bitrateChoices, -1,
    )
    val maxResolution = Setting(
        prefs, "max_resolution", "movies + tv resolution",
        "Maximum output resolution for movies and TV shows. Higher-resolution files are downscaled by the server's transcoder. " +
            "Automatic plays files at full size when this TV decodes them, and has the server convert to the screen's size only when it must convert anyway.",
        resolutionChoices, -1,
    )
    val liveBitrate = Setting(
        prefs, "live_bitrate", "live tv quality",
        "Maximum bitrate for live TV streams. Live streams can't buffer far ahead, so automatic leaves more headroom than for movies.",
        bitrateChoices, -1,
    )
    val liveResolution = Setting(
        prefs, "live_resolution", "live tv resolution",
        "Maximum output resolution for live TV. Higher-resolution channels are downscaled by the server. Automatic works as for movies.",
        resolutionChoices, -1,
    )
    val directPlay = onOff(
        "direct_play", "direct play",
        "Play files in their original container and codecs when this device supports them. Off forces server-side transcoding to HLS.", true,
    )
    val allowHevc = onOff("allow_hevc", "HEVC (H.265)", "Play HEVC as-is when this TV decodes it. Turn off if HEVC stutters or shows a black screen; the server then converts it to H.264.", true)
    val videoDecoding = Setting(
        prefs, "video_decoding", "video decoding",
        "Automatic uses the TV's hardware decoders, with its software decoders filling in for formats the hardware can't play (at sizes the processor keeps up with). " +
            "Hardware only leaves those to the server. Prefer software tries software decoders first, which can get round a faulty hardware decoder. " +
            "The app's own (FFmpeg) decodes in the app itself, for formats and boxes nothing else handles (AV1 on older boxes, MPEG-2); it's the slowest.",
        listOf(Choice("auto", "automatic"), Choice("hardware", "hardware only"), Choice("software", "prefer software"), Choice("ffmpeg", "the app's own (FFmpeg)")), "auto",
    )
    val allowVp9 = onOff("allow_vp9", "VP9", "Play VP9 as-is when this TV decodes it. Off makes the server convert it.", true)
    val allowAv1 = onOff("allow_av1", "AV1", "Play AV1 as-is when this TV decodes it. Off makes the server convert it.", true)
    val allowHdr = onOff(
        "allow_hdr", "HDR (HDR10, HLG)",
        "Play HDR as HDR when the TV and screen support it. Off, or on a screen without HDR, the server converts it to SDR with tone mapping.", true,
    )
    val allowDolbyVision = onOff(
        "allow_dolby_vision", "Dolby Vision",
        "Play Dolby Vision when the TV and screen support it. Otherwise its HDR10 base is played where there is one, or the server converts it.", true,
    )
    val softwareAudio = onOff(
        "software_audio", "decode audio in the app",
        "Play DTS, DTS-HD, Dolby TrueHD, Dolby Digital and more with the app's own decoder when the TV can't, instead of having the server convert them.", true,
    )
    val passthrough = onOff(
        "passthrough", "audio passthrough",
        "Send Dolby and DTS audio untouched to a receiver or soundbar that supports it. Off decodes it on this TV instead.", true,
    )
    val hiResAudio = onOff(
        "hires_audio", "high-resolution audio",
        "Keeps 24-bit and high sample-rate audio (FLAC, ALAC, WAV, TrueHD) at full resolution all the way to the TV or receiver. " +
            "Off plays it as 16-bit. With stereo output, sound is mixed down to 16-bit stereo either way.", true,
    )
    val losslessConversions = onOff(
        "lossless_conversions", "lossless conversions",
        "When the server has to convert audio (music the TV can't play, like APE, WavPack or DSD), convert it to FLAC so no quality is lost, " +
            "as long as the quality setting allows (8 Mbps or more). Off, or on slower connections, it's compressed instead.", true,
    )
    val surround = Setting(
        prefs, "surround", "audio output",
        "Surround sends Dolby and DTS on to a receiver or soundbar as they are. Stereo decodes them on this TV and mixes them down to two channels. " +
            "Soundtracks play as they are either way; this also sets what the server converts audio to, when it must. " +
            "Automatic picks surround when a receiver, soundbar or multichannel HDMI output is connected.",
        listOf(Choice("auto", "automatic"), Choice("surround", "surround"), Choice("stereo", "stereo")), "auto",
    )
    val autoplayNext = onOff("autoplay_next", "play next episode", "Continue to the next episode when the current one ends.", true)
    val nextCountdown = Setting(
        prefs, "next_countdown", "next episode countdown",
        "Delay before the next episode starts, shown on the Up Next card. OK plays immediately; Back cancels.",
        listOf(Choice(10, "10 seconds"), Choice(15, "15 seconds"), Choice(20, "20 seconds"), Choice(30, "30 seconds"), Choice(5, "5 seconds"), Choice(0, "immediately")),
        10,
    )
    val trickplay = onOff(
        "trickplay", "trickplay previews",
        "Thumbnail previews while scrubbing. Requires trickplay images on the server (Dashboard \u203a Libraries \u203a Trickplay).", true,
    )
    private val skipChoices = listOf(Choice("button", "show skip button"), Choice("auto", "on (skip automatically)"), Choice("off", "off"))
    val skipIntro = Setting(prefs, "skip_intro", "skip intros", "Action at intro segments. Requires media segments (Jellyfin 10.10+, e.g. the Intro Skipper plugin). Individual shows and movies can override this.", skipChoices, "button")
    val skipCredits = Setting(prefs, "skip_credits", "skip credits", "Action at end-credit segments. With Play next episode on, skipping credits opens Up Next.", skipChoices, "button")
    val skipCommercials = Setting(prefs, "skip_commercials", "skip commercials", "Action at commercial segments marked in recordings (e.g. by Comskip).", skipChoices, "auto")
    val skipRecaps = Setting(prefs, "skip_recaps", "skip recaps + previews", "Action at recap (\u201cpreviously on\u201d) and preview segments.", skipChoices, "button")
    val sleepTimer = Setting(
        prefs, "sleep_timer", "sleep timer",
        "Stops playback after this long without remote input. \u201cAfter this item\u201d stops when the current title ends.",
        listOf(
            Choice(0, "off"), Choice(15, "15 minutes"), Choice(30, "30 minutes"), Choice(45, "45 minutes"), Choice(60, "1 hour"),
            Choice(90, "1\u00bd hours"), Choice(120, "2 hours"), Choice(180, "3 hours"), Choice(-1, "after this item"),
        ),
        0,
    )
    // Player controls: what the remote does with the controls hidden, and small conveniences.
    val okPauses = onOff(
        "ok_pauses", "OK pauses and plays",
        "With the controls hidden, OK pauses and plays straight away. Hold OK, or press Up, for the controls.", true,
    )
    val arrowSkip = onOff(
        "arrow_skip", "left and right skip",
        "With the controls hidden, Left replays and Right skips by the distances below, without opening the controls. Hold either to scan faster; the picture jumps when you let go.", true,
    )
    val controlsAtStart = onOff(
        "controls_at_start", "controls when a video starts",
        "Show the controls for a few seconds as a video starts. Off, it starts clean, and OK, Left and Right work from the first second.", true,
    )
    val pauseInfo = onOff(
        "pause_info", "info when paused",
        "When you pause with OK, show the title, how far in you are, the time and when it'll end, as Media Center did.", true,
    )
    val downForTracks = onOff(
        "down_tracks", "down for subtitles",
        "With the controls hidden, Down opens the subtitle choices directly, instead of the controls.", false,
    )
    val replaySubtitles = onOff(
        "replay_subtitles", "subtitles on replay",
        "When subtitles are off and you replay with Left, show them for the replayed seconds, for when you missed a line; they go off again after.", false,
    )

    val replaySeconds = Setting(
        prefs, "replay_seconds", "replay jumps back",
        "Seek distance for Left and the Replay button.",
        listOf(Choice(7, "7 seconds"), Choice(10, "10 seconds"), Choice(15, "15 seconds"), Choice(30, "30 seconds")), 7,
    )
    val skipSeconds = Setting(
        prefs, "skip_seconds", "skip jumps ahead",
        "Seek distance for Right and the Skip button.",
        listOf(Choice(30, "30 seconds"), Choice(15, "15 seconds"), Choice(10, "10 seconds"), Choice(60, "1 minute")), 30,
    )

    // Audio and subtitles
    val audioLanguage = Setting(prefs, "audio_language", "audio language", "Preferred soundtrack language. Automatic uses the file's default audio track.", languages, "")
    val subtitleMode = Setting(
        prefs, "subtitle_mode", "subtitles",
        "Automatic: default and forced tracks only. Always: any track in the subtitle language. Forced only: foreign-dialogue tracks. Off: none.",
        listOf(Choice("auto", "automatic"), Choice("always", "always"), Choice("forced", "forced only"), Choice("off", "off")), "auto",
    )
    val subtitleLanguage = Setting(prefs, "subtitle_language", "subtitle language", "Preferred subtitle track language.", languages, "")
    val subtitleSize = Setting(
        prefs, "subtitle_size", "subtitle size", "Text subtitle scale relative to the standard size. Image subtitles (PGS, VobSub) keep their authored size.",
        listOf(Choice(1.0f, "medium"), Choice(1.25f, "large"), Choice(1.5f, "extra large"), Choice(0.8f, "small")), 1.0f,
    )
    val subtitleBackground = onOff("subtitle_background", "subtitle background", "Draw text subtitles on a translucent black band for legibility over bright scenes.", false)

    // Interface
    val sounds = onOff("sounds", "interface sounds", "Navigation, selection and startup sounds.", true)
    val backgroundVideo = onOff(
        "background_video", "video behind menus",
        "Keep video playing full-screen, dimmed, behind the menus when you leave the player.", true,
    )
    val intro = onOff("intro", "startup animation", "Play the startup animation and sound at launch.", true)
    val introStyle = Setting(
        prefs, "intro_style", "startup animation style",
        "The look of the startup animation: ribbons of light, a swirl of lights fusing into the orb, glass shards assembling, an aurora, or Media Center's own zoom out of the blue.",
        dev.mediacenter.jf.ui.screens.IntroStyle.entries.map { Choice(it.key, it.label) }, "wmc",
    )
    val animatedBackground = onOff(
        "animated_background", "animated background",
        "Ribbons of light sweep in from the left and drift behind the start menu, carrying on from the startup animation.", true,
    )
    val tasksSwitchServer = onOff(
        "tasks_switch_server", "switch server in tasks",
        "Adds a switch server button to Tasks on the start menu, next to switch users.", false,
    )
    val showDemo = onOff(
        "show_demo", "demo library",
        "Offer \u201ctry the demo\u201d on the sign-in screen. Turns off automatically after the first sign-in to a server.", true,
    )
    val showClock = onOff("show_clock", "clock", "Show the time at the top centre of every screen.", true)
    val textSize = Setting(
        prefs, "text_size", "font size",
        "Scales all text, independent of interface size. Layouts reflow; at the largest sizes long titles may be shortened with an ellipsis.",
        listOf(Choice(0.9f, "90%"), Choice(1.0f, "100%"), Choice(1.1f, "110%"), Choice(1.2f, "120%"), Choice(1.3f, "130%")), 1.0f,
    )
    val uiScale = Setting(
        prefs, "ui_scale", "interface size",
        "Scales all menus and text. 100% is designed for a Full HD TV at couch distance.",
        listOf(Choice(0.8f, "80%"), Choice(0.9f, "90%"), Choice(1.0f, "100%"), Choice(1.1f, "110%"), Choice(1.2f, "120%")), 1.0f,
    )
    val playerScale = Setting(
        prefs, "player_scale", "player controls size",
        "Scale of the playback overlay: title, progress bar and transport controls.",
        listOf(Choice(0.7f, "small"), Choice(0.8f, "medium"), Choice(1.0f, "large")), 0.7f,
    )

    // Live TV
    val channelBanner = onOff("channel_banner", "channel banner", "Show channel number, name and current program for 3.5 seconds after tuning.", true)

    // Per-title skipping: a show or movie can override the defaults above for its
    // intros, credits, recaps and previews. Episodes use their series' choice.
    private val overrideState = androidx.compose.runtime.mutableStateMapOf<String, String>()

    fun skipOverride(titleId: String): String =
        overrideState.getOrPut(titleId) { prefs.getString("skip_override_$titleId", "default") ?: "default" }

    fun cycleSkipOverride(titleId: String) {
        val order = listOf("default", "auto", "button", "off")
        val next = order[(order.indexOf(skipOverride(titleId)) + 1) % order.size]
        overrideState[titleId] = next
        prefs.edit { if (next == "default") remove("skip_override_$titleId") else putString("skip_override_$titleId", next) }
    }

    fun skipOverrideLabel(titleId: String) = when (skipOverride(titleId)) {
        "auto" -> "on"
        "button" -> "show button"
        "off" -> "off"
        else -> "default"
    }

    /** What to do at a segment of [type] in [item]: "auto", "button" or "off". */
    fun skipMode(type: String, item: BaseItem): String {
        val global = when (type) {
            "Intro" -> skipIntro.value
            "Outro" -> skipCredits.value
            "Commercial" -> skipCommercials.value
            "Recap", "Preview" -> skipRecaps.value
            else -> "off"
        }
        if (type == "Commercial") return global
        val override = skipOverride(item.seriesId ?: item.id)
        return if (override == "default") global else override
    }

    // Per-library view, sort and list choices, remembered like Media Center did.
    fun libraryPref(libraryId: String, key: String, default: String): String =
        prefs.getString("lib_${key}_$libraryId", default) ?: default

    fun setLibraryPref(libraryId: String, key: String, value: String) =
        prefs.edit { putString("lib_${key}_$libraryId", value) }

    // Pictures (Windows Media Center's slide show settings).
    val slideRandom = onOff("slide_random", "show pictures in random order", "Shuffle the pictures in a slide show instead of showing them by name.", false)
    val slideSubfolders = onOff("slide_subfolders", "show pictures in subfolders", "Include pictures in folders inside the one you start the slide show from.", true)
    val slideCaptions = onOff("slide_captions", "show caption", "Show each picture's name and position while a slide show plays.", true)
    val slideSongInfo = Setting(
        prefs, "slide_song_info", "show song information",
        "When music is playing during a slide show: show the song title and artist at the start and end of each song, the whole time, or never.",
        listOf(Choice("edges", "at beginning and end of song"), Choice("always", "always"), Choice("never", "never")), "edges",
    )
    val slideTransition = Setting(
        prefs, "slide_transition", "transition type",
        "How one picture changes to the next: a slow pan and zoom with a cross-fade (animated), a plain cross-fade, or an instant cut.",
        listOf(Choice("animated", "animated"), Choice("fade", "cross-fade"), Choice("cut", "none")), "animated",
    )
    val slideSeconds = Setting(
        prefs, "slide_seconds", "show each picture for",
        "How long each picture stays on screen in a slide show.",
        listOf(Choice(3, "3 seconds"), Choice(5, "5 seconds"), Choice(7, "7 seconds"), Choice(10, "10 seconds"), Choice(15, "15 seconds"), Choice(30, "30 seconds")), 7,
    )
    // Recording defaults (Media Center's Settings \u203a TV \u203a Recorder \u203a Recording Defaults).
    val recordStartEarly = Setting(
        prefs, "record_start_early", "start recording",
        "When new recordings start. Starting a few minutes early catches shows that begin before their listed time.",
        listOf(Choice(0, "on time"), Choice(1, "1 minute early"), Choice(2, "2 minutes early"), Choice(5, "5 minutes early"), Choice(10, "10 minutes early")), 1,
    )
    val recordStopLate = Setting(
        prefs, "record_stop_late", "stop recording",
        "When new recordings stop. Stopping late catches shows (especially sports) that run over.",
        listOf(Choice(0, "on time"), Choice(1, "1 minute late"), Choice(5, "5 minutes late"), Choice(10, "10 minutes late"), Choice(30, "30 minutes late"), Choice(60, "1 hour late")), 5,
    )
    val recordNewOnly = onOff(
        "record_new_only", "series: new episodes only",
        "When you record a series, record only episodes that haven't aired before, skipping reruns.", true,
    )
    val recording get() = listOf(recordStartEarly, recordStopLate, recordNewOnly)

    val slidePan = onOff(
        "slide_pan", "slow pan and zoom",
        "Each picture in a slide show slowly pans and zooms while it's on screen, as Media Center's did.", true,
    )
    val pictures get() = listOf(slideRandom, slideSubfolders, slideCaptions, slideSongInfo, slideTransition, slideSeconds, slidePan)

    // Picture and sound, and the player's comforts.
    val matchFrameRate = onOff(
        "match_frame_rate", "match frame rate",
        "Switches the TV to the film's own frame rate (23.976, 24, 25, 50 Hz…) while it plays, so camera pans don't judder, and back afterwards. " +
            "The screen may go dark for a moment as the TV switches. Only where the TV offers that rate at the same resolution.",
        true,
    )
    val styledSubtitles = onOff(
        "styled_subtitles", "styled subtitles",
        "Shows ASS/SSA subtitles (common with anime) with their own fonts, colours and positions. Off shows them as plain text.", true,
    )
    val rememberTracks = onOff(
        "remember_tracks", "remember choices per show",
        "When you pick a soundtrack or subtitles during an episode, the rest of the show's episodes start with the same.", true,
    )
    val nightMode = onOff(
        "night_mode", "night mode",
        "Evens out loud and quiet: explosions come down, dialogue comes up. Needs Android 9 or later, and doesn't apply to sound passed through to a receiver. " +
            "Also in the player's playback panel.",
        false,
    )
    val upForInfo = onOff(
        "up_for_info", "up shows the info bar",
        "With the controls hidden, up shows a slim bar with the time, when it ends, and the picture and sound format. Up again brings the full controls.", true,
    )
    val miniGuide = onOff(
        "mini_guide", "mini guide",
        "While watching live TV, OK brings up Media Center's mini guide: what's on now and next on each channel, without leaving the picture.", true,
    )
    val playback get() = listOf(maxBitrate, maxResolution, directPlay, surround, matchFrameRate, autoplayNext, nextCountdown, trickplay, sleepTimer)
    val playerControls get() = listOf(okPauses, arrowSkip, replaySeconds, skipSeconds, controlsAtStart, upForInfo, pauseInfo, downForTracks, replaySubtitles)
    val skipping get() = listOf(skipIntro, skipCredits, skipCommercials, skipRecaps)
    val audioSubtitles get() = listOf(audioLanguage, subtitleMode, subtitleLanguage, subtitleSize, subtitleBackground, styledSubtitles, rememberTracks, nightMode)

    /** How much later (positive) or earlier the sound plays than the picture, in milliseconds; set in the player. */
    var audioDelayMs: Long
        get() = prefs.getLong("audio_delay_ms", 0L)
        set(v) = prefs.edit { putLong("audio_delay_ms", v) }

    /** The soundtrack and subtitles last picked for a show ("audio=eng;text=off"), or null. */
    fun showTracks(seriesId: String): String? = prefs.getString("show_tracks_$seriesId", null)

    fun setShowTracks(seriesId: String, value: String) = prefs.edit { putString("show_tracks_$seriesId", value) }
    val updateCheck = onOff(
        "update_check", "check for updates",
        "Looks for a new version of Media Center on GitHub now and then, and offers to install it on the start menu. " +
            "You can also check any time in settings \u203a about.",
        true,
    )

    val screensaver = Setting(
        prefs, "screensaver", "screensaver",
        "After a while with nothing playing and no buttons pressed, your films' and shows' artwork drifts across the screen. " +
            "Any button brings you back. (Media Center can also be the TV's own screensaver, in Android's settings, where the TV allows it.)",
        listOf(Choice(0, "off"), Choice(5, "after 5 minutes"), Choice(10, "after 10 minutes"), Choice(20, "after 20 minutes"), Choice(30, "after 30 minutes")), 10,
    )
    val visualizer = onOff(
        "visualizer", "music visualizer",
        "Light that moves with the music behind now playing, as Media Center's visualizations did.", true,
    )
    val lyrics = onOff(
        "lyrics", "lyrics",
        "Shows a song's lyrics on now playing when the server has them, following along where they're timed.", true,
    )
    val interfaceSettings get() = listOf(textSize, uiScale, playerScale, sounds, backgroundVideo, intro, introStyle, animatedBackground, showClock, screensaver, visualizer, lyrics, showDemo, updateCheck)
    val liveTv get() = listOf(liveBitrate, liveResolution, channelBanner, miniGuide)
    val codecs get() = listOf(videoDecoding, allowHevc, allowVp9, allowAv1, allowHdr, allowDolbyVision, softwareAudio, passthrough, hiResAudio, losslessConversions)

    /** Every setting, by the settings section it's in (as the server's settings page shows them). */
    val catalog: List<Pair<String, Setting<*>>>
        get() = listOf(
            "playback" to playback, "player controls" to playerControls, "skipping" to skipping,
            "audio + subtitles" to audioSubtitles, "codecs" to codecs, "live tv" to liveTv, "recording" to recording,
            "pictures" to pictures, "general" to interfaceSettings + listOf(tasksSwitchServer),
        ).flatMap { (section, list) -> list.map { section to it } }

    fun byKey(key: String): Setting<*>? = catalog.firstOrNull { it.second.key == key }?.second
}

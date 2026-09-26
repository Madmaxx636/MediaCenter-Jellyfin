package dev.mediacenter.jf.playback

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.Renderer
import io.github.peerless2012.ass.media.kt.withAssMkvSupport
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import dev.mediacenter.jf.data.BaseItem
import dev.mediacenter.jf.data.MediaRepository
import android.os.SystemClock
import dev.mediacenter.jf.data.MediaSegment
import dev.mediacenter.jf.data.PlaybackReport
import dev.mediacenter.jf.data.Settings
import dev.mediacenter.jf.data.Stream
import dev.mediacenter.jf.data.StreamMode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Repeat { Off, All, One }

data class NowPlaying(
    val queue: List<BaseItem>,
    val index: Int,
    val stream: Stream? = null,
    val shuffle: Boolean = false,
    val repeat: Repeat = Repeat.Off,
) {
    val item get() = queue[index]
    val isVideo get() = item.isVideo
    val isLive get() = item.isChannel
    val hasNext get() = index < queue.lastIndex
}

/** The "up next" card: the episode about to start and how many seconds remain. */
data class UpNext(val item: BaseItem, val secondsLeft: Int)

/** A selectable audio or subtitle track, as shown in the player's track menu. */
data class TrackOption(val label: String, val group: Tracks.Group?, val trackIndex: Int, val selected: Boolean)

/**
 * Owns the single ExoPlayer instance and the play queue, resolves Jellyfin streams
 * (falling back to a server transcode if direct play fails) and reports progress.
 * It outlives screens so playback continues in the "now playing" inset.
 */
class PlaybackManager(
    context: Context,
    private val scope: CoroutineScope,
    private val settings: Settings,
    private val repository: () -> MediaRepository?,
) {
    /** Compose state, so screens showing the video pick up a rebuilt player straight away. */
    private var activePlayer by androidx.compose.runtime.mutableStateOf<ExoPlayer?>(null)
    private var rebuildPending = false

    /**
     * "Compatible sound" for the current item: every format decoded here and mixed down to
     * 16-bit stereo, the one output every TV takes. Used when a TV fails with the sound as it
     * is (some can't take multichannel or high-resolution output), before asking the server.
     */
    private var compatibleAudio = false

    /**
     * "Plain sound": high-resolution (32-bit float) output off, with the channels and passthrough
     * as they are. The first thing tried when an item fails while its sound goes out in float,
     * which some TVs' audio drivers mishandle with multichannel sound.
     */
    private var plainAudio = false

    /** Soundtracks (e.g. "flac/6") this TV has failed to play as-is, and the sound they start in from then on. */
    private val soundFallbacks = mutableMapOf<String, Attempt>()

    /** The output encoding of the current item's sound, once it's started (for choosing a fallback). */
    private var outputEncoding = C.ENCODING_INVALID

    /** The audio settings the current player was built with; a change rebuilds it before the next item. */
    private var builtFor = ""
    /** What the player is built around (sound output, decoders, subtitles); a change means building it afresh. */
    private fun playerSetup() = "${settings.softwareAudio.value}/${dev.mediacenter.jf.data.JellyfinRepository.passthroughAllowed(settings)}/" +
        "${settings.hiResAudio.value}/${dev.mediacenter.jf.data.JellyfinRepository.stereoOutput(settings)}/$compatibleAudio/$plainAudio/" +
        "${settings.videoDecoding.value == "ffmpeg"}/${settings.styledSubtitles.value}"

    /** Created on first use rather than at app start, which keeps launch quick. */
    val player: ExoPlayer
        get() {
            activePlayer?.let { p ->
                // Audio decoding and passthrough are fixed when the player is built; if they've changed
                // and nothing is playing, start afresh with the new ones.
                if (builtFor == playerSetup() || p.mediaItemCount > 0) return p
                activePlayer = null
                p.release()
            }
            return synchronized(this) {
                activePlayer ?: createPlayer(appContext).also { activePlayer = it; builtFor = playerSetup() }
            }
        }

    /** Builds the player afresh if its setup no longer matches (called as an item loads). */
    private fun ensurePlayerSetup() {
        val p = activePlayer ?: return
        if (builtFor == playerSetup()) return
        activePlayer = null
        p.release()
    }

    private val hasPlayer get() = activePlayer != null

    /** Frees the player (and its decoders) when nothing is playing; it's built again on next use. */
    fun releaseIfIdle() {
        val p = activePlayer ?: return
        if (_nowPlaying.value != null) return
        activePlayer = null
        p.release()
    }

    /**
     * The audio decoding settings changed: the player is built afresh with them, now if
     * nothing is playing, otherwise as soon as playback stops.
     */
    fun rebuildPlayer() {
        val p = activePlayer ?: return
        if (_nowPlaying.value != null) {
            rebuildPending = true
            return
        }
        activePlayer = null
        p.release()
    }

    fun pauseIfActive() {
        if (hasPlayer) player.pause()
    }

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying = _nowPlaying.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    /** A marked segment (intro, credits...) the viewer can skip right now, if skipping is set to "show button". */
    private val _segment = MutableStateFlow<MediaSegment?>(null)
    val segment = _segment.asStateFlow()

    private val _upNext = MutableStateFlow<UpNext?>(null)
    val upNext = _upNext.asStateFlow()

    private val _trickplay = MutableStateFlow<TrickplayFrames?>(null)
    val trickplay = _trickplay.asStateFlow()

    /** libass's handler for the player as built (null with styled subtitles off); the player screen hosts its layer. */
    private val _assHandler = MutableStateFlow<io.github.peerless2012.ass.media.AssHandler?>(null)
    val assHandler = _assHandler.asStateFlow()

    /** The chapters of the video playing (empty for none); skip next and previous go chapter by chapter. */
    private val _chapters = MutableStateFlow<List<dev.mediacenter.jf.data.Chapter>>(emptyList())
    val chapters = _chapters.asStateFlow()

    /** The frame rate the TV should switch to while this video plays, or null to leave the TV as it is. */
    private val _frameRate = MutableStateFlow<Float?>(null)
    val frameRate = _frameRate.asStateFlow()

    /**
     * Sync: the sound later (positive) or earlier than the picture, kept for every video (it's usually the TV
     * or receiver that's out); and the subtitles later or earlier, for this video only. Milliseconds.
     */
    private val _audioDelay = MutableStateFlow(settings.audioDelayMs)
    val audioDelay = _audioDelay.asStateFlow()
    private val _subtitleDelay = MutableStateFlow(0L)
    val subtitleDelay = _subtitleDelay.asStateFlow()

    fun setAudioDelay(ms: Long) {
        _audioDelay.value = ms.coerceIn(-2_000L, 2_000L)
        settings.audioDelayMs = _audioDelay.value
    }

    fun setSubtitleDelay(ms: Long) {
        _subtitleDelay.value = ms.coerceIn(-10_000L, 10_000L)
    }

    private val _speed = MutableStateFlow(1f)
    val speed = _speed.asStateFlow()

    fun setSpeed(speed: Float) {
        _speed.value = speed
        player.setPlaybackSpeed(speed)
    }

    private val nightMode = NightMode()

    /** How loud the bass, middle and treble of what's playing are, for the music visualizer. */
    val audioLevels = AudioLevels()

    /** Turns night mode on or off for what's playing (and remembers it). */
    fun setNightMode(on: Boolean) {
        settings.nightMode.set(on)
        applyNightMode()
    }

    private fun applyNightMode() {
        if (!hasPlayer) return
        nightMode.apply(settings.nightMode.value && outputEncoding.isPcm(), player.audioSessionId, player.audioFormat?.channelCount ?: 2)
    }

    private fun Int.isPcm() = this == C.ENCODING_INVALID || this == C.ENCODING_PCM_16BIT || this == C.ENCODING_PCM_FLOAT ||
        this == C.ENCODING_PCM_24BIT || this == C.ENCODING_PCM_32BIT

    private var segments: List<MediaSegment> = emptyList()
    private val autoSkipped = mutableSetOf<String>()
    private var monitorJob: Job? = null
    private var upNextJob: Job? = null
    private var sleepDeadline = 0L

    /** Subtitles to draw right now; the player screen renders these over the video. */
    private val _cues = MutableStateFlow<List<Cue>>(emptyList())
    val cues = _cues.asStateFlow()

    /** How far the current item has fallen back after failing (see [recover]). */
    private var attempt = Attempt.AsIs

    private enum class Attempt { AsIs, PlainSound, CompatibleSound, ServerSound, ServerAll }

    /** The current item's way of playing, and whether a network error has already been retried. */
    private var lastMode = StreamMode.Direct
    private var networkRetried = false

    /**
     * Live TV: how many times in a row the channel has been tuned in again after the stream dropped,
     * and since when it's been buffering (0 when it isn't). A live stream that drops is tuned in
     * again, quietly and as often as it takes; it never falls back to conversions meant for files.
     */
    private var liveRetunes = 0
    private var liveBufferingSince = 0L
    private var livePlayingSince = 0L
    private var loadJob: Job? = null
    private var progressJob: Job? = null



    private fun createPlayer(context: Context): ExoPlayer {
        // The TV's own decoders first; the bundled FFmpeg then covers DTS, TrueHD, Dolby Digital and
        // the like when the TV has no decoder for them (and nothing takes them as passthrough).
        val compatible = compatibleAudio
        val plain = plainAudio
        val useFfmpegVideo = settings.videoDecoding.value == "ffmpeg"
        val stereo = dev.mediacenter.jf.data.JellyfinRepository.stereoOutput(settings)
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink? {
                // Last in each chain: the music visualizer's levels, measured from what's about to play.
                val levels = androidx.media3.exoplayer.audio.TeeAudioProcessor(audioLevels)
                // Stereo (chosen, or automatic with no surround connected) and compatible sound: everything is
                // decoded here and mixed down to two channels by the app itself, rather than handing 5.1 or 7.1
                // to Android to mix, which some TVs can't do. The mix is 16-bit: high-resolution output skips
                // the app's audio processing, and two TV speakers gain nothing audible from 24-bit.
                if (compatible || stereo) {
                    @Suppress("DEPRECATION")
                    return DefaultAudioSink.Builder(context)
                        .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                        .setEnableFloatOutput(false)
                        .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                        .setAudioProcessors(arrayOf(StereoDownmix.create(), levels))
                        .build()
                }
                if (dev.mediacenter.jf.data.JellyfinRepository.passthroughAllowed(settings)) {
                    // As the player's own, plus the levels (sound passed through to a receiver isn't measured).
                    return DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                        .setAudioProcessors(arrayOf(levels))
                        .build()
                }
                // Passthrough off: claim a plain stereo PCM output, so every format is decoded here.
                @Suppress("DEPRECATION")
                return DefaultAudioSink.Builder(context)
                    .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .setAudioProcessors(arrayOf(levels))
                    .build()
            }

            override fun buildVideoRenderers(
                context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
                eventHandler: android.os.Handler, eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>,
            ) {
                val built = ArrayList<Renderer>()
                super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, allowedVideoJoiningTimeMs, built)
                // The app's own FFmpeg video decoder: first when chosen in settings, otherwise the last resort
                // for what no decoder on the TV plays (AV1 on older boxes, MPEG-2 and the like).
                val ffmpeg = runCatching {
                    io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegVideoRenderer(allowedVideoJoiningTimeMs, eventHandler, eventListener, 50)
                }.getOrNull()
                if (ffmpeg != null) if (useFfmpegVideo) built.add(0, ffmpeg) else built.add(ffmpeg)
                // Audio sync: the picture runs ahead by the delay, so the sound lands later against it.
                built.forEach { out += SyncOffset(it) { _audioDelay.value } }
            }

            override fun buildTextRenderers(
                context: Context, output: androidx.media3.exoplayer.text.TextOutput, outputLooper: android.os.Looper,
                extensionRendererMode: Int, out: ArrayList<Renderer>,
            ) {
                val built = ArrayList<Renderer>()
                super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, built)
                // Subtitle sync: a later subtitle is shown for an earlier position.
                built.forEach { out += SyncOffset(it) { -_subtitleDelay.value } }
            }
        }
            .setExtensionRendererMode(
                if (settings.softwareAudio.value) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            )
            .setEnableDecoderFallback(true)
            // 24-bit and high-sample-rate sound kept at full resolution (as 32-bit float) to the output.
            .setEnableAudioFloatOutput(settings.hiResAudio.value && !compatible && !stereo && !plain)
            // Which video decoder to try first follows the "video decoding" setting, read each time a
            // decoder is chosen; if one fails to start, the next in line is tried.
            .setMediaCodecSelector { mime, secure, tunneling ->
                val all = MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
                if (!mime.startsWith("video/")) return@setMediaCodecSelector all
                when (settings.videoDecoding.value) {
                    "hardware" -> all.filter { it.hardwareAccelerated }.ifEmpty { all }
                    "software" -> all.sortedBy { !it.softwareOnly }
                    else -> all.sortedBy { !it.hardwareAccelerated }
                }
            }
        // Buffer ahead within what this TV's memory allows (see Hardware.videoBufferBytes).
        val loadControl = DefaultLoadControl.Builder()
            .setTargetBufferBytes(dev.mediacenter.jf.Hardware.videoBufferBytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        // A server converting a film can take a while to produce the first seconds (more so on a small
        // server or with several people watching), so allow up to 30 s rather than the player's usual 8.
        val http = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
        val data = androidx.media3.datasource.DefaultDataSource.Factory(context, http)
        val builder = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        // ASS/SSA subtitles through libass, with their fonts, colours, positions and animations, drawn over the
        // picture on their own layer (see assHandler); everything else goes through the app's subtitle overlay.
        val ass = if (settings.styledSubtitles.value) {
            io.github.peerless2012.ass.media.AssHandler(
                io.github.peerless2012.ass.media.type.AssRenderType.OVERLAY_OPEN_GL,
                // Drawn at up to 1080p and scaled to the screen, so a 4K TV's processor isn't asked for 4K subtitles.
                io.github.peerless2012.ass.media.AssHandlerConfig(maxRenderPixels = 1920 * 1080),
            )
        } else null
        val extractors = androidx.media3.extractor.DefaultExtractorsFactory().let { base ->
            if (ass == null) base else base.withAssMkvSupport(io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory(ass), ass)
        }
        val sources = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(data, extractors).apply {
            if (ass != null) setSubtitleParserFactory(io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory(ass))
        }
        // The ASS layer follows the same subtitle sync as the rest.
        val factory = if (ass == null) renderers else androidx.media3.exoplayer.RenderersFactory { handler, video, audio, text, metadata ->
            renderers.createRenderers(handler, video, audio, text, metadata) +
                SyncOffset(io.github.peerless2012.ass.media.render.AssRenderer(ass)) { -_subtitleDelay.value }
        }
        val p = builder.setRenderersFactory(factory).setMediaSourceFactory(sources).build()
        ass?.init(p)
        _assHandler.value = ass
        // What actually reaches the TV or receiver, for the log: format, rate and channels.
        p.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onAudioTrackInitialized(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                config: AudioSink.AudioTrackConfig,
            ) {
                outputEncoding = config.encoding
                applyNightMode()
                dev.mediacenter.jf.AppLog.i(
                    "Player",
                    "Audio output: ${encodingName(config.encoding)}, ${config.sampleRate} Hz, " +
                        channelName(Integer.bitCount(config.channelConfig)) + (if (compatible) " (compatible sound)" else if (plain) " (plain sound)" else ""),
                )
            }

            override fun onAudioSessionIdChanged(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, audioSessionId: Int) {
                applyNightMode()
            }

            override fun onVideoInputFormatChanged(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                format: androidx.media3.common.Format,
                decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
            ) {
                // The server didn't say (a conversion, a recording): the frame rate the video itself declares.
                val np = _nowPlaying.value ?: return
                if (_frameRate.value == null && format.frameRate > 0f && np.isVideo && !np.isLive && settings.matchFrameRate.value) {
                    _frameRate.value = format.frameRate
                }
            }

            override fun onAudioSinkError(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, audioSinkError: Exception) {
                dev.mediacenter.jf.AppLog.w("Player", "Audio output problem: ${audioSinkError.message}")
            }
        })
        p.addListener(object : Player.Listener {
            @android.annotation.SuppressLint("PrivateApi") // Debug builds only, below.
            override fun onPlaybackStateChanged(state: Int) {
                // Test builds only: "adb shell setprop debug.mc.failaudio N" pretends the sound fails for the first
                // N ways of playing an item, to exercise the fallbacks on a TV where it wouldn't fail.
                if (dev.mediacenter.jf.BuildConfig.DEBUG && state == Player.STATE_READY &&
                    attempt.ordinal < (runCatching { Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.mc.failaudio") as String }.getOrNull()?.toIntOrNull() ?: 0)
                ) {
                    dev.mediacenter.jf.AppLog.w("Player", "Test: pretending the sound failed")
                    _nowPlaying.value?.let { recover(it, ErrorSide.Sound) }
                    return
                }
                if (state == Player.STATE_BUFFERING) dev.mediacenter.jf.AppLog.i("Player", "Buffering at ${player.currentPosition} ms")
                liveBufferingSince = if (state == Player.STATE_BUFFERING) SystemClock.elapsedRealtime() else 0L
                if (state == Player.STATE_ENDED) onEnded()
            }

            override fun onTracksChanged(tracks: Tracks) {
                val formats = tracks.groups.filter { it.isSelected }.map { g ->
                    val f = g.getTrackFormat((0 until g.length).firstOrNull { g.isTrackSelected(it) } ?: 0)
                    listOfNotNull(f.sampleMimeType, f.codecs, if (f.width > 0) "${f.width}x${f.height}" else null,
                        if (f.channelCount > 0) "${f.channelCount}ch" else null, f.language).joinToString(" ")
                }
                if (formats.isNotEmpty()) dev.mediacenter.jf.AppLog.i("Player", "Selected tracks: ${formats.joinToString(" | ")}")
                val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                if (text.isNotEmpty()) dev.mediacenter.jf.AppLog.i(
                    "Player",
                    "Subtitle tracks: " + text.joinToString(" | ") { g -> g.getTrackFormat(0).let { f -> listOfNotNull(f.sampleMimeType, f.language, f.label).joinToString(" ") } },
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                val current = _nowPlaying.value ?: return
                dev.mediacenter.jf.AppLog.e(
                    "Player",
                    "Playback error ${error.errorCodeName} on \"${current.item.name}\" (${current.item.id}), " +
                        "${if (current.stream?.isTranscode == true) "transcode" else "direct"} at ${player.currentPosition} ms",
                    error,
                )
                logFailedAddress(error)
                if (current.isLive && recoverLive(current, error)) return
                // Network trouble isn't the file's fault: try the same way again once before anything else.
                if (error.errorCode in 2000..2999 && !networkRetried) {
                    networkRetried = true
                    dev.mediacenter.jf.AppLog.w("Player", "Network problem; trying again")
                    load(current.index, player.currentPosition * BaseItem.TicksPerMs, lastMode, fallback = true)
                    return
                }
                // Unexpected errors don't say where they came from: log where, for the box's logcat.
                if (error.errorCode == PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK || error.errorCode == PlaybackException.ERROR_CODE_UNSPECIFIED) {
                    val cause = generateSequence(error.cause) { it.cause }.lastOrNull()
                    if (cause != null) dev.mediacenter.jf.AppLog.e(
                        "Player",
                        "Error cause: $cause @ " + cause.stackTrace.take(6).joinToString(" < ") { "${it.className}.${it.methodName}:${it.lineNumber}" },
                    )
                }
                if (!recover(current, errorSide(error))) _error.value = error.localizedMessage ?: "This item can't be played."
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                report { repo, r -> repo.reportProgress(r) }
                livePlayingSince = if (isPlaying) SystemClock.elapsedRealtime() else 0L
            }

            override fun onCues(cueGroup: CueGroup) {
                _cues.value = cueGroup.cues
            }
        })
        return p
    }

    fun play(queue: List<BaseItem>, index: Int = 0, resume: Boolean = true) {
        if (queue.isEmpty()) return
        reportStopped()
        _error.value = null
        _nowPlaying.value = NowPlaying(queue, index)
        if (queue[index].isChannel) onChannelChanged?.invoke(queue[index])
        load(index, if (resume) queue[index].resumeTicks else 0L)
    }

    fun skipTo(index: Int) {
        val np = _nowPlaying.value ?: return
        if (index !in np.queue.indices) return
        upNextJob?.cancel()
        _upNext.value = null
        reportStopped()
        load(index, 0L)
    }

    /** The next chapter when the video has them (as Media Center's skip did on a DVD), else the next in the queue. */
    fun next() {
        val np = _nowPlaying.value ?: return
        val position = player.currentPosition
        _chapters.value.firstOrNull { it.startMs > position + 1_000 }?.let { return player.seekTo(it.startMs) }
        skipTo(np.index + 1)
    }

    // --- Queue (Media Center's "Now Playing + Queue") ------------------------------

    /** Adds to the end of the queue, or starts playing if nothing is. */
    fun enqueue(items: List<BaseItem>) {
        if (items.isEmpty()) return
        val np = _nowPlaying.value
        if (np == null) play(items, 0, resume = false) else _nowPlaying.update { it?.copy(queue = it.queue + items) }
    }

    /** Queues [items] straight after what's playing, or starts playing them if nothing is. */
    fun playNext(items: List<BaseItem>) {
        if (items.isEmpty()) return
        val np = _nowPlaying.value
        if (np == null) play(items, 0, resume = false)
        else _nowPlaying.update { it?.copy(queue = it.queue.take(it.index + 1) + items + it.queue.drop(it.index + 1)) }
    }

    fun removeFromQueue(index: Int) {
        val np = _nowPlaying.value ?: return
        if (index !in np.queue.indices) return
        if (index == np.index) {
            if (np.queue.size == 1) return stop()
            val rest = np.queue.toMutableList().apply { removeAt(index) }
            _nowPlaying.update { it?.copy(queue = rest, index = index.coerceAtMost(rest.lastIndex)) }
            reportStopped()
            load(index.coerceAtMost(rest.lastIndex), 0L)
        } else {
            _nowPlaying.update { it?.copy(queue = it.queue.toMutableList().apply { removeAt(index) }, index = if (index < it.index) it.index - 1 else it.index) }
        }
    }

    /** Moves a queued song up (-1) or down (+1). */
    fun moveInQueue(index: Int, delta: Int) {
        val np = _nowPlaying.value ?: return
        val to = index + delta
        if (index !in np.queue.indices || to !in np.queue.indices) return
        val q = np.queue.toMutableList().apply { add(to, removeAt(index)) }
        val current = when (np.index) { index -> to; to -> index; else -> np.index }
        _nowPlaying.update { it?.copy(queue = q, index = current) }
    }

    /** Shuffles everything after the current song (or restores nothing: shuffle is one-way, like Media Center's). */
    fun toggleShuffle() {
        val np = _nowPlaying.value ?: return
        val on = !np.shuffle
        val q = if (on) np.queue.take(np.index + 1) + np.queue.drop(np.index + 1).shuffled() else np.queue
        _nowPlaying.update { it?.copy(queue = q, shuffle = on) }
    }

    fun cycleRepeat() = _nowPlaying.update { it?.copy(repeat = Repeat.entries[(it.repeat.ordinal + 1) % Repeat.entries.size]) }

    /** Changes channel while watching live TV, wrapping around the channel list. */
    fun channel(delta: Int) {
        val np = _nowPlaying.value ?: return
        if (!np.isLive || np.queue.size < 2) return
        val target = ((np.index + delta) % np.queue.size + np.queue.size) % np.queue.size
        skipTo(target)
        onChannelChanged?.invoke(np.queue[target])
    }

    /** Lets the app remember the last channel watched. */
    var onChannelChanged: ((BaseItem) -> Unit)? = null

    fun previous() {
        val np = _nowPlaying.value ?: return
        if (np.isLive) return channel(-1)
        // By chapter: back to this chapter's start, or, near its start, to the one before.
        val chapters = _chapters.value
        if (chapters.isNotEmpty()) {
            val position = player.currentPosition
            val current = chapters.lastOrNull { it.startMs <= position }
            val target = if (current != null && position - current.startMs > 5_000) current else chapters.lastOrNull { it.startMs < (current?.startMs ?: 0) }
            if (target != null) return player.seekTo(target.startMs)
        }
        if (player.currentPosition > 5_000 || np.index == 0) player.seekTo(0) else skipTo(np.index - 1)
    }

    fun togglePlayPause() {
        if (_nowPlaying.value == null) return
        if (player.isPlaying) player.pause() else player.play()
    }

    fun replay() = player.seekTo((player.currentPosition - settings.replaySeconds.value * 1000L).coerceAtLeast(0))
    fun skip() = player.seekTo(player.currentPosition + settings.skipSeconds.value * 1000L)

    private var replaySubtitlesJob: Job? = null

    /**
     * "Subtitles on replay": with subtitles off, turns them on for a replayed stretch (in the
     * subtitle or audio language, else the first there is) until playback gets back to [until],
     * then puts them back as they were, unless they've been changed meanwhile.
     */
    fun subtitlesForReplay(until: Long) {
        val groups = player.currentTracks.groups
        if (groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected }) return
        val options = tracks(C.TRACK_TYPE_TEXT).filter { it.group != null }
        if (options.isEmpty()) return
        val audioLanguage = groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).language } }
        val wanted = listOf(settings.subtitleLanguage.value, settings.audioLanguage.value, audioLanguage).filter { !it.isNullOrEmpty() }
        val choice = options.firstOrNull { o ->
            val lang = o.group!!.getTrackFormat(o.trackIndex).language ?: return@firstOrNull false
            wanted.any { w -> lang.startsWith(w!!.take(2), ignoreCase = true) }
        } ?: options.first()
        val before = player.trackSelectionParameters
        selectTrack(C.TRACK_TYPE_TEXT, choice, remember = false)
        val ours = player.trackSelectionParameters
        val itemId = _nowPlaying.value?.item?.id
        replaySubtitlesJob?.cancel()
        replaySubtitlesJob = scope.launch {
            while (_nowPlaying.value?.item?.id == itemId && player.currentPosition < until) delay(300)
            if (_nowPlaying.value?.item?.id == itemId && player.trackSelectionParameters == ours) player.trackSelectionParameters = before
        }
    }

    fun stop() {
        reportStopped()
        loadJob?.cancel()
        monitorJob?.cancel()
        upNextJob?.cancel()
        _upNext.value = null
        _segment.value = null
        _trickplay.value?.release()
        _trickplay.value = null
        _chapters.value = emptyList()
        _frameRate.value = null
        nightMode.release()
        segments = emptyList()
        if (hasPlayer) {
            player.stop()
            player.clearMediaItems()
        }
        _cues.value = emptyList()
        _nowPlaying.value = null
        if (rebuildPending) {
            rebuildPending = false
            rebuildPlayer()
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun tracks(type: Int): List<TrackOption> {
        val options = mutableListOf<TrackOption>()
        if (type == C.TRACK_TYPE_TEXT) {
            val anySelected = player.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
            options += TrackOption("off", null, -1, !anySelected)
        }
        player.currentTracks.groups.filter { it.type == type }.forEach { group ->
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val f = group.getTrackFormat(i)
                val label = listOfNotNull(f.label, f.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifEmpty { it } })
                    .distinct().joinToString(" · ").ifEmpty { "track ${options.size + 1}" } +
                    (if (f.channelCount > 0) " (${channelName(f.channelCount)})" else "")
                options += TrackOption(label, group, i, group.isTrackSelected(i))
            }
        }
        return options
    }

    /** Picks a soundtrack or subtitles (off when [option] has no group); by the viewer, so it's kept for the show. */
    fun selectTrack(type: Int, option: TrackOption, remember: Boolean = true) {
        val builder = player.trackSelectionParameters.buildUpon()
        if (option.group == null) {
            builder.setTrackTypeDisabled(type, true)
        } else {
            builder.setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(option.group.mediaTrackGroup, option.trackIndex))
        }
        player.trackSelectionParameters = builder.build()
        if (remember) rememberForShow(type, option)
    }

    /** Keeps an episode's soundtrack or subtitle choice for the rest of its show: the language, "off", or forced only. */
    private fun rememberForShow(type: Int, option: TrackOption) {
        val item = _nowPlaying.value?.item ?: return
        val show = item.seriesId?.takeIf { item.type == "Episode" && settings.rememberTracks.value } ?: return
        val format = option.group?.getTrackFormat(option.trackIndex)
        val choice = when {
            format == null -> "off"
            format.language.isNullOrEmpty() -> return
            type == C.TRACK_TYPE_TEXT && format.selectionFlags and C.SELECTION_FLAG_FORCED != 0 -> "${format.language}:forced"
            else -> format.language!!
        }
        val key = if (type == C.TRACK_TYPE_AUDIO) "audio" else "text"
        val kept = showTracks(show).filterKeys { it != key } + (key to choice)
        settings.setShowTracks(show, kept.entries.joinToString(";") { "${it.key}=${it.value}" })
    }

    private fun showTracks(show: String): Map<String, String> =
        settings.showTracks(show)?.split(';')?.mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }?.toMap().orEmpty()

    /** Audio/subtitle language and subtitle mode from settings, applied before each item starts. */
    private fun applyTrackPreferences() {
        val b = player.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setPreferredAudioLanguage(settings.audioLanguage.value.ifEmpty { null })
            .setPreferredTextLanguage(settings.subtitleLanguage.value.ifEmpty { null })
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, settings.subtitleMode.value == "off")
        when (settings.subtitleMode.value) {
            "always" -> b.setSelectUndeterminedTextLanguage(true).setIgnoredTextSelectionFlags(0)
            "forced" -> b.setSelectUndeterminedTextLanguage(false).setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            else -> b.setSelectUndeterminedTextLanguage(false).setIgnoredTextSelectionFlags(0)
        }
        if (settings.subtitleMode.value == "always" && settings.subtitleLanguage.value.isEmpty()) {
            b.setPreferredTextLanguageAndRoleFlagsToCaptioningManagerSettings()
        }
        // What was picked earlier in this show wins over the general settings.
        val item = _nowPlaying.value?.item
        val show = item?.seriesId?.takeIf { item.type == "Episode" && settings.rememberTracks.value }
        if (show != null) {
            val kept = showTracks(show)
            kept["audio"]?.let { b.setPreferredAudioLanguage(it) }
            when (val text = kept["text"]) {
                null -> {}
                "off" -> b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                else -> {
                    val forced = text.endsWith(":forced")
                    b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setPreferredTextLanguage(text.removeSuffix(":forced"))
                        .setSelectUndeterminedTextLanguage(false)
                        .setIgnoredTextSelectionFlags(if (forced) C.SELECTION_FLAG_DEFAULT else 0)
                }
            }
        }
        player.trackSelectionParameters = b.build()
    }

    private val appContext = context.applicationContext

    private fun channelName(n: Int) = when (n) { 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "$n ch" }

    /** Where a playback error came from: the sound, the picture, or it doesn't say. */
    private enum class ErrorSide { Sound, Picture, Unknown }

    private fun errorSide(error: PlaybackException): ErrorSide {
        if (error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED
        ) return ErrorSide.Sound
        val mime = (error as? androidx.media3.exoplayer.ExoPlaybackException)?.rendererFormat?.sampleMimeType
        return when {
            mime?.startsWith("audio/") == true -> ErrorSide.Sound
            mime?.startsWith("video/") == true -> ErrorSide.Picture
            else -> ErrorSide.Unknown
        }
    }

    /**
     * After an item fails, tries the next way of playing it, gentlest first, so as little as
     * possible is converted and the server does as little work as possible:
     *  1. as it is, with high-resolution output off ("plain sound"; when the sound was going out in float),
     *  2. as it is, with the sound decoded here in 16-bit stereo ("compatible sound"; sound errors only),
     *  3. the picture as it is, with the server converting only the sound (unless the picture failed),
     *  4. converted entirely by the server.
     * Errors that don't say where they came from are treated as possibly the sound's, short of
     * giving up surround for them. Returns false when there's nothing left to try.
     */
    private fun recover(current: NowPlaying, side: ErrorSide): Boolean {
        val stream = current.stream ?: return false
        val position = player.currentPosition * BaseItem.TicksPerMs
        val server = repository()?.isDemo == false
        val local = !stream.isTranscode
        val next = when {
            side != ErrorSide.Picture && attempt < Attempt.PlainSound && local && outputEncoding == C.ENCODING_PCM_FLOAT && !plainAudio -> Attempt.PlainSound
            side == ErrorSide.Sound && attempt < Attempt.CompatibleSound && local && !compatibleAudio &&
                !dev.mediacenter.jf.data.JellyfinRepository.stereoOutput(settings) -> Attempt.CompatibleSound
            side != ErrorSide.Picture && attempt < Attempt.ServerSound && lastMode == StreamMode.Direct && server -> Attempt.ServerSound
            attempt < Attempt.ServerAll && server -> Attempt.ServerAll
            else -> return false
        }
        attempt = next
        when (next) {
            Attempt.PlainSound -> {
                stream.audioKey?.let { soundFallbacks[it] = Attempt.PlainSound }
                dev.mediacenter.jf.AppLog.w("Player", "Failed with high-resolution sound; trying plain sound (the same channels, not in float)")
                plainAudio = true
                load(current.index, position, StreamMode.Direct, fallback = true)
            }
            Attempt.CompatibleSound -> {
                stream.audioKey?.let { soundFallbacks[it] = Attempt.CompatibleSound }
                dev.mediacenter.jf.AppLog.w("Player", "Sound failed as it is; trying compatible sound (decoded here, 16-bit stereo)")
                compatibleAudio = true
                load(current.index, position, StreamMode.Direct, fallback = true)
            }
            Attempt.ServerSound -> {
                dev.mediacenter.jf.AppLog.w("Player", "Still failing; asking the server to convert only the sound (the picture stays as it is)")
                load(current.index, position, StreamMode.ConvertAudio, fallback = true)
            }
            else -> {
                dev.mediacenter.jf.AppLog.w("Player", "Playing as it is failed; asking the server to convert it")
                load(current.index, position, StreamMode.ConvertAll, fallback = true)
            }
        }
        return true
    }

    private fun encodingName(encoding: Int) = when (encoding) {
        C.ENCODING_PCM_16BIT -> "PCM 16-bit"
        C.ENCODING_PCM_24BIT -> "PCM 24-bit"
        C.ENCODING_PCM_32BIT -> "PCM 32-bit"
        C.ENCODING_PCM_FLOAT -> "PCM float (high resolution)"
        C.ENCODING_AC3 -> "Dolby Digital (passthrough)"
        C.ENCODING_E_AC3 -> "Dolby Digital Plus (passthrough)"
        C.ENCODING_E_AC3_JOC -> "Dolby Atmos in Dolby Digital Plus (passthrough)"
        C.ENCODING_DTS -> "DTS (passthrough)"
        C.ENCODING_DTS_HD -> "DTS-HD (passthrough)"
        C.ENCODING_DOLBY_TRUEHD -> "Dolby TrueHD (passthrough)"
        else -> "encoding $encoding"
    }

    private fun load(index: Int, startTicks: Long, mode: StreamMode = StreamMode.Direct, fallback: Boolean = false) {
        val np = _nowPlaying.value ?: return
        val item = np.queue[index]
        if (!fallback) {
            // A new item starts afresh: as it is, with the sound set up normally.
            attempt = Attempt.AsIs
            compatibleAudio = false
            plainAudio = false
            networkRetried = false
            liveRetunes = 0
        }
        outputEncoding = C.ENCODING_INVALID
        lastMode = mode
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                val repo = repository() ?: return@launch
                dev.mediacenter.jf.AppLog.i(
                    "Player",
                    "Loading \"${item.name}\" (${item.type}, ${item.id})" + when (mode) {
                        StreamMode.Direct -> ""
                        StreamMode.ConvertAudio -> " with the sound converted"
                        StreamMode.ConvertAll -> " converted"
                    },
                )
                val stream = repo.resolveStream(item, mode)
                // A soundtrack this TV couldn't play as it is before starts straight in the sound that worked.
                val known = stream.audioKey?.let { soundFallbacks[it] }
                if (known != null && !stream.isTranscode && attempt < known) {
                    dev.mediacenter.jf.AppLog.i("Player", "Using ${if (known == Attempt.PlainSound) "plain" else "compatible"} sound for ${stream.audioKey} (failed as it is earlier)")
                    if (known == Attempt.PlainSound) plainAudio = true else compatibleAudio = true
                }
                ensurePlayerSetup()
                dev.mediacenter.jf.AppLog.i(
                    "Player",
                    "Stream: ${if (stream.isTranscode) "transcode" else "direct"}${if (stream.isHls) " (HLS)" else ""}, source ${stream.mediaSourceId}" +
                        (if (stream.subtitles.isNotEmpty()) ", ${stream.subtitles.size} separate subtitle track(s)" else "") + ", ${stream.url}",
                )
                val mediaItem = MediaItem.Builder()
                    .setUri(stream.url)
                    .setMediaId(item.id)
                    .apply { if (stream.isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
                    .setSubtitleConfigurations(
                        stream.subtitles.map { sub ->
                            MediaItem.SubtitleConfiguration.Builder(sub.url.toUri())
                                .setMimeType(sub.mimeType)
                                .setLanguage(sub.language)
                                .setLabel(sub.label)
                                .setSelectionFlags(
                                    (if (sub.isDefault) C.SELECTION_FLAG_DEFAULT else 0) or (if (sub.isForced) C.SELECTION_FLAG_FORCED else 0)
                                )
                                .build()
                        }
                    )
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(item.name)
                            .setArtist(item.albumArtist ?: item.seriesName)
                            .setAlbumTitle(item.album)
                            .build()
                    )
                    .build()
                _nowPlaying.update { it?.copy(index = index, stream = stream) }
                // The TV switches to the film's frame rate before it starts (not for live TV: every channel change would blank it).
                _frameRate.value = stream.frameRate.takeIf { settings.matchFrameRate.value && item.isVideo && !item.isChannel }
                _subtitleDelay.value = 0L
                if (_speed.value != 1f) setSpeed(1f)
                applyTrackPreferences()
                // Live TV starts at the live edge; anything else where it left off.
                if (item.isChannel) player.setMediaItem(mediaItem) else player.setMediaItem(mediaItem, startTicks / BaseItem.TicksPerMs)
                player.prepare()
                player.play()
                report { r, rep -> r.reportStart(rep) }
                startProgressLoop()
                prepareExtras(repo, item, stream)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                dev.mediacenter.jf.AppLog.e("Player", "Couldn't start \"${item.name}\"", e)
                _nowPlaying.update { it?.copy(index = index) }
                _error.value = e.message ?: "This item can't be played."
            }
        }
    }

    private fun onEnded() {
        val np = _nowPlaying.value ?: return
        reportStopped()
        when {
            settings.sleepTimer.value == -1 -> stop()
            np.isLive -> stop()
            np.repeat == Repeat.One && !np.isVideo -> { player.seekTo(0); player.play() }
            canUpNext(np) -> beginUpNext()
            !np.hasNext && np.repeat == Repeat.All && np.queue.size > 1 -> load(0, 0L)
            np.hasNext && np.item.type != "Episode" -> load(np.index + 1, 0L)
            else -> stop()
        }
    }

    // --- Segments, up next, sleep timer, trickplay -------------------------------

    private fun prepareExtras(repo: MediaRepository, item: BaseItem, stream: Stream) {
        segments = emptyList()
        autoSkipped.clear()
        _segment.value = null
        _trickplay.value?.release()
        _trickplay.value = null
        cancelUpNext(stopIfEnded = false)
        _chapters.value = emptyList()
        if (item.isVideo && !item.isChannel) {
            scope.launch { segments = runCatching { repo.segments(item.id) }.getOrDefault(emptyList()) }
            scope.launch {
                val list = runCatching { repo.chapters(item.id) }.getOrDefault(emptyList()).sortedBy { it.startMs }
                // One "chapter" covering the whole film isn't worth skipping by.
                if (list.size > 1 && _nowPlaying.value?.item?.id == item.id) _chapters.value = list
            }
            if (settings.trickplay.value) scope.launch {
                val info = runCatching { repo.trickplay(item, stream.mediaSourceId) }
                    .onFailure { dev.mediacenter.jf.AppLog.w("Player", "Trickplay info couldn't be fetched: ${it.message}") }
                    .getOrNull()
                if (info == null) {
                    dev.mediacenter.jf.AppLog.i("Player", "Trickplay: none for this video (the server makes them per library: Dashboard > Libraries > Trickplay)")
                    return@launch
                }
                dev.mediacenter.jf.AppLog.i("Player", "Trickplay: ${info.info.width}x${info.info.height}, ${info.info.thumbnailCount} frames, ${info.perSheet} per sheet")
                val dir = java.io.File(appContext.cacheDir, "trickplay")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { TrickplayFrames.clearOthers(dir, item.id) }
                // Moved on to another video meanwhile: these previews aren't its.
                if (_nowPlaying.value?.item?.id != item.id) return@launch
                val frames = TrickplayFrames(info, scope, dir) { sheet -> repo.trickplaySheet(info, sheet) }
                // The sheet around where playback starts, so the first scrub has its previews ready.
                frames.prefetch(player.currentPosition)
                _trickplay.value = frames
            }
        }
        userActivity()
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive) {
                delay(400)
                checkSegments()
                checkSleep()
                checkLive()
            }
        }
    }

    /**
     * Live TV that's gone quiet: after a long stall the server has usually dropped the stream, so tune
     * in again rather than wait forever. A stretch of good playback clears the count of tries.
     */
    private fun checkLive() {
        val np = _nowPlaying.value ?: return
        if (!np.isLive) return
        val now = SystemClock.elapsedRealtime()
        if (livePlayingSince > 0 && now - livePlayingSince > 30_000) liveRetunes = 0
        if (liveBufferingSince > 0 && now - liveBufferingSince > 15_000 && player.playWhenReady) {
            liveBufferingSince = 0L
            retuneLive(np, "stalled for 15 s")
        }
    }

    /** Handles a live stream's error: back to the live edge, or tune in again. False to handle it as usual. */
    private fun recoverLive(current: NowPlaying, error: PlaybackException): Boolean {
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            dev.mediacenter.jf.AppLog.w("Player", "Fell behind the live stream; jumping to live")
            player.seekToDefaultPosition()
            player.prepare()
            return true
        }
        if (error.errorCode !in 2000..2999 && error.errorCode != PlaybackException.ERROR_CODE_UNSPECIFIED) return false
        return retuneLive(current, error.errorCodeName)
    }

    private fun retuneLive(current: NowPlaying, why: String): Boolean {
        // Many drops in a row, with no real playback between them: the channel is genuinely off the air.
        if (liveRetunes >= 8) return false
        liveRetunes++
        dev.mediacenter.jf.AppLog.w("Player", "Live stream dropped ($why); tuning in again (try $liveRetunes)")
        val index = current.index
        // Let go of the old stream first: the server closes its tuner when told playback stopped. Opening a
        // new stream while the old one still holds the tuner fails (the server answers 500).
        player.stop()
        reportStopped()
        scope.launch {
            delay(1_000L + (liveRetunes * 1_000L).coerceAtMost(4_000L))
            if (_nowPlaying.value?.index == index) load(index, 0L, lastMode, fallback = true)
        }
        return true
    }

    /** For a failed request, which address the server refused (without the sign-in in it), for the log. */
    private fun logFailedAddress(error: PlaybackException) {
        val http = generateSequence(error.cause) { it.cause }.filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull() ?: return
        dev.mediacenter.jf.AppLog.w("Player", "Server answered ${http.responseCode} for ${http.dataSpec.uri.path}")
    }

    private fun checkSegments() {
        val np = _nowPlaying.value ?: return
        if (segments.isEmpty() || _upNext.value != null) return
        val pos = player.currentPosition
        val seg = segments.firstOrNull { pos >= it.startMs && pos < it.endMs - 1_000 }
        if (seg == null) {
            _segment.value = null
            return
        }
        when (settings.skipMode(seg.type, np.item)) {
            "auto" -> {
                val key = seg.id ?: "${seg.type}@${seg.startTicks}"
                if (autoSkipped.add(key)) skipSegment(seg)
            }
            "button" -> _segment.value = seg
            else -> _segment.value = null
        }
    }

    private fun skipSegment(seg: MediaSegment) {
        _segment.value = null
        val np = _nowPlaying.value ?: return
        val nearEnd = player.duration > 0 && seg.endMs >= player.duration - 5_000
        if (seg.type == "Outro" && nearEnd && canUpNext(np)) beginUpNext() else player.seekTo(seg.endMs)
    }

    /** OK on the skip button. */
    fun skipCurrentSegment() {
        _segment.value?.let(::skipSegment)
    }

    private fun canUpNext(np: NowPlaying) = np.item.type == "Episode" && np.hasNext && settings.autoplayNext.value

    private fun beginUpNext() {
        val np = _nowPlaying.value ?: return
        if (_upNext.value != null) return
        val next = np.queue[np.index + 1]
        val seconds = settings.nextCountdown.value
        if (seconds <= 0) return playUpNextNow()
        _upNext.value = UpNext(next, seconds)
        upNextJob?.cancel()
        upNextJob = scope.launch {
            for (left in seconds downTo 1) {
                _upNext.value = UpNext(next, left)
                delay(1_000)
            }
            playUpNextNow()
        }
    }

    fun playUpNextNow() {
        val np = _nowPlaying.value ?: return
        upNextJob?.cancel()
        _upNext.value = null
        if (np.hasNext) skipTo(np.index + 1)
    }

    /** Back on the up next card: stay on the credits, or leave if the episode has already ended. */
    fun cancelUpNext(stopIfEnded: Boolean = true) {
        upNextJob?.cancel()
        if (_upNext.value == null) return
        _upNext.value = null
        if (stopIfEnded && hasPlayer && player.playbackState == Player.STATE_ENDED) stop()
    }

    /** Any button press restarts the sleep timer, so it only fires when nobody's watching. */
    fun userActivity() {
        val minutes = settings.sleepTimer.value
        sleepDeadline = if (minutes > 0) SystemClock.elapsedRealtime() + minutes * 60_000L else 0L
    }

    private fun checkSleep() {
        if (sleepDeadline == 0L) {
            if (settings.sleepTimer.value > 0) userActivity()
            return
        }
        if (settings.sleepTimer.value <= 0) {
            sleepDeadline = 0L
            return
        }
        if (SystemClock.elapsedRealtime() >= sleepDeadline) stop()
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                delay(10_000)
                val np = _nowPlaying.value
                when {
                    player.isPlaying -> report { r, rep -> r.reportProgress(rep) }
                    // Paused or buffering: still tell the server the session's alive, or it may end a
                    // conversion or close a live channel that's only waiting for its next piece.
                    np?.stream?.isTranscode == true || np?.isLive == true -> {
                        report { r, rep -> r.reportProgress(rep) }
                        np.stream?.playSessionId?.let { id -> repository()?.let { repo -> scope.launch { runCatching { repo.ping(id) } } } }
                    }
                }
            }
        }
    }

    private fun reportStopped() {
        progressJob?.cancel()
        report { r, rep -> r.reportStop(rep) }
    }

    private fun report(block: suspend (MediaRepository, PlaybackReport) -> Unit) {
        val np = _nowPlaying.value ?: return
        val stream = np.stream ?: return
        val repo = repository() ?: return
        val rep = PlaybackReport(np.item.id, stream, player.currentPosition * BaseItem.TicksPerMs, !player.isPlaying)
        scope.launch { runCatching { block(repo, rep) } }
    }

    fun release() {
        stop()
        activePlayer?.release()
        activePlayer = null
    }

}

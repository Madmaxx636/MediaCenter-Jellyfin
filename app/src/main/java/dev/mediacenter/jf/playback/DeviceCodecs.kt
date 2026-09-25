package dev.mediacenter.jf.playback

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.audio.AudioCapabilities
import dev.mediacenter.jf.AppLog

@kotlinx.serialization.Serializable
/**
 * A video decoder this TV has, in Jellyfin's terms. For a software decoder, the size is
 * what the processor keeps up with in real time, not just what the decoder accepts.
 */
class VideoDecoder(val codec: String, val maxWidth: Int, val maxHeight: Int, val tenBit: Boolean, val hardware: Boolean)

/**
 * What this TV can play, found by asking Android: its hardware video decoders (with
 * their largest sizes and 10-bit support), which HDR formats the screen shows, which
 * audio it decodes itself, which audio the app's bundled FFmpeg decodes, and which
 * Dolby / DTS formats go straight to a receiver or soundbar.
 *
 * The device profile sent to Jellyfin is built from this, so anything the TV can play
 * is played as-is and only the rest is converted by the server.
 */
@kotlinx.serialization.Serializable
class DeviceCodecs(
    /** The best hardware decoder for each format. */
    val hardwareVideo: Map<String, VideoDecoder>,
    /** The best software decoder for each format, capped at what plays smoothly. */
    val softwareVideo: Map<String, VideoDecoder>,
    val dolbyVisionDecoder: Boolean,
    /** Audio codecs (Jellyfin names) Android decodes on this TV. */
    val platformAudio: Set<String>,
    /** Audio codecs the app's FFmpeg decoder handles. */
    val ffmpegAudio: Set<String>,
) {
    /** HDR formats the screen shows: "HDR10", "HDR10+", "HLG", "DOVI". Checked again before each playback. */
    @kotlinx.serialization.Transient @Volatile var screenHdr: Set<String> = emptySet()
        private set

    /** Audio codecs the connected receiver, soundbar or TV accepts as-is. Checked again before each playback. */
    @kotlinx.serialization.Transient @Volatile var passthroughAudio: Set<String> = emptySet()
        private set

    /** Re-checks what's plugged in (screen HDR, receiver passthrough): quick, unlike listing the decoders. */
    fun updateOutputs(context: Context) {
        screenHdr = screenHdr(context)
        passthroughAudio = passthrough(context)
    }

    companion object {
        /** Jellyfin's names for the video formats Android reports, in order of preference. */
        private val videoTypes = linkedMapOf(
            "video/avc" to "h264",
            "video/hevc" to "hevc",
            "video/x-vnd.on2.vp9" to "vp9",
            "video/av01" to "av1",
            "video/x-vnd.on2.vp8" to "vp8",
            "video/mpeg2" to "mpeg2video",
            "video/mp4v-es" to "mpeg4",
            "video/wvc1" to "vc1",
        )

        /** Jellyfin's names for the audio formats Android reports. */
        private val audioTypes = mapOf(
            "audio/mp4a-latm" to "aac",
            "audio/mpeg" to "mp3",
            "audio/mpeg-l2" to "mp2",
            "audio/ac3" to "ac3",
            "audio/eac3" to "eac3",
            "audio/eac3-joc" to "eac3",
            "audio/ac4" to "ac4",
            "audio/vnd.dts" to "dts",
            "audio/vnd.dts.hd" to "dts",
            "audio/true-hd" to "truehd",
            "audio/opus" to "opus",
            "audio/vorbis" to "vorbis",
            "audio/flac" to "flac",
            "audio/alac" to "alac",
            "audio/raw" to "pcm",
        )

        /** What the bundled FFmpeg is asked to decode (it covers the rest of the list above too). */
        private val ffmpegTypes = listOf(
            "audio/ac3", "audio/eac3", "audio/vnd.dts", "audio/vnd.dts.hd", "audio/true-hd",
            "audio/flac", "audio/alac", "audio/mpeg", "audio/mp4a-latm",
        )

        private lateinit var appContext: Context

        @Volatile private var cached: DeviceCodecs? = null

        /** Remembers the app so detection can run later on any thread; starts it in the background. */
        fun init(context: Context) {
            appContext = context.applicationContext
            Thread({ current }, "codec-detect").start()
        }

        /** The detected capabilities; worked out once (a quick look at the codec list) and then kept. */
        val current: DeviceCodecs
            get() = cached ?: synchronized(this) {
                cached ?: load(appContext).also { cached = it; it.updateOutputs(appContext); AppLog.i("Codecs", it.describe()) }
            }

        /** Checks what's plugged in again, e.g. after a receiver or a different TV was connected. */
        fun refreshOutputs() = current.updateOutputs(appContext)

        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        /**
         * The decoder list, saved after the first check: listing every codec takes a noticeable moment
         * on TV chips (about 0.1 s on an onn box), and it only changes with an Android update or a new
         * version of this app, so both are part of the key.
         */
        private fun load(context: Context): DeviceCodecs {
            val prefs = context.getSharedPreferences("codecs", Context.MODE_PRIVATE)
            val key = "${Build.FINGERPRINT}|${dev.mediacenter.jf.BuildConfig.VERSION_CODE}"
            if (prefs.getString("key", null) == key) {
                prefs.getString("codecs", null)?.let { saved ->
                    runCatching { json.decodeFromString<DeviceCodecs>(saved) }.getOrNull()?.let { return it }
                }
            }
            val found = detect()
            runCatching { prefs.edit().putString("key", key).putString("codecs", json.encodeToString(found)).apply() }
            return found
        }

        private fun detect(): DeviceCodecs {
            val decoders = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder } }.getOrDefault(emptyList())

            val hardwareVideo = linkedMapOf<String, VideoDecoder>()
            val softwareVideo = linkedMapOf<String, VideoDecoder>()
            for ((type, codec) in videoTypes) {
                val candidates = decoders.filter { info -> info.supportedTypes.any { it.equals(type, ignoreCase = true) } }
                for (hardware in listOf(true, false)) {
                    var best: VideoDecoder? = null
                    for (info in candidates.filter { isHardware(it) == hardware }) {
                        val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull() ?: continue
                        val vc = caps.videoCapabilities ?: continue
                        val tenBit = caps.profileLevels.any { it.profile in tenBitProfiles(codec) }
                        val (w, h) = if (hardware) vc.supportedWidths.upper to vc.supportedHeights.upper else realTimeSize(vc, codec)
                        val found = VideoDecoder(codec, w, h, tenBit, hardware)
                        best = best?.let {
                            VideoDecoder(codec, maxOf(it.maxWidth, found.maxWidth), maxOf(it.maxHeight, found.maxHeight), it.tenBit || found.tenBit, hardware)
                        } ?: found
                    }
                    if (best != null) (if (hardware) hardwareVideo else softwareVideo)[codec] = best
                }
            }
            val dolbyVision = decoders.any { info -> isHardware(info) && info.supportedTypes.any { it.equals("video/dolby-vision", ignoreCase = true) } }

            val platformAudio = decoders.flatMap { it.supportedTypes.toList() }.mapNotNull { audioTypes[it.lowercase()] }.toSortedSet()

            val ffmpegAudio = runCatching {
                if (FfmpegLibrary.isAvailable()) ffmpegTypes.filter { FfmpegLibrary.supportsFormat(it) }.mapNotNull { audioTypes[it] }.toSortedSet()
                else sortedSetOf()
            }.getOrDefault(sortedSetOf())

            return DeviceCodecs(hardwareVideo, softwareVideo, dolbyVision, platformAudio, ffmpegAudio)
        }

        private fun passthrough(context: Context): Set<String> = runCatching {
            val caps = AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null)
            buildSet {
                if (caps.supportsEncoding(C.ENCODING_AC3)) add("ac3")
                if (caps.supportsEncoding(C.ENCODING_E_AC3) || caps.supportsEncoding(C.ENCODING_E_AC3_JOC)) add("eac3")
                if (caps.supportsEncoding(C.ENCODING_DTS) || caps.supportsEncoding(C.ENCODING_DTS_HD)) add("dts")
                if (caps.supportsEncoding(C.ENCODING_DOLBY_TRUEHD)) add("truehd")
            }
        }.getOrDefault(emptySet())

        /**
         * The largest common size a software decoder plays at 30 fps on this processor: Android's
         * own measurements where the TV has them, otherwise a cautious guess from the core count.
         */
        private fun realTimeSize(vc: MediaCodecInfo.VideoCapabilities, codec: String): Pair<Int, Int> {
            val sizes = listOf(3840 to 2160, 2560 to 1440, 1920 to 1080, 1280 to 720, 854 to 480, 640 to 360)
            var measuredAny = false
            for ((w, h) in sizes) {
                if (!vc.isSizeSupported(w, h)) continue
                val rates = runCatching { vc.getAchievableFrameRatesFor(w, h) }.getOrNull()
                if (rates != null) {
                    measuredAny = true
                    if (rates.upper >= 29.0) return w to h
                    continue
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    val points = vc.supportedPerformancePoints
                    if (!points.isNullOrEmpty()) {
                        measuredAny = true
                        if (points.any { it.covers(MediaCodecInfo.VideoCapabilities.PerformancePoint(w, h, 30)) }) return w to h
                    }
                }
            }
            if (measuredAny) return 640 to 360
            val cores = Runtime.getRuntime().availableProcessors()
            // H.264, MPEG-2/4 and VP8 are light to decode; HEVC, VP9 and AV1 much heavier.
            val light = codec in setOf("h264", "mpeg2video", "mpeg4", "vp8")
            return when {
                cores >= 8 -> 1920 to 1080
                cores >= 4 -> if (light) 1920 to 1080 else 1280 to 720
                else -> if (light) 1280 to 720 else 854 to 480
            }
        }

        private fun isHardware(info: MediaCodecInfo): Boolean {
            if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated
            val name = info.name.lowercase()
            return !name.startsWith("omx.google.") && !name.startsWith("c2.android.") && !name.contains(".sw.")
        }

        /** The profiles that mean a decoder handles 10-bit video. */
        private fun tenBitProfiles(codec: String): Set<Int> = when (codec) {
            "h264" -> setOf(CodecProfileLevel.AVCProfileHigh10)
            "hevc" -> setOf(CodecProfileLevel.HEVCProfileMain10, CodecProfileLevel.HEVCProfileMain10HDR10, CodecProfileLevel.HEVCProfileMain10HDR10Plus)
            "vp9" -> setOf(CodecProfileLevel.VP9Profile2, CodecProfileLevel.VP9Profile2HDR, CodecProfileLevel.VP9Profile2HDR10Plus)
            "av1" -> setOf(CodecProfileLevel.AV1ProfileMain10, CodecProfileLevel.AV1ProfileMain10HDR10, CodecProfileLevel.AV1ProfileMain10HDR10Plus)
            else -> emptySet()
        }

        @Suppress("DEPRECATION")
        private fun screenHdr(context: Context): Set<String> {
            val types: IntArray = try {
                val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                when {
                    Build.VERSION.SDK_INT >= 34 -> display.mode.supportedHdrTypes
                    Build.VERSION.SDK_INT >= 24 -> display.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
                    else -> IntArray(0) // Android 6 can't report HDR; the server tone-maps.
                }
            } catch (e: Exception) {
                IntArray(0)
            }
            val found = HashSet<String>()
            for (type in types) {
                when (type) {
                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> found.add("DOVI")
                    Display.HdrCapabilities.HDR_TYPE_HDR10 -> found.add("HDR10")
                    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> found.add("HDR10+")
                    Display.HdrCapabilities.HDR_TYPE_HLG -> found.add("HLG")
                }
            }
            return found
        }

        private val niceNames = mapOf(
            "h264" to "H.264", "hevc" to "HEVC", "vp9" to "VP9", "av1" to "AV1", "vp8" to "VP8",
            "mpeg2video" to "MPEG-2", "mpeg4" to "MPEG-4", "vc1" to "VC-1",
            "aac" to "AAC", "mp3" to "MP3", "mp2" to "MP2", "ac3" to "Dolby Digital", "eac3" to "Dolby Digital Plus",
            "ac4" to "Dolby AC-4", "dts" to "DTS", "truehd" to "Dolby TrueHD", "opus" to "Opus", "vorbis" to "Vorbis",
            "flac" to "FLAC", "alac" to "ALAC", "pcm" to "PCM",
        )

        fun nice(codec: String) = niceNames[codec] ?: codec.uppercase()
    }

    /**
     * The decoder used for each format under a "video decoding" setting: "auto" is hardware
     * with software filling the gaps, "hardware" is hardware only (H.264 always plays), and
     * "software" puts software first.
     */
    fun video(mode: String): Map<String, VideoDecoder> = linkedMapOf<String, VideoDecoder>().apply {
        for (codec in videoTypes.values.distinct()) {
            val hw = hardwareVideo[codec]
            val sw = softwareVideo[codec]
            val pick = when (mode) {
                "hardware" -> hw ?: sw.takeIf { codec == "h264" }
                "software" -> sw ?: hw
                else -> hw ?: sw
            }
            if (pick != null) put(codec, pick)
        }
    }

    /** Whether any of the app's own decoding covers [codec] ([ffmpeg] when software decoding is on). */
    fun playsAudio(codec: String, ffmpeg: Boolean, passthrough: Boolean) =
        codec in platformAudio || (ffmpeg && codec in ffmpegAudio) || (passthrough && codec in passthroughAudio)

    /** The class of the largest 16:9 picture the decoder fits (decoders report square-ish limits like 2048x2048). */
    private fun size(d: VideoDecoder): String {
        val w = minOf(d.maxWidth, d.maxHeight * 16 / 9)
        return AutoTune.qualityLabel(w * 9 / 16, w)
    }

    /** A short line per video format, e.g. "HEVC 10-bit up to UHD" or "AV1 up to HD (software)". */
    fun videoLines(mode: String): List<String> = video(mode).values.map { d ->
        "${nice(d.codec)}${if (d.tenBit) " 10-bit" else ""} up to ${size(d)}${if (d.hardware) "" else " (software)"}"
    }

    /** For the log: everything found, on one line. */
    fun describe() = "hardware video [${hardwareVideo.values.joinToString { "${it.codec} ${it.maxWidth}x${it.maxHeight}${if (it.tenBit) " 10-bit" else ""}" }}]" +
        "; software video [${softwareVideo.values.joinToString { "${it.codec} ${it.maxWidth}x${it.maxHeight}${if (it.tenBit) " 10-bit" else ""}" }}]" +
        (if (dolbyVisionDecoder) " + Dolby Vision decoder" else "") +
        "; screen HDR ${screenHdr.ifEmpty { setOf("none") }.joinToString()}" +
        "; audio ${platformAudio.joinToString()}; FFmpeg ${ffmpegAudio.ifEmpty { setOf("unavailable") }.joinToString()}" +
        "; passthrough ${passthroughAudio.ifEmpty { setOf("none") }.joinToString()}"
}

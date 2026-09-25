package dev.mediacenter.jf.playback

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.view.Display
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import dev.mediacenter.jf.AppLog
import dev.mediacenter.jf.data.MediaRepository
import kotlinx.coroutines.withTimeoutOrNull

/** The screen and sound this TV was found to have. */
data class DeviceScan(
    /** Tallest picture the screen can show (e.g. 2160 for a 4K TV). */
    val screenHeight: Int,
    val screenWidth: Int,
    /** Most audio channels the output takes as PCM; 0 when it doesn't say. */
    val outputChannels: Int,
    /** Where sound goes: "HDMI", "HDMI ARC", "TV speakers" and so on. */
    val outputName: String,
    /** Surround reaches the listener: a receiver or soundbar that takes Dolby/DTS, or a multichannel HDMI output. */
    val surround: Boolean,
)

/**
 * Sets playback up to suit this TV. The screen and sound are checked every time
 * something plays (it's quick), so moving the box to another TV or adding a receiver
 * is picked up by itself. The connection to each server is measured once, on first
 * sign-in or from "optimize for this TV", and quietly again when a week old.
 *
 * Only settings left on "automatic" follow these results; a choice made by hand stays.
 * Nothing here touches the interface's look.
 */
object AutoTune {
    private lateinit var appContext: Context
    private val prefs by lazy { appContext.getSharedPreferences("autotune", Context.MODE_PRIVATE) }

    /** The latest screen and sound check; Compose state, so labels in settings update. */
    var device by mutableStateOf(DeviceScan(1080, 1920, 0, "unknown", true))
        private set

    /** Bumped when a speed test finishes, so anything showing a measured speed updates. */
    private var measured by mutableStateOf(0)

    /** Remembers the app and checks the screen and sound in the background (the codec check is shared). */
    fun init(context: Context) {
        appContext = context.applicationContext
        Thread({ runCatching { rescanDevice() } }, "autotune").start()
    }

    fun rescanDevice(): DeviceScan {
        runCatching { DeviceCodecs.refreshOutputs() }
        device = scan(appContext)
        return device
    }

    // --- Connection speed, per server -------------------------------------------------------

    /** The measured speed to [serverKey], in bits per second, or null if never measured. */
    fun measuredBps(serverKey: String): Long? {
        measured // read, so callers recompose after a new measurement
        return prefs.getLong("bps_$serverKey", 0L).takeIf { it > 0 }
    }

    fun measuredAt(serverKey: String): Long = prefs.getLong("at_$serverKey", 0L)

    /** Whether this server has been set up yet (the first-run check has been shown). */
    fun isTuned(serverKey: String) = prefs.getBoolean("tuned_$serverKey", false)

    fun markTuned(serverKey: String) = prefs.edit { putBoolean("tuned_$serverKey", true) }

    /**
     * Measures the connection to the server with Jellyfin's own test download, the way
     * Jellyfin's web app does: a small download first, then larger ones while they finish in
     * under a second, so fast networks get a proper reading and slow ones aren't kept waiting.
     */
    suspend fun measure(repo: MediaRepository, serverKey: String): Long? {
        if (repo.isDemo) return null
        val bps = withTimeoutOrNull(20_000) {
            var last: Double? = null
            for (size in listOf(500_000, 2_000_000, 10_000_000, 30_000_000)) {
                val start = System.nanoTime()
                val bytes = repo.speedTest(size) ?: break
                val seconds = (System.nanoTime() - start) / 1e9
                if (seconds <= 0.0 || bytes <= 0) break
                last = bytes * 8 / seconds
                if (seconds > 1.0) break
            }
            last
        }?.toLong() ?: return null
        prefs.edit {
            putLong("bps_$serverKey", bps)
            putLong("at_$serverKey", System.currentTimeMillis())
        }
        measured++
        AppLog.i("AutoTune", "Connection to $serverKey: ${bps / 1_000_000} Mbps")
        return bps
    }

    /** Re-measures quietly when the last reading is over a week old. */
    suspend fun refreshIfStale(repo: MediaRepository, serverKey: String) {
        if (repo.isDemo || !isTuned(serverKey)) return
        if (System.currentTimeMillis() - measuredAt(serverKey) < 7L * 24 * 3600 * 1000) return
        runCatching { measure(repo, serverKey) }
    }

    // --- What "automatic" works out to ------------------------------------------------------

    /**
     * The streaming limit for "automatic" quality: 80% of the measured speed for files (they
     * buffer well ahead), 60% for live TV (it can't), between 2 and 120 Mbps. Unmeasured
     * connections aren't limited, as before.
     */
    fun autoBitrate(serverKey: String, live: Boolean): Int {
        val bps = measuredBps(serverKey) ?: return MaxBitrate
        val usable = bps * (if (live) 0.6 else 0.8)
        return usable.toLong().coerceIn(2_000_000L, MaxBitrate.toLong()).toInt()
    }

    /** The resolution limit for "automatic": the tallest picture the screen shows. */
    fun autoMaxHeight(): Int = when {
        device.screenHeight >= 2000 -> 2160
        device.screenHeight >= 1400 -> 1440
        device.screenHeight >= 1000 -> 1080
        device.screenHeight >= 700 -> 720
        device.screenHeight > 0 -> 480
        else -> 0
    }

    /** "Automatic" audio output: stereo unless surround actually reaches the listener. */
    fun autoStereo(): Boolean = !device.surround

    /** A streaming limit, e.g. "40 Mbps". */
    fun bitrateLabel(bps: Int): String = speedLabel(bps.toLong())

    /** A measured or chosen speed: "250 Mbps", "4.5 Mbps", "800 kbps". */
    fun speedLabel(bps: Long): String = when {
        bps >= 10_000_000 -> "${bps / 1_000_000} Mbps"
        bps >= 1_000_000 -> "%.1f Mbps".format(bps / 1_000_000.0)
        else -> "${bps / 1000} kbps"
    }

    fun heightLabel(h: Int): String = if (h == 0) "no limit" else qualityLabel(h)

    /**
     * The name for a picture size, as used throughout the app: SD, HD (720p), Full HD (1080p),
     * QHD (1440p) and UHD (4K). [width] catches wide films cropped shorter than their class.
     */
    fun qualityLabel(height: Int, width: Int = 0): String = when {
        height >= 2000 || width >= 3800 -> "UHD"
        height >= 1400 || width >= 2500 -> "QHD"
        height >= 1000 || width >= 1900 -> "Full HD"
        height >= 700 || width >= 1260 -> "HD"
        else -> "SD"
    }

    const val MaxBitrate = 120_000_000

    // --- The checks -------------------------------------------------------------------------

    private fun scan(context: Context): DeviceScan {
        var width = 1920
        var height = 1080
        runCatching {
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            // The largest mode the TV offers, not just the one the menus run in: many boxes run
            // their interface at 1080p on a 4K TV and switch up for video.
            val best = display.supportedModes.maxByOrNull { it.physicalWidth.toLong() * it.physicalHeight }
            if (best != null) {
                width = maxOf(best.physicalWidth, best.physicalHeight)
                height = minOf(best.physicalWidth, best.physicalHeight)
            }
        }

        var channels = 0
        var name = "TV speakers"
        var hdmi = false
        runCatching {
            val am = context.getSystemService(AudioManager::class.java)
            val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
            val hdmiTypes = buildSet {
                add(AudioDeviceInfo.TYPE_HDMI)
                add(AudioDeviceInfo.TYPE_HDMI_ARC)
                if (Build.VERSION.SDK_INT >= 31) add(AudioDeviceInfo.TYPE_HDMI_EARC)
            }
            val hdmiOut = outputs.filter { it.type in hdmiTypes }
            val preferred = hdmiOut.firstOrNull { it.type != AudioDeviceInfo.TYPE_HDMI } ?: hdmiOut.firstOrNull()
            if (preferred != null) {
                hdmi = true
                name = when (preferred.type) {
                    AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
                    AudioDeviceInfo.TYPE_HDMI -> "HDMI"
                    else -> "HDMI eARC"
                }
                channels = hdmiOut.flatMap { it.channelCounts.toList() }.maxOrNull() ?: 0
            } else {
                val bt = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                if (bt != null) name = "Bluetooth"
            }
        }
        val passthrough = runCatching { DeviceCodecs.current.passthroughAudio }.getOrDefault(emptySet())
        val surround = when {
            passthrough.isNotEmpty() -> true // a receiver, soundbar or TV that takes Dolby/DTS
            hdmi && channels >= 6 -> true
            hdmi && channels == 0 -> true // HDMI that doesn't say: don't hold surround back
            else -> false
        }
        return DeviceScan(height, width, channels, name, surround)
    }
}

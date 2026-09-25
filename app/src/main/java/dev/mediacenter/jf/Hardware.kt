package dev.mediacenter.jf

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs

/**
 * What this TV can afford, worked out once at start: how much memory the app may use,
 * how much the TV has, and how much storage is free. It sizes caches and the video
 * buffer so the app fits 1 GB boxes as well as it uses what high-end players have.
 * Nothing here changes how anything looks; it only decides how much is kept around.
 */
object Hardware {
    /** The heap this app may grow to (with the large heap requested in the manifest). */
    var heapBytes: Long = 256L shl 20
        private set

    /** The TV's total memory. */
    var totalRamBytes: Long = 2L shl 30
        private set

    /** A 1 GB-class or Android "low RAM" device. */
    var lowRam: Boolean = false
        private set

    var cores: Int = 4
        private set

    fun init(context: Context) {
        val am = context.getSystemService(ActivityManager::class.java)
        heapBytes = Runtime.getRuntime().maxMemory()
        val info = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        if (info.totalMem > 0) totalRamBytes = info.totalMem
        lowRam = am?.isLowRamDevice == true || totalRamBytes < (1536L shl 20)
        cores = Runtime.getRuntime().availableProcessors()
        AppLog.i(
            "Hardware",
            "RAM ${totalRamBytes shr 20} MB${if (lowRam) " (low)" else ""}, heap ${heapBytes shr 20} MB, " +
                "$cores cores, video buffer ${videoBufferBytes shr 20} MB, image memory ${(imageMemoryFraction * 100).toInt()}%",
        )
    }

    /**
     * How much the player may buffer ahead. Its default (~140 MB) would take most of the
     * heap on a 2 GB box and could run out of memory on a high-bitrate 4K file; this keeps it
     * to about a third of the heap, which still holds many seconds of even a 4K remux.
     */
    val videoBufferBytes: Int
        get() = (heapBytes * 0.3).toLong().coerceIn(24L shl 20, 144L shl 20).toInt()

    /** The share of memory kept for decoded artwork: more on roomy TVs, less on tight ones. */
    val imageMemoryFraction: Double
        get() = when {
            lowRam -> 0.12
            totalRamBytes < (3L shl 30) -> 0.18
            else -> 0.25
        }

    /** The artwork disk cache: up to 256 MB, but never more than a tenth of the free storage. */
    fun artworkDiskBytes(context: Context): Long {
        val free = runCatching { StatFs(context.cacheDir.path).availableBytes }.getOrDefault(1L shl 30)
        return (free / 10).coerceIn(48L shl 20, 256L shl 20)
    }
}

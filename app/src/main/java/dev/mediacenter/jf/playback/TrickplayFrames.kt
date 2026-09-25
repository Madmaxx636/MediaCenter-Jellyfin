package dev.mediacenter.jf.playback

import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.mediacenter.jf.AppLog
import dev.mediacenter.jf.data.Trickplay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Trickplay previews for the video playing. The server keeps them as sprite sheets: JPEG grids
 * of up to 100 frames, several thousand pixels across. Each sheet is fetched once with the
 * viewer's sign-in (the server won't hand them out otherwise), ahead of when it's needed, and
 * kept on disk while the video plays; a preview then decodes just its own frame out of the sheet,
 * which is quick and small, rather than the whole sheet, which is neither on a TV box.
 */
class TrickplayFrames(
    val trickplay: Trickplay,
    private val scope: CoroutineScope,
    private val dir: File,
    private val fetch: suspend (Int) -> ByteArray,
) {
    private val mutex = Mutex()
    private val downloads = mutableMapOf<Int, Deferred<File?>>()

    /** The few sheets decoded from most recently, least recent first. */
    private val decoders = LinkedHashMap<Int, BitmapRegionDecoder>(4, 0.75f, true)

    val info get() = trickplay.info

    /** Starts fetching the sheet for [positionMs] (and the one after it) if it isn't here yet. */
    fun prefetch(positionMs: Long) {
        val sheet = trickplay.cell(trickplay.frameIndex(positionMs)).first
        scope.async { sheetFile(sheet); sheetFile(sheet + 1) }
    }

    /** The preview frame for [positionMs], or null while its sheet is still on its way (or missing). */
    suspend fun frame(positionMs: Long): ImageBitmap? {
        val (sheet, col, row) = trickplay.cell(trickplay.frameIndex(positionMs))
        val decoder = decoder(sheet) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val w = info.width
                val h = info.height
                val area = Rect(col * w, row * h, (col + 1) * w, (row + 1) * h)
                // The last sheet can be shorter than the rest.
                if (!area.intersect(0, 0, decoder.width, decoder.height)) return@runCatching null
                decoder.decodeRegion(area, BitmapFactory.Options())?.asImageBitmap()
            }.getOrNull()
        }
    }

    private suspend fun decoder(sheet: Int): BitmapRegionDecoder? {
        mutex.withLock { decoders[sheet]?.let { return it } }
        val file = sheetFile(sheet) ?: return null
        val decoder = withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(file.path) else BitmapRegionDecoder.newInstance(file.path, false)
            }.getOrNull()
        } ?: return null
        return mutex.withLock {
            decoders[sheet] = decoder
            // Three sheets cover the better part of an hour of scrubbing; let older ones go.
            while (decoders.size > 3) decoders.remove(decoders.keys.first())?.recycle()
            decoder
        }
    }

    private suspend fun sheetFile(sheet: Int): File? {
        if (sheet < 0 || sheet * trickplay.perSheet >= info.thumbnailCount) return null
        val job = mutex.withLock {
            downloads.getOrPut(sheet) {
                scope.async(Dispatchers.IO) {
                    val file = File(dir, "${trickplay.itemId}-${info.width}-$sheet.jpg")
                    if (file.length() > 0) return@async file
                    runCatching {
                        dir.mkdirs()
                        file.writeBytes(fetch(sheet))
                        file
                    }.onFailure { AppLog.w("Player", "Trickplay sheet $sheet couldn't be fetched: ${it.message}") }.getOrNull()
                }
            }
        }
        return job.await()
    }

    /** Frees the decoders (the video has finished or changed); the sheets stay until another video starts. */
    fun release() {
        scope.async {
            mutex.withLock {
                downloads.values.forEach { it.cancel() }
                decoders.values.forEach { it.recycle() }
                decoders.clear()
            }
        }
    }

    companion object {
        /** Clears sheets left from other videos, so they never pile up on the TV's storage. */
        fun clearOthers(dir: File, keepItemId: String) {
            runCatching { dir.listFiles()?.filter { !it.name.startsWith(keepItemId) }?.forEach { it.delete() } }
        }
    }
}

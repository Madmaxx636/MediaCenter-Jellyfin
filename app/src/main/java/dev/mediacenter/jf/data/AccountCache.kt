package dev.mediacenter.jf.data

import dev.mediacenter.jf.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * What the app has loaded for the person signed in (the start menu, library lists), kept so that
 * switching to another user or server and back doesn't mean waiting for it all again. While
 * signed in it's held in memory; switching away writes it to one compressed file for that
 * person; switching back to them reads it back, so their screens show at once and then catch up
 * with the server. Only switching does this: a fresh start of the app loads everything new.
 * Artwork is kept separately (the image cache), whoever is signed in.
 */
class AccountCache(context: android.content.Context) {
    private val dir = File(context.filesDir, "accounts")
    private val entries = LinkedHashMap<String, String>(32, 0.75f, true)
    private var size = 0L

    /** Reading a switched-to account's file, which screens wait for before looking here. */
    @Volatile var restoring: kotlinx.coroutines.Job? = null

    suspend fun await() {
        restoring?.join()
    }

    @Synchronized
    fun <T> get(key: String, serializer: KSerializer<T>): T? =
        entries[key]?.let { runCatching { JellyfinJson.decodeFromString(serializer, it) }.getOrNull() }

    @Synchronized
    fun <T> put(key: String, serializer: KSerializer<T>, value: T) {
        val text = JellyfinJson.encodeToString(serializer, value)
        // A very long list (a whole music library's songs) isn't worth holding; it loads in pages anyway.
        if (text.length > MaxEntryChars) return
        entries.put(key, text)?.let { size -= it.length }
        size += text.length
        // The most recently used lists, within reason for a TV box's memory.
        while (entries.size > MaxEntries || size > MaxTotalChars) {
            val eldest = entries.keys.first()
            size -= entries.remove(eldest)?.length ?: 0
        }
    }

    /** Signing out without switching: nothing is kept. */
    @Synchronized
    fun clear() {
        entries.clear()
        size = 0
    }

    /**
     * Switching away from [account]: what's loaded goes into its compressed file, over what the
     * file already kept (so lists from an earlier visit stay), and memory is cleared.
     */
    suspend fun saveAndClear(account: String) {
        val snapshot = synchronized(this) { entries.toMap().also { entries.clear(); size = 0 } }
        if (snapshot.isEmpty()) return
        withContext(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                val target = file(account)
                // Older lists first, so the ones just loaded count as the most recent when trimming.
                val kept = LinkedHashMap(read(target).orEmpty())
                snapshot.forEach { (key, text) -> kept.remove(key); kept[key] = text }
                var total = kept.values.sumOf { it.length.toLong() }
                while (kept.size > MaxEntries || total > MaxTotalChars) total -= kept.remove(kept.keys.first())?.length ?: 0
                val temporary = File(dir, target.name + ".tmp")
                GZIPOutputStream(temporary.outputStream().buffered()).bufferedWriter().use { it.write(JellyfinJson.encodeToString(Snapshot, kept)) }
                temporary.renameTo(target)
                AppLog.i("Cache", "Kept ${kept.size} lists for later (${target.length() / 1024} KB compressed)")
            }.onFailure { AppLog.w("Cache", "Couldn't keep this account's lists: ${it.message}") }
        }
    }

    /** Switching back to [account]: reads its compressed file, if it's recent, so its screens show at once. */
    suspend fun restore(account: String) {
        val restored = withContext(Dispatchers.IO) { read(file(account)) }
        synchronized(this) {
            entries.clear()
            restored?.let { entries.putAll(it) }
            size = entries.values.sumOf { it.length.toLong() }
        }
        if (restored != null) AppLog.i("Cache", "Opened ${restored.size} kept lists")
    }

    /** A user or server forgotten on this TV: their kept lists go too. */
    fun forget(prefix: String) {
        dir.listFiles()?.filter { it.name.startsWith(safe(prefix)) }?.forEach { it.delete() }
    }

    /** A kept file's lists, or null if there's none, it's too old or it can't be read. */
    private fun read(source: File): Map<String, String>? {
        if (!source.exists() || System.currentTimeMillis() - source.lastModified() > MaxAgeMs) return null
        return runCatching {
            GZIPInputStream(source.inputStream().buffered()).bufferedReader().use { JellyfinJson.decodeFromString(Snapshot, it.readText()) }
        }.onFailure { AppLog.w("Cache", "Couldn't read the kept lists: ${it.message}") }.getOrNull()
    }

    private fun file(account: String) = File(dir, safe(account) + ".json.gz")

    private fun safe(text: String) = text.filter { it.isLetterOrDigit() || it == '_' }

    companion object {
        private const val MaxEntries = 60
        private const val MaxEntryChars = 3_000_000
        private const val MaxTotalChars = 12_000_000L
        private const val MaxAgeMs = 14L * 24 * 3_600_000
        private val Snapshot = MapSerializer(String.serializer(), String.serializer())

        /** A list of items, the shape most screens keep here. */
        val Items = ListSerializer(BaseItem.serializer())

        /** The cache's name for a person on a server. */
        fun key(serverId: String, userId: String) = "${serverId}_$userId"
    }
}

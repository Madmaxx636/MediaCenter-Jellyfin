package dev.mediacenter.jf

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Interface sounds in the spirit of Media Center's soft glassy clicks and airy startup chime.
 * The app's own are original, rendered by tools/make_sounds.py into res/raw (mc_*). A server with
 * the Media Center plugin can supply its own ([serverSound]: intro, focus, select, back, error).
 *
 * A personal build can use other sounds instead: files in the git-ignored src/localres/raw
 * named custom_focus (cursor move), custom_select, custom_back, custom_error and custom_intro
 * (or custom_click for all the clicks) take the place of the app's own. Builds for publishing
 * (-Ppublic) leave that folder out.
 */
class Sounds(
    private val context: Context,
    private val enabled: () -> Boolean,
    private val serverSound: (String) -> java.io.File? = { null },
) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    /**
     * Samples that have finished loading. SoundPool silently skips a sample played before it's
     * loaded, so the intro waits for its chime (see [awaitIntro]) rather than starting without it.
     */
    private val loaded = MutableStateFlow<Set<Int>>(emptySet())

    // Sound IDs stay 0 (silent) until loaded.
    @Volatile private var focusId = 0
    @Volatile private var selectId = 0
    @Volatile private var backId = 0
    @Volatile private var errorId = 0
    @Volatile private var introId = 0

    // By name on purpose: the custom_* sounds only exist in personal builds.
    @android.annotation.SuppressLint("DiscouragedApi")
    private fun raw(name: String) = context.resources.getIdentifier(name, "raw", context.packageName).takeIf { it != 0 }

    private val customClick = raw("custom_click")
    private val customSelect = raw("custom_select") ?: customClick

    /** Each distinct sound file, loaded once even when several sounds share it. */
    private val loadedFiles = mutableMapOf<String, Int>()

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loaded.value = loaded.value + sampleId
        }
        // The chime first: the intro is waiting for it. Loading only starts the decoding, on SoundPool's own thread.
        introId = load(raw("custom_intro"), "intro", R.raw.mc_intro)
        loadClicks()
    }

    /** A personal build's own sound, else the server's, else the app's. */
    private fun load(custom: Int?, serverName: String, own: Int): Int {
        val file = if (custom == null) serverSound(serverName) else null
        return when {
            custom != null -> loadedFiles.getOrPut("res:$custom") { pool.load(context, custom, 1) }
            file != null -> loadedFiles.getOrPut("file:${file.path}:${file.lastModified()}") { pool.load(file.path, 1) }
            else -> loadedFiles.getOrPut("res:$own") { pool.load(context, own, 1) }
        }
    }

    private fun loadClicks() {
        focusId = load(raw("custom_focus") ?: customClick, "focus", R.raw.mc_focus)
        selectId = load(customSelect, "select", R.raw.mc_select)
        backId = load(raw("custom_back") ?: customSelect, "back", R.raw.mc_back)
        errorId = load(raw("custom_error"), "error", R.raw.mc_error)
        // Server sounds that have been replaced: let their samples go.
        val inUse = setOf(introId, focusId, selectId, backId, errorId)
        loadedFiles.filter { (key, id) -> key.startsWith("file:") && id !in inUse }.forEach { (key, id) ->
            pool.unload(id)
            loadedFiles.remove(key)
        }
    }

    /** The server's sounds changed: loads the clicks afresh (the chime is used from the next launch). */
    fun reloadServerSounds() = loadClicks()

    private var lastFocus = 0L
    private var quietUntil = 0L

    fun focus() {
        val now = SystemClock.uptimeMillis()
        if (now < quietUntil || now - lastFocus < 45) return
        lastFocus = now
        play(focusId)
    }

    fun select() {
        quiet()
        play(selectId)
    }

    fun back() {
        quiet()
        play(backId)
    }

    fun error() = play(errorId)

    fun intro() = play(introId)

    /** Waits up to [timeoutMs] for the intro chime to be ready to play; false if it wasn't in time. */
    suspend fun awaitIntro(timeoutMs: Long): Boolean {
        if (!enabled()) return true
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            loaded.first { introId != 0 && introId in it }
            true
        } ?: false
    }

    /** Screen changes move focus programmatically; don't tick for that. */
    fun quiet(ms: Long = 400) {
        quietUntil = SystemClock.uptimeMillis() + ms
    }

    private fun play(id: Int) {
        if (id != 0 && enabled()) pool.play(id, 0.9f, 0.9f, 1, 0, 1f)
    }
}

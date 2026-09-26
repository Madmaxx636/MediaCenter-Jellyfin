package dev.mediacenter.jf

import android.os.Bundle
import kotlinx.coroutines.launch
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.mediacenter.jf.ui.AppHost
import dev.mediacenter.jf.ui.theme.WmcBackground

class MainActivity : ComponentActivity() {
    private val state get() = (application as App).state

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) { state.playback.frameRate.collect { matchFrameRate(it) } }
        }
        openSearch(intent)
        // Android plays its own click and D-pad sounds on views; turn those off so only ours play.
        window.decorView.isSoundEffectsEnabled = false
        setContent {
            val composeView = androidx.compose.ui.platform.LocalView.current
            androidx.compose.runtime.SideEffect { composeView.isSoundEffectsEnabled = false }
            // The interface-size setting scales every dp and sp in the app at once.
            val base = LocalDensity.current
            val scale = state.settings.uiScale.value
            CompositionLocalProvider(
                LocalAppState provides state,
                LocalDensity provides Density(base.density * scale, base.fontScale * state.settings.textSize.value),
            ) {
                // With the app's own screensaver on, the TV's is kept away while the app is in front: the TV's would
                // send the app to the background, which stops the music. The app's plays over everything instead.
                val keepAwake = state.settings.screensaver.value > 0 && !state.idleLong
                androidx.compose.runtime.SideEffect {
                    if (keepAwake) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                WmcBackground { AppHost() }
            }
        }
    }

    private var wakeKey = -1

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        openSearch(intent)
    }

    /** "Search Media Center for…" from the Assistant or the TV's own search: straight to search, words filled in. */
    private fun openSearch(intent: android.content.Intent?) {
        val searching = intent?.action == android.content.Intent.ACTION_SEARCH || intent?.action == "com.google.android.gms.actions.SEARCH_ACTION"
        val query = intent?.takeIf { searching }?.getStringExtra(android.app.SearchManager.QUERY)?.trim()
        if (query.isNullOrEmpty() || state.repository == null) return
        state.screensaver = false
        state.navigator.push(dev.mediacenter.jf.ui.SearchDest().also { it.query = query })
    }

    /** Remote media keys work from any screen while something is playing. */
    // Lint mistakes the call to super for androidx-internal use; it's the ordinary Activity override.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        state.lastInputAt = android.os.SystemClock.elapsedRealtime()
        if (state.idleLong) state.idleLong = false
        // Any button wakes from the screensaver, and does only that (its release is swallowed too).
        if (state.screensaver || event.keyCode == wakeKey) {
            if (event.action == KeyEvent.ACTION_DOWN && state.screensaver) { state.screensaver = false; wakeKey = event.keyCode }
            if (event.action == KeyEvent.ACTION_UP) wakeKey = -1
            return true
        }
        val pm = state.playback
        if (event.action == KeyEvent.ACTION_DOWN) pm.userActivity()
        // The remote's search button opens search from anywhere (once signed in).
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_SEARCH && state.repository != null) {
            if (state.navigator.current !is dev.mediacenter.jf.ui.SearchDest) state.navigator.push(dev.mediacenter.jf.ui.SearchDest())
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && pm.nowPlaying.value != null) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> pm.togglePlayPause()
                KeyEvent.KEYCODE_MEDIA_PLAY -> pm.player.play()
                KeyEvent.KEYCODE_MEDIA_PAUSE -> pm.player.pause()
                KeyEvent.KEYCODE_MEDIA_STOP -> pm.stop()
                KeyEvent.KEYCODE_MEDIA_NEXT -> pm.next()
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> pm.previous()
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> pm.skip()
                KeyEvent.KEYCODE_MEDIA_REWIND -> pm.replay()
                KeyEvent.KEYCODE_CHANNEL_UP -> pm.channel(1)
                KeyEvent.KEYCODE_CHANNEL_DOWN -> pm.channel(-1)
                else -> return super.dispatchKeyEvent(event)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Frame-rate matching: while a video with a known frame rate plays, asks for the TV mode at the same
     * resolution whose refresh rate is that rate (or a whole multiple: 23.976 fps on 23.976 or 47.95 Hz,
     * 25 on 50), and goes back to the TV's own choice afterwards. Playback holds still while the TV
     * switches (the screen usually goes dark for a moment), so nothing is missed.
     */
    private fun matchFrameRate(fps: Float?) {
        @Suppress("DEPRECATION")
        val display = (if (android.os.Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay) ?: return
        val current = display.mode
        // The mode at this resolution whose rate is closest to a whole multiple of the film's (23.976 → 23.976 Hz rather
        // than 24, 25 → 50 Hz), and the lowest multiple of those.
        val wanted = fps?.let { rate ->
            display.supportedModes
                .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
                .mapNotNull { m ->
                    (1..4).map { k -> k to kotlin.math.abs(m.refreshRate - rate * k) / k }
                        .filter { (_, off) -> off < 0.01f }.minByOrNull { it.second }?.let { (k, off) -> Triple(m, k, off) }
                }
                .minWithOrNull(compareBy<Triple<android.view.Display.Mode, Int, Float>> { it.third }.thenBy { it.second })?.first
        }
        val modeId = wanted?.modeId ?: 0
        val attrs = window.attributes
        if (attrs.preferredDisplayModeId == modeId) return
        val switching = wanted != null && wanted.modeId != current.modeId
        AppLog.i("Display", if (wanted == null) "Back to the TV's own refresh rate" else "Frame rate ${"%.3f".format(fps)}: ${"%.3f".format(wanted.refreshRate)} Hz")
        window.attributes = attrs.apply { preferredDisplayModeId = modeId }
        if (switching) holdWhileSwitching()
    }

    private var holdJob: kotlinx.coroutines.Job? = null

    /** Pauses while the TV changes mode (until it reports the change, at most four seconds), then carries on. */
    private fun holdWhileSwitching() {
        val player = state.playback.player
        if (!player.playWhenReady) return
        player.playWhenReady = false
        holdJob?.cancel()
        holdJob = lifecycleScope.launch {
            val displays = getSystemService(android.hardware.display.DisplayManager::class.java)
            val changed = kotlinx.coroutines.CompletableDeferred<Unit>()
            val listener = object : android.hardware.display.DisplayManager.DisplayListener {
                override fun onDisplayChanged(displayId: Int) { changed.complete(Unit) }
                override fun onDisplayAdded(displayId: Int) {}
                override fun onDisplayRemoved(displayId: Int) {}
            }
            displays.registerDisplayListener(listener, null)
            try {
                kotlinx.coroutines.withTimeoutOrNull(4_000) { changed.await() }
                // Many TVs take a moment more to show a picture after the switch.
                kotlinx.coroutines.delay(1_200)
            } finally {
                displays.unregisterDisplayListener(listener)
            }
            if (state.playback.nowPlaying.value != null) player.playWhenReady = true
        }
    }

    override fun onStop() {
        super.onStop()
        // No background service yet, so don't keep playing behind the TV's home screen.
        if (!isChangingConfigurations) state.playback.pauseIfActive()
    }
}

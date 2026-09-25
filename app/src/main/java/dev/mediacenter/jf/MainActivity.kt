package dev.mediacenter.jf

import android.os.Bundle
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
                WmcBackground { AppHost() }
            }
        }
    }

    /** Remote media keys work from any screen while something is playing. */
    // Lint mistakes the call to super for androidx-internal use; it's the ordinary Activity override.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
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

    override fun onStop() {
        super.onStop()
        // No background service yet, so don't keep playing behind the TV's home screen.
        if (!isChangingConfigurations) state.playback.pauseIfActive()
    }
}

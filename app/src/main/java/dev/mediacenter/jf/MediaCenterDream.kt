package dev.mediacenter.jf

import android.service.dreams.DreamService
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.mediacenter.jf.ui.screens.Screensaver

/**
 * Media Center as the TV's own screensaver (Android's "daydream"): the same artwork and clock as
 * the app's, chosen in the TV's settings where it offers a choice (Settings › Device preferences ›
 * Screen saver on Android TV). A dream isn't an activity, so it carries the lifecycle Compose needs.
 */
class MediaCenterDream : DreamService(), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        val state = (application as App).state
        setContentView(
            ComposeView(this).apply {
                setViewTreeLifecycleOwner(this@MediaCenterDream)
                setViewTreeSavedStateRegistryOwner(this@MediaCenterDream)
                setContent {
                    // The app's interface size, as in the app.
                    val base = LocalDensity.current
                    CompositionLocalProvider(
                        LocalAppState provides state,
                        LocalDensity provides Density(base.density * state.settings.uiScale.value, base.fontScale * state.settings.textSize.value),
                    ) { Screensaver(state) }
                }
            }
        )
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onDreamingStopped() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDetachedFromWindow()
    }
}

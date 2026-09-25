package dev.mediacenter.jf.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * While a video is playing (full screen or behind the menus), menu animations
 * switch to short, simple tweens so the GPU stays free for decoding and frames
 * aren't dropped. With no video, the full springs and fades are used.
 */
object Motion {
    var videoActive by mutableStateOf(false)

    fun <T> spec(full: AnimationSpec<T>): AnimationSpec<T> = if (videoActive) tween(110) else full
    fun <T> finite(full: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> = if (videoActive) tween(110) else full
}

package dev.mediacenter.jf.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.mediacenter.jf.LocalAppState
import dev.mediacenter.jf.ui.theme.Wmc

/**
 * Media Center's focus treatment: a soft cyan halo, a thin bright rim and, for
 * buttons, a blue glass fill with a gloss highlight on the top half.
 * [amount] runs 0..1 so the glow can fade in and out.
 */
fun Modifier.focusFrame(amount: Float, fill: Boolean, corner: Dp = 5.dp, artwork: Boolean = false): Modifier =
    focusFrame({ amount }, fill, corner, artwork)

/**
 * Same as above, but reads [amount] while drawing, so an animating glow only
 * redraws; it doesn't recompose or re-lay-out the tile on every frame.
 */
fun Modifier.focusFrame(amount: () -> Float, fill: Boolean, corner: Dp = 5.dp, artwork: Boolean = false): Modifier = drawWithContent {
    val amount = amount()
    val r = corner.toPx()
    if (amount > 0f) {
        val layers = 7
        val halo = if (artwork) Color(0xFF000814) else Wmc.Glow
        val strength = if (artwork) 0.11f else 0.075f
        for (i in layers downTo 1) {
            val grow = i * 2.4.dp.toPx()
            drawRoundRect(
                color = halo.copy(alpha = strength * amount * (layers - i + 1) / layers),
                topLeft = Offset(-grow, -grow),
                size = Size(size.width + grow * 2, size.height + grow * 2),
                cornerRadius = CornerRadius(r + grow),
            )
        }
        if (fill) {
            drawRoundRect(
                Brush.verticalGradient(0f to Color(0xFF6CBBF7), 0.48f to Color(0xFF2F7FD6), 0.52f to Color(0xFF1F68C4), 1f to Color(0xFF3A8FE0)),
                cornerRadius = CornerRadius(r),
                alpha = 0.94f * amount,
            )
        }
    }
    drawContent()
    if (amount > 0f && artwork) {
        // The rim hugs the cover from outside, so it never covers any of the picture.
        val w = 2.5.dp.toPx()
        drawRoundRect(
            Color(0xF2EAF4FF).copy(alpha = amount), topLeft = Offset(-w / 2, -w / 2),
            size = Size(size.width + w, size.height + w),
            cornerRadius = CornerRadius(r + w / 2), style = Stroke(w),
        )
    } else if (amount > 0f) {
        // Round buttons get just the rim; the Aero gloss band only suits rectangles.
        if (r * 2 < size.minDimension) {
            val gloss = androidx.compose.ui.graphics.Path().apply {
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        0f, 0f, size.width, size.height * 0.5f,
                        topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r),
                    )
                )
            }
            drawPath(gloss, Brush.verticalGradient(listOf(Color(0x55FFFFFF), Color(0x14FFFFFF)), endY = size.height * 0.5f), alpha = amount)
        }
        drawRoundRect(
            Color.White.copy(alpha = 0.85f * amount),
            cornerRadius = CornerRadius(r),
            style = Stroke(1.5.dp.toPx()),
        )
    }
}

/** Where the most recently focused cover sits on screen, so a cover losing focus knows which way focus went. */
private object FocusedCover {
    var center = Offset.Unspecified
}

/** A cover's own on-screen centre; a plain holder, since it's only read while drawing. */
private class CenterHolder {
    var center = Offset.Unspecified
}

/** How far a focused cover rises off the grid. */
private val HoverLift = 7.dp

/**
 * While a cover settles back after losing focus, fades out just the part that sticks out
 * past its resting bounds ([grow] is its current scale, [lift] how far it's raised, 0..1 of [HoverLift]),
 * easing back to solid just inside them. Wherever it overlaps a neighbour it blends
 * instead of showing a hard edge; the rest of the cover stays solid. Only the sides facing
 * [towards] (the offset to the newly focused cover) fade, each only as far as it actually
 * overhangs: a lifted cover overhangs more at the top than the bottom.
 * [strength] (0..1) turns the fade on. Needs an offscreen layer underneath.
 */
private fun Modifier.featherOverhang(grow: () -> Float, lift: () -> Float, strength: () -> Float, towards: () -> Offset): Modifier = drawWithContent {
    drawContent()
    val g = grow()
    val k = strength()
    if (k <= 0f || g <= 1.001f) return@drawWithContent
    val w = size.width
    val h = size.height
    // Overhang on each side, in this (unscaled) layer's own coordinates.
    val ox = w * (g - 1f) / (2f * g)
    val oy = h * (g - 1f) / (2f * g)
    val l = HoverLift.toPx() * lift() / g
    val clear = Color.Black.copy(alpha = 1f - k)
    val solid = Color.Black
    // Fully faded at the outer edge, solid again a little inside the resting edge.
    fun band(over: Float) = over * 1.25f
    fun fadeX(x0: Float, x1: Float, outerAtStart: Boolean) {
        if (x1 - x0 < 0.5f) return
        drawRect(
            Brush.horizontalGradient(if (outerAtStart) listOf(clear, solid) else listOf(solid, clear), x0, x1),
            Offset(x0, 0f), Size(x1 - x0, h), blendMode = BlendMode.DstIn,
        )
    }
    // Above and below, a neighbour's long edge crosses the whole cover, so a gentle ramp
    // still meets it nearly solid. Stay fully clear across the overlapped strip, then ramp
    // to solid over a short band that shrinks away as the cover settles (no pop at rest).
    val ramp = 18.dp.toPx() * lift().coerceIn(0f, 1f)
    val gap = 4.dp.toPx()
    fun fadeY(over: Float, top: Boolean) {
        val hidden = (over - gap).coerceAtLeast(0f)
        val span = hidden + ramp
        if (span < 0.5f) return
        val a = hidden / span
        val y0 = if (top) 0f else h - span
        drawRect(
            if (top) Brush.verticalGradient(0f to clear, a to clear, 1f to solid, startY = 0f, endY = span)
            else Brush.verticalGradient(0f to solid, (1f - a) to clear, 1f to clear, startY = y0, endY = h),
            Offset(0f, y0), Size(w, span), blendMode = BlendMode.DstIn,
        )
    }
    // Only the newly focused cover is drawn above this one, so only the sides facing it
    // can get cut; the other neighbours sit underneath and the other sides stay solid.
    val d = towards()
    val near = 8.dp.toPx()
    if (d.x < -near) fadeX(0f, band(ox), true)
    if (d.x > near) fadeX(w - band(ox), w, false)
    if (d.y < -near) fadeY(oy + l, top = true)
    if (d.y > near) fadeY((oy - l).coerceAtLeast(0f), top = false)
}

/**
 * A focusable, clickable box with the Media Center focus look. Plays the focus
 * and select sounds and grows slightly when focused.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
    scale: Float = 1.06f,
    corner: Dp = 5.dp,
    artwork: Boolean = false,
    onFocus: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    contentAlignment: Alignment = Alignment.TopStart,
    /** False when a parent draws the focus glow and scale itself (e.g. an expanding settings row). */
    decorate: Boolean = true,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val sounds = LocalAppState.current.sounds
    var focused by remember { mutableStateOf(false) }
    // The glow fades out quicker than it fades in, so a departing cover's frame doesn't linger over its neighbours.
    val amount by animateFloatAsState(if (focused) 1f else 0f, dev.mediacenter.jf.ui.theme.Motion.spec(tween(if (focused) 160 else 90)), label = "glow")
    val grow by animateFloatAsState(if (focused) scale else 1f, dev.mediacenter.jf.ui.theme.Motion.spec(spring(dampingRatio = 0.7f, stiffness = 600f)), label = "scale")
    // A cover that just lost focus fades its overhang from the first frame, before the
    // newly focused cover (drawn above it) can show a hard edge across it.
    val leaving = { if (focused) 0f else 1f }
    val here = remember { CenterHolder() }
    val towards = {
        val c = FocusedCover.center
        if (c.isSpecified && here.center.isSpecified) c - here.center else Offset.Zero
    }
    Box(
        modifier
            // Stay above the neighbours until fully shrunk back, not just while focused;
            // while settling, the cover turns partly clear (below), then rests behind them.
            .zIndex(if (focused) 2f else if (grow > 1.001f || amount > 0.01f) 1f else 0f)
            .then(
                if (decorate && artwork) Modifier
                    // Covers zoom and lift off the grid together, on the same spring.
                    .graphicsLayer {
                        scaleX = grow; scaleY = grow
                        translationY = -HoverLift.toPx() * (if (scale > 1f) (grow - 1f) / (scale - 1f) else amount)
                        // Offscreen only while a settling cover fades its overhang.
                        compositingStrategy = if (!focused && grow > 1.001f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                    }
                    .focusFrame({ amount }, fill, corner, artwork)
                    .featherOverhang({ grow }, { if (scale > 1f) (grow - 1f) / (scale - 1f) else amount }, leaving, towards)
                    .onPlaced { here.center = it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f) }
                else if (decorate) Modifier.graphicsLayer { scaleX = grow; scaleY = grow }.focusFrame({ amount }, fill, corner, artwork)
                else Modifier
            )
            .onFocusChanged {
                if (it.isFocused != focused) {
                    focused = it.isFocused
                    if (it.isFocused && artwork) FocusedCover.center = here.center
                    if (it.isFocused) {
                        sounds.focus()
                        onFocus()
                    }
                }
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = onLongClick,
                onClick = {
                    sounds.select()
                    onClick()
                },
            ),
        contentAlignment = contentAlignment,
    ) {
        content(focused)
    }
}

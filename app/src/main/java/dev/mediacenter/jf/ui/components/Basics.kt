package dev.mediacenter.jf.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import dev.mediacenter.jf.ui.theme.Wmc
import dev.mediacenter.jf.ui.theme.WmcType
import kotlinx.coroutines.delay
import java.util.Date
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun WText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Unspecified,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.merge(TextStyle(color = color, textAlign = align)),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Current time, refreshed every few seconds, in the device's 12/24-hour format. */
@Composable
fun rememberClock(): String {
    val context = LocalContext.current
    val format = android.text.format.DateFormat.getTimeFormat(context)
    val time by produceState(format.format(Date())) {
        while (true) {
            value = format.format(Date())
            delay(5_000)
        }
    }
    return time
}

fun formatTime(date: Date, context: android.content.Context): String =
    android.text.format.DateFormat.getTimeFormat(context).format(date)

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = total / 60 % 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Media Center's busy indicator: a ring of dots chasing each other. */
@Composable
fun BusyIndicator(modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val t = rememberInfiniteTransition(label = "busy")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "p")
    Canvas(modifier.size(size)) {
        val n = 10
        val r = this.size.minDimension / 2 * 0.78f
        for (i in 0 until n) {
            val a = 2 * PI * i / n - PI / 2
            val lag = ((phase * n - i) % n + n) % n / n
            val alpha = (1f - lag).coerceIn(0.12f, 1f)
            drawCircle(
                color = Wmc.Accent.copy(alpha = alpha),
                radius = this.size.minDimension * 0.06f * (0.6f + 0.4f * alpha),
                center = center + Offset((r * cos(a)).toFloat(), (r * sin(a)).toFloat()),
            )
        }
    }
}

@Composable
fun CenteredBusy() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { BusyIndicator() }

@Composable
fun CenteredMessage(title: String, body: String? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(48.dp)) {
            WText(title, WmcType.Heading, align = TextAlign.Center, maxLines = 2)
            if (body != null) WText(body, WmcType.Body, Modifier.padding(top = 10.dp), maxLines = 6, align = TextAlign.Center)
        }
    }
}

/** A glassy progress bar with a glowing fill, as used on the player and on resume points. */
@Composable
fun ProgressBar(progress: Float, modifier: Modifier = Modifier, buffered: Float = 0f, thickness: Dp = 6.dp, marks: List<Float> = emptyList()) {
    Canvas(modifier.fillMaxWidth().height(thickness + 6.dp)) {
        val h = thickness.toPx()
        val top = (this.size.height - h) / 2
        val radius = CornerRadius(h / 2)
        drawRoundRect(Color(0x40FFFFFF), Offset(0f, top), Size(this.size.width, h), radius)
        if (buffered > 0f) {
            drawRoundRect(Color(0x30FFFFFF), Offset(0f, top), Size(this.size.width * buffered.coerceIn(0f, 1f), h), radius)
        }
        val w = this.size.width * progress.coerceIn(0f, 1f)
        if (w > 0f) {
            drawRoundRect(Wmc.Glow.copy(alpha = 0.35f), Offset(0f, top - 3f), Size(w, h + 6f), CornerRadius(h))
            drawRoundRect(
                Brush.verticalGradient(listOf(Color(0xFFB5E2FF), Wmc.FocusTop, Wmc.FocusBottom), startY = top, endY = top + h),
                Offset(0f, top), Size(w, h), radius,
            )
        }
        // Chapter starts: a small notch across the bar at each, as on Media Center's DVD progress bar.
        marks.forEach { m ->
            if (m <= 0f || m >= 1f) return@forEach
            val x = this.size.width * m
            drawRect(Color(0xCC03101F), Offset(x - 1.5f, top - 2f), Size(3f, h + 4f))
            drawRect(Color(0xE6FFFFFF), Offset(x - 0.5f, top - 2f), Size(1f, h + 4f))
        }
    }
}

/** requestFocus() throws if the target isn't laid out yet; screens call this after loading. */
fun FocusRequester.tryFocus(): Boolean = runCatching { requestFocus() }.isSuccess

/**
 * Gives the target focus as soon as it's laid out, retrying for a few frames.
 * Screens that appear mid-animation (after the intro, or during a transition)
 * aren't always attached on the first frame.
 */
suspend fun FocusRequester.focusWhenReady(frames: Int = 30) {
    repeat(frames) {
        androidx.compose.runtime.withFrameNanos { }
        if (runCatching { requestFocus() }.getOrDefault(false) != false) return
    }
}

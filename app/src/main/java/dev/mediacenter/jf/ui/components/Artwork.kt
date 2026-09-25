package dev.mediacenter.jf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.mediacenter.jf.ui.theme.Wmc
import dev.mediacenter.jf.ui.theme.WmcType

/**
 * An image from the server, or a tinted glass placeholder with the title when
 * there's no artwork (or while it loads).
 */
@Composable
fun Artwork(
    url: String?,
    title: String?,
    modifier: Modifier = Modifier,
    glyph: Glyph? = null,
    corner: Dp = 3.dp,
    contentScale: ContentScale = ContentScale.Crop,
    showTitle: Boolean = true,
) {
    val tint = remember(title) {
        val h = (title ?: "").hashCode()
        val hues = listOf(Color(0xFF2D6FB8), Color(0xFF3A4FA8), Color(0xFF1F7C9C), Color(0xFF5A3F9E), Color(0xFF2C8A6E), Color(0xFF8A4A7A))
        hues[(h and 0x7fffffff) % hues.size]
    }
    // The tinted placeholder only exists until the image arrives, so a wall of
    // posters doesn't keep laying out hidden titles and icons.
    var loaded by remember(url) { mutableStateOf(false) }
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .then(
                if (loaded) Modifier
                else Modifier.background(Brush.verticalGradient(listOf(lerp(tint, Color.White, 0.12f), lerp(tint, Color.Black, 0.45f))))
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                if (glyph != null) GlyphIcon(glyph, size = 30.dp, color = Wmc.TextDim)
                if (showTitle && title != null) {
                    WText(title, WmcType.Caption, color = Wmc.TextDim, maxLines = 3, align = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = title,
                contentScale = contentScale,
                onSuccess = { loaded = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Draws [content] with a faded mirror image beneath it, like album art and
 * posters standing on Media Center's glass floor.
 */
@Composable
fun Reflected(
    height: Dp,
    modifier: Modifier = Modifier,
    depth: Float = 0.3f,
    strength: Float = 0.3f,
    content: @Composable () -> Unit,
) {
    Column(modifier) {
        Box(Modifier.height(height)) { content() }
        Box(
            Modifier
                .padding(top = 3.dp)
                .height(height * depth)
                .clipToBounds()
                .graphicsLayer { scaleY = -1f; compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = strength))),
                        blendMode = BlendMode.DstIn,
                    )
                }
        ) {
            Box(Modifier.wrapContentHeight(Alignment.Bottom, unbounded = true).height(height)) { content() }
        }
    }
}

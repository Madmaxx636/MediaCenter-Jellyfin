package dev.mediacenter.jf.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * Rows of covers at the artwork size setting (settings › general), in a space of height [space]
 * where [rows] rows of [fit]-high covers fill it at 100%. Smaller covers sit centred in the space.
 * Larger ones don't all fit, so the rows slide up and down to keep the focused cover's row in full
 * view (see [placeCoverRows]), and the rows partly out of view fade at the space's edges (see
 * [coverRowsSpace]). A single row never grows past the space.
 */
class CoverRows internal constructor(
    val rows: Int,
    /** One cover's height. */
    val tile: Dp,
    val gap: Dp,
    /** Above and below the rows, inside the grid: its top and bottom content padding. */
    val padding: Dp,
    /** The grid's height: the rows, the gaps between them and [padding] above and below. */
    val height: Dp,
    /** The rows are taller than the space and slide to follow the focus. */
    val pans: Boolean,
    internal val space: Dp,
)

/**
 * Works out the rows: [edge] is the room the layout keeps between the space's edges and the rows at
 * 100%, and [padding] the grid's own padding for a cover of a given height (at least [edge]).
 */
fun coverRows(rows: Int, fit: Dp, gap: Dp, edge: Dp, space: Dp, scale: Float, padding: (tile: Dp) -> Dp = { edge }): CoverRows {
    val tile = fit * if (rows > 1) scale else scale.coerceAtMost(1f)
    val rowsHeight = tile * rows + gap * (rows - 1)
    val pans = rowsHeight > space - edge * 2 + 0.5.dp
    // Sliding, the focused row stops clear of the edges: room for its zoom and lift, then the fade.
    val pad = if (pans) maxOf(padding(tile), tile * ZoomRoom + Lift + FadeBand) else padding(tile)
    return CoverRows(rows, tile, gap, pad, rowsHeight + pad * 2, pans, space)
}

/**
 * The grid's place in the space: centred, or, when the rows slide, moved to keep the row of the
 * cover at [focusedIndex] in view (a horizontal grid fills each column top to bottom, so that's
 * the index modulo the rows). The move springs, and is read only while laying out.
 */
@Composable
fun Modifier.placeCoverRows(layout: CoverRows, focusedIndex: () -> Int): Modifier {
    val index by rememberUpdatedState(focusedIndex)
    val row by remember(layout.rows) { derivedStateOf { index().coerceAtLeast(0) % layout.rows } }
    val spare = layout.space - layout.height
    val target = if (!layout.pans) spare / 2
    else (layout.space / 2 - (layout.padding + (layout.tile + layout.gap) * row + layout.tile / 2)).coerceIn(spare, 0.dp)
    val y = animateDpAsState(target, spring(stiffness = Spring.StiffnessMediumLow), label = "cover rows")
    return fillMaxWidth()
        .wrapContentHeight(Alignment.Top, unbounded = true)
        .height(layout.height)
        .offset { IntOffset(0, y.value.roundToPx()) }
}

/** The space the rows sit in: while they slide, it clips them and fades them out at its top and bottom. */
fun Modifier.coverRowsSpace(layout: CoverRows): Modifier =
    if (!layout.pans) this
    else graphicsLayer { clip = true; compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val band = (FadeBand.toPx() / size.height).coerceAtMost(0.5f)
            drawRect(
                Brush.verticalGradient(0f to Color.Transparent, band to Color.Black, 1f - band to Color.Black, 1f to Color.Transparent),
                blendMode = BlendMode.DstIn,
            )
        }

// Half the focused cover's 16% zoom, its lift, and the band where rows out of view fade.
private const val ZoomRoom = 0.08f
private val Lift = 7.dp
private val FadeBand = 20.dp

package dev.mediacenter.jf.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import dev.mediacenter.jf.ui.components.focusWhenReady
import dev.mediacenter.jf.ui.components.placeCoverRows
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mediacenter.jf.LocalAppState
import dev.mediacenter.jf.data.AccountCache
import dev.mediacenter.jf.data.BaseItem
import dev.mediacenter.jf.data.MediaRepository
import dev.mediacenter.jf.data.channelLogoUrl
import dev.mediacenter.jf.data.posterUrl
import dev.mediacenter.jf.data.thumbUrl
import dev.mediacenter.jf.ui.LibraryDest
import dev.mediacenter.jf.ui.components.Artwork
import dev.mediacenter.jf.ui.components.CenteredBusy
import dev.mediacenter.jf.ui.components.CenteredMessage
import dev.mediacenter.jf.ui.components.FocusBox
import dev.mediacenter.jf.ui.components.Glyph
import dev.mediacenter.jf.ui.components.GlyphIcon
import dev.mediacenter.jf.ui.components.PivotBar
import dev.mediacenter.jf.ui.components.ProgressBar
import dev.mediacenter.jf.ui.components.ScreenPadH
import dev.mediacenter.jf.ui.components.TopChrome
import dev.mediacenter.jf.ui.components.WText
import dev.mediacenter.jf.ui.components.coverRows
import dev.mediacenter.jf.ui.components.coverRowsSpace
import dev.mediacenter.jf.ui.components.tryFocus
import dev.mediacenter.jf.ui.theme.Wmc
import dev.mediacenter.jf.ui.theme.WmcType
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.width
import kotlinx.coroutines.launch

enum class TileShape(val aspect: Float, val rows: Int) {
    Poster(2f / 3f, 2), Square(1f, 2), Wide(16f / 9f, 3), Text(16f / 9f, 4);

    companion object {
        fun of(items: List<BaseItem>): TileShape {
            val type = items.firstOrNull()?.type
            return when (type) {
                "Movie", "Series", "BoxSet", "Trailer", "Person" -> Poster
                "MusicAlbum", "MusicArtist", "Audio", "Playlist" -> Square
                "Genre", "MusicGenre" -> Text
                else -> Wide
            }
        }
    }
}

/**
 * A Media Center library gallery: a page title, pivots, a horizontally scrolling
 * wall of artwork, and details of the focused item along the bottom.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LibraryScreen(dest: LibraryDest) {
    val app = LocalAppState.current
    val repo = app.repository ?: return
    val pivot = dest.pivot
    val items = dest.cache[pivot]
    var error by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf<BaseItem?>(null) }
    // Focus the gallery when the screen opens (or is returned to), but not when
    // the user is scrolling through pivots.
    val entered = remember { mutableStateOf(false) }

    LaunchedEffect(pivot) {
        error = null
        if (dest.cache[pivot] == null) delay(150) // let quick pivot scrolling settle before loading
        // This list as it was last seen (kept across switching users and servers), while it's fetched afresh.
        val cacheKey = "library:${dest.view?.id}:${dest.title}:${dest.pivots[pivot].label}"
        if (dest.cache[pivot] == null) {
            app.accountCache.await()
            app.accountCache.get(cacheKey, AccountCache.Items)?.let { dest.cache[pivot] = it }
        }
        try {
            dest.cache[pivot] = dest.pivots[pivot].load(repo).also { app.accountCache.put(cacheKey, AccountCache.Items, it) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (dest.cache[pivot] == null) error = e.message ?: "Couldn't load this library."
        }
    }

    // Letter-jump, as in Media Center's music library: type letters (on a keyboard,
    // or the remote's number keys) and focus jumps to the first matching title.
    var typed by remember { mutableStateOf("") }
    LaunchedEffect(typed) {
        if (typed.isEmpty()) return@LaunchedEffect
        val list = items ?: return@LaunchedEffect
        val t = typed.lowercase()
        val hit = list.indexOfFirst { (it.name ?: "").lowercase().removePrefix("the ").startsWith(t) }
            .takeIf { it >= 0 } ?: list.indexOfFirst { (it.name ?: "").lowercase().startsWith(t) }
        if (hit >= 0) dest.jump = hit
        delay(1500)
        typed = ""
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val c = e.nativeKeyEvent.unicodeChar.toChar()
            if (c.isLetterOrDigit()) { typed += c; true } else false
        },
    ) {
    val left = maxWidth * GalleryLeft
    if (typed.isNotEmpty()) {
        WText(
            typed.lowercase(), WmcType.PageTitle.copy(fontSize = WmcType.PageTitle.fontSize * 1.6f, color = Color(0x70FFFFFF)),
            Modifier.align(Alignment.BottomEnd).padding(end = ScreenPadH, bottom = 120.dp),
        )
    }
    // Media Center's big, faded page title in the top-right corner.
    WText(
        dest.title.lowercase(), WmcType.PageTitle,
        Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = ScreenPadH),
    )
    Column(Modifier.fillMaxSize()) {
        TopChrome()
        Box(Modifier.padding(start = left - 12.dp, top = 48.dp).height(40.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (dest.pivots.size > 1) {
                    PivotBar(dest.pivots.map { it.label }, pivot, onSelect = { dest.pivot = it; dest.focusIndex = 0 }, Modifier.weight(1f, fill = false))
                }
                dest.slideshow?.let { source ->
                    // Media Center's "play slide show" for a picture folder.
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    dev.mediacenter.jf.ui.components.ActionButton(
                        "play slide show",
                        {
                            scope.launch {
                                val photos = runCatching { source(repo, app.settings.slideSubfolders.value) }.getOrDefault(emptyList())
                                if (photos.isNotEmpty()) {
                                    val ordered = if (app.settings.slideRandom.value) photos.shuffled() else photos
                                    app.navigator.push(dev.mediacenter.jf.ui.PhotoDest(ordered, 0, slideshow = true))
                                }
                            }
                        },
                        Modifier.padding(start = 24.dp).width(230.dp), Glyph.Play, height = 38.dp,
                    )
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                error != null -> CenteredMessage("Something went wrong", error)
                items == null -> CenteredBusy()
                items.isEmpty() -> CenteredMessage("There's nothing here yet")
                dest.pivots[pivot].layout == dev.mediacenter.jf.ui.PivotLayout.Songs ->
                    SongList(dest, items, entered, left, onFocused = { focused = it }, onHold = { app.showItemMenu(it, items, dest.view) }) { item ->
                        app.open(item, items, dest.view)
                    }
                else -> Gallery(
                    dest, repo, items, entered, onFocused = { focused = it },
                    onHold = { app.showItemMenu(it, items, dest.view, dest.pivots[pivot].genreItemType) },
                ) { item ->
                    app.open(item, items, dest.view, dest.pivots[pivot].genreItemType)
                }
            }
        }
        Footer(focused.takeIf { items != null && it in items }, items?.indexOf(focused) ?: -1, items?.size ?: 0, left)
    }
    }
}

/** Where gallery content starts, as a fraction of screen width (Media Center's left margin). */
private const val GalleryLeft = 0.17f

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Gallery(
    dest: LibraryDest,
    repo: MediaRepository,
    items: List<BaseItem>,
    entered: androidx.compose.runtime.MutableState<Boolean>,
    onFocused: (BaseItem) -> Unit,
    onHold: (BaseItem) -> Unit,
    onOpen: (BaseItem) -> Unit,
) {
    val sounds = LocalAppState.current.sounds
    val shape = if (dest.pivots[dest.pivot].layout == dev.mediacenter.jf.ui.PivotLayout.Text) TileShape.Text else TileShape.of(items)
    val state = rememberLazyGridState()
    val jumpRequester = remember { FocusRequester() }
    val jumpTarget = dest.jump
    LaunchedEffect(jumpTarget) {
        if (jumpTarget < 0) return@LaunchedEffect
        state.scrollToItem(jumpTarget)
        jumpRequester.focusWhenReady()
        dest.jump = -1
    }
    val restore = remember { FocusRequester() }
    val restoreIndex = remember(items) { dest.focusIndex.coerceIn(0, items.lastIndex) }

    LaunchedEffect(items) {
        if (restoreIndex > 0) state.scrollToItem(restoreIndex)
        withFrameNanos { }
        if (!entered.value) {
            entered.value = true
            sounds.quiet()
            restore.tryFocus()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gap = 3.dp
        val vPad = 18.dp
        val left = maxWidth * GalleryLeft
        val rows = shape.rows
        val layout = coverRows(
            rows, fit = (maxHeight - vPad * 2 - gap * (rows - 1)) / rows, gap, edge = vPad, space = maxHeight,
            scale = LocalAppState.current.settings.artworkSize.value,
        )
        val tileH: Dp = layout.tile
        val tileW: Dp = tileH * shape.aspect
        // Ask the server for images at the size they're drawn (rounded up so similar sizes share the cache).
        val imageHeight = with(androidx.compose.ui.platform.LocalDensity.current) { ((tileH.toPx() * 1.15f / 60).toInt() + 1) * 60 }

        Box(Modifier.fillMaxSize().coverRowsSpace(layout)) {
            LazyHorizontalGrid(
                rows = GridCells.Fixed(rows),
                state = state,
                contentPadding = PaddingValues(start = left, end = ScreenPadH, top = layout.padding, bottom = layout.padding),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(gap),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(gap),
                modifier = Modifier.placeCoverRows(layout) { dest.focusIndex }.focusRestorer(restore),
            ) {
                itemsIndexed(items, key = { i, it -> "${it.id}#$i" }, contentType = { _, _ -> shape }) { i, item ->
                    FocusBox(
                        onClick = { onOpen(item) },
                        onLongClick = { onHold(item) },
                        onFocus = { dest.focusIndex = i; onFocused(item) },
                        scale = 1.16f,
                        corner = 1.dp,
                        artwork = true,
                        modifier = Modifier
                            .size(tileW, tileH)
                            .then(if (i == restoreIndex) Modifier.focusRequester(restore) else Modifier)
                            .then(if (i == jumpTarget) Modifier.focusRequester(jumpRequester) else Modifier),
                    ) { focusedTile ->
                        Tile(repo, item, shape, focusedTile, imageHeight)
                    }
                }
            }
        }
    }
}

@Composable
internal fun Tile(repo: MediaRepository, item: BaseItem, shape: TileShape, focused: Boolean, imageHeight: Int) {
    when (shape) {
        TileShape.Text -> Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0x552F7DD0), Color(0x33103060))), RoundedCornerShape(1.dp),
            ),
            contentAlignment = Alignment.Center,
        ) {
            WText(item.name?.lowercase() ?: "", WmcType.Label, Modifier.padding(8.dp), maxLines = 2, align = TextAlign.Center)
        }
        TileShape.Wide -> Box(Modifier.fillMaxSize()) {
            val glyph = when {
                item.isChannel -> Glyph.LiveTv
                item.isFolder -> Glyph.Folder
                item.type == "Photo" -> Glyph.Pictures
                else -> Glyph.Video
            }
            val image = if (item.isChannel) item.currentProgram?.let { p -> p.imageTags["Primary"]?.let { repo.imageUrl(p.id, dev.mediacenter.jf.data.ImageKind.Primary, it, imageHeight) } }
                ?: repo.channelLogoUrl(item, imageHeight) else repo.thumbUrl(item, imageHeight)
            Artwork(image, item.name, Modifier.fillMaxSize(), glyph = glyph, showTitle = item.isChannel, corner = 1.dp)
            if (item.type != "Photo") {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xCC000A20)),
                    )
                )
                val title = if (item.type == "Episode") "${item.seriesName ?: ""} · ${item.name ?: ""}" else item.name ?: ""
                WText(title, WmcType.Caption, Modifier.align(Alignment.BottomStart).padding(8.dp), color = Wmc.Text, maxLines = 2)
            }
            Badges(item)
        }
        else -> Box(Modifier.fillMaxSize()) {
            val glyph = when {
                shape == TileShape.Square -> Glyph.Music
                item.type == "Series" -> Glyph.Tv
                item.type == "Person" -> Glyph.User
                else -> Glyph.Movies
            }
            // An episode among covers (next up, continue watching) shows its show's poster.
            val cover = (if (item.type == "Episode") item.seriesPrimaryImageTag?.let { tag -> item.seriesId?.let { repo.imageUrl(it, dev.mediacenter.jf.data.ImageKind.Primary, tag, imageHeight) } } else null)
                ?: repo.posterUrl(item, imageHeight)
            Artwork(cover, if (item.type == "Episode") item.seriesName ?: item.name else item.name, Modifier.fillMaxSize(), glyph = glyph, corner = 1.dp)
            Badges(item)
        }
    }
}

/** Watched tick, unwatched-episode count and resume bar, drawn over artwork. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.Badges(item: BaseItem) {
    val data = dev.mediacenter.jf.LocalAppState.current.userData(item) ?: return
    val unplayed = data.unplayedItemCount
    when {
        data.played -> Box(
            Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).background(Wmc.FocusBottom, CircleShape),
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(Glyph.Check, size = 14.dp) }
        unplayed != null && unplayed > 0 && item.type == "Series" -> Box(
            Modifier.align(Alignment.TopEnd).padding(6.dp).background(Wmc.FocusBottom, RoundedCornerShape(11.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
        ) { WText(unplayed.toString(), WmcType.Caption, color = Wmc.Text) }
    }
    val pct = data.playedPercentage
    if (!data.played && pct != null && pct > 0) {
        ProgressBar((pct / 100).toFloat(), Modifier.align(Alignment.BottomCenter).padding(horizontal = 6.dp, vertical = 2.dp), thickness = 3.dp)
    }
}

@Composable
private fun Footer(item: BaseItem?, index: Int, count: Int, left: Dp) {
    Row(
        Modifier.fillMaxWidth().height(116.dp).padding(start = maxOf(left, LocalInsetPadding.current + ScreenPadH), end = ScreenPadH + 40.dp, bottom = 20.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            if (item != null) {
                WText(item.name ?: "", WmcType.ItemTitle)
                val line1 = listOfNotNull(
                    item.albumArtist?.takeIf { item.type == "MusicAlbum" || item.type == "Audio" },
                    item.album?.takeIf { item.type == "Audio" },
                    item.runTimeTicks?.takeIf { item.type == "Audio" }?.let { dev.mediacenter.jf.ui.components.formatDuration(it / 10_000) },
                    item.seriesName?.takeIf { item.type == "Episode" },
                )
                if (line1.isNotEmpty()) WText(line1.joinToString("  "), WmcType.Label, color = Wmc.TextDim)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val year = item.productionYear?.toString()
                    if (year != null) WText(year, WmcType.Label, Modifier.padding(end = 12.dp), color = Wmc.TextDim)
                    item.communityRating?.let { StarRating(it / 2f, Modifier.padding(end = 14.dp)) }
                    val rest = listOfNotNull(
                        item.officialRating,
                        item.runtimeMinutes?.takeIf { it > 0 }?.let { "$it min" },
                        item.childCount?.takeIf { item.type == "Series" }?.let { if (it == 1) "1 season" else "$it seasons" },
                        if (item.type == "Episode") episodeCode(item) else null,
                    )
                    if (rest.isNotEmpty()) WText(rest.joinToString("   "), WmcType.Label, color = Wmc.TextDim)
                }
            }
        }
        if (count > 0 && index >= 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WText("${index + 1}", WmcType.Heading.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
                Box(Modifier.padding(horizontal = 8.dp).size(2.dp, 28.dp).background(Wmc.TextDim))
                WText("$count", WmcType.Heading, color = Wmc.TextDim)
            }
        }
    }
}

/** Five stars, filled to [stars] (0–5, halves allowed). */
@Composable
fun StarRating(stars: Float, modifier: Modifier = Modifier) {
    Row(modifier) {
        for (i in 0 until 5) {
            val fill = (stars - i).coerceIn(0f, 1f)
            Box(Modifier.size(20.dp)) {
                GlyphIcon(Glyph.Star, size = 20.dp, color = Wmc.TextGhost)
                if (fill > 0f) {
                    Box(Modifier.size(20.dp * fill, 20.dp).clipToBounds()) {
                        GlyphIcon(Glyph.Star, Modifier.wrapContentWidth(Alignment.Start, unbounded = true), size = 20.dp, color = Wmc.Text)
                    }
                }
            }
        }
    }
}

/**
 * Songs as columns of text, like Media Center's songs pivot: move through
 * titles, OK plays from that song on, letters jump.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SongList(
    dest: LibraryDest,
    items: List<BaseItem>,
    entered: androidx.compose.runtime.MutableState<Boolean>,
    left: Dp,
    onFocused: (BaseItem) -> Unit,
    onHold: (BaseItem) -> Unit,
    onOpen: (BaseItem) -> Unit,
) {
    val state = rememberLazyGridState()
    val restore = remember { FocusRequester() }
    val restoreIndex = remember(items) { dest.focusIndex.coerceIn(0, items.lastIndex) }
    val jumpRequester = remember { FocusRequester() }
    val jumpTarget = dest.jump
    LaunchedEffect(items) {
        if (restoreIndex > 0) state.scrollToItem(restoreIndex)
        if (!entered.value) {
            entered.value = true
            restore.focusWhenReady()
        }
    }
    LaunchedEffect(jumpTarget) {
        if (jumpTarget < 0) return@LaunchedEffect
        state.scrollToItem(jumpTarget)
        jumpRequester.focusWhenReady()
        dest.jump = -1
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rowH = 38.dp
        val rows = ((maxHeight - 36.dp) / rowH).toInt().coerceIn(3, 12)
        LazyHorizontalGrid(
            rows = GridCells.Fixed(rows),
            state = state,
            contentPadding = PaddingValues(start = left, end = ScreenPadH, top = 18.dp, bottom = 18.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().focusRestorer(restore),
        ) {
            itemsIndexed(items, key = { i, it -> "${it.id}#$i" }) { i, item ->
                FocusBox(
                    onClick = { onOpen(item) },
                    onLongClick = { onHold(item) },
                    onFocus = { dest.focusIndex = i; onFocused(item) },
                    fill = true, scale = 1.03f, corner = 3.dp,
                    modifier = Modifier.size(360.dp, rowH)
                        .then(if (i == restoreIndex) Modifier.focusRequester(restore) else Modifier)
                        .then(if (i == jumpTarget) Modifier.focusRequester(jumpRequester) else Modifier),
                ) { f ->
                    WText(
                        item.name ?: "", WmcType.Label,
                        Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp),
                        color = if (f) Wmc.Text else Wmc.TextDim,
                    )
                }
            }
        }
    }
}

package dev.mediacenter.jf.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.mediacenter.jf.LocalAppState
import dev.mediacenter.jf.data.BaseItem
import dev.mediacenter.jf.ui.SearchDest
import dev.mediacenter.jf.ui.components.BusyIndicator
import dev.mediacenter.jf.ui.components.FocusBox
import dev.mediacenter.jf.ui.components.PivotBar
import dev.mediacenter.jf.ui.components.ScreenPadH
import dev.mediacenter.jf.ui.components.TopChrome
import dev.mediacenter.jf.ui.components.WText
import dev.mediacenter.jf.ui.components.WmcTextField
import dev.mediacenter.jf.ui.components.focusWhenReady
import dev.mediacenter.jf.ui.theme.Wmc
import dev.mediacenter.jf.ui.theme.WmcType
import kotlinx.coroutines.delay

private class SearchPivot(val label: String, val shape: TileShape, val accepts: (BaseItem) -> Boolean)

private val SearchPivots = listOf(
    SearchPivot("all", TileShape.Poster) { it.type !in setOf("Episode", "Audio", "Photo", "PhotoAlbum", "Video") },
    SearchPivot("movies", TileShape.Poster) { it.type == "Movie" || it.type == "BoxSet" },
    SearchPivot("tv shows", TileShape.Poster) { it.type == "Series" },
    SearchPivot("episodes", TileShape.Wide) { it.type == "Episode" },
    SearchPivot("music", TileShape.Square) { it.type in setOf("MusicAlbum", "Audio", "MusicArtist", "Playlist") },
    SearchPivot("people", TileShape.Poster) { it.type == "Person" },
    SearchPivot("pictures + videos", TileShape.Wide) { it.type == "Photo" || it.type == "PhotoAlbum" || it.type == "Video" },
)

/**
 * Search, Media Center style: a text box at the top, results as a gallery
 * below with pivots by kind. Results update as you type.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(dest: SearchDest) {
    val app = LocalAppState.current
    val repo = app.repository ?: return
    val field = remember { FocusRequester() }
    val grid = remember { FocusRequester() }
    var searching by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf<BaseItem?>(null) }
    var focusResults by remember { mutableStateOf(false) }
    LaunchedEffect(focusResults) {
        if (focusResults) {
            grid.focusWhenReady()
            focusResults = false
        }
    }

    LaunchedEffect(Unit) { if (dest.results == null) field.focusWhenReady() }
    // Search a moment after typing stops, so each keystroke doesn't hit the server.
    LaunchedEffect(dest.query) {
        val q = dest.query.trim()
        if (q.length < 2) {
            dest.results = null
            return@LaunchedEffect
        }
        delay(350)
        searching = true
        val results = runCatching { repo.search(q) }.getOrDefault(emptyList())
        dest.results = results
        searching = false
        // If the current tab has nothing but another does (only episodes or songs matched, say), show that one.
        if (results.none(SearchPivots[dest.pivot].accepts)) {
            SearchPivots.indexOfFirst { p -> results.any(p.accepts) }.takeIf { it >= 0 }?.let { dest.pivot = it }
        }
    }

    val all = dest.results
    val pivot = SearchPivots[dest.pivot]
    val shown = all?.filter(pivot.accepts).orEmpty()

    Box(Modifier.fillMaxSize()) {
        WText("search", WmcType.PageTitle, Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = ScreenPadH))
        Column(Modifier.fillMaxSize()) {
            TopChrome()
            Column(Modifier.padding(start = 150.dp, top = 30.dp)) {
                WmcTextField(
                    dest.query, { dest.query = it }, "title, show, song or person",
                    Modifier.width(560.dp).focusRequester(field),
                    imeAction = ImeAction.Search,
                    onDone = { if (shown.isNotEmpty()) focusResults = true },
                )
                Box(Modifier.height(48.dp).padding(top = 10.dp)) {
                    if (all != null) {
                        PivotBar(
                            SearchPivots.map { p -> "${p.label} ${all.count(p.accepts).takeIf { it > 0 } ?: ""}".trim() },
                            dest.pivot, onSelect = { dest.pivot = it },
                        )
                    }
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    searching && all == null -> BusyIndicator(Modifier.align(Alignment.Center))
                    all == null -> WText(
                        "Type at least two letters.", WmcType.Label, Modifier.align(Alignment.Center), color = Wmc.TextDim,
                    )
                    shown.isEmpty() -> WText(
                        if (all.isEmpty()) "No matches." else "Nothing here \u2014 see the other tabs.",
                        WmcType.Label, Modifier.align(Alignment.Center), color = Wmc.TextDim,
                    )
                    else -> {
                        val gap = 3.dp
                        // One row of large tiles (two for wide episode thumbnails): search results should be easy to read.
                        val rows = if (pivot.shape == TileShape.Wide) 2 else 1
                        val tileH = (maxHeight - 36.dp - gap * (rows - 1)) / rows
                        val tileW = tileH * pivot.shape.aspect
                        val imageHeight = with(LocalDensity.current) { ((tileH.toPx() * 1.15f / 60).toInt() + 1) * 60 }
                        LazyHorizontalGrid(
                            rows = GridCells.Fixed(rows),
                            contentPadding = PaddingValues(start = 150.dp, end = ScreenPadH, top = 18.dp, bottom = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(gap),
                            verticalArrangement = Arrangement.spacedBy(gap),
                            modifier = Modifier.fillMaxSize().focusRestorer(grid),
                        ) {
                            itemsIndexed(shown, key = { i, it -> "${it.id}#$i" }) { i, item ->
                                FocusBox(
                                    onClick = { app.open(item, shown, null) },
                                    onLongClick = { app.showItemMenu(item, shown) },
                                    onFocus = { focused = item },
                                    scale = 1.16f, corner = 1.dp, artwork = true,
                                    modifier = Modifier.size(tileW, tileH).then(if (i == 0) Modifier.focusRequester(grid) else Modifier),
                                ) { f -> Tile(repo, item, pivot.shape, f, imageHeight) }
                            }
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(70.dp).padding(start = 150.dp, bottom = 16.dp), contentAlignment = Alignment.BottomStart) {
                focused?.takeIf { it in shown }?.let { item ->
                    Column {
                        WText(item.name ?: "", WmcType.ItemTitle)
                        WText(
                            listOfNotNull(item.type?.let(::kindLabel), metaLine(item).ifEmpty { null }).joinToString("  ·  "),
                            WmcType.Label, color = Wmc.TextDim,
                        )
                    }
                }
            }
        }
    }
}

private fun kindLabel(type: String) = when (type) {
    "Movie" -> "movie"; "Series" -> "tv show"; "Episode" -> "episode"; "MusicAlbum" -> "album"
    "Audio" -> "song"; "MusicArtist" -> "artist"; "Person" -> "person"; "Playlist" -> "playlist"; "BoxSet" -> "collection"
    else -> type.lowercase()
}

package dev.mediacenter.jf.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import dev.mediacenter.jf.LocalAppState
import dev.mediacenter.jf.data.BaseItem
import dev.mediacenter.jf.data.thumbUrl
import dev.mediacenter.jf.ui.DetailsDest
import dev.mediacenter.jf.ui.SeriesDest
import dev.mediacenter.jf.ui.components.Artwork
import dev.mediacenter.jf.ui.components.CenteredBusy
import dev.mediacenter.jf.ui.components.CenteredMessage
import dev.mediacenter.jf.ui.components.FocusBox
import dev.mediacenter.jf.ui.components.Glyph
import dev.mediacenter.jf.ui.components.GlyphIcon
import dev.mediacenter.jf.ui.components.PivotBar
import dev.mediacenter.jf.ui.components.ScreenPadH
import dev.mediacenter.jf.ui.components.TopChrome
import dev.mediacenter.jf.ui.components.WText
import dev.mediacenter.jf.ui.components.focusWhenReady
import dev.mediacenter.jf.ui.components.tryFocus
import dev.mediacenter.jf.ui.theme.Wmc
import dev.mediacenter.jf.ui.theme.WmcType

/** A show's seasons as pivots, its episodes as a list, and the focused episode's details alongside. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SeriesScreen(dest: SeriesDest) {
    val app = LocalAppState.current
    val repo = app.repository ?: return
    var series by remember { mutableStateOf<BaseItem?>(null) }
    var seasons by remember { mutableStateOf<List<BaseItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(dest.seriesId) {
        try {
            series = repo.item(dest.seriesId)
            val list = repo.seasons(dest.seriesId)
            seasons = list
            if (dest.season !in list.indices) {
                dest.season = list.indexOfFirst { (it.userData?.unplayedItemCount ?: 0) > 0 }.coerceAtLeast(0)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.message
        }
    }

    val season = seasons?.getOrNull(dest.season)
    val episodes = season?.let { dest.episodes[it.id] }
    LaunchedEffect(season?.id) {
        val s = season ?: return@LaunchedEffect
        runCatching { repo.episodes(dest.seriesId, s.id) }.onSuccess { dest.episodes[s.id] = it }
    }

    Box(Modifier.fillMaxSize()) {
    WText(series?.name?.lowercase() ?: "", WmcType.PageTitle, Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = ScreenPadH).fillMaxWidth(0.7f), align = androidx.compose.ui.text.style.TextAlign.End)
    Column(Modifier.fillMaxSize()) {
        TopChrome()
        // Browsing the seasons: the remote stays on them while each season's episodes come in below.
        var onSeasons by remember { mutableStateOf(false) }
        Column(Modifier.padding(start = 148.dp, top = 48.dp).height(40.dp).onFocusChanged { onSeasons = it.hasFocus }) {
            val list = seasons
            if (list != null && list.size > 1) {
                PivotBar(list.map { it.name?.lowercase() ?: "season" }, dest.season, onSelect = { dest.season = it; dest.episode = 0 })
            }
        }
        when {
            error != null -> CenteredMessage("Couldn't load this series", error)
            episodes == null -> CenteredBusy()
            episodes.isEmpty() -> CenteredMessage("No episodes")
            else -> EpisodeBrowser(dest, episodes, takeFocus = { !onSeasons }) { app.navigator.push(DetailsDest(it.id, it)) }
        }
    }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun EpisodeBrowser(dest: SeriesDest, episodes: List<BaseItem>, takeFocus: () -> Boolean, onOpen: (BaseItem) -> Unit) {
    val app = LocalAppState.current
    val repo = app.repository ?: return
    val listState = rememberLazyListState()
    val restore = remember { FocusRequester() }
    val restoreIndex = remember(episodes) { dest.episode.coerceIn(0, episodes.lastIndex) }
    val focused = episodes.getOrNull(dest.episode.coerceIn(0, episodes.lastIndex))

    LaunchedEffect(episodes) {
        listState.scrollToItem((restoreIndex - 2).coerceAtLeast(0))
        // Opening the show (or coming back to it) lands on the episodes; changing season leaves you on the seasons.
        if (takeFocus()) {
            app.sounds.quiet()
            restore.focusWhenReady()
        }
    }

    Row(Modifier.fillMaxSize().padding(start = 152.dp, end = ScreenPadH, top = 14.dp, bottom = 24.dp)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.width(470.dp).fillMaxHeight().focusRestorer(restore),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp),
        ) {
            item(key = "skipping") {
                val settings = app.settings
                dev.mediacenter.jf.ui.components.ActionButton(
                    "skipping for this show", { settings.cycleSkipOverride(dest.seriesId) }, glyph = Glyph.SkipNext,
                    detail = "\u2039 ${settings.skipOverrideLabel(dest.seriesId)} \u203a", height = 40.dp,
                )
            }
            itemsIndexed(episodes, key = { _, e -> e.id }) { i, e ->
                FocusBox(
                    onClick = { onOpen(e) },
                    onLongClick = { app.showItemMenu(e, episodes) },
                    onFocus = { dest.episode = i },
                    fill = true,
                    scale = 1.02f,
                    modifier = Modifier.fillMaxWidth().height(44.dp).then(if (i == restoreIndex) Modifier.focusRequester(restore) else Modifier),
                ) { f ->
                    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        WText("${e.indexNumber ?: i + 1}", WmcType.Label, Modifier.width(38.dp), color = if (f) Wmc.Text else Wmc.TextFaint)
                        WText(e.name ?: "", WmcType.Label, Modifier.weight(1f), color = if (f) Wmc.Text else Wmc.TextDim)
                        if (app.userData(e)?.played == true) GlyphIcon(Glyph.Check, size = 16.dp, color = if (f) Wmc.Text else Wmc.Accent)
                        e.runtimeMinutes?.let { WText("$it min", WmcType.Caption, Modifier.padding(start = 12.dp), color = if (f) Wmc.Text else Wmc.TextFaint) }
                    }
                }
            }
        }
        Box(Modifier.weight(1f).padding(start = 40.dp, top = 8.dp)) {
            if (focused != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Artwork(repo.thumbUrl(focused, 480), focused.name, Modifier.size(352.dp, 198.dp), glyph = Glyph.Tv)
                    WText(focused.name ?: "", WmcType.Heading, Modifier.padding(top = 8.dp))
                    WText(metaLine(focused), WmcType.Caption, color = Wmc.TextDim)
                    focused.overview?.let { WText(it, WmcType.Body, maxLines = 6) }
                }
            }
        }
    }
}

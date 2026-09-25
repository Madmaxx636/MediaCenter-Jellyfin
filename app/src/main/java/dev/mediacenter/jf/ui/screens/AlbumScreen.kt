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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.dp
import dev.mediacenter.jf.LocalAppState
import dev.mediacenter.jf.data.BaseItem
import dev.mediacenter.jf.data.ItemQuery
import dev.mediacenter.jf.data.posterUrl
import dev.mediacenter.jf.ui.AlbumDest
import dev.mediacenter.jf.ui.QueueDest
import dev.mediacenter.jf.ui.components.ActionButton
import dev.mediacenter.jf.ui.components.Artwork
import dev.mediacenter.jf.ui.components.CenteredBusy
import dev.mediacenter.jf.ui.components.CenteredMessage
import dev.mediacenter.jf.ui.components.FocusBox
import dev.mediacenter.jf.ui.components.Glyph
import dev.mediacenter.jf.ui.components.ScreenPadH
import dev.mediacenter.jf.ui.components.TopChrome
import dev.mediacenter.jf.ui.components.WText
import dev.mediacenter.jf.ui.components.focusWhenReady
import dev.mediacenter.jf.ui.components.formatDuration
import dev.mediacenter.jf.ui.theme.Wmc
import dev.mediacenter.jf.ui.theme.WmcType

/**
 * Media Center's "album details": cover and album facts top-left, the album's
 * actions down the left (play album, add to queue, shuffle), songs on the
 * right. OK on a song plays from it; holding OK adds just that song to the queue.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AlbumScreen(dest: AlbumDest) {
    val app = LocalAppState.current
    val repo = app.repository ?: return
    var album by remember { mutableStateOf<BaseItem?>(null) }
    var tracks by remember { mutableStateOf<List<BaseItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val nowPlaying by app.playback.nowPlaying.collectAsState()
    val playButton = remember { FocusRequester() }
    val restore = remember { FocusRequester() }

    LaunchedEffect(dest.albumId) {
        try {
            val a = repo.item(dest.albumId)
            album = a
            tracks = if (a.type == "Playlist") {
                repo.items(ItemQuery(a.id, recursive = false, sortBy = null))
            } else {
                repo.items(ItemQuery(a.id, listOf("Audio"), sortBy = "ParentIndexNumber,IndexNumber,SortName"))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.message
        }
    }
    LaunchedEffect(tracks != null) {
        if (tracks == null) return@LaunchedEffect
        app.sounds.quiet()
        if (dest.focusIndex > 0) restore.focusWhenReady() else playButton.focusWhenReady()
    }
    LaunchedEffect(notice) {
        if (notice != null) { kotlinx.coroutines.delay(2500); notice = null }
    }

    fun play(list: List<BaseItem>, index: Int) {
        app.playback.play(list, index, resume = false)
        app.navigator.showPlayer()
    }

    Box(Modifier.fillMaxSize()) {
        val isPlaylist = album?.type == "Playlist"
        WText(if (isPlaylist) "playlist" else "album details", WmcType.PageTitle, Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = ScreenPadH))
        Column(Modifier.fillMaxSize()) {
            TopChrome()
            val a = album
            val t = tracks
            when {
                error != null -> CenteredMessage("Couldn't load this album", error)
                a == null || t == null -> CenteredBusy()
                else -> Row(Modifier.fillMaxSize().padding(start = ScreenPadH + 20.dp, end = ScreenPadH).padding(top = 40.dp, bottom = 24.dp)) {
                    Column(Modifier.width(290.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Artwork(repo.posterUrl(a, 400), a.name, Modifier.size(150.dp), glyph = Glyph.Music, corner = 2.dp)
                        }
                        Box(Modifier.height(14.dp))
                        ActionButton(if (isPlaylist) "play playlist" else "play album", { play(t, 0) }, Modifier.focusRequester(playButton), Glyph.Play)
                        ActionButton("add to queue", {
                            app.playback.enqueue(t); notice = "Added ${t.size} songs to the queue"
                        }, glyph = Glyph.Playlist)
                        ActionButton("shuffle", { play(t.shuffled(), 0) }, glyph = Glyph.Shuffle)
                        if (nowPlaying != null) {
                            ActionButton("view queue", { app.navigator.push(QueueDest()) }, glyph = Glyph.ListLines)
                        }
                        notice?.let { WText(it, WmcType.Caption, Modifier.padding(top = 8.dp), color = Wmc.Accent, maxLines = 2) }
                    }
                    Column(Modifier.weight(1f).padding(start = 36.dp)) {
                        WText(a.name ?: "", WmcType.Title, maxLines = 1)
                        WText(a.albumArtist ?: "", WmcType.Heading, color = Wmc.TextDim, maxLines = 1)
                        val totalMs = t.sumOf { it.runTimeTicks ?: 0 } / BaseItem.TicksPerMs
                        WText(
                            listOfNotNull("${t.size} tracks, ${formatDuration(totalMs)}", a.productionYear?.toString()).joinToString(", "),
                            WmcType.Label, color = Wmc.TextDim,
                        )
                        LazyColumn(
                            Modifier.fillMaxHeight().padding(top = 12.dp).focusRestorer(restore),
                            verticalArrangement = Arrangement.spacedBy(1.dp),
                            contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
                        ) {
                            itemsIndexed(t, key = { i, it -> "${it.id}#$i" }) { i, track ->
                                FocusBox(
                                    onClick = { play(t, i) },
                                    onLongClick = { app.showItemMenu(track, t) },
                                    onFocus = { dest.focusIndex = i },
                                    fill = true, scale = 1.01f, corner = 3.dp,
                                    modifier = Modifier.fillMaxWidth().height(36.dp)
                                        .then(if (i == dest.focusIndex) Modifier.focusRequester(restore) else Modifier),
                                ) { f ->
                                    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        WText(track.name ?: "", WmcType.Label, Modifier.weight(1f), color = if (f) Wmc.Text else Wmc.TextDim)
                                        if (isPlaylist) WText(track.albumArtist ?: "", WmcType.Caption, Modifier.padding(horizontal = 12.dp), color = if (f) Wmc.Text else Wmc.TextFaint)
                                        WText(formatDuration((track.runTimeTicks ?: 0) / BaseItem.TicksPerMs), WmcType.Caption, color = if (f) Wmc.Text else Wmc.TextFaint)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

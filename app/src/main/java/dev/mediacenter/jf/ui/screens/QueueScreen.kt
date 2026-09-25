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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import dev.mediacenter.jf.LocalAppState
import dev.mediacenter.jf.data.BaseItem
import dev.mediacenter.jf.playback.Repeat
import dev.mediacenter.jf.ui.QueueDest
import dev.mediacenter.jf.ui.components.ActionButton
import dev.mediacenter.jf.ui.components.CenteredMessage
import dev.mediacenter.jf.ui.components.FocusBox
import dev.mediacenter.jf.ui.components.Glyph
import dev.mediacenter.jf.ui.components.GlyphIcon
import dev.mediacenter.jf.ui.components.ScreenPadH
import dev.mediacenter.jf.ui.components.TopChrome
import dev.mediacenter.jf.ui.components.WText
import dev.mediacenter.jf.ui.components.WmcTextField
import dev.mediacenter.jf.ui.components.focusWhenReady
import dev.mediacenter.jf.ui.components.formatDuration
import dev.mediacenter.jf.ui.theme.Wmc
import dev.mediacenter.jf.ui.theme.WmcType
import kotlinx.coroutines.launch

/**
 * Now Playing + Queue: the songs lined up to play. "edit queue" adds move
 * up / move down / remove buttons to each song; "save as playlist" stores the
 * queue as a Jellyfin playlist.
 */
@Composable
fun QueueScreen(@Suppress("UNUSED_PARAMETER") dest: QueueDest) {
    val app = LocalAppState.current
    val pm = app.playback
    val np by pm.nowPlaying.collectAsState()
    var editing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val name = remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.focusWhenReady() }

    Box(Modifier.fillMaxSize()) {
        WText("queue", WmcType.PageTitle, Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = ScreenPadH))
        Column(Modifier.fillMaxSize()) {
            TopChrome()
            val current = np
            if (current == null) {
                CenteredMessage("The queue is empty", "Play an album or add songs to the queue.")
                return@Column
            }
            Row(Modifier.fillMaxSize().padding(start = ScreenPadH + 20.dp, end = ScreenPadH).padding(top = 40.dp, bottom = 24.dp)) {
                Column(Modifier.width(290.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ActionButton(if (editing) "done" else "edit queue", { editing = !editing }, Modifier.focusRequester(first), Glyph.ListLines)
                    ActionButton("shuffle", { pm.toggleShuffle() }, glyph = Glyph.Shuffle, detail = if (current.shuffle) "on" else "off")
                    ActionButton(
                        "repeat", { pm.cycleRepeat() }, glyph = Glyph.Resume,
                        detail = when (current.repeat) { Repeat.Off -> "off"; Repeat.All -> "all"; Repeat.One -> "this song" },
                    )
                    ActionButton("save as playlist", { saving = !saving }, glyph = Glyph.Playlist)
                    ActionButton("now playing", { app.navigator.showPlayer() }, glyph = Glyph.NowPlaying)
                    if (saving) {
                        WmcTextField(
                            name.value, { name.value = it }, "playlist name", Modifier.padding(top = 10.dp).fillMaxWidth(),
                            onDone = {
                                val n = name.value.trim()
                                if (n.isEmpty()) return@WmcTextField
                                scope.launch {
                                    status = runCatching { app.repository?.createPlaylist(n, current.queue.map { it.id }) }
                                        .fold({ "Saved as “$n”" }, { "Couldn't save: ${it.message}" })
                                    saving = false
                                }
                            },
                        )
                    }
                    status?.let { WText(it, WmcType.Caption, Modifier.padding(top = 8.dp), color = Wmc.Accent, maxLines = 2) }
                }
                Column(Modifier.weight(1f).padding(start = 36.dp)) {
                    val total = current.queue.sumOf { it.runTimeTicks ?: 0 } / BaseItem.TicksPerMs
                    WText("${current.queue.size} songs, ${formatDuration(total)}", WmcType.Heading, color = Wmc.TextDim)
                    LazyColumn(
                        Modifier.fillMaxHeight().padding(top = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                        contentPadding = PaddingValues(4.dp),
                    ) {
                        itemsIndexed(current.queue, key = { i, it -> "${it.id}#$i" }) { i, track ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FocusBox(
                                    onClick = { pm.skipTo(i) }, fill = true, scale = 1.01f, corner = 3.dp,
                                    onLongClick = {
                                        app.showMenu(track.name ?: "song", buildList {
                                            if (i != current.index) add(MenuChoice("play now", Glyph.Play) { pm.skipTo(i) })
                                            if (i > current.index + 1) add(MenuChoice("play next", Glyph.SkipNext) {
                                                repeat(i - current.index - 1) { step -> pm.moveInQueue(i - step, -1) }
                                            })
                                            if (i != current.index || current.queue.size > 1) add(MenuChoice("remove from queue", Glyph.Minus) { pm.removeFromQueue(i) })
                                            track.albumId?.let { album -> add(MenuChoice("go to album", Glyph.Music) { navigator.push(dev.mediacenter.jf.ui.AlbumDest(album)) }) }
                                        })
                                    },
                                    modifier = Modifier.weight(1f).height(36.dp),
                                ) { f ->
                                    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.width(26.dp)) {
                                            if (i == current.index) GlyphIcon(Glyph.NowPlaying, size = 16.dp, color = Wmc.Accent)
                                        }
                                        WText(track.name ?: "", WmcType.Label, Modifier.weight(1f), color = if (f || i == current.index) Wmc.Text else Wmc.TextDim)
                                        WText(track.albumArtist ?: "", WmcType.Caption, Modifier.padding(horizontal = 12.dp), color = Wmc.TextFaint)
                                        WText(formatDuration((track.runTimeTicks ?: 0) / BaseItem.TicksPerMs), WmcType.Caption, color = Wmc.TextFaint)
                                    }
                                }
                                if (editing) {
                                    QueueButton(Glyph.Sort) { pm.moveInQueue(i, -1) }
                                    QueueButton(Glyph.Sort, flipped = true) { pm.moveInQueue(i, 1) }
                                    QueueButton(Glyph.Plus, rotated = true) { pm.removeFromQueue(i) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueButton(glyph: Glyph, flipped: Boolean = false, rotated: Boolean = false, onClick: () -> Unit) {
    FocusBox(onClick = onClick, fill = true, scale = 1.1f, corner = 4.dp, modifier = Modifier.padding(start = 4.dp).size(36.dp), contentAlignment = Alignment.Center) {
        GlyphIcon(
            glyph,
            Modifier.then(
                when {
                    flipped -> Modifier.androidxRotate(180f)
                    rotated -> Modifier.androidxRotate(45f)
                    else -> Modifier
                }
            ),
            size = 16.dp,
        )
    }
}

private fun Modifier.androidxRotate(degrees: Float) = this.rotate(degrees)

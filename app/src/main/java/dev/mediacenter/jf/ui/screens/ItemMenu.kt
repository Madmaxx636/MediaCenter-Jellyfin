package dev.mediacenter.jf.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import dev.mediacenter.jf.AppState
import dev.mediacenter.jf.LocalAppState
import dev.mediacenter.jf.data.BaseItem
import dev.mediacenter.jf.data.ItemQuery
import dev.mediacenter.jf.data.MediaRepository
import dev.mediacenter.jf.data.UserData
import dev.mediacenter.jf.ui.AlbumDest
import dev.mediacenter.jf.ui.DetailsDest
import dev.mediacenter.jf.ui.PhotoDest
import dev.mediacenter.jf.ui.ProgramDest
import dev.mediacenter.jf.ui.SeriesDest
import dev.mediacenter.jf.ui.components.ActionButton
import dev.mediacenter.jf.ui.components.Glyph
import dev.mediacenter.jf.ui.components.WText
import dev.mediacenter.jf.ui.components.aeroGlass
import dev.mediacenter.jf.ui.components.focusWhenReady
import dev.mediacenter.jf.ui.theme.Wmc
import dev.mediacenter.jf.ui.theme.WmcType
import kotlinx.coroutines.launch

/** One choice in a hold-OK menu. */
class MenuChoice(val label: String, val glyph: Glyph, val run: suspend AppState.() -> Unit)

/** A hold-OK menu: what it's for, and what can be done with it. */
class MenuSheet(val title: String, val subtitle: String?, val choices: List<MenuChoice>)

/** An item's watched and favourite marks, including any just changed from a menu. */
fun AppState.userData(item: BaseItem): UserData? = userDataEdits[item.id] ?: item.userData

/**
 * Holding OK on an item: Media Center's context menu for it (play, add to queue, shuffle,
 * more info, watched, favourites and so on). [siblings] and [view] are what OK itself would
 * open it with; [fromDetails] leaves out "more info" on the item's own details page.
 */
fun AppState.showItemMenu(
    item: BaseItem,
    siblings: List<BaseItem> = listOf(item),
    view: BaseItem? = null,
    genreItemType: String? = null,
    fromDetails: Boolean = false,
) {
    val repo = repository ?: return
    val choices = itemChoices(repo, item, siblings, view, genreItemType, fromDetails)
    if (choices.isEmpty()) return
    sounds.select()
    val subtitle = when (item.type) {
        "Episode" -> listOfNotNull(item.seriesName, episodeCode(item)).joinToString("  ·  ")
        "Audio" -> listOfNotNull(item.albumArtist ?: item.artists.firstOrNull(), item.album).joinToString("  ·  ")
        "MusicAlbum" -> item.albumArtist
        "Program" -> item.channelName
        else -> item.productionYear?.toString()
    }?.ifEmpty { null }
    menu = MenuSheet(item.name ?: "", subtitle, choices)
}

/** Shows [menu] for a tile that isn't a single item (a library, favourites and the like). */
fun AppState.showMenu(title: String, choices: List<MenuChoice>) {
    if (choices.isEmpty()) return
    sounds.select()
    menu = MenuSheet(title, null, choices)
}

private fun AppState.itemChoices(
    repo: MediaRepository,
    item: BaseItem,
    siblings: List<BaseItem>,
    view: BaseItem?,
    genreItemType: String?,
    fromDetails: Boolean,
): List<MenuChoice> {
    val out = mutableListOf<MenuChoice>()
    fun add(label: String, glyph: Glyph, run: suspend AppState.() -> Unit) { out += MenuChoice(label, glyph, run) }
    val playing = playback.nowPlaying.value != null
    val data = userData(item)
    val name = item.name ?: "this"

    when {
        item.isChannel -> {
            add("watch", Glyph.LiveTv) { watchLiveTv(siblings.filter { it.isChannel }.ifEmpty { listOf(item) }, item) }
        }
        item.type == "Program" -> {
            val now = java.time.Instant.now()
            val airing = item.start?.let { it <= now } == true && item.end?.let { it > now } == true
            if (airing && item.channelId != null) add("watch channel", Glyph.LiveTv) {
                val channels = repo.channels()
                channels.firstOrNull { it.id == item.channelId }?.let { watchLiveTv(channels, it) }
            }
            if (item.timerId != null) add("don't record", Glyph.Record) { repo.cancelRecording(item, series = false); toast = "“$name” won't be recorded" }
            else add("record", Glyph.Record) { repo.record(item, series = false); toast = "“$name” will be recorded" }
            if (item.isSeries == true) {
                if (item.seriesTimerId != null) add("stop recording series", Glyph.Record) { repo.cancelRecording(item, series = true); toast = "Stopped recording the series" }
                else add("record series", Glyph.Record) { repo.record(item, series = true); toast = "The series will be recorded" }
            }
            add("more info", Glyph.Info) { navigator.push(ProgramDest(item.id)) }
            return out
        }
        item.type == "Photo" -> {
            val photos = siblings.filter { it.type == "Photo" }.ifEmpty { listOf(item) }
            val at = photos.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
            add("view", Glyph.Pictures) { navigator.push(PhotoDest(photos, at, slideshow = false)) }
            add("play slide show from here", Glyph.Play) { navigator.push(PhotoDest(photos, at, slideshow = true)) }
        }
        item.type == "Person" -> add("open", Glyph.User) { open(item, siblings, view) }
        else -> {
            val single = !item.isFolder && (item.isVideo || item.isAudio)
            val contents = if (single) null else contentsOf(repo, item, view, genreItemType)
            val pictures = item.type != "MusicAlbum" && (view?.collectionType == "photos" || item.collectionType == "photos") && item.isFolder
            // A whole library (its start menu tile): open it, or shuffle it; playing it all in order isn't much use.
            val library = item.type == "CollectionFolder" || item.type == "UserView"
            if (library) add("open", Glyph.Folder) { open(item, siblings, view) }
            if (library && !pictures) {
                if (contents != null) add("shuffle", Glyph.Shuffle) { startPlaying(contents(true)) }
            } else if (single) {
                val resume = item.resumeTicks > 0 && data?.played != true
                if (resume) add("resume", Glyph.Resume) { playItem(repo, item, resume = true) }
                add(if (resume) "play from beginning" else "play", Glyph.Play) { playItem(repo, item, resume = false) }
                add("add to queue", Glyph.Playlist) { queue(listOf(item), next = false, what = "“$name”") }
                if (playing) add("play next", Glyph.SkipNext) { queue(listOf(item), next = true, what = "“$name”") }
            } else if (pictures) {
                add("play slide show", Glyph.Play) { slideShow(repo, item, shuffle = false) }
                add("shuffle slide show", Glyph.Shuffle) { slideShow(repo, item, shuffle = true) }
            } else if (contents != null) {
                add("play", Glyph.Play) {
                    val all = contents(false)
                    // A show carries on from the first episode not yet watched.
                    val from = if (item.type == "Series" || item.type == "Season") all.indexOfFirst { it.userData?.played != true }.coerceAtLeast(0) else 0
                    startPlaying(all, from, resume = item.type == "Series" || item.type == "Season")
                }
                add("shuffle", Glyph.Shuffle) { startPlaying(contents(true)) }
                add("add to queue", Glyph.Playlist) { contents(false).let { queue(it, next = false, what = countOf(it)) } }
                if (playing) add("play next", Glyph.SkipNext) { contents(false).let { queue(it, next = true, what = countOf(it)) } }
            }
            // Where OK itself goes, when that's somewhere other than playing.
            if (!fromDetails && !library) when {
                item.type == "Audio" -> {}
                single -> add("more info", Glyph.Info) { navigator.push(DetailsDest(item.id, item)) }
                else -> add("open", Glyph.Folder) { open(item, siblings, view, genreItemType) }
            }
            if (item.type == "Episode" && item.seriesId != null) add("go to series", Glyph.Tv) { navigator.push(SeriesDest(item.seriesId)) }
            if (item.type == "Audio" && item.albumId != null) add("go to album", Glyph.Music) { navigator.push(AlbumDest(item.albumId)) }
        }
    }

    // Watched and favourites, for the things Jellyfin keeps them for.
    val markable = item.type in setOf("Movie", "Episode", "Video", "Series", "Season", "MusicVideo", "Recording", "Audio", "MusicAlbum", "BoxSet")
    if (markable) {
        val played = data?.played == true
        val music = item.type == "Audio" || item.type == "MusicAlbum"
        val label = when {
            music -> if (played) "mark as not played" else "mark as played"
            else -> if (played) "mark as unwatched" else "mark as watched"
        }
        add(label, Glyph.Check) {
            repo.setPlayed(item.id, !played)
            userDataEdits[item.id] = (data ?: UserData()).copy(
                played = !played, playbackPositionTicks = 0, playedPercentage = null,
                unplayedItemCount = if (!played) 0 else null,
            )
            toast = "“$name” marked as " + if (music) (if (played) "not played" else "played") else if (played) "unwatched" else "watched"
        }
    }
    val favoritable = markable || item.type in setOf("MusicArtist", "Playlist", "Person", "TvChannel", "Photo", "Folder", "PhotoAlbum")
    if (favoritable) {
        val fav = data?.isFavorite == true
        add(if (fav) "remove from favorites" else "add to favorites", Glyph.Star) {
            repo.setFavorite(item.id, !fav)
            userDataEdits[item.id] = (data ?: UserData()).copy(isFavorite = !fav)
            toast = if (fav) "“$name” removed from favorites" else "“$name” added to favorites"
        }
    }
    return out
}

/** What a folder-like item plays: in order, or shuffled ([random]). Null when it has nothing to play. */
private fun contentsOf(repo: MediaRepository, item: BaseItem, view: BaseItem?, genreItemType: String?): (suspend (Boolean) -> List<BaseItem>)? {
    fun fetch(block: suspend (random: Boolean) -> List<BaseItem>) = block
    fun sorted(random: Boolean, order: String?) = if (random) "Random" else order
    fun limit(random: Boolean, otherwise: Int? = null) = if (random) 500 else otherwise
    return when (item.type) {
        "Series", "Season" -> fetch { r ->
            repo.items(ItemQuery(item.id, listOf("Episode"), sortBy = sorted(r, "ParentIndexNumber,IndexNumber"), limit = limit(r)))
        }
        "MusicAlbum" -> fetch { r -> repo.items(ItemQuery(item.id, listOf("Audio"), sortBy = sorted(r, "ParentIndexNumber,IndexNumber,SortName"))) }
        "Playlist" -> fetch { r -> repo.items(ItemQuery(item.id, recursive = false, sortBy = null)).let { if (r) it.shuffled() else it } }
        "MusicArtist" -> fetch { r ->
            repo.items(ItemQuery(view?.id, listOf("Audio"), artistIds = item.id, sortBy = sorted(r, "Album,ParentIndexNumber,IndexNumber"), limit = limit(r)))
        }
        "MusicComposer" -> fetch { r -> repo.items(ItemQuery(view?.id, listOf("Audio"), personIds = item.id, sortBy = sorted(r, "SortName"), limit = limit(r))) }
        "Year" -> fetch { r -> repo.items(ItemQuery(view?.id, listOf("Audio"), years = item.name, sortBy = sorted(r, "Album,ParentIndexNumber,IndexNumber"), limit = limit(r))) }
        "Genre", "MusicGenre" -> {
            // A genre plays what it groups: songs for music, episodes for shows, otherwise films.
            val type = when (genreItemType) {
                "MusicAlbum" -> "Audio"
                "Series" -> "Episode"
                null -> if (item.type == "MusicGenre") "Audio" else "Movie"
                else -> genreItemType
            }
            val order = if (type == "Episode") "SeriesSortName,ParentIndexNumber,IndexNumber" else "SortName"
            fetch { r -> repo.items(ItemQuery(view?.id, listOf(type), genreIds = item.id, sortBy = sorted(r, order), limit = limit(r, 1000))) }
        }
        "BoxSet" -> fetch { r -> repo.items(ItemQuery(item.id, recursive = false, sortBy = sorted(r, "ProductionYear,SortName"))).filter { !it.isFolder } }
        "CollectionFolder", "UserView" -> {
            val (types, order) = when (item.collectionType) {
                "movies" -> listOf("Movie") to "SortName"
                "tvshows" -> listOf("Episode") to "SeriesSortName,ParentIndexNumber,IndexNumber"
                "music" -> listOf("Audio") to "Album,ParentIndexNumber,IndexNumber"
                "homevideos", "musicvideos" -> listOf("Video", "MusicVideo") to "SortName"
                else -> return null
            }
            fetch { r -> repo.items(ItemQuery(item.id, types, sortBy = sorted(r, order), limit = limit(r, 1000))) }
        }
        else -> if (item.isFolder) {
            val type = if (view?.collectionType == "music") "Audio" else "Video"
            fetch { r -> repo.items(ItemQuery(item.id, mediaTypes = type, sortBy = sorted(r, "SortName"), limit = limit(r, 1000))) }
        } else null
    }
}

private fun countOf(items: List<BaseItem>): String {
    val kind = when {
        items.all { it.isAudio } -> "songs"
        items.all { it.type == "Episode" } -> "episodes"
        else -> "titles"
    }
    return if (items.size == 1) "“${items[0].name ?: "1 title"}”" else "${items.size} $kind"
}

/** Plays one title: an episode carries on through the rest of its season, as OK on it does. */
private suspend fun AppState.playItem(repo: MediaRepository, item: BaseItem, resume: Boolean) {
    val queue = if (item.type == "Episode" && item.seriesId != null) {
        runCatching { repo.episodes(item.seriesId, item.seasonId) }.getOrDefault(emptyList())
            .let { eps -> eps.dropWhile { it.id != item.id }.ifEmpty { listOf(item) } }
    } else listOf(item)
    startPlaying(queue, 0, resume)
}

fun AppState.startPlaying(items: List<BaseItem>, index: Int = 0, resume: Boolean = false) {
    if (items.isEmpty()) { toast = "There's nothing here to play"; return }
    playback.play(items, index, resume)
    navigator.showPlayer()
}

/** Adds to the queue (or to play next); with nothing playing, that starts it. */
private fun AppState.queue(items: List<BaseItem>, next: Boolean, what: String) {
    if (items.isEmpty()) { toast = "There's nothing here to play"; return }
    if (playback.nowPlaying.value == null) return startPlaying(items)
    if (next) playback.playNext(items) else playback.enqueue(items)
    toast = if (next) "$what will play next" else "Added $what to the queue"
}

private suspend fun AppState.slideShow(repo: MediaRepository, folder: BaseItem, shuffle: Boolean) {
    val photos = repo.items(ItemQuery(folder.id, listOf("Photo"), recursive = settings.slideSubfolders.value, sortBy = if (shuffle) "Random" else "SortName", limit = 2000))
    if (photos.isEmpty()) { toast = "There are no pictures here"; return }
    navigator.push(PhotoDest(photos, 0, slideshow = true))
}

/**
 * The open hold-OK menu, over everything: a glass panel of choices with "cancel" at the bottom,
 * as Media Center's. In its own window, so the remote can't wander off it onto the screen
 * underneath, and that screen keeps its place for when the menu closes.
 */
@Composable
fun ItemMenuHost() {
    val app = LocalAppState.current
    val sheet = app.menu ?: return
    fun close() { app.menu = null }
    // The dialog's window has the screen's own density; keep the app's interface size in it.
    val density = androidx.compose.ui.platform.LocalDensity.current
    Dialog(onDismissRequest = { app.sounds.back(); close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Our own shade instead of the window's grey dim.
        (androidx.compose.ui.platform.LocalView.current.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
        val first = remember(sheet) { FocusRequester() }
        LaunchedEffect(sheet) { first.focusWhenReady() }
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides density) {
        Box(Modifier.fillMaxSize().background(Color(0x8C000814)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(440.dp).heightIn(max = 520.dp)
                    .background(Color(0xE6020C24), RoundedCornerShape(8.dp))
                    .aeroGlass(corner = 8.dp, strong = true)
                    .padding(horizontal = 14.dp, vertical = 16.dp)
                    .focusProperties { onExit = { cancelFocusChange() } }
                    .focusGroup(),
            ) {
                WText(sheet.title, WmcType.Heading, Modifier.padding(horizontal = 14.dp), maxLines = 2)
                sheet.subtitle?.let { WText(it, WmcType.Label, Modifier.padding(horizontal = 14.dp), color = Wmc.TextDim, maxLines = 1) }
                Column(
                    Modifier.padding(top = 12.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    sheet.choices.forEachIndexed { i, choice ->
                        ActionButton(
                            choice.label,
                            {
                                close()
                                app.scope.launch {
                                    try {
                                        choice.run(app)
                                    } catch (e: Exception) {
                                        if (e is kotlinx.coroutines.CancellationException) throw e
                                        app.toast = "Couldn't do that: ${e.message ?: "the server didn't answer"}"
                                    }
                                }
                            },
                            if (i == 0) Modifier.focusRequester(first) else Modifier,
                            glyph = choice.glyph,
                        )
                    }
                    Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().padding(horizontal = 8.dp).background(Color(0x40E6F4FF)).heightIn(min = 1.dp, max = 1.dp))
                    ActionButton("cancel", { app.sounds.back(); close() }, glyph = Glyph.Back)
                }
            }
        }
        }
    }
}

/** A short confirmation along the bottom of the screen ("Added … to the queue"), gone after a moment. */
@Composable
fun ToastHost(modifier: Modifier = Modifier) {
    val app = LocalAppState.current
    val text = app.toast ?: return
    LaunchedEffect(text) {
        kotlinx.coroutines.delay(2800)
        if (app.toast == text) app.toast = null
    }
    Box(modifier.fillMaxSize().padding(bottom = 40.dp), contentAlignment = Alignment.BottomCenter) {
        WText(
            text, WmcType.Label,
            Modifier.background(Color(0xE6020C24), RoundedCornerShape(6.dp)).aeroGlass(corner = 6.dp).padding(horizontal = 22.dp, vertical = 10.dp),
            color = Wmc.Text, maxLines = 2,
        )
    }
}

/** True for the remote's OK (and a keyboard's Enter). */
fun isOkKey(e: androidx.compose.ui.input.key.KeyEvent) = e.key == androidx.compose.ui.input.key.Key.DirectionCenter ||
    e.key == androidx.compose.ui.input.key.Key.Enter || e.key == androidx.compose.ui.input.key.Key.NumPadEnter

/**
 * Tells a press of OK from a hold, for screens that read the remote's keys themselves: a press
 * acts when OK is let go, a hold opens the menu as soon as the remote reports it held.
 */
class OkPress {
    private var down = false

    /** Feed it every OK key event; it always handles them. */
    fun handle(e: androidx.compose.ui.input.key.KeyEvent, tap: () -> Unit, hold: () -> Unit): Boolean {
        when (e.type) {
            androidx.compose.ui.input.key.KeyEventType.KeyDown -> when {
                e.nativeKeyEvent.repeatCount == 0 -> down = true
                down -> { down = false; hold() }
            }
            // Only a press that started here: OK let go after opening this screen does nothing.
            androidx.compose.ui.input.key.KeyEventType.KeyUp -> if (down) { down = false; tap() }
        }
        return true
    }
}

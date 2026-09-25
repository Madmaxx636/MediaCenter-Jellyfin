package dev.mediacenter.jf.ui.screens

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import dev.mediacenter.jf.ui.theme.fadeLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import dev.mediacenter.jf.AppState
import dev.mediacenter.jf.LocalAppState
import dev.mediacenter.jf.data.BaseItem
import dev.mediacenter.jf.data.MediaRepository
import dev.mediacenter.jf.data.backdropUrl
import dev.mediacenter.jf.data.posterUrl
import dev.mediacenter.jf.ui.components.aeroGlass
import dev.mediacenter.jf.data.thumbUrl
import dev.mediacenter.jf.playback.NowPlaying
import dev.mediacenter.jf.ui.GuideDest
import dev.mediacenter.jf.ui.SettingsDest
import dev.mediacenter.jf.ui.StartDest
import dev.mediacenter.jf.ui.components.Artwork
import dev.mediacenter.jf.ui.components.CenteredBusy
import dev.mediacenter.jf.ui.components.TileArt
import dev.mediacenter.jf.ui.components.TileArtwork
import dev.mediacenter.jf.ui.components.ScreenPadH
import dev.mediacenter.jf.ui.components.TopChrome
import dev.mediacenter.jf.ui.components.WText
import dev.mediacenter.jf.ui.components.focusFrame
import dev.mediacenter.jf.ui.components.focusWhenReady
import dev.mediacenter.jf.ui.components.tryFocus
import dev.mediacenter.jf.ui.theme.Wmc
import dev.mediacenter.jf.ui.theme.WmcType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private class StripItem(
    val key: String,
    val label: String,
    val art: TileArt,
    /** One picture filling the tile when it's focused (what's playing, where you left off). */
    val image: String? = null,
    /** A library's own pictures, shown when it's focused in Media Center's way for that library. */
    val pictures: List<String> = emptyList(),
    val layout: TileLayout = TileLayout.Mosaic,
    val action: () -> Unit,
)

/** How a focused library tile shows its pictures, as Media Center's did. */
private enum class TileLayout {
    /** Music and picture libraries: a mosaic of covers or photos, six across and three down. */
    Mosaic,
    /** The movie library: a shelf of cases, some face out, some showing their spines, one leaning. */
    Shelf,
    /** Recorded tv (here the tv library): a strip of pictures, the middle one larger. */
    Filmstrip,
}
private class Category(val title: String, val items: List<StripItem>)

private class StartData(
    val views: List<BaseItem>,
    val resume: List<BaseItem>,
    val nextUp: List<BaseItem>,
    val latest: Map<String, List<BaseItem>>,
    val error: String? = null,
)

private val TileW = 184.dp
private val TileH = 102.dp
private val SlotW = 214.dp
private val LabelStep = 54.dp
private val TitleH = 56.dp
private val StripH = 148.dp

/**
 * The Media Center start menu: categories stacked vertically, the focused one
 * enlarged in the middle of the screen with its strip of tiles running to the right.
 * Up/down moves between categories; left/right along the strip.
 */
@Composable
fun StartScreen(dest: StartDest) {
    val app = LocalAppState.current
    val repo = app.repository ?: return
    val activity = LocalActivity.current
    val nowPlaying by app.playback.nowPlaying.collectAsState()

    val data by produceState(dest.cache as? StartData, repo) {
        value = loadStart(repo).also { dest.cache = it }
    }
    val categories = remember(data, nowPlaying?.isVideo, nowPlaying == null) {
        data?.let { buildCategories(app, it, nowPlaying) { activity?.finish() } }
    }

    Box(Modifier.fillMaxSize()) {
        // The intro's ribbons carry on behind the menu (not over a video playing behind it).
        if (app.settings.animatedBackground.value && !dev.mediacenter.jf.ui.LocalVideoBehind.current) {
            dev.mediacenter.jf.ui.theme.RibbonBackdrop(Modifier.fillMaxSize(), fadeIn = { app.introHandoff })
        }
        TopChrome(showBack = false)
        if (categories == null) {
            CenteredBusy()
        } else {
            StartMenu(app, dest, categories)
            data?.error?.let {
                WText(it, WmcType.Caption, Modifier.align(Alignment.BottomEnd).padding(ScreenPadH, 24.dp), color = Wmc.Warning, maxLines = 2)
            }
        }
    }
}

private suspend fun loadStart(repo: MediaRepository): StartData = coroutineScope {
    val views = async { runCatching { repo.views() } }
    val resume = async { runCatching { repo.resume() }.getOrDefault(emptyList()) }
    val nextUp = async { runCatching { repo.nextUp() }.getOrDefault(emptyList()) }
    val v = views.await()
    val list = v.getOrDefault(emptyList())
    val latest = list.filter { it.collectionType in setOf("movies", "tvshows", "music", "photos", "homevideos") }
        .map { view -> view.id to async { runCatching { repo.latest(view.id) }.getOrDefault(emptyList()) } }
        .associate { (id, d) -> id to d.await() }
    StartData(list, resume.await(), nextUp.await(), latest, v.exceptionOrNull()?.let { "Couldn't reach the server: ${it.message}" })
}

private fun buildCategories(app: AppState, data: StartData, nowPlaying: NowPlaying?, exit: () -> Unit): List<Category> {
    val repo = app.repository!!
    val nav = app.navigator
    fun byType(vararg types: String?) = data.views.filter { it.collectionType in types }
    fun label(view: BaseItem, default: String, count: Int) =
        if (count == 1) default else (view.name ?: default).lowercase()
    /** A library's tile, with its latest additions as the pictures Media Center showed for it. */
    fun viewTile(view: BaseItem, label: String, art: TileArt, layout: TileLayout = TileLayout.Mosaic): StripItem {
        val latest = data.latest[view.id].orEmpty().distinctBy { it.seriesId ?: it.albumId ?: it.id }
        val pictures = when (layout) {
            TileLayout.Mosaic -> latest.mapNotNull { repo.posterUrl(it, 120) }.take(18)
            TileLayout.Shelf -> latest.mapNotNull { repo.posterUrl(it, 200) }.take(7)
            TileLayout.Filmstrip -> latest.mapNotNull { repo.thumbUrl(it, 160) }.take(3)
        }
        return StripItem(view.id, label, art, pictures = pictures, layout = layout) { nav.push(libraryFor(view)) }
    }
    fun search(pivot: Int) = StripItem("search-$pivot", "search", TileArt.Search) {
        nav.push(dev.mediacenter.jf.ui.SearchDest().also { it.pivot = pivot })
    }
    /** Plays a queue fetched from the server (favourites, radio), opening now playing as Media Center did. */
    fun playFrom(fetch: suspend (MediaRepository) -> List<BaseItem>) {
        app.scope.launch {
            val queue = runCatching { fetch(repo) }.getOrDefault(emptyList())
            if (queue.isNotEmpty()) { app.playback.play(queue, 0, resume = false); nav.showPlayer() }
        }
    }
    val nowPlayingTile = nowPlaying?.let {
        StripItem("now-playing", "now playing", TileArt.NowPlaying, repo.thumbUrl(it.item)) { nav.showPlayer() }
    }
    val live = byType("livetv").isNotEmpty()

    val categories = mutableListOf<Category>()

    // Search stays at the very top.
    categories += Category("Search", listOf(
        StripItem("search", "search", TileArt.Search) { nav.push(dev.mediacenter.jf.ui.SearchDest()) },
    ))

    // Extras first, as in Media Center: anything else the server has (collections, books, folders).
    val extras = byType("boxsets", "books", "musicvideos", null, "folders", "mixed").map {
        viewTile(
            it, (it.name ?: "extras").lowercase(),
            when (it.collectionType) { "boxsets" -> TileArt.Collections; "books" -> TileArt.Books; "musicvideos" -> TileArt.Filmstrip; else -> TileArt.Folder },
        )
    }
    if (extras.isNotEmpty()) categories += Category("Extras", extras)

    // Pictures + Videos, as Media Center's: picture library, play favorites, video library.
    val photoViews = byType("photos")
    val videoViews = byType("homevideos")
    if (photoViews.isNotEmpty() || videoViews.isNotEmpty()) categories += Category("Pictures + Videos", buildList {
        photoViews.forEach { add(viewTile(it, label(it, "picture library", photoViews.size), TileArt.Photos)) }
        if (photoViews.isNotEmpty()) add(StripItem("fav-pictures", "play favorites", TileArt.Star) {
            // A slide show of your favourite pictures (or, with none marked, a random selection).
            app.scope.launch {
                val photos = runCatching {
                    repo.items(dev.mediacenter.jf.data.ItemQuery(includeItemTypes = listOf("Photo"), filters = "IsFavorite", sortBy = "Random", limit = 300))
                        .ifEmpty { repo.items(dev.mediacenter.jf.data.ItemQuery(photoViews.first().id, listOf("Photo"), sortBy = "Random", limit = 300)) }
                }.getOrDefault(emptyList())
                if (photos.isNotEmpty()) nav.push(dev.mediacenter.jf.ui.PhotoDest(photos, 0, slideshow = true))
            }
        })
        videoViews.forEach { view ->
            add(viewTile(view, label(view, "video library", videoViews.size), TileArt.Screen, TileLayout.Filmstrip))
        }
    })

    // Music, as Media Center's: music library, play favorites, radio, search; then playlists.
    val musicViews = byType("music")
    if (musicViews.isNotEmpty()) categories += Category("Music", buildList {
        musicViews.forEach { add(viewTile(it, label(it, "music library", musicViews.size), TileArt.MusicMosaic)) }
        add(StripItem("fav-music", "play favorites", TileArt.Star) {
            // Your favourite songs shuffled; with none marked, the ones you play most.
            playFrom { r ->
                r.items(dev.mediacenter.jf.data.ItemQuery(includeItemTypes = listOf("Audio"), filters = "IsFavorite", sortBy = "Random", limit = 300))
                    .ifEmpty { r.items(dev.mediacenter.jf.data.ItemQuery(includeItemTypes = listOf("Audio"), filters = "IsPlayed", sortBy = "PlayCount", descending = true, limit = 100)).shuffled() }
            }
        })
        add(StripItem("radio", "radio", TileArt.Radio) {
            // A station of your own: the whole music library, shuffled, playing on.
            playFrom { r -> r.items(dev.mediacenter.jf.data.ItemQuery(musicViews.first().id, listOf("Audio"), sortBy = "Random", limit = 200)) }
        })
        add(search(4))
        byType("playlists").forEach { add(viewTile(it, "playlists", TileArt.Playlist)) }
    })

    // Now Playing, between Music and Movies while something plays, as in Media Center.
    if (nowPlayingTile != null) categories += Category("Now Playing", buildList {
        add(nowPlayingTile)
        if (nowPlaying.queue.size > 1) add(StripItem("queue", "queue", TileArt.Queue) { nav.push(dev.mediacenter.jf.ui.QueueDest()) })
    })

    // Movies: Media Center's tiles in its order (movie library, movie guide, search), then the app's own.
    val movieViews = byType("movies")
    if (movieViews.isNotEmpty()) categories += Category("Movies", buildList {
        movieViews.forEach { add(viewTile(it, label(it, "movie library", movieViews.size), TileArt.DvdCases, TileLayout.Shelf)) }
        if (live) add(StripItem("movies-guide", "movie guide", TileArt.PosterWall) {
            nav.push(dev.mediacenter.jf.ui.LibraryDest("movie guide", null, listOf(
                dev.mediacenter.jf.ui.Pivot("on now") { it.tvMovies("now") },
                dev.mediacenter.jf.ui.Pivot("on next") { it.tvMovies("next") },
                dev.mediacenter.jf.ui.Pivot("top rated") { it.tvMovies("top") },
            )))
        })
        add(search(1))
        val resumeMovies = data.resume.filter { it.type != "Episode" }
        if (resumeMovies.isNotEmpty()) add(StripItem("resume", "continue watching", TileArt.Resume, repo.thumbUrl(resumeMovies.first())) {
            nav.push(simpleList("continue watching") { r -> r.resume().filter { it.type != "Episode" } })
        })
        movieViews.firstOrNull()?.let { v ->
            if (!data.latest[v.id].isNullOrEmpty()) add(StripItem("latest-movies", "recently added", TileArt.Recent) {
                nav.push(simpleList("recently added") { it.latest(v.id) })
            })
        }
        add(StripItem("fav-movies", "favorites", TileArt.Star) {
            nav.push(simpleList("favorite movies") { it.items(dev.mediacenter.jf.data.ItemQuery(includeItemTypes = listOf("Movie"), filters = "IsFavorite")) })
        })
        add(StripItem("people", "people", TileArt.People) {
            // My Movies' person library: everyone in your films and shows, by role.
            nav.push(dev.mediacenter.jf.ui.LibraryDest("people", null, listOf(
                dev.mediacenter.jf.ui.Pivot("all") { it.people(null) },
                dev.mediacenter.jf.ui.Pivot("actors") { it.people("Actor") },
                dev.mediacenter.jf.ui.Pivot("directors") { it.people("Director") },
                dev.mediacenter.jf.ui.Pivot("writers") { it.people("Writer") },
                dev.mediacenter.jf.ui.Pivot("producers") { it.people("Producer") },
                dev.mediacenter.jf.ui.Pivot("composers") { it.people("Composer") },
            )))
        })
    })

    // TV Shows, on their own strip where Media Center's TV was: the library (its recorded tv), search, then the app's own.
    val showViews = byType("tvshows")
    if (showViews.isNotEmpty()) categories += Category("TV Shows", buildList {
        showViews.forEach { add(viewTile(it, label(it, "tv library", showViews.size), TileArt.Filmstrip, TileLayout.Filmstrip)) }
        add(search(2))
        if (data.nextUp.isNotEmpty()) add(StripItem("next-up", "next up", TileArt.NextUp, repo.thumbUrl(data.nextUp.first())) {
            nav.push(simpleList("next up") { it.nextUp() })
        })
        val resumeEpisodes = data.resume.filter { it.type == "Episode" }
        if (resumeEpisodes.isNotEmpty()) add(StripItem("resume-tv", "continue watching", TileArt.Resume, repo.thumbUrl(resumeEpisodes.first())) {
            nav.push(simpleList("continue watching") { r -> r.resume().filter { it.type == "Episode" } })
        })
        showViews.firstOrNull()?.let { v ->
            if (!data.latest[v.id].isNullOrEmpty()) add(StripItem("latest-tv", "recently added", TileArt.Recent) {
                nav.push(simpleList("recently added") { it.latest(v.id) })
            })
        }
        add(StripItem("fav-shows", "favorites", TileArt.Star) {
            nav.push(
                dev.mediacenter.jf.ui.LibraryDest(
                    "favorite shows", null,
                    listOf(
                        dev.mediacenter.jf.ui.Pivot("shows") { it.items(dev.mediacenter.jf.data.ItemQuery(includeItemTypes = listOf("Series"), filters = "IsFavorite")) },
                        dev.mediacenter.jf.ui.Pivot("episodes") { it.items(dev.mediacenter.jf.data.ItemQuery(includeItemTypes = listOf("Episode"), filters = "IsFavorite")) },
                    ),
                )
            )
        })
    })

    // Live TV, below TV Shows, in the order of Media Center's tv strip (recorded tv, guide, live tv), then on now.
    if (live) categories += Category("Live TV", buildList {
        add(StripItem("recorded-tv", "recorded tv", TileArt.Filmstrip) {
            nav.push(dev.mediacenter.jf.ui.LibraryDest("recorded tv", null, listOf(
                dev.mediacenter.jf.ui.Pivot("date recorded") { r -> r.recordings().sortedByDescending { it.dateCreated ?: it.premiereDate } },
                dev.mediacenter.jf.ui.Pivot("title") { r -> r.recordings().sortedBy { it.seriesName ?: it.name } },
                dev.mediacenter.jf.ui.Pivot("scheduled") { it.scheduled() },
            )))
        })
        add(StripItem("guide", "guide", TileArt.Guide) { nav.push(GuideDest()) })
        add(StripItem("live-tv", "live tv", TileArt.LiveTv) {
            app.scope.launch { runCatching { repo.channels() }.onSuccess { app.watchLiveTv(it) } }
        })
        add(StripItem("on-now", "on now", TileArt.OnNow) { nav.push(simpleList("on now") { it.channels() }) })
    })

    // Tasks, as Media Center's: settings, then shut down (here, close), then the rest.
    categories += Category(
        "Tasks",
        buildList {
            add(StripItem("settings", "settings", TileArt.Settings) { nav.push(SettingsDest()) })
            add(StripItem("exit", "close", TileArt.Power) { exit() })
            add(StripItem("switch-user", if (repo.isDemo) "connect server" else "switch users", TileArt.User) { app.switchUser() })
            if (!repo.isDemo && app.settings.tasksSwitchServer.value) {
                add(StripItem("switch-server", "switch server", TileArt.Server) { app.switchServer() })
            }
        },
    )
    return categories
}

@Composable
private fun StartMenu(app: AppState, dest: StartDest, categories: List<Category>) {
    val focus = remember { FocusRequester() }
    // Stay on the same row when one appears or goes above it (now playing, while something plays).
    remember(categories) {
        dest.categoryTitle?.let { title ->
            if (categories.getOrNull(dest.category)?.title != title) categories.indexOfFirst { it.title == title }.takeIf { it >= 0 }?.let { dest.category = it }
        }
        true
    }
    if (dest.category !in categories.indices) {
        dest.category = categories.indexOfFirst { it.title == "Movies" }.takeIf { it >= 0 }
            ?: categories.indexOfFirst { it.title == "TV Shows" }.takeIf { it >= 0 } ?: 0
    }
    val cat = dest.category
    dest.categoryTitle = categories[cat].title
    fun itemIndex(c: Int) = (dest.itemIndex[categories[c].title] ?: 0).coerceIn(0, (categories[c].items.size - 1).coerceAtLeast(0))

    LaunchedEffect(Unit) {
        app.sounds.quiet()
        focus.focusWhenReady()
    }
    // Built and drawn (twice, so its images and glass have started): the intro can begin.
    LaunchedEffect(Unit) {
        withFrameNanos {}
        withFrameNanos {}
        app.introMenuReady = true
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                // Media Center's intro: the menu settles in from slightly too large, about the focused row.
                if (app.introZoomMenu && app.introHandoff < 1f) {
                    val x = 1f - app.introHandoff
                    val zoom = 1f + x * x
                    scaleX = zoom; scaleY = zoom
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.22f, 0.56f)
                }
            }
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val items = categories[cat].items
                val idx = itemIndex(cat)
                when (e.key) {
                    Key.DirectionUp -> if (cat > 0) { dest.category = cat - 1; app.sounds.focus() }
                    Key.DirectionDown -> if (cat < categories.lastIndex) { dest.category = cat + 1; app.sounds.focus() }
                    Key.DirectionLeft -> if (idx > 0) { dest.itemIndex[categories[cat].title] = idx - 1; app.sounds.focus() }
                    Key.DirectionRight -> if (idx < items.lastIndex) { dest.itemIndex[categories[cat].title] = idx + 1; app.sounds.focus() }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (e.nativeKeyEvent.repeatCount == 0) items.getOrNull(idx)?.let { app.sounds.select(); it.action() }
                    }
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        val left = maxWidth * 0.17f
        val focusedTop = maxHeight * 0.47f
        val visible = ((maxWidth - left - ScreenPadH) / SlotW).toInt().coerceAtLeast(1)
        val stripWidth = maxWidth - left - 8.dp

        categories.forEachIndexed { i, category ->
            val distance = i - cat
            val targetY = when {
                distance == 0 -> focusedTop
                distance < 0 -> focusedTop + LabelStep * distance
                else -> focusedTop + TitleH + StripH + LabelStep * (distance - 1)
            }
            val y by animateDpAsState(targetY, dev.mediacenter.jf.ui.theme.Motion.spec(spring(dampingRatio = 0.86f, stiffness = 320f)), label = "rowY")
            val alpha by animateFloatAsState(
                when (kotlin.math.abs(distance)) { 0 -> 1f; 1 -> 0.5f; 2 -> 0.42f; 3 -> 0.3f; 4 -> 0.16f; else -> 0f },
                dev.mediacenter.jf.ui.theme.Motion.spec(tween(260)), label = "rowAlpha",
            )
            val indent by animateDpAsState(if (distance == 0) (-10).dp else 0.dp, dev.mediacenter.jf.ui.theme.Motion.spec(tween(220)), label = "indent")

            Column(
                Modifier
                    .offset(x = left + indent, y = y)
                    .graphicsLayer {
                        // As the intro hands over, the categories slide in from the right, nearest first;
                        // after Media Center's own intro they fade in together as the menu settles (below).
                        val k = if (app.introZoomMenu) 0 else kotlin.math.abs(distance).coerceAtMost(4)
                        val enter = if (app.introZoomMenu) app.introHandoff else ((app.introHandoff - 0.08f * k) / 0.62f).coerceIn(0f, 1f)
                        val eased = 1f - (1f - enter) * (1f - enter) * (1f - enter)
                        this.alpha = alpha * enter
                        if (!app.introZoomMenu) translationX = (1f - eased) * 380.dp.toPx()
                        // Dimmed categories stay a cached layer: otherwise each is redrawn off-screen on
                        // every frame of the ribbons behind them (see fadeLayer).
                        compositingStrategy = if (this.alpha > 0f && this.alpha < 1f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                    },
            ) {
                WText(
                    category.title,
                    WmcType.Hero.copy(fontSize = androidx.compose.ui.unit.TextUnit(40f, androidx.compose.ui.unit.TextUnitType.Sp)),
                    Modifier.height(TitleH),
                    color = if (distance == 0) Wmc.Text else Wmc.TextDim,
                )
                AnimatedVisibility(
                    visible = distance == 0,
                    enter = if (dev.mediacenter.jf.ui.theme.Motion.videoActive) fadeIn(tween(110)) else fadeIn(tween(260, delayMillis = 80)) + slideInHorizontally(tween(320)) { it / 14 },
                    exit = fadeOut(tween(if (dev.mediacenter.jf.ui.theme.Motion.videoActive) 60 else 100)),
                ) {
                    Strip(category, itemIndex(i), visible, stripWidth)
                }
            }
        }
    }
}

@Composable
private fun Strip(category: Category, selected: Int, visible: Int, width: androidx.compose.ui.unit.Dp) {
    var first by remember(category) { mutableIntStateOf(0) }
    if (selected < first) first = selected
    if (selected >= first + visible) first = selected - visible + 1
    val scroll by animateDpAsState(-SlotW * first, dev.mediacenter.jf.ui.theme.Motion.spec(spring(dampingRatio = 0.9f, stiffness = 300f)), label = "strip")

    Box(Modifier.width(width).height(StripH).clipToBounds().padding(top = 8.dp)) {
        Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).offset(x = scroll)) {
            category.items.forEachIndexed { i, item -> StripTile(item, focused = i == selected) }
        }
    }
}

/**
 * One entry on a start-menu strip, as in Windows 7 Media Center: at rest, a pale blue picture
 * over its caption; focused, an Aero glass tile, with a library's own pictures in it (a mosaic,
 * a shelf of cases or a strip of shows) or its picture brightened.
 */
@Composable
private fun StripTile(item: StripItem, focused: Boolean) {
    val repo = LocalAppState.current.repository
    val amount by animateFloatAsState(if (focused) 1f else 0f, dev.mediacenter.jf.ui.theme.Motion.spec(tween(200)), label = "tile")
    val scale by animateFloatAsState(if (focused) 1f else 0.92f, dev.mediacenter.jf.ui.theme.Motion.spec(spring(dampingRatio = 0.7f, stiffness = 500f)), label = "tileScale")
    val hasPictures = repo != null && (item.pictures.isNotEmpty() || item.image != null)
    Column(Modifier.requiredWidth(SlotW), horizontalAlignment = Alignment.Start) {
        Box(
            Modifier
                .padding(start = 6.dp)
                .requiredSize(TileW, TileH)
                .graphicsLayer { scaleX = scale; scaleY = scale },
            contentAlignment = Alignment.Center,
        ) {
            // The glass tile, faded in as the entry gains focus.
            Box(
                Modifier
                    .fillMaxSize()
                    .fadeLayer { amount }
                    .focusFrame(amount, fill = false, corner = 4.dp)
                    .aeroGlass(corner = 4.dp, tint = Color(0xFF5AA8EE)),
            )
            // Media Center's pale picture; where the library's own pictures take over, it fades out for them.
            TileArtwork(
                item.art, Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp),
                alpha = { if (hasPictures) 0.62f * (1f - amount) else 0.62f + 0.38f * amount },
            )
            // Only composed while focused (or fading), so a strip's pictures load as it's reached.
            if (hasPictures && (focused || amount > 0f)) {
                Box(Modifier.fillMaxSize().padding(6.dp).fadeLayer { amount }) {
                    when {
                        item.image != null -> Artwork(item.image, null, Modifier.fillMaxSize(), corner = 2.dp, showTitle = false)
                        item.layout == TileLayout.Shelf -> CaseShelf(item.pictures, Modifier.fillMaxSize())
                        item.layout == TileLayout.Filmstrip -> PictureStrip(item.pictures, Modifier.fillMaxSize())
                        else -> PictureMosaic(item.pictures, Modifier.fillMaxSize())
                    }
                }
            }
        }
        WText(
            item.label,
            WmcType.Label.copy(fontSize = androidx.compose.ui.unit.TextUnit(20f, androidx.compose.ui.unit.TextUnitType.Sp)),
            Modifier.padding(start = 6.dp, top = 6.dp).requiredWidth(TileW),
            color = if (focused) Wmc.Text else Wmc.TextFaint,
            align = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** Media Center's music and picture library tiles: covers or photos, six across and three down. */
@Composable
private fun PictureMosaic(urls: List<String>, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val gap = 2.dp
        val cols = 6
        val rows = 3
        val w = (maxWidth - gap * (cols - 1)) / cols
        val h = (maxHeight - gap * (rows - 1)) / rows
        for (r in 0 until rows) for (c in 0 until cols) {
            Artwork(
                urls[(r * cols + c) % urls.size], null,
                Modifier.offset(x = (w + gap) * c, y = (h + gap) * r).size(w, h),
                corner = 1.dp, showTitle = false,
            )
        }
    }
}

/**
 * Media Center's movie library tile: cases standing on a shelf, some face out, some turned
 * to show their spines (a narrow slice of the cover) and one leaning against its neighbour.
 */
@Composable
private fun CaseShelf(urls: List<String>, modifier: Modifier) {
    // Face out (true) or spine out (false), left to right; the fourth leans.
    val pattern = listOf(true, false, false, false, true, false, true, false)
    BoxWithConstraints(modifier.clipToBounds()) {
        val h = maxHeight * 0.92f
        val face = h * 2f / 3f
        val spine = 11.dp
        Row(Modifier.align(Alignment.BottomStart), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            pattern.forEachIndexed { i, faceOut ->
                val url = urls[i % urls.size]
                if (i == 3) {
                    Box(Modifier.size(spine + 9.dp, h)) {
                        Artwork(
                            url, null,
                            Modifier.size(spine, h * 0.96f).align(Alignment.BottomStart)
                                .graphicsLayer { rotationZ = 16f; transformOrigin = TransformOrigin(1f, 1f) },
                            corner = 1.dp, showTitle = false,
                        )
                    }
                } else {
                    Artwork(url, null, Modifier.size(if (faceOut) face else spine, if (faceOut) h else h * (0.94f + 0.02f * (i % 3))), corner = 1.dp, showTitle = false)
                }
            }
        }
    }
}

/** Media Center's recorded tv tile: three pictures in a strip, the middle one larger and in front. */
@Composable
private fun PictureStrip(urls: List<String>, modifier: Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = maxWidth * 0.34f
        val middle = maxWidth * 0.44f
        Box(Modifier.fillMaxWidth().height(maxHeight * 0.62f).background(Color(0x66061A3A), RoundedCornerShape(2.dp)))
        listOf(-1, 1, 0).forEach { pos ->
            val url = urls[(pos + 1).coerceAtMost(urls.size - 1).let { if (urls.size == 1) 0 else it }]
            val w = if (pos == 0) middle else side
            Artwork(
                url, null,
                Modifier
                    .offset(x = (maxWidth * 0.33f) * pos)
                    .size(w, w * 9f / 16f)
                    .graphicsLayer { alpha = if (pos == 0) 1f else 0.75f; shadowElevation = if (pos == 0) 8f else 0f },
                corner = 1.dp, showTitle = false,
            )
        }
    }
}

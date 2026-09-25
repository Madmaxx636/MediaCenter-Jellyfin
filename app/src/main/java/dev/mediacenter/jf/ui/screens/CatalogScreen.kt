package dev.mediacenter.jf.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mediacenter.jf.AppState
import dev.mediacenter.jf.LocalAppState
import dev.mediacenter.jf.data.BaseItem
import dev.mediacenter.jf.data.ItemQuery
import dev.mediacenter.jf.data.MediaRepository
import dev.mediacenter.jf.data.posterUrl
import dev.mediacenter.jf.ui.CatalogDest
import dev.mediacenter.jf.ui.SearchDest
import dev.mediacenter.jf.ui.SettingsDest
import dev.mediacenter.jf.ui.components.Artwork
import dev.mediacenter.jf.ui.components.CenteredBusy
import dev.mediacenter.jf.ui.components.CenteredMessage
import dev.mediacenter.jf.ui.components.FocusBox
import dev.mediacenter.jf.ui.components.Glyph
import dev.mediacenter.jf.ui.components.GlyphIcon
import dev.mediacenter.jf.ui.components.ScreenPadH
import dev.mediacenter.jf.ui.components.TopChrome
import dev.mediacenter.jf.ui.components.WText
import dev.mediacenter.jf.ui.components.aeroGlass
import dev.mediacenter.jf.ui.components.focusWhenReady
import dev.mediacenter.jf.ui.theme.Wmc
import dev.mediacenter.jf.ui.theme.WmcType
import kotlinx.coroutines.delay

/** The library layouts from My Movies for Windows Media Center. */
enum class CatalogView(val label: String) {
    CoverStrip("cover strip"),
    CoversFull("covers full screen"),
    CoversDetails("covers and details"),
    CoversCentered("covers centered"),
    RowDetails("cover row and details"),
    ListDetails("list and details"),
}

private class SortOption(val label: String, val sortBy: String, val descending: Boolean)

private val SortOptions = listOf(
    SortOption("by title", "SortName", false),
    SortOption("by star rating", "CommunityRating,SortName", true),
    SortOption("by runtime", "Runtime,SortName", false),
    SortOption("by production year", "ProductionYear,SortName", true),
    SortOption("by added date", "DateCreated", true),
    SortOption("by parental rating", "OfficialRating,SortName", false),
    SortOption("by watched status", "IsPlayed,SortName", false),
    SortOption("by bitrate", "VideoBitRate,SortName", true),
    SortOption("by last played", "DatePlayed,SortName", true),
)

/** Media Center's video types, each as the server's filter for it. */
private val VideoTypes: List<Pair<String, (ItemQuery) -> ItemQuery>> = listOf(
    "SD" to { q -> q.copy(maxWidth = 1279) },
    "HD" to { q -> q.copy(minWidth = 1280, maxWidth = 1919) },
    "Full HD" to { q -> q.copy(minWidth = 1920, maxWidth = 3799) },
    "4K / UHD" to { q -> q.copy(is4K = true) },
    "3D" to { q -> q.copy(is3D = true) },
    "DVD" to { q -> q.copy(videoTypes = "Dvd") },
    "Blu-ray" to { q -> q.copy(videoTypes = "BluRay") },
    "ISO" to { q -> q.copy(videoTypes = "Iso") },
)

/** The list options: the first ones show at once, the ones ending in "›" open a second menu of choices. */
private fun listOptions(isShows: Boolean) = buildList {
    add("all titles")
    if (isShows) add("next up")
    add("continue watching")
    add("not watched")
    add("watched")
    add("favorites")
    add("last added")
    add("genres ›")
    add("years ›")
    add("parental ratings ›")
    // Resolution and disc types belong to films' own files; a show's are its episodes'.
    if (!isShows) add("video type ›")
    if (!isShows) add("collections")
}

private enum class Menu { View, Sort, List, Choices }

/** A choice in a second-level list menu (a genre, year, rating or video type): what it shows and its value. */
private class Pick(val label: String, val value: String)

/**
 * A movie or TV library. The toolbar (view, list, sort, search, settings) sits
 * top-left; the chosen layout fills the rest. View, sort and list choices are
 * remembered per library, and separately for each start menu tile that opens it
 * on a list of its own (favorites, recently added, continue watching, next up).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CatalogScreen(dest: CatalogDest) {
    val app = LocalAppState.current
    val repo = app.repository ?: return
    val lib = dest.view
    val isShows = lib.collectionType == "tvshows"
    val itemType = if (isShows) "Series" else "Movie"
    val s = app.settings
    val key = dest.prefKey
    val options = remember(isShows) { listOptions(isShows) }

    var view by remember { mutableStateOf(runCatching { CatalogView.valueOf(s.libraryPref(key, "view", "CoverStrip")) }.getOrDefault(CatalogView.CoverStrip)) }
    var sort by remember { mutableStateOf(s.libraryPref(key, "sort", if (dest.start == "last added") "by added date" else "by title")) }
    // "ascending" / "descending"; empty means the sort's natural direction.
    var order by remember { mutableStateOf(s.libraryPref(key, "order", "")) }
    var list by remember { mutableStateOf(s.libraryPref(key, "list", dest.start ?: "all titles").takeIf { it in options } ?: "all titles") }
    // The choice within a second-level list (the genre, year, rating or video type), kept with the list.
    var pick by remember {
        mutableStateOf(s.libraryPref(key, "pick", "").takeIf { it.isNotEmpty() }?.let { Pick(it, s.libraryPref(key, "pickValue", it)) })
    }
    var menu by remember { mutableStateOf<Menu?>(null) }
    // The list whose choices the second menu shows, and the choices once loaded.
    var choicesFor by remember { mutableStateOf("") }
    var choices by remember { mutableStateOf<List<Pick>?>(null) }
    var items by remember { mutableStateOf<List<BaseItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf<BaseItem?>(null) }
    var toolbarFocused by remember { mutableStateOf(false) }
    val firstTool = remember { FocusRequester() }

    LaunchedEffect(sort, order, list, pick) {
        error = null
        val so = SortOptions.firstOrNull { it.label == sort } ?: SortOptions[0]
        val descending = when (order) { "ascending" -> false; "descending" -> true; else -> so.descending }
        val base = ItemQuery(lib.id, listOf(itemType), sortBy = so.sortBy, descending = descending)
        // Episodes where a show's list is about episodes (where you are, what's next), most recent first.
        val episodes = ItemQuery(lib.id, listOf("Episode"), sortBy = "DatePlayed", descending = true, limit = 200)
        val value = pick?.value
        runCatching {
            when (list) {
                "next up" -> repo.nextUp(lib.id, 100)
                "continue watching" -> repo.items(if (isShows) episodes.copy(filters = "IsResumable") else base.copy(filters = "IsResumable"))
                "not watched" -> repo.items(base.copy(filters = "IsUnplayed"))
                "watched" -> repo.items(base.copy(filters = "IsPlayed"))
                "favorites" -> repo.items(base.copy(filters = "IsFavorite"))
                "last added" -> repo.items(base.copy(sortBy = if (isShows) "DateLastContentAdded,SortName" else "DateCreated", descending = true, limit = 100))
                // Jellyfin collections (box sets) live outside libraries, so they're listed server-wide.
                "collections" -> repo.items(ItemQuery(includeItemTypes = listOf("BoxSet"), sortBy = so.sortBy, descending = descending))
                "genres ›" -> repo.items(base.copy(genreIds = value))
                "years ›" -> repo.items(base.copy(years = value))
                "parental ratings ›" -> repo.items(base.copy(officialRatings = value))
                "video type ›" -> repo.items(VideoTypes.firstOrNull { it.first == value }?.second?.invoke(base) ?: base)
                else -> repo.items(base)
            }
        }
            .onSuccess { items = it; if (dest.focusIndex > it.lastIndex) dest.focusIndex = 0 }
            .onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.message ?: "Couldn't load this library." }
    }

    BackHandler(enabled = menu != null) { app.sounds.back(); menu = if (menu == Menu.Choices) Menu.List else null }

    // A tile's own title ("favorite movies") while its list is showing; the library's once another list is chosen.
    val title = dest.title?.takeIf { list == dest.start } ?: ((lib.name ?: if (isShows) "tv series" else "movies").lowercase() + " library")
    val so = SortOptions.firstOrNull { it.label == sort } ?: SortOptions[0]
    val isDescending = when (order) { "ascending" -> false; "descending" -> true; else -> so.descending }
    // Lists that come in their own order (what's next, where you left off, newest) don't show a sort.
    val ownOrder = list == "next up" || list == "continue watching" || list == "last added"
    val listLabel = if (list.endsWith("›")) pick?.label ?: list.removeSuffix(" ›") else list
    val subtitle = (listOf(listLabel) + if (ownOrder) emptyList() else listOf(sort, if (isDescending) "descending" else "ascending"))
        .joinToString("  ·  ")

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val left = maxWidth * 0.1f
        WText(title, WmcType.PageTitle, Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = ScreenPadH))
        WText(subtitle, WmcType.Caption, Modifier.align(Alignment.TopEnd).padding(top = 104.dp, end = ScreenPadH + 6.dp), color = Wmc.TextDim)

        Column(Modifier.fillMaxSize()) {
            TopChrome()
            // In "covers full screen" the toolbar hides until you move up onto it.
            val hideTools = view == CatalogView.CoversFull && !toolbarFocused && menu == null
            Toolbar(
                Modifier.padding(start = left, top = 8.dp).alpha(if (hideTools) 0f else 1f)
                    .onFocusChanged { toolbarFocused = it.hasFocus },
                firstTool,
                onView = { menu = Menu.View },
                onList = { menu = Menu.List },
                onSort = { menu = Menu.Sort },
                onSearch = { app.navigator.push(SearchDest().also { it.pivot = if (isShows) 2 else 1 }) },
                onSettings = { app.navigator.push(SettingsDest()) },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val current = items
                when {
                    error != null -> CenteredMessage("Something went wrong", error)
                    current == null -> CenteredBusy()
                    current.isEmpty() -> CenteredMessage("There's nothing here", "Try a different list option.")
                    else -> CatalogBody(app, repo, dest, view, current, left, onFocused = { focused = it })
                }
            }
        }

        menu?.let { m ->
            val (shown, selected) = when (m) {
                Menu.View -> CatalogView.entries.map { it.label } to view.ordinal
                // Sort fields, then the direction, as the last two entries.
                Menu.Sort -> (SortOptions.map { it.label } + listOf(
                    (if (!isDescending) "✓ " else "") + "ascending",
                    (if (isDescending) "✓ " else "") + "descending",
                )) to SortOptions.indexOfFirst { it.label == sort }
                Menu.List -> options to options.indexOf(list)
                Menu.Choices -> choices.orEmpty().map { it.label } to
                    (if (choicesFor == list) choices.orEmpty().indexOfFirst { it.value == pick?.value } else -1)
            }
            DropMenu(
                shown, selected,
                Modifier.align(Alignment.TopStart).padding(start = left + 40.dp, top = 118.dp),
                onPick = { i ->
                    when (m) {
                        Menu.View -> { view = CatalogView.entries[i]; s.setLibraryPref(key, "view", view.name); menu = null }
                        Menu.Sort -> {
                            if (i < SortOptions.size) {
                                // A new sort field starts in its natural direction.
                                sort = SortOptions[i].label; order = ""
                                s.setLibraryPref(key, "sort", sort)
                            } else {
                                order = if (i == SortOptions.size) "ascending" else "descending"
                            }
                            s.setLibraryPref(key, "order", order)
                            menu = null
                        }
                        Menu.List -> if (options[i].endsWith("›")) {
                            if (choicesFor != options[i]) { choicesFor = options[i]; choices = null }
                            menu = Menu.Choices
                        } else {
                            list = options[i]; pick = null
                            s.setLibraryPref(key, "list", list); s.setLibraryPref(key, "pick", "")
                            menu = null
                        }
                        Menu.Choices -> choices?.getOrNull(i)?.let { p ->
                            list = choicesFor; pick = p
                            s.setLibraryPref(key, "list", list)
                            s.setLibraryPref(key, "pick", p.label); s.setLibraryPref(key, "pickValue", p.value)
                            menu = null
                        }
                    }
                    dest.focusIndex = 0
                },
                onCancel = { menu = if (m == Menu.Choices) Menu.List else null },
            )
            if (m == Menu.Choices && choices == null) {
                LaunchedEffect(choicesFor) {
                    choices = runCatching {
                        when (choicesFor) {
                            "genres ›" -> repo.genres(lib.id, itemType).map { Pick(it.name?.lowercase() ?: "", it.id) }
                            "years ›" -> repo.years(lib.id, itemType).mapNotNull { y -> y.name?.let { Pick(it, it) } }
                            "parental ratings ›" -> repo.officialRatings(lib.id, itemType).map { Pick(it, it) }
                            "video type ›" -> VideoTypes.map { Pick(it.first, it.first) }
                            else -> emptyList()
                        }
                    }.getOrDefault(emptyList())
                }
            }
        }
    }
}

@Composable
private fun Toolbar(
    modifier: Modifier,
    first: FocusRequester,
    onView: () -> Unit,
    onList: () -> Unit,
    onSort: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Two rows of three, as in Media Center: view / list / search, then sort / settings.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ToolButton("view", Glyph.Grid, Modifier.focusRequester(first), onView)
            ToolButton("list", Glyph.ListLines, onClick = onList)
            ToolButton("search", Glyph.Search, onClick = onSearch)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ToolButton("sort", Glyph.Sort, onClick = onSort)
            ToolButton("settings", Glyph.Settings, onClick = onSettings)
        }
    }
}

@Composable
private fun ToolButton(label: String, glyph: Glyph, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusBox(onClick = onClick, fill = true, scale = 1.04f, corner = 4.dp, modifier = modifier.width(126.dp)) { f ->
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(glyph, size = 16.dp, color = if (f) Wmc.Text else Wmc.TextDim)
            WText(label, WmcType.Pivot, Modifier.padding(start = 12.dp), color = if (f) Wmc.Text else Wmc.TextDim)
        }
    }
}

/** Media Center's drop-down: a glass panel of choices with "cancel" at the bottom. */
@Composable
private fun DropMenu(options: List<String>, selected: Int, modifier: Modifier, onPick: (Int) -> Unit, onCancel: () -> Unit) {
    val focus = remember(options) { FocusRequester() }
    LaunchedEffect(options) { focus.focusWhenReady() }
    Column(
        modifier.width(380.dp).heightIn(max = 400.dp)
            // A dark base under the glass so the choices stay readable over busy posters.
            .background(Color(0xE6020C24), androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .aeroGlass(corner = 6.dp, strong = true).padding(8.dp)
            // Keep the cursor inside the open menu: only a choice or Back closes it.
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup()
            .verticalScroll(rememberScrollState()),
    ) {
        if (options.isEmpty()) CenteredBusyInline()
        options.forEachIndexed { i, label ->
            FocusBox(
                onClick = { onPick(i) }, fill = true, scale = 1.01f, corner = 3.dp,
                modifier = Modifier.fillMaxWidth().then(if (i == selected.coerceAtLeast(0)) Modifier.focusRequester(focus) else Modifier),
            ) { f ->
                val shown = if (label.startsWith("\u2713 ")) "\u2713 " + label.drop(2).replaceFirstChar { it.uppercase() } else label.replaceFirstChar { it.uppercase() }
                WText(
                    shown, WmcType.Label,
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    color = if (f || i == selected) Wmc.Text else Wmc.TextDim,
                )
            }
        }
        Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(1.dp).background(Color(0x40E6F4FF)))
        FocusBox(onClick = onCancel, fill = true, scale = 1.01f, corner = 3.dp, modifier = Modifier.fillMaxWidth()) { f ->
            WText("Cancel", WmcType.Label, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = if (f) Wmc.Text else Wmc.TextDim)
        }
    }
}

@Composable
private fun CenteredBusyInline() = Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
    dev.mediacenter.jf.ui.components.BusyIndicator(size = 32.dp)
}

/** The full item (overview, cast) for the focused title, fetched shortly after focus settles and cached. */
@Composable
private fun rememberDetails(dest: CatalogDest, repo: MediaRepository, item: BaseItem?): BaseItem? {
    val loaded by produceState(item?.let { dest.details[it.id] } ?: item, item?.id) {
        // Show what we have for the newly focused title straight away (the state
        // otherwise still holds the previous title), then fetch the full details.
        value = item?.let { dest.details[it.id] } ?: item
        if (item == null || dest.details[item.id] != null) return@produceState
        delay(250)
        runCatching { repo.item(item.id) }.onSuccess { dest.details[item.id] = it; value = it }
    }
    return loaded
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun CatalogBody(
    app: AppState,
    repo: MediaRepository,
    dest: CatalogDest,
    view: CatalogView,
    items: List<BaseItem>,
    left: Dp,
    onFocused: (BaseItem) -> Unit,
) {
    var focused by remember(items) { mutableStateOf(items.getOrNull(dest.focusIndex)) }
    val restore = remember { FocusRequester() }
    val restoreIndex = remember(items, view) { dest.focusIndex.coerceIn(0, items.lastIndex) }
    LaunchedEffect(items, view) { restore.focusWhenReady() }
    val details = rememberDetails(dest, repo, focused)

    fun open(item: BaseItem) = app.open(item, items, dest.view)
    val onFocus: (Int, BaseItem) -> Unit = { i, item ->
        dest.focusIndex = i
        focused = item
        onFocused(item)
    }

    when (view) {
        CatalogView.ListDetails -> Row(Modifier.fillMaxSize().padding(start = left, end = ScreenPadH, bottom = 24.dp)) {
            val listState = rememberLazyListState(restoreIndex)
            LazyColumn(
                state = listState,
                modifier = Modifier.width(420.dp).fillMaxHeight().focusRestorer(restore),
                contentPadding = PaddingValues(vertical = 12.dp, horizontal = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(items, key = { i, it -> "${it.id}#$i" }) { i, item ->
                    FocusBox(
                        onClick = { open(item) }, onLongClick = { app.showItemMenu(item, items, dest.view) }, onFocus = { onFocus(i, item) }, fill = true, scale = 1.02f, corner = 4.dp,
                        modifier = Modifier.fillMaxWidth().then(if (i == restoreIndex) Modifier.focusRequester(restore) else Modifier),
                    ) { f ->
                        WText(item.name ?: "", WmcType.Pivot, Modifier.padding(horizontal = 14.dp, vertical = 7.dp), color = if (f) Wmc.Text else Wmc.TextDim)
                    }
                }
            }
            DetailsPanel(repo, details ?: focused, Modifier.weight(1f).padding(start = 36.dp, top = 12.dp), withCover = true, count = items.size, index = dest.focusIndex)
        }

        else -> BoxWithConstraints(Modifier.fillMaxSize()) {
            val rows = when (view) {
                CatalogView.CoverStrip -> 2
                CatalogView.RowDetails -> 1
                else -> 3
            }
            val gridTop = if (view == CatalogView.RowDetails) maxHeight * 0.5f else 10.dp
            val footer = if (view == CatalogView.CoverStrip) 120.dp else 24.dp
            val gridWidth = if (view == CatalogView.CoversDetails) maxWidth * 0.56f else maxWidth
            val gap = 3.dp
            val gridHeight = maxHeight - gridTop - footer
            val tileH = (gridHeight - gap * (rows - 1) - 20.dp) / rows
            val tileW = tileH * 2f / 3f
            val imageHeight = with(LocalDensity.current) { ((tileH.toPx() * 1.15f / 60).toInt() + 1) * 60 }
            val gridState = rememberLazyGridState(restoreIndex)
            val centered = view == CatalogView.CoversCentered
            // Room the focused cover needs past its own edges: half its 16% zoom, its lift and the glow.
            val overflowV = tileH * 0.08f + 28.dp
            val edgePad = maxOf(10.dp, overflowV)
            val extraV = edgePad - 10.dp
            val edgeMargin = with(LocalDensity.current) { (tileW * 0.08f + 20.dp + gap).toPx() }
            // "Covers centered" keeps the focused cover in the middle of the screen; the other
            // views scroll just enough to keep the zoomed cover and its glow clear of the grid edges.
            val centerSpec = remember {
                object : BringIntoViewSpec {
                    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) =
                        offset - (containerSize - size) / 2f
                }
            }
            val edgeSpec = remember(edgeMargin) {
                object : BringIntoViewSpec {
                    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                        val m = edgeMargin.coerceAtMost(((containerSize - size) / 2f).coerceAtLeast(0f))
                        return when {
                            offset < m -> offset - m
                            offset + size > containerSize - m -> offset + size - (containerSize - m)
                            else -> 0f
                        }
                    }
                }
            }
            val grid: @Composable () -> Unit = {
                LazyHorizontalGrid(
                    rows = GridCells.Fixed(rows),
                    state = gridState,
                    contentPadding = PaddingValues(start = if (view == CatalogView.CoversFull || centered) ScreenPadH else left, end = ScreenPadH, top = edgePad, bottom = edgePad),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(gap),
                    modifier = Modifier.offset(y = gridTop - extraV).width(gridWidth).height(gridHeight + extraV * 2).focusRestorer(restore)
                        // Row + details: the zoomed cover may reach up over the details panel.
                        .then(if (view == CatalogView.RowDetails) Modifier.zIndex(1f) else Modifier),
                ) {
                    itemsIndexed(items, key = { i, it -> "${it.id}#$i" }, contentType = { _, _ -> "cover" }) { i, item ->
                        FocusBox(
                            onClick = { open(item) }, onLongClick = { app.showItemMenu(item, items, dest.view) }, onFocus = { onFocus(i, item) },
                            scale = 1.16f, corner = 1.dp, artwork = true,
                            modifier = Modifier.size(tileW, tileH).then(if (i == restoreIndex) Modifier.focusRequester(restore) else Modifier),
                        ) { f -> Tile(repo, item, TileShape.Poster, f, imageHeight) }
                    }
                }
            }
            CompositionLocalProvider(LocalBringIntoViewSpec provides if (centered) centerSpec else edgeSpec) { grid() }

            when (view) {
                CatalogView.CoverStrip -> focused?.let {
                    StripFooter(it, dest.focusIndex, items.size, Modifier.align(Alignment.BottomStart).padding(start = left, end = ScreenPadH, bottom = 20.dp))
                }
                CatalogView.CoversDetails -> DetailsPanel(
                    repo, details ?: focused,
                    Modifier.align(Alignment.TopEnd).width(maxWidth * 0.40f).fillMaxHeight().padding(end = ScreenPadH, top = 10.dp, bottom = 24.dp),
                    withCover = false, count = items.size, index = dest.focusIndex,
                )
                CatalogView.CoversCentered -> DetailsPanel(
                    repo, details ?: focused,
                    Modifier.align(Alignment.Center).offset(x = tileW * 0.62f + 150.dp).width(360.dp)
                        .aeroGlass(corner = 6.dp, strong = true).padding(16.dp),
                    withCover = false, count = items.size, index = dest.focusIndex, compact = true,
                )
                CatalogView.RowDetails -> DetailsPanel(
                    repo, details ?: focused,
                    Modifier.align(Alignment.TopStart).fillMaxWidth().height(maxHeight * 0.5f).padding(start = left, end = ScreenPadH, top = 4.dp),
                    withCover = false, count = items.size, index = dest.focusIndex, wide = true,
                )
                else -> Unit
            }
        }
    }
}

/** Title, year and stars, runtime and the position counter under the cover strip. */
@Composable
private fun StripFooter(item: BaseItem, index: Int, count: Int, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            WText(catalogName(item), WmcType.ItemTitle)
            Row(verticalAlignment = Alignment.CenterVertically) {
                item.productionYear?.let { WText("$it", WmcType.Label, Modifier.padding(end = 12.dp), color = Wmc.TextDim) }
                item.communityRating?.let { StarRating(it / 2f) }
            }
            val sub = listOfNotNull(
                item.runtimeMinutes?.takeIf { it > 0 }?.let { "$it min" },
                item.childCount?.takeIf { item.type == "Series" }?.let { if (it == 1) "1 season" else "$it seasons" },
            )
            if (sub.isNotEmpty()) WText(sub.joinToString("   "), WmcType.Label, color = Wmc.Accent)
        }
        Counter(index, count)
    }
}

@Composable
private fun Counter(index: Int, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        WText("${index + 1}", WmcType.Heading)
        Box(Modifier.padding(horizontal = 8.dp).size(2.dp, 28.dp).background(Wmc.TextDim))
        WText("$count", WmcType.Heading, color = Wmc.TextDim)
    }
}

/**
 * The details block used by the detail layouts: title, year and stars, date,
 * runtime and rating, genres, synopsis and cast. [wide] puts the synopsis in a
 * second column, as in "cover row and details".
 */
@Composable
private fun DetailsPanel(
    repo: MediaRepository,
    item: BaseItem?,
    modifier: Modifier,
    withCover: Boolean,
    count: Int,
    index: Int,
    compact: Boolean = false,
    wide: Boolean = false,
) {
    item ?: return
    val facts: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            WText(catalogName(item), if (compact) WmcType.Heading else WmcType.ItemTitle, maxLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                item.productionYear?.let { WText("$it", WmcType.Label, Modifier.padding(end = 12.dp), color = Wmc.TextDim) }
                item.communityRating?.let { StarRating(it / 2f) }
            }
            val line = listOfNotNull(
                item.premiereDate?.take(10)?.let { d -> runCatching { java.time.LocalDate.parse(d) }.getOrNull() }
                    ?.let { java.time.format.DateTimeFormatter.ofPattern("M/d/yyyy").format(it) },
                item.runtimeMinutes?.takeIf { it > 0 }?.let { "$it min" },
                item.officialRating?.let { "Cert. $it" },
                item.childCount?.takeIf { item.type == "Series" }?.let { if (it == 1) "1 season" else "$it seasons" },
            )
            if (line.isNotEmpty()) WText(line.joinToString(", "), WmcType.Label, color = Wmc.TextDim)
            if (item.genres.isNotEmpty()) WText(item.genres.take(4).joinToString(", "), WmcType.Label, color = Wmc.Accent)
        }
    }
    val synopsis: @Composable (Modifier) -> Unit = { m ->
        Column(m, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item.overview?.let { WText(it, WmcType.Body.copy(color = Wmc.Text), maxLines = if (compact) 6 else 9) }
            val cast = item.people.filter { it.type == "Actor" }.mapNotNull { it.name }.take(4)
            if (cast.isNotEmpty()) WText(cast.joinToString(", "), WmcType.Caption, color = Wmc.TextDim, maxLines = 2)
            item.people.firstOrNull { it.type == "Director" }?.name?.let { WText("Directed by $it", WmcType.Caption, color = Wmc.TextDim) }
        }
    }
    Box(modifier) {
        if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                Box(Modifier.weight(0.42f)) { facts() }
                synopsis(Modifier.weight(0.58f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (withCover) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Artwork(repo.posterUrl(item, 420), item.name, Modifier.size(150.dp, 225.dp), glyph = Glyph.Movies, corner = 2.dp)
                        facts()
                    }
                } else facts()
                synopsis(Modifier)
            }
        }
        if (!compact) Box(Modifier.align(Alignment.BottomEnd)) { Counter(index, count) }
    }
}

/** A title as the catalog names it: an episode with its show and number ("Harbor Lights · S1 E2 · Pilot"). */
private fun catalogName(item: BaseItem): String =
    if (item.type == "Episode") listOfNotNull(item.seriesName, episodeCode(item), item.name).joinToString("  ·  ") else item.name ?: ""

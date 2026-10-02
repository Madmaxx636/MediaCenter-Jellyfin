package dev.mediacenter.jf.ui.screens

import androidx.compose.ui.focus.focusProperties
import dev.mediacenter.jf.ui.components.aeroGlass
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.gestures.animateScrollBy
import dev.mediacenter.jf.ui.components.PivotBar
import dev.mediacenter.jf.ui.components.GlyphIcon
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mediacenter.jf.AppState
import dev.mediacenter.jf.LocalAppState
import dev.mediacenter.jf.data.BaseItem
import dev.mediacenter.jf.data.ImageKind
import dev.mediacenter.jf.data.ItemQuery
import dev.mediacenter.jf.data.MediaRepository
import dev.mediacenter.jf.data.MediaStream
import dev.mediacenter.jf.data.Person
import dev.mediacenter.jf.data.backdropUrl
import dev.mediacenter.jf.data.posterUrl
import dev.mediacenter.jf.data.thumbUrl
import dev.mediacenter.jf.ui.DetailsDest
import dev.mediacenter.jf.ui.LibraryDest
import dev.mediacenter.jf.ui.Pivot
import dev.mediacenter.jf.ui.SeriesDest
import dev.mediacenter.jf.ui.components.ActionButton
import dev.mediacenter.jf.ui.components.Artwork
import dev.mediacenter.jf.ui.components.CenteredBusy
import dev.mediacenter.jf.ui.components.CenteredMessage
import dev.mediacenter.jf.ui.components.FocusBox
import dev.mediacenter.jf.ui.components.Glyph
import dev.mediacenter.jf.ui.components.Reflected
import dev.mediacenter.jf.ui.components.ScreenPadH
import dev.mediacenter.jf.ui.components.TopChrome
import dev.mediacenter.jf.ui.components.WText
import dev.mediacenter.jf.ui.components.formatDuration
import dev.mediacenter.jf.ui.components.formatTime
import dev.mediacenter.jf.ui.components.focusWhenReady
import dev.mediacenter.jf.ui.components.tryFocus
import dev.mediacenter.jf.ui.theme.Wmc
import dev.mediacenter.jf.ui.theme.WmcType
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * Title details, as in My Movies for Media Center: the film's backdrop fills the
 * screen, and a curved Aero glass panel along the bottom carries the pivots:
 * details, synopsis, cast + crew, more like this and actions.
 */
@Composable
fun DetailsScreen(dest: DetailsDest) {
    val app = LocalAppState.current
    val repo = app.repository ?: return
    var item by remember { mutableStateOf(dest.preview) }
    var full by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var similar by remember { mutableStateOf<List<BaseItem>>(emptyList()) }
    var showInfo by remember { mutableStateOf(false) }
    var pivot by remember { mutableIntStateOf(0) }
    val firstButton = remember { FocusRequester() }

    LaunchedEffect(version) {
        runCatching { repo.item(dest.itemId) }
            .onSuccess { item = it; full = true }
            .onFailure { if (item == null) error = it.message }
    }
    LaunchedEffect(dest.itemId) {
        if (dest.preview?.type != "Episode") similar = runCatching { repo.similar(dest.itemId) }.getOrDefault(emptyList()).distinctBy { it.id }.filter { it.id != dest.itemId }
    }
    LaunchedEffect(item != null) {
        if (item != null) {
            app.sounds.quiet()
            firstButton.focusWhenReady()
        }
    }

    val current = item
    Box(Modifier.fillMaxSize()) {
        if (current != null && !dev.mediacenter.jf.ui.LocalVideoBehind.current) {
            Artwork(repo.backdropUrl(current, 1080), null, Modifier.fillMaxSize(), corner = 0.dp, showTitle = false)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x66000814), 0.45f to Color(0x10000814), 1f to Color(0xCC000814))))
        }
        TopChrome()
        when {
            error != null -> CenteredMessage("Couldn't load this item", error)
            current == null -> CenteredBusy()
            else -> {
                val pivots = buildList {
                    add("details"); add("synopsis")
                    if (current.people.isNotEmpty()) add("cast + crew")
                    if (similar.isNotEmpty()) add("more like this")
                    add("actions")
                }
                val selected = pivots.getOrElse(pivot) { "details" }
                CurvedPanel(Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(0.62f)) {
                    Column(Modifier.fillMaxSize().padding(start = ScreenPadH + 20.dp, end = ScreenPadH, top = 40.dp, bottom = 22.dp)) {
                        PivotBar(pivots, pivot, onSelect = { pivot = it }, big = true)
                        Box(Modifier.padding(top = 12.dp).fillMaxSize()) {
                            when (selected) {
                                "details" -> DetailsPivot(app, repo, current, firstButton, onInfo = { showInfo = true })
                                "synopsis" -> SynopsisPivot(current)
                                "cast + crew" -> CastRow(app, repo, current.people.filter { it.name != null }.distinctBy { "${it.id}-${it.type}-${it.role}" }, current)
                                "more like this" -> SimilarRow(app, repo, similar)
                                else -> ActionsPivot(app, repo, current, onInfo = { showInfo = true }) { version++ }
                            }
                        }
                    }
                }
            }
        }
        if (showInfo && current != null) MediaInfoPanel(current) { showInfo = false }
    }
}

/**
 * The Aero glass panel with the gently curved top edge that My Movies used for
 * its details page: it rises from the left and sweeps down to the right.
 */
@Composable
private fun CurvedPanel(modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.drawWithCache {
            val w = size.width
            val h = size.height
            val lift = 34.dp.toPx()
            val shape = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, lift)
                cubicTo(w * 0.35f, -lift * 0.3f, w * 0.7f, lift * 0.2f, w, lift * 1.4f)
                lineTo(w, h); lineTo(0f, h); close()
            }
            // Soft fade (option B): no rim line; the glass is clear at the curve and
            // becomes solid over the next ~60dp, so the backdrop melts into the panel.
            val fadeEnd = ((lift * 1.4f + 60.dp.toPx()) / h).coerceIn(0.05f, 0.9f)
            val body = Brush.verticalGradient(
                0f to Color(0x00103A78),
                fadeEnd * 0.45f to Wmc.themed(Color(0x6A0C3470)),
                fadeEnd to Wmc.themed(Color(0xD00A2E64)),
                1f to Wmc.themed(Color(0xEE03153A)),
            )
            onDrawBehind { drawPath(shape, body) }
        },
    ) { content() }
}

/** Play buttons on the left; title, facts and a short synopsis in the middle; cover and format badges on the right. */
@Composable
private fun DetailsPivot(app: AppState, repo: MediaRepository, item: BaseItem, firstButton: FocusRequester, onInfo: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isEpisode = item.type == "Episode"
    fun play(resume: Boolean) {
        scope.launch {
            val queue = if (isEpisode && item.seriesId != null) {
                runCatching { repo.episodes(item.seriesId, item.seasonId) }.getOrDefault(emptyList())
                    .let { eps -> eps.dropWhile { it.id != item.id }.ifEmpty { listOf(item) } }
            } else listOf(item)
            app.playback.play(queue, 0, resume)
            app.navigator.showPlayer()
        }
    }
    // "media info" sits low on the right with nothing level with it, so right from the buttons
    // is pointed at it directly (otherwise the remote jumps up to the pivots).
    val info = remember { FocusRequester() }
    val toInfo = Modifier.focusProperties { right = info }
    val menu = { app.showItemMenu(item, fromDetails = true) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.width(230.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (item.resumeTicks > 0) {
                ActionButton("resume", { play(true) }, Modifier.focusRequester(firstButton).then(toInfo), Glyph.Resume, detail = formatDuration(item.resumeTicks / BaseItem.TicksPerMs), onLongClick = menu)
                ActionButton("play from beginning", { play(false) }, toInfo, glyph = Glyph.Play, onLongClick = menu)
            } else {
                ActionButton("play", { play(false) }, Modifier.focusRequester(firstButton).then(toInfo), Glyph.Play, onLongClick = menu)
            }
            if (isEpisode && item.seriesId != null) ActionButton("go to series", { app.navigator.push(SeriesDest(item.seriesId)) }, toInfo, glyph = Glyph.Tv, onLongClick = menu)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            WText(if (isEpisode) item.seriesName ?: "" else item.name ?: "", WmcType.Title)
            if (isEpisode) WText(listOfNotNull(episodeCode(item), item.name).joinToString("  "), WmcType.Label, color = Wmc.Text)
            Row(verticalAlignment = Alignment.CenterVertically) {
                item.productionYear?.let { WText("$it", WmcType.Label, Modifier.padding(end = 12.dp), color = Wmc.Text) }
                item.communityRating?.let { StarRating(it / 2f) }
                item.criticRating?.let { WText("   critics ${it.toInt()}%", WmcType.Label, color = Wmc.TextDim) }
            }
            val ends = item.runTimeTicks?.let {
                "ends at " + formatTime(Date(System.currentTimeMillis() + (it - item.resumeTicks) / BaseItem.TicksPerMs), context)
            }
            WText(
                listOfNotNull(
                    item.premiereDate?.take(10)?.let { d -> runCatching { java.time.LocalDate.parse(d) }.getOrNull() }
                        ?.let { DateTimeFormatter.ofPattern("M/d/yyyy").format(it) },
                    item.runtimeMinutes?.takeIf { it > 0 }?.let { "$it min" },
                    item.genres.take(3).joinToString(", ").ifEmpty { null },
                    ends,
                ).joinToString(", "),
                WmcType.Label, color = Wmc.TextDim, maxLines = 2,
            )
            item.overview?.let { WText(it, WmcType.Body.copy(color = Wmc.Text), Modifier.padding(top = 6.dp), maxLines = 5) }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                item.officialRating?.let { Badge(it) }
                val streams = item.mediaStreams.ifEmpty { item.mediaSources.firstOrNull()?.mediaStreams.orEmpty() }
                videoBadge(streams)?.let { Badge(it) }
                streams.firstOrNull { it.type == "Video" }?.codec?.uppercase()?.let { Badge(it) }
                streams.firstOrNull { it.type == "Audio" && it.isDefault }?.let { a -> (a.channelLayout ?: a.channels?.let { "$it ch" })?.let { Badge(it) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                FocusBox(
                    onClick = onInfo, fill = true, scale = 1.04f, corner = 4.dp,
                    modifier = Modifier.focusRequester(info).focusProperties { left = firstButton },
                ) { f ->
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlyphIcon(Glyph.Info, size = 16.dp, color = if (f) Wmc.Text else Wmc.TextDim)
                        WText("media info", WmcType.Label, Modifier.padding(start = 8.dp), color = if (f) Wmc.Text else Wmc.TextDim)
                    }
                }
                val poster = if (isEpisode) repo.thumbUrl(item, 300) else repo.posterUrl(item, 400)
                Artwork(poster, item.name, if (isEpisode) Modifier.size(200.dp, 113.dp) else Modifier.size(110.dp, 165.dp), glyph = Glyph.Movies, corner = 2.dp)
            }
        }
    }
}

@Composable
private fun Badge(text: String) = WText(
    text, WmcType.Caption.copy(fontWeight = FontWeight.Bold),
    Modifier.border(1.dp, Wmc.TextDim, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
    color = Wmc.Text,
)

private fun videoBadge(streams: List<MediaStream>): String? {
    val v = streams.firstOrNull { it.type == "Video" } ?: return null
    val h = v.height ?: return null
    return dev.mediacenter.jf.playback.AutoTune.qualityLabel(h, v.width ?: 0) + if ((v.videoRangeType ?: v.videoRange)?.let { it != "SDR" && it != "Unknown" } == true) " HDR" else ""
}

/** The full synopsis and the credits, in one scrollable, focusable block. */
@Composable
private fun SynopsisPivot(item: BaseItem) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    FocusBox(
        onClick = {}, fill = false, scale = 1f, corner = 6.dp,
        modifier = Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.DirectionDown -> if (scroll.value < scroll.maxValue) { scope.launch { scroll.animateScrollBy(120f) }; true } else false
                Key.DirectionUp -> if (scroll.value > 0) { scope.launch { scroll.animateScrollBy(-120f) }; true } else false
                else -> false
            }
        },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item.taglines.firstOrNull()?.let { WText(it, WmcType.Label.copy(fontStyle = FontStyle.Italic), color = Wmc.Text, maxLines = 2) }
            WText(item.overview ?: "No synopsis available.", WmcType.Body.copy(color = Wmc.Text), maxLines = 40)
            Facts(item, Modifier.padding(top = 6.dp))
        }
    }
}

/** Mark watched, favorites, skipping and media info, as Media Center's "actions" page. */
@Composable
private fun ActionsPivot(app: AppState, repo: MediaRepository, item: BaseItem, onInfo: () -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val played = item.userData?.played == true
        ActionButton(if (played) "mark as unwatched" else "mark as watched", {
            scope.launch { runCatching { repo.setPlayed(item.id, !played) }; onChanged() }
        }, glyph = Glyph.Check)
        val fav = item.userData?.isFavorite == true
        ActionButton(if (fav) "remove from favorites" else "add to favorites", {
            scope.launch { runCatching { repo.setFavorite(item.id, !fav) }; onChanged() }
        }, glyph = Glyph.Star)
        val titleId = item.seriesId ?: item.id
        ActionButton(
            "skipping", { app.settings.cycleSkipOverride(titleId) }, glyph = Glyph.SkipNext,
            detail = "\u2039 ${app.settings.skipOverrideLabel(titleId)} \u203a",
        )
        ActionButton("media info", onInfo, glyph = Glyph.Info)
    }
}

/** The fact sheet: credits, release, and what's in the file. */
@Composable
private fun Facts(item: BaseItem, modifier: Modifier) {
    val streams = item.mediaStreams.ifEmpty { item.mediaSources.firstOrNull()?.mediaStreams.orEmpty() }
    val source = item.mediaSources.firstOrNull()
    val names = { type: String -> item.people.filter { it.type == type }.mapNotNull { it.name }.distinct() }
    val released = item.premiereDate?.let { d ->
        item.start ?: runCatching { java.time.Instant.parse(d) }.getOrNull()
    }?.let { DateTimeFormatter.ofPattern("MMMM d, yyyy").withZone(ZoneId.systemDefault()).format(it) }

    val facts = listOfNotNull(
        names("Director").takeIf { it.isNotEmpty() }?.let { "director" to it.take(3).joinToString(", ") },
        names("Writer").takeIf { it.isNotEmpty() }?.let { "writers" to it.take(3).joinToString(", ") },
        item.studios.mapNotNull { it.name }.takeIf { it.isNotEmpty() }?.let { "studio" to it.take(2).joinToString(", ") },
        released?.let { "released" to it },
        videoLabel(streams)?.let { "video" to it },
        audioLabel(streams)?.let { "audio" to it },
        subtitleLabel(streams)?.let { "subtitles" to it },
        source?.let { s ->
            listOfNotNull(s.container?.uppercase(), s.size?.let { "%.1f GB".format(it / 1e9) }).joinToString("  ·  ").ifEmpty { null }
        }?.let { "file" to it },
    ) + listOf("media info" to "select for full details  \u203a")
    if (facts.isEmpty()) return
    // Two columns, filled down then across, like a printed fact sheet.
    val half = (facts.size + 1) / 2
    Row(
        modifier.fillMaxWidth().aeroGlass(corner = 8.dp, strong = true).padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        listOf(facts.take(half), facts.drop(half)).forEach { column ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                column.forEach { (label, value) ->
                    Row {
                        WText(label, WmcType.Label, Modifier.width(120.dp), color = Wmc.Accent)
                        WText(value, WmcType.Label, Modifier.weight(1f), color = Wmc.Text, maxLines = 2)
                    }
                }
            }
        }
    }
}

private fun language(code: String?): String? = code?.let {
    Locale.forLanguageTag(it).displayLanguage.takeIf { d -> d.isNotBlank() && d != it } ?: it
}

private fun videoLabel(streams: List<MediaStream>): String? {
    val v = streams.firstOrNull { it.type == "Video" } ?: return null
    val res = v.height?.let { h -> dev.mediacenter.jf.playback.AutoTune.qualityLabel(h, v.width ?: 0) }
    return listOfNotNull(res, v.codec?.uppercase(), v.videoRange?.takeIf { it != "SDR" }).joinToString("  ·  ").ifEmpty { null }
}

private fun audioLabel(streams: List<MediaStream>): String? {
    val audio = streams.filter { it.type == "Audio" }
    val main = audio.firstOrNull { it.isDefault } ?: audio.firstOrNull() ?: return null
    val text = listOfNotNull(language(main.language), main.channelLayout ?: main.channels?.let { "$it ch" }, main.codec?.uppercase())
        .joinToString("  ·  ")
    return if (audio.size > 1) "$text  (+${audio.size - 1} more)" else text
}

private fun subtitleLabel(streams: List<MediaStream>): String? {
    val langs = streams.filter { it.type == "Subtitle" }.mapNotNull { language(it.language) }.distinct()
    if (langs.isEmpty()) return null
    return if (langs.size > 4) langs.take(4).joinToString(", ") + " +${langs.size - 4}" else langs.joinToString(", ")
}

@Composable
private fun SectionTitle(text: String) =
    WText(text, WmcType.Heading, Modifier.padding(top = 30.dp, bottom = 8.dp), color = Wmc.TextDim)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CastRow(app: AppState, repo: MediaRepository, people: List<Person>, item: BaseItem) {
    val ordered = people.sortedBy { when (it.type) { "Director" -> 0; "Actor", "GuestStar" -> 1; else -> 2 } }
    LazyRow(
        Modifier.focusRestorer(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp),
    ) {
        items(ordered.distinctBy { "${it.id}-${it.type}-${it.role}" }, key = { "${it.id}-${it.type}-${it.role}" }) { person ->
            Column(Modifier.width(116.dp)) {
                FocusBox(
                    onClick = { person.id?.let { id -> app.navigator.push(dev.mediacenter.jf.ui.PersonDest(id)) } },
                    scale = 1.08f,
                    artwork = true,
                    corner = 2.dp,
                    modifier = Modifier.size(116.dp, 160.dp),
                ) {
                    val url = person.primaryImageTag?.let { tag -> person.id?.let { repo.imageUrl(it, ImageKind.Primary, tag, 320) } }
                    Artwork(url, person.name?.split(' ')?.mapNotNull { it.firstOrNull()?.toString() }?.take(2)?.joinToString(""), Modifier.fillMaxSize(), corner = 2.dp)
                }
                WText(person.name ?: "", WmcType.Label.copy(fontSize = 15.sp), Modifier.padding(top = 8.dp), color = Wmc.Text)
                val role = when (person.type) {
                    "Director" -> "director"
                    "Writer" -> "writer"
                    else -> person.role
                }
                role?.let { WText(it, WmcType.Caption, color = Wmc.TextDim) }
            }
        }
    }
}


@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SimilarRow(app: AppState, repo: MediaRepository, items: List<BaseItem>) {
    LazyRow(
        Modifier.focusRestorer(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(vertical = 12.dp, horizontal = 6.dp),
    ) {
        items(items, key = { it.id }) { similar ->
            Column(Modifier.width(128.dp)) {
                FocusBox(
                    onClick = { app.open(similar, items, null) },
                    onLongClick = { app.showItemMenu(similar, items) },
                    scale = 1.1f,
                    artwork = true,
                    corner = 1.dp,
                    modifier = Modifier.size(128.dp, 192.dp),
                ) {
                    Artwork(repo.posterUrl(similar, 400), similar.name, Modifier.fillMaxSize(), glyph = Glyph.Movies, corner = 1.dp)
                }
                WText(similar.name ?: "", WmcType.Caption, Modifier.padding(top = 6.dp), color = Wmc.TextDim)
            }
        }
    }
}

/** Full technical media info: the file, then every video, audio and subtitle stream. */
@Composable
private fun MediaInfoPanel(item: BaseItem, onClose: () -> Unit) {
    val app = LocalAppState.current
    val first = remember { FocusRequester() }
    androidx.activity.compose.BackHandler { app.sounds.back(); onClose() }
    LaunchedEffect(Unit) { first.focusWhenReady() }
    val source = item.mediaSources.firstOrNull()
    val streams = item.mediaStreams.ifEmpty { source?.mediaStreams.orEmpty() }
    val sep = "  ·  "

    fun rate(b: Long?) = b?.let { if (it >= 1_000_000) "%.1f Mbps".format(it / 1e6) else "${it / 1000} kbps" }
    fun lang(s: MediaStream) = language(s.language) ?: "unknown language"
    val sections = buildList {
        add("file" to listOf(
            listOfNotNull(
                source?.container?.uppercase(),
                source?.size?.let { "%.2f GB".format(it / 1e9) },
                rate(source?.bitrate)?.let { "$it overall" },
                item.runTimeTicks?.let { formatDuration(it / BaseItem.TicksPerMs) },
            ).joinToString(sep).ifEmpty { "no file details" },
        ))
        streams.filter { it.type == "Video" }.takeIf { it.isNotEmpty() }?.let { v ->
            add("video" to v.map { s ->
                listOfNotNull(
                    listOfNotNull(s.codec?.uppercase(), s.profile).joinToString(" ").ifEmpty { null },
                    if (s.width != null && s.height != null) "${s.width}×${s.height}${if (s.isInterlaced) "i" else "p"}" else null,
                    (s.averageFrameRate ?: s.realFrameRate)?.let { "%.3f".format(it).trimEnd('0').trimEnd('.') + " fps" },
                    s.bitDepth?.let { "$it-bit" },
                    (s.videoRangeType ?: s.videoRange)?.takeIf { it != "SDR" && it != "Unknown" },
                    s.aspectRatio,
                    rate(s.bitRate),
                ).joinToString(sep)
            })
        }
        streams.filter { it.type == "Audio" }.takeIf { it.isNotEmpty() }?.let { a ->
            add("audio" to a.map { s ->
                listOfNotNull(
                    lang(s), s.codec?.uppercase(), s.channelLayout ?: s.channels?.let { "$it ch" },
                    s.sampleRate?.let { "%.1f kHz".format(it / 1000.0).replace(".0 ", " ") }, rate(s.bitRate),
                    "default".takeIf { s.isDefault },
                ).joinToString(sep)
            })
        }
        streams.filter { it.type == "Subtitle" }.takeIf { it.isNotEmpty() }?.let { t ->
            add("subtitles" to t.map { s ->
                listOfNotNull(
                    lang(s), s.codec?.uppercase(), s.title?.takeIf { it.isNotBlank() },
                    "forced".takeIf { s.isForced }, "default".takeIf { s.isDefault }, "external".takeIf { s.isExternal },
                ).joinToString(sep)
            })
        }
    }

    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xB0000814)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth(0.72f).fillMaxHeight(0.82f).aeroGlass(corner = 10.dp, strong = true).padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            WText("media info", WmcType.Title)
            WText(item.name ?: "", WmcType.Label, Modifier.padding(bottom = 6.dp), color = Wmc.TextDim)
            var firstUsed = false
            sections.forEach { (title, lines) ->
                WText(title, WmcType.Label, Modifier.padding(top = 10.dp, bottom = 2.dp), color = Wmc.Accent)
                lines.forEachIndexed { i, line ->
                    val mod = if (!firstUsed) { firstUsed = true; Modifier.focusRequester(first) } else Modifier
                    FocusBox(onClick = {}, fill = true, scale = 1.01f, corner = 4.dp, modifier = mod.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
                            if (lines.size > 1) WText("${i + 1}", WmcType.Label, Modifier.width(30.dp), color = Wmc.TextFaint)
                            WText(line, WmcType.Label, color = Wmc.Text, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

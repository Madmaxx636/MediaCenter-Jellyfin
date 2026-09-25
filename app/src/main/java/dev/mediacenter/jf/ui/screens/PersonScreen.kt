package dev.mediacenter.jf.ui.screens

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import dev.mediacenter.jf.LocalAppState
import dev.mediacenter.jf.data.BaseItem
import dev.mediacenter.jf.data.ImageKind
import dev.mediacenter.jf.data.ItemQuery
import dev.mediacenter.jf.data.posterUrl
import dev.mediacenter.jf.ui.PersonDest
import dev.mediacenter.jf.ui.components.Artwork
import dev.mediacenter.jf.ui.components.CenteredBusy
import dev.mediacenter.jf.ui.components.CenteredMessage
import dev.mediacenter.jf.ui.components.FocusBox
import dev.mediacenter.jf.ui.components.Glyph
import dev.mediacenter.jf.ui.components.PivotBar
import dev.mediacenter.jf.ui.components.Reflected
import dev.mediacenter.jf.ui.components.ScreenPadH
import dev.mediacenter.jf.ui.components.TopChrome
import dev.mediacenter.jf.ui.components.WText
import dev.mediacenter.jf.ui.theme.Wmc
import dev.mediacenter.jf.ui.theme.WmcType
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * A person's page, as in My Movies' person library: photo, birth date and
 * biography, then the films and shows in your library they appear in.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PersonScreen(dest: PersonDest) {
    val app = LocalAppState.current
    val repo = app.repository ?: return
    var person by remember { mutableStateOf<BaseItem?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pivot by remember { mutableIntStateOf(0) }
    LaunchedEffect(dest.personId) {
        runCatching { repo.item(dest.personId) }.onSuccess { person = it }.onFailure { error = it.message }
    }
    val movies by produceState<List<BaseItem>?>(null, dest.personId) {
        value = runCatching { repo.items(ItemQuery(includeItemTypes = listOf("Movie"), personIds = dest.personId, sortBy = "ProductionYear,SortName", descending = true)) }.getOrDefault(emptyList())
    }
    val shows by produceState<List<BaseItem>?>(null, dest.personId) {
        value = runCatching { repo.items(ItemQuery(includeItemTypes = listOf("Series"), personIds = dest.personId, sortBy = "ProductionYear,SortName", descending = true)) }.getOrDefault(emptyList())
    }

    Box(Modifier.fillMaxSize()) {
        WText("person", WmcType.PageTitle, Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = ScreenPadH))
        Column(Modifier.fillMaxSize()) {
            TopChrome()
            val p = person
            when {
                error != null -> CenteredMessage("Couldn't load this person", error)
                p == null -> CenteredBusy()
                else -> Column(Modifier.fillMaxSize().padding(start = ScreenPadH + 20.dp, end = ScreenPadH, top = 24.dp, bottom = 12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        Reflected(180.dp) {
                            val photo = p.imageTags["Primary"]?.let { repo.imageUrl(p.id, ImageKind.Primary, it, 480) }
                            Artwork(photo, p.name, Modifier.size(120.dp, 180.dp), glyph = Glyph.User, corner = 2.dp)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            WText(p.name ?: "", WmcType.Title)
                            val born = p.premiereDate?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                            if (born != null) WText("Born ${DateTimeFormatter.ofPattern("MMMM d, yyyy").format(born)}", WmcType.Label, color = Wmc.TextDim)
                            WText(p.overview ?: "No biography available.", WmcType.Body.copy(color = Wmc.Text), Modifier.padding(top = 8.dp), maxLines = 5)
                        }
                    }
                    val lists = listOfNotNull(
                        movies?.takeIf { it.isNotEmpty() }?.let { "movies" to it },
                        shows?.takeIf { it.isNotEmpty() }?.let { "tv shows" to it },
                    )
                    if (lists.isNotEmpty()) {
                        val current = lists[pivot.coerceIn(0, lists.lastIndex)]
                        PivotBar(lists.map { "${it.first} ${it.second.size}" }, pivot.coerceIn(0, lists.lastIndex), { pivot = it }, Modifier.padding(top = 6.dp))
                        LazyRow(
                            Modifier.padding(top = 8.dp).focusRestorer(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            contentPadding = PaddingValues(vertical = 10.dp, horizontal = 6.dp),
                        ) {
                            itemsIndexed(current.second, key = { i, it -> "${it.id}#$i" }) { _, item ->
                                Column(Modifier.width(100.dp)) {
                                    FocusBox(
                                        onClick = { app.open(item, current.second, null) },
                                        onLongClick = { app.showItemMenu(item, current.second) },
                                        scale = 1.1f, corner = 1.dp, artwork = true,
                                        modifier = Modifier.size(100.dp, 150.dp),
                                    ) {
                                        Artwork(repo.posterUrl(item, 360), item.name, Modifier.fillMaxSize(), glyph = Glyph.Movies, corner = 1.dp)
                                    }
                                    WText(listOfNotNull(item.name, item.productionYear?.toString()).joinToString("  "), WmcType.Caption, Modifier.padding(top = 6.dp), color = Wmc.TextDim)
                                }
                            }
                        }
                    } else if (movies != null && shows != null) {
                        WText("Nothing in your library features ${p.name}.", WmcType.Label, Modifier.padding(top = 24.dp), color = Wmc.TextDim)
                    }
                }
            }
        }
    }
}

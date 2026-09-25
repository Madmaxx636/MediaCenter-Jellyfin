package dev.mediacenter.jf.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import dev.mediacenter.jf.data.BaseItem
import dev.mediacenter.jf.data.MediaRepository
import java.util.UUID

/**
 * A screen on the back stack. Each destination keeps its own focus position,
 * so pressing back lands you exactly where you left off, as Media Center did.
 */
sealed class Destination {
    val key: String = UUID.randomUUID().toString()
}

class StartDest : Destination() {
    var category by mutableIntStateOf(-1)

    /** The focused row's title, so it stays focused when a row (now playing) comes or goes above it. */
    var categoryTitle: String? = null
    val itemIndex = mutableStateMapOf<String, Int>()

    /** Last loaded start-menu data, shown instantly on return while a refresh runs. */
    var cache: Any? = null
}

/** A tab ("pivot") across the top of a gallery, and how to fill it. */
class Pivot(
    val label: String,
    /** For genre pivots: which item type the chosen genre should list. */
    val genreItemType: String? = null,
    /** How the pivot is drawn: artwork tiles (default), text tiles, or a song list. */
    val layout: PivotLayout = PivotLayout.Tiles,
    val load: suspend (MediaRepository) -> List<BaseItem>,
)

enum class PivotLayout { Tiles, Text, Songs }

class LibraryDest(
    val title: String,
    val view: BaseItem?,
    val pivots: List<Pivot>,
    /** For picture folders: the pictures a "play slide show" button should show. */
    val slideshow: (suspend (MediaRepository, Boolean) -> List<BaseItem>)? = null,
) : Destination() {
    var pivot by mutableIntStateOf(0)
    var focusIndex by mutableIntStateOf(0)
    val cache = mutableStateMapOf<Int, List<BaseItem>>()
    /** Set by letter-jump: the gallery scrolls to and focuses this index. */
    var jump by mutableIntStateOf(-1)
}

/** [preview] is the list item that was selected, shown instantly while the full item loads. */
/**
 * A movie or TV library in My Movies / Media Center style: toolbar (view, list,
 * sort, search, settings), six layouts, and per-library remembered choices.
 */
/**
 * A movie or TV library with Media Center's toolbar. [start] opens it on one of its lists
 * (favorites, last added, continue watching, next up) under its own [title]; its view, sort
 * and list are then remembered apart from the library's own.
 */
class CatalogDest(val view: BaseItem, val start: String? = null, val title: String? = null) : Destination() {
    val prefKey get() = if (start == null) view.id else "${view.id}:$start"
    var focusIndex by mutableIntStateOf(0)
    val details = mutableStateMapOf<String, BaseItem>()
}

class DetailsDest(val itemId: String, val preview: BaseItem? = null) : Destination()

class SeriesDest(val seriesId: String) : Destination() {
    var season by mutableIntStateOf(-1)
    var episode by mutableIntStateOf(0)
    val episodes = mutableStateMapOf<String, List<BaseItem>>()
}

class AlbumDest(val albumId: String) : Destination() {
    var focusIndex by mutableIntStateOf(0)
}

class PhotoDest(val photos: List<BaseItem>, startIndex: Int, val slideshow: Boolean) : Destination() {
    var index by mutableIntStateOf(startIndex)
}

class PlayerDest : Destination()

/** The TV guide keeps its place: which channel row and which time is focused. */
class GuideDest : Destination() {
    var channel by mutableIntStateOf(-1)
    var anchorEpochSec by androidx.compose.runtime.mutableLongStateOf(0L)
}

class ProgramDest(val programId: String) : Destination()

class SettingsDest : Destination()

/** "Optimize for this TV": checks the TV and connection and sets playback to suit. [firstRun] after a new sign-in. */
class OptimizeDest(val firstRun: Boolean = false) : Destination()

/**
 * Adding a server, or signing in more people on one ([serverId]), from settings while
 * signed in. Whoever is watching stays signed in.
 */
class ServerSetupDest(val serverId: String? = null) : Destination()

class QueueDest : Destination()

class PersonDest(val personId: String) : Destination()

class SearchDest : Destination() {
    var query by androidx.compose.runtime.mutableStateOf("")
    var pivot by mutableIntStateOf(0)
    var results by androidx.compose.runtime.mutableStateOf<List<BaseItem>?>(null)
}

class Navigator {
    val stack = mutableStateListOf<Destination>(StartDest())
    val current get() = stack.last()

    fun push(destination: Destination) {
        stack.add(destination)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** Opens the player, or returns to it if it's already on the stack. */
    fun showPlayer() {
        val existing = stack.indexOfFirst { it is PlayerDest }
        if (existing >= 0) {
            while (stack.lastIndex > existing) stack.removeAt(stack.lastIndex)
        } else {
            push(PlayerDest())
        }
    }

    fun reset() {
        stack.clear()
        stack.add(StartDest())
    }
}

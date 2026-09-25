package dev.mediacenter.jf.ui.screens

import dev.mediacenter.jf.AppState
import dev.mediacenter.jf.data.BaseItem
import dev.mediacenter.jf.data.ItemQuery
import dev.mediacenter.jf.ui.AlbumDest
import dev.mediacenter.jf.ui.DetailsDest
import dev.mediacenter.jf.ui.LibraryDest
import dev.mediacenter.jf.ui.PhotoDest
import dev.mediacenter.jf.ui.Pivot
import dev.mediacenter.jf.ui.SeriesDest

/** The library gallery for one of the user's views, with pivots matching Media Center's for that kind of media. */
fun libraryFor(view: BaseItem): dev.mediacenter.jf.ui.Destination {
    if (view.collectionType == "movies" || view.collectionType == "tvshows") return dev.mediacenter.jf.ui.CatalogDest(view)
    val id = view.id
    val pivots = when (view.collectionType) {
        "movies" -> listOf(
            Pivot("title") { it.items(ItemQuery(id, listOf("Movie"))) },
            Pivot("genre", genreItemType = "Movie") { it.genres(id, "Movie") },
            Pivot("year") { it.items(ItemQuery(id, listOf("Movie"), sortBy = "ProductionYear,SortName", descending = true)) },
            Pivot("date added") { it.items(ItemQuery(id, listOf("Movie"), sortBy = "DateCreated", descending = true)) },
            Pivot("unwatched") { it.items(ItemQuery(id, listOf("Movie"), filters = "IsUnplayed")) },
            Pivot("favorites") { it.items(ItemQuery(id, listOf("Movie"), filters = "IsFavorite")) },
        )
        "tvshows" -> listOf(
            Pivot("title") { it.items(ItemQuery(id, listOf("Series"))) },
            Pivot("genre", genreItemType = "Series") { it.genres(id, "Series") },
            Pivot("date added") { it.items(ItemQuery(id, listOf("Series"), sortBy = "DateCreated", descending = true)) },
            Pivot("unwatched") { it.items(ItemQuery(id, listOf("Series"), filters = "IsUnplayed")) },
            Pivot("favorites") { it.items(ItemQuery(id, listOf("Series"), filters = "IsFavorite")) },
        )
        // Windows Media Center's music library pivots, in its order.
        "music" -> listOf(
            Pivot("album artists") { it.albumArtists(id) },
            Pivot("albums") { it.items(ItemQuery(id, listOf("MusicAlbum"))) },
            Pivot("artists") { it.artists(id) },
            Pivot("genres", genreItemType = "MusicAlbum", layout = dev.mediacenter.jf.ui.PivotLayout.Text) { it.genres(id, "MusicAlbum") },
            Pivot("songs", layout = dev.mediacenter.jf.ui.PivotLayout.Songs) { it.items(ItemQuery(id, listOf("Audio"), limit = 5000)) },
            Pivot("playlists") { it.items(ItemQuery(includeItemTypes = listOf("Playlist"), mediaTypes = "Audio")) },
            Pivot("composers", layout = dev.mediacenter.jf.ui.PivotLayout.Text) { it.composers(id) },
            Pivot("years", layout = dev.mediacenter.jf.ui.PivotLayout.Text) { it.years(id, "MusicAlbum") },
        )
        "playlists" -> listOf(Pivot("playlists") { it.items(ItemQuery(id, recursive = false)) })
        // Pictures + Videos: folders, date taken or tags, as in Media Center.
        "homevideos", "photos" -> listOf(
            Pivot("folders") { it.items(ItemQuery(id, recursive = false, sortBy = "IsFolder,SortName")) },
            Pivot("date taken") {
                it.items(ItemQuery(id, listOf("Photo", "Video"), sortBy = "PremiereDate,DateCreated", descending = true, limit = 2000))
            },
            Pivot("tags", layout = dev.mediacenter.jf.ui.PivotLayout.Text) { r ->
                r.tags(id, "Photo,Video").map { BaseItem(id = "tag-$it", name = it, type = "Tag") }
            },
        )
        else -> listOf(Pivot("title") { it.items(ItemQuery(id, recursive = false, sortBy = "IsFolder,SortName")) })
    }
    val slideshow = if (view.collectionType == "photos" || view.collectionType == "homevideos") slideshowOf(id) else null
    return LibraryDest(view.name ?: "library", view, pivots, slideshow)
}

fun simpleList(title: String, load: suspend (dev.mediacenter.jf.data.MediaRepository) -> List<BaseItem>) =
    LibraryDest(title, null, listOf(Pivot("all", load = load)))


/** What pressing OK on an item does, wherever it appears. */
fun AppState.open(item: BaseItem, siblings: List<BaseItem>, view: BaseItem?, genreItemType: String? = null) {
    val nav = navigator
    when (item.type) {
        "TvChannel" -> watchLiveTv(siblings.filter { it.isChannel }, item)
        "Program" -> nav.push(dev.mediacenter.jf.ui.ProgramDest(item.id))
        "Person" -> nav.push(dev.mediacenter.jf.ui.PersonDest(item.id))
        "Series" -> nav.push(SeriesDest(item.id))
        "MusicAlbum", "Playlist" -> nav.push(AlbumDest(item.id))
        "BoxSet" -> nav.push(LibraryDest(item.name ?: "collection", view, listOf(Pivot("titles") {
            it.items(ItemQuery(item.id, recursive = false, sortBy = "ProductionYear,SortName"))
        })))
        "MusicArtist" -> nav.push(
            LibraryDest(item.name ?: "artist", view, listOf(
                Pivot("albums") {
                    it.items(ItemQuery(view?.id, listOf("MusicAlbum"), artistIds = item.id, sortBy = "ProductionYear,SortName", descending = true))
                },
                Pivot("songs", layout = dev.mediacenter.jf.ui.PivotLayout.Songs) {
                    it.items(ItemQuery(view?.id, listOf("Audio"), artistIds = item.id, sortBy = "Album,ParentIndexNumber,IndexNumber"))
                },
            ))
        )
        "MusicComposer" -> nav.push(
            LibraryDest(item.name ?: "composer", view, listOf(Pivot("songs", layout = dev.mediacenter.jf.ui.PivotLayout.Songs) {
                it.items(ItemQuery(view?.id, listOf("Audio"), personIds = item.id))
            }))
        )
        "Tag" -> nav.push(
            LibraryDest(item.name ?: "tag", view, listOf(Pivot("pictures") {
                it.items(ItemQuery(view?.id, listOf("Photo", "Video"), tags = item.name, sortBy = "PremiereDate,DateCreated", descending = true))
            }), slideshow = { r, _ -> r.items(ItemQuery(view?.id, listOf("Photo"), tags = item.name, limit = 2000)) })
        )
        "Year" -> nav.push(
            LibraryDest(item.name ?: "year", view, listOf(Pivot("albums") {
                it.items(ItemQuery(view?.id, listOf("MusicAlbum"), years = item.name))
            }))
        )
        "Genre", "MusicGenre" -> {
            val type = genreItemType ?: "Movie"
            nav.push(LibraryDest(item.name ?: "genre", view, listOf(Pivot("title") {
                it.items(ItemQuery(view?.id, listOf(type), genreIds = item.id))
            })))
        }
        "Audio" -> {
            val tracks = siblings.filter { it.isAudio }
            playback.play(tracks, tracks.indexOf(item).coerceAtLeast(0), resume = false)
            nav.showPlayer()
        }
        "Photo" -> {
            val photos = siblings.filter { it.type == "Photo" }
            nav.push(PhotoDest(photos, photos.indexOf(item).coerceAtLeast(0), slideshow = false))
        }
        else -> when {
            item.isVideo -> nav.push(DetailsDest(item.id, item))
            item.isFolder -> nav.push(LibraryDest(item.name ?: "folder", view, listOf(Pivot("title") {
                it.items(dev.mediacenter.jf.data.ItemQuery(item.id, recursive = false, sortBy = "IsFolder,SortName"))
            }), slideshow = if (view?.collectionType == "photos" || view?.collectionType == "homevideos") slideshowOf(item.id) else null))
            else -> nav.push(DetailsDest(item.id))
        }
    }
}

/** "1927  ·  NR  ·  153 min  ·  ★ 8.1" */
fun metaLine(item: BaseItem): String = if (item.isChannel) {
    listOfNotNull(item.channelNumber, item.currentProgram?.name?.let { "now: $it" }).joinToString("  \u00b7  ")
} else listOfNotNull(
    if (item.type == "Episode") episodeCode(item) else null,
    item.productionYear?.toString(),
    item.officialRating,
    item.runtimeMinutes?.takeIf { it > 0 }?.let { "$it min" },
    item.communityRating?.let { "★ %.1f".format(it) },
    item.albumArtist?.takeIf { item.type == "MusicAlbum" },
    item.childCount?.takeIf { item.type == "Series" }?.let { if (it == 1) "1 season" else "$it seasons" },
).joinToString("  ·  ")

fun episodeCode(item: BaseItem): String? {
    val s = item.parentIndexNumber
    val e = item.indexNumber ?: return null
    return if (s != null) "S$s  E$e" else "E$e"
}

/** The pictures under [parentId] for a slide show (subfolders included unless turned off in settings). */
private fun slideshowOf(parentId: String): suspend (dev.mediacenter.jf.data.MediaRepository, Boolean) -> List<BaseItem> = { repo, subfolders ->
    repo.items(ItemQuery(parentId, listOf("Photo"), recursive = subfolders, limit = 2000))
}

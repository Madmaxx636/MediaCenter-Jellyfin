package dev.mediacenter.jf.data

/**
 * A fake library for trying the interface without a server. Titles are public-domain
 * films and made-up shows and albums; artwork falls back to generated placeholders.
 */
class DemoRepository(
    /** Optional local file the demo plays for every video, so the player can be tried without a server. */
    private val sampleVideo: java.io.File? = null,
) : MediaRepository {
    override val serverName = "Demo library"
    override val userName = "Guest"
    override val isDemo = true

    private val played = mutableSetOf<String>()
    private val favorites = mutableSetOf<String>()

    private val views = listOf(
        view("v-movies", "Movies", "movies"),
        view("v-tv", "TV Shows", "tvshows"),
        view("v-music", "Music", "music"),
        view("v-photos", "Photos", "homevideos"),
        view("v-livetv", "Live TV", "livetv"),
    )

    private val movies = listOf(
        Triple("Metropolis", 1927, "Sci-Fi"), Triple("Nosferatu", 1922, "Horror"),
        Triple("The General", 1926, "Comedy"), Triple("Sherlock Jr.", 1924, "Comedy"),
        Triple("The Kid", 1921, "Drama"), Triple("His Girl Friday", 1940, "Comedy"),
        Triple("Night of the Living Dead", 1968, "Horror"), Triple("Charade", 1963, "Thriller"),
        Triple("Detour", 1945, "Thriller"), Triple("D.O.A.", 1949, "Thriller"),
        Triple("Carnival of Souls", 1962, "Horror"), Triple("The Little Shop of Horrors", 1960, "Comedy"),
        Triple("A Trip to the Moon", 1902, "Sci-Fi"), Triple("The Cabinet of Dr. Caligari", 1920, "Horror"),
        Triple("Safety Last!", 1923, "Comedy"), Triple("The Phantom of the Opera", 1925, "Horror"),
        Triple("Plan 9 from Outer Space", 1957, "Sci-Fi"), Triple("The Last Man on Earth", 1964, "Sci-Fi"),
        Triple("McLintock!", 1963, "Western"), Triple("Angel and the Badman", 1947, "Western"),
        Triple("The Stranger", 1946, "Drama"), Triple("Scarlet Street", 1945, "Drama"),
        Triple("Royal Wedding", 1951, "Musical"), Triple("Gulliver's Travels", 1939, "Family"),
    ).mapIndexed { i, (title, year, genre) ->
        BaseItem(
            id = "m$i", name = title, type = "Movie", mediaType = "Video", productionYear = year,
            genres = listOf(genre), officialRating = if (i % 3 == 0) "NR" else "PG",
            communityRating = 6.1f + (i * 37 % 30) / 10f, runTimeTicks = (70L + i * 7 % 60) * BaseItem.TicksPerMinute,
            overview = "A demo entry for “$title” ($year). Connect a Jellyfin server to see your own library, " +
                "artwork and descriptions here.",
            dateCreated = "2026-0${1 + i % 9}-1${i % 9}T00:00:00Z",
            userData = UserData(playbackPositionTicks = if (i % 7 == 2) 25L * BaseItem.TicksPerMinute else 0),
            people = listOf(
                Person("Demo Director", id = "person-director", type = "Director"),
                Person("Demo Writer", id = "person-writer", type = "Writer"),
                Person("Ada Lane", id = "person-ada", role = "Eleanor", type = "Actor"),
                Person("Marcus Hale", id = "person-marcus", role = "Detective Ross", type = "Actor"),
                Person("June Park", id = "person-june", role = "The Stranger", type = "Actor"),
                Person("Omar Reyes", id = "person-omar", role = "Mayor Finch", type = "Actor"),
                Person("Tess Morgan", id = "person-tess", role = "Clara", type = "Actor"),
            ),
            taglines = listOf("Every classic has a story.").filter { i % 2 == 0 },
            studios = listOf(NameId("Public Domain Pictures")),
            criticRating = (60 + i * 13 % 40).toFloat(),
            premiereDate = "$year-0${1 + i % 9}-15T00:00:00Z",
            mediaSources = listOf(
                MediaSource(
                    id = "ms$i", container = "mkv", size = 1_800_000_000L + i * 97_000_000L,
                    mediaStreams = listOf(
                        MediaStream(type = "Video", codec = if (i % 2 == 0) "hevc" else "h264", width = 1920, height = 1080, videoRange = "SDR"),
                        MediaStream(type = "Audio", codec = "eac3", language = "eng", channels = 6, channelLayout = "5.1", isDefault = true),
                        MediaStream(type = "Audio", codec = "aac", language = "fre", channels = 2, channelLayout = "stereo"),
                        MediaStream(type = "Subtitle", codec = "subrip", language = "eng"),
                        MediaStream(type = "Subtitle", codec = "subrip", language = "spa"),
                    ),
                )
            ),
        )
    }

    private val series = listOf("Harbor Lights", "The Night Shift", "Mapmakers", "Kitchen Table", "Northbound", "Signal & Noise")
        .mapIndexed { i, title ->
            BaseItem(
                id = "s$i", name = title, type = "Series", isFolder = true, productionYear = 2015 + i,
                genres = listOf(if (i % 2 == 0) "Drama" else "Documentary"), officialRating = "TV-PG",
                overview = "A made-up series used to show off the TV library.",
                childCount = 2, userData = UserData(unplayedItemCount = 6 - i % 4),
            )
        }

    private val albums = listOf(
        "Blue Hour" to "The Lanterns", "Static Bloom" to "Neon Choir", "Low Tide" to "The Lanterns",
        "Paper Planets" to "Juniper", "Night Drive" to "Neon Choir", "Wildflower Radio" to "Juniper",
        "Cold Coffee" to "Ada & The Ghosts", "Parade" to "Ada & The Ghosts",
    ).mapIndexed { i, (title, artist) ->
        BaseItem(
            id = "a$i", name = title, type = "MusicAlbum", isFolder = true, albumArtist = artist,
            artists = listOf(artist), productionYear = 2010 + i, genres = listOf(if (i % 2 == 0) "Indie" else "Electronic"),
        )
    }

    private val collections = listOf(
        BaseItem(id = "bs0", name = "Silent Comedy Collection", type = "BoxSet", isFolder = true, childCount = 4, productionYear = 1921),
        BaseItem(id = "bs1", name = "Classic Horror Collection", type = "BoxSet", isFolder = true, childCount = 5, productionYear = 1920),
    )
    private val collectionMembers = mapOf(
        "bs0" to listOf("The General", "Sherlock Jr.", "The Kid", "Safety Last!"),
        "bs1" to listOf("Nosferatu", "The Cabinet of Dr. Caligari", "The Phantom of the Opera", "Night of the Living Dead", "Carnival of Souls"),
    )

    private fun view(id: String, name: String, collection: String) =
        BaseItem(id = id, name = name, type = "CollectionFolder", collectionType = collection, isFolder = true)

    override suspend fun views() = views

    override suspend fun items(query: ItemQuery): List<BaseItem> {
        val types = query.includeItemTypes
        var list = when {
            "BoxSet" in types -> collections
            query.parentId in collectionMembers -> movies.filter { it.name in collectionMembers.getValue(query.parentId!!) }
            "Movie" in types -> movies
            "Series" in types -> series
            // A show's (or one season's) episodes, or every episode.
            "Episode" in types -> {
                // Under a show or season just its own; under the library (or nothing), every episode.
                val within = series.any { s -> s.id == query.parentId || seasonsOf(s).any { it.id == query.parentId } }
                series.flatMap { s ->
                    seasonsOf(s).filter { !within || query.parentId == s.id || query.parentId == it.id }.flatMap { episodesOf(s, it) }
                }
            }
            "Playlist" in types -> playlists
            "MusicAlbum" in types -> albums.filter {
                (query.albumArtistIds ?: query.artistIds).let { a -> a == null || "artist-${it.albumArtist}" == a } &&
                    (query.years == null || it.productionYear.toString() == query.years)
            }
            "Audio" in types -> albums.filter { a -> albums.none { it.id == query.parentId } || a.id == query.parentId }.flatMap { tracks(it) }
            query.parentId == "v-photos" -> (0 until 18).map {
                BaseItem(id = "p$it", name = "Photo ${it + 1}", type = "Photo", mediaType = "Photo")
            }
            else -> emptyList()
        }.map(::withUserState)
        query.genreIds?.let { g -> list = list.filter { "genre-${it.genres.firstOrNull()}" == g } }
        query.personIds?.let { p -> list = list.filter { item -> item.people.any { it.id == p } }.filterIndexed { i, _ -> i % 3 != 1 } }
        when (query.filters) {
            "IsUnplayed" -> list = list.filter { it.userData?.played != true }
            "IsFavorite" -> list = list.filter { it.userData?.isFavorite == true }
        }
        list = when (query.sortBy) {
            "ProductionYear,SortName" -> list.sortedBy { it.productionYear }
            "CommunityRating,SortName" -> list.sortedBy { it.communityRating }
            "Runtime,SortName" -> list.sortedBy { it.runTimeTicks }
            "OfficialRating,SortName" -> list.sortedBy { it.officialRating }
            "IsPlayed,SortName" -> list.sortedBy { it.userData?.played == true }
            "DateCreated" -> list.sortedBy { it.dateCreated }
            "SortName" -> list.sortedBy { it.name?.removePrefix("The ") }
            "Random" -> list.shuffled()
            else -> list
        }
        if (query.descending) list = list.reversed()
        return query.limit?.let { list.take(it) } ?: list
    }

    private fun withUserState(item: BaseItem) = item.copy(
        userData = (item.userData ?: UserData()).copy(played = item.id in played, isFavorite = item.id in favorites)
    )

    override suspend fun item(id: String): BaseItem = if (id.startsWith("person-")) people(null).first { it.id == id } else demoItem(id)

    private fun demoItem(id: String): BaseItem =
        (movies + series + albums + series.flatMap { s -> seasonsOf(s).flatMap { episodesOf(s, it) } } + albums.flatMap(::tracks))
            .first { it.id == id }.let(::withUserState)

    override suspend fun resume() = movies.filter { it.resumeTicks > 0 }
    override suspend fun nextUp() = series.take(3).map { episodesOf(it, seasonsOf(it).first()).first() }
    override suspend fun latest(parentId: String) = when (parentId) {
        "v-movies" -> movies.takeLast(8).reversed()
        "v-tv" -> series.reversed()
        "v-music" -> albums.reversed()
        else -> emptyList()
    }

    private fun seasonsOf(s: BaseItem) = (1..2).map {
        BaseItem(id = "${s.id}-s$it", name = "Season $it", type = "Season", indexNumber = it, seriesId = s.id, seriesName = s.name)
    }

    private fun episodesOf(s: BaseItem, season: BaseItem) = (1..6).map { e ->
        BaseItem(
            id = "${season.id}-e$e", name = listOf("Pilot", "Crossing", "The Long Way", "Undertow", "Open Water", "Homecoming")[e - 1],
            type = "Episode", mediaType = "Video", seriesId = s.id, seriesName = s.name, seasonId = season.id,
            indexNumber = e, parentIndexNumber = season.indexNumber, runTimeTicks = 44 * BaseItem.TicksPerMinute,
            premiereDate = "2019-0${season.indexNumber}-${10 + e}T00:00:00Z",
            overview = "Episode $e of ${s.name}. In demo mode nothing plays, but everything else works.",
        )
    }

    private fun tracks(album: BaseItem) = (1..9).map { t ->
        BaseItem(
            id = "${album.id}-t$t", name = listOf("Intro", "Satellites", "Glass House", "Afterglow", "Driftwood",
                "Signal Fires", "Undercurrent", "Porchlight", "Coda")[t - 1],
            type = "Audio", mediaType = "Audio", albumId = album.id, album = album.name, albumArtist = album.albumArtist,
            artists = album.artists, indexNumber = t, runTimeTicks = (150L + t * 23) * 10_000_000L,
        )
    }

    override suspend fun seasons(seriesId: String) = seasonsOf(series.first { it.id == seriesId })
    override suspend fun episodes(seriesId: String, seasonId: String?): List<BaseItem> {
        val s = series.first { it.id == seriesId }
        return seasonsOf(s).filter { seasonId == null || it.id == seasonId }.flatMap { episodesOf(s, it) }.map(::withUserState)
    }

    override suspend fun genres(parentId: String, itemType: String): List<BaseItem> {
        val source = when (itemType) { "Movie" -> movies; "Series" -> series; else -> albums }
        return source.flatMap { it.genres }.distinct().sorted().map { BaseItem(id = "genre-$it", name = it, type = "Genre") }
    }

    override suspend fun artists(parentId: String) = albumArtists(parentId)

    override suspend fun people(role: String?): List<BaseItem> = movies.first().people
        .filter { role == null || it.type == role }
        .map {
            BaseItem(
                id = it.id ?: "", name = it.name, type = "Person",
                overview = "${it.name} is a made-up ${it.type?.lowercase()} used by the demo library. " +
                    "On your server, this page shows the person's biography from Jellyfin's metadata.",
                premiereDate = "1970-05-12T00:00:00Z",
            )
        }

    override suspend fun composers(parentId: String) = listOf("Clara Voss", "Ennio Hart", "Mira Sol")
        .map { BaseItem(id = "composer-$it", name = it, type = "MusicComposer") }

    override suspend fun years(parentId: String, itemType: String) =
        (if (itemType == "Movie") movies else albums).mapNotNull { it.productionYear }.distinct().sortedDescending()
            .map { BaseItem(id = "year-$it", name = "$it", type = "Year") }

    override suspend fun tags(parentId: String, itemType: String) = listOf("family", "holidays", "outdoors")

    private val playlists = mutableListOf<BaseItem>()

    override suspend fun createPlaylist(name: String, ids: List<String>): String {
        val id = "pl${playlists.size}"
        playlists += BaseItem(id = id, name = name, type = "Playlist", isFolder = true, childCount = ids.size)
        return id
    }

    override suspend fun albumArtists(parentId: String) =
        albums.mapNotNull { it.albumArtist }.distinct().sorted().map { BaseItem(id = "artist-$it", name = it, type = "MusicArtist") }

    override suspend fun search(term: String): List<BaseItem> {
        val t = term.trim().lowercase()
        if (t.isEmpty()) return emptyList()
        val episodes = series.flatMap { s -> seasonsOf(s).flatMap { episodesOf(s, it) } }
        val people = movies.first().people.map { BaseItem(id = it.id ?: "", name = it.name, type = "Person") }
        return (movies + series + episodes + albums + albums.flatMap(::tracks) + people)
            .filter { it.name?.lowercase()?.contains(t) == true }.map(::withUserState)
    }

    override suspend fun similar(id: String): List<BaseItem> {
        val genre = movies.firstOrNull { it.id == id }?.genres?.firstOrNull()
        return (movies.filter { it.id != id && it.genres.firstOrNull() == genre } + movies.filter { it.id != id }.take(4)).distinctBy { it.id }
    }

    override suspend fun setPlayed(id: String, played: Boolean) {
        if (played) this.played += id else this.played -= id
    }

    override suspend fun setFavorite(id: String, favorite: Boolean) {
        if (favorite) favorites += id else favorites -= id
    }

    // --- Live TV: a dozen made-up channels with a schedule generated around the current time.

    private val channelList = listOf(
        "2.1" to "KDMO", "4.1" to "Metro 4", "5.1" to "Channel 5", "7.1" to "WNDR", "9.1" to "Nine News", "11.1" to "PBS Demo",
        "13.1" to "Classic Movies", "20.1" to "Sports One", "24.1" to "Kids Zone", "30.1" to "Weather Now",
        "36.1" to "Cooking Plus", "44.1" to "Discovery Demo",
    ).mapIndexed { i, (num, name) ->
        BaseItem(id = "ch$i", name = name, type = "TvChannel", mediaType = "Video", channelNumber = num)
    }

    private val showNames = listOf(
        "Evening News", "Harbor Lights", "Kitchen Table", "The Night Shift", "Late Movie: Charade", "Weather Update",
        "Mapmakers", "College Football", "Cartoon Hour", "Northbound", "Nature Walk", "Quiz Night", "Signal & Noise",
        "Morning Show", "Garden Talk", "Documentary: Tides", "Movie: Metropolis", "Movie: Detour", "Movie: His Girl Friday",
    )
    private val scheduleStart = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS).minus(java.time.Duration.ofHours(2))
    private val schedule: List<BaseItem> by lazy {
        channelList.flatMapIndexed { c, ch ->
            var t = scheduleStart
            var n = 0
            buildList {
                while (t.isBefore(scheduleStart.plus(java.time.Duration.ofHours(30)))) {
                    val minutes = listOf(30L, 60L, 30L, 90L, 60L, 120L)[(c * 7 + n) % 6]
                    val end = t.plus(java.time.Duration.ofMinutes(minutes))
                    val name = showNames[(c * 5 + n * 3) % showNames.size]
                    add(
                        BaseItem(
                            id = "p$c-$n", name = name, type = "Program", channelId = ch.id, channelName = ch.name,
                            channelNumber = ch.channelNumber, startDate = t.toString(), endDate = end.toString(),
                            episodeTitle = if (n % 3 == 0) null else listOf("Pilot", "Crossing", "Undertow", "Homecoming")[n % 4],
                            overview = "A made-up program on ${ch.name}. Connect a Jellyfin server with a tuner to see your real guide.",
                            isHD = (c + n) % 3 != 0, isSeries = n % 2 == 0, isNews = "News" in name, isSports = "Football" in name,
                            isMovie = "Movie" in name, isKids = "Cartoon" in name,
                        )
                    )
                    t = end
                    n++
                }
            }
        }
    }
    private val timers = mutableSetOf<String>()
    private val seriesTimers = mutableSetOf<String>()

    private fun withTimers(p: BaseItem) = p.copy(
        timerId = if (p.id in timers || p.name in seriesTimers) "t-${p.id}" else null,
        seriesTimerId = if (p.name in seriesTimers) "s-${p.name}" else null,
    )

    override suspend fun channels() = channelList.map { ch ->
        val now = java.time.Instant.now()
        ch.copy(currentProgram = schedule.firstOrNull { it.channelId == ch.id && it.start!! <= now && it.end!! > now })
    }

    override suspend fun programs(from: java.time.Instant, to: java.time.Instant) =
        schedule.filter { it.end!! > from && it.start!! < to }.map(::withTimers)

    override suspend fun program(id: String) = withTimers(schedule.first { it.id == id })

    private val padding = mutableMapOf<String, Pair<Int, Int>>()

    override suspend fun tvMovies(mode: String): List<BaseItem> {
        val now = java.time.Instant.now()
        val films = schedule.filter { it.isMovie == true || it.name?.contains("Movie") == true }.map(::withTimers)
        return when (mode) {
            "now" -> films.filter { it.start!! <= now && it.end!! > now }
            "next" -> films.filter { it.start!! > now }.sortedBy { it.start }
            else -> films.distinctBy { it.name }
        }
    }

    override suspend fun scheduled() = schedule.map(::withTimers).filter { it.timerId != null }.sortedBy { it.start }

    override suspend fun recordingPadding(timerId: String) = padding[timerId] ?: (1 to 5)

    override suspend fun setRecordingPadding(timerId: String, earlyMinutes: Int, lateMinutes: Int) {
        padding[timerId] = earlyMinutes to lateMinutes
    }

    override suspend fun recordings() = series.take(2).flatMap { s -> episodesOf(s, seasonsOf(s).first()).take(2) }

    override suspend fun record(program: BaseItem, series: Boolean) {
        if (series) seriesTimers += program.name ?: "" else timers += program.id
    }

    override suspend fun cancelRecording(program: BaseItem, series: Boolean) {
        if (series) seriesTimers -= program.name ?: "" else timers -= program.id
    }

    override fun imageUrl(itemId: String, kind: ImageKind, tag: String?, maxHeight: Int): String? = null

    override suspend fun resolveStream(item: BaseItem, mode: StreamMode): Stream {
        val file = sampleVideo?.takeIf { it.exists() }
            ?: error("Demo mode has no media to play. Connect a Jellyfin server from Tasks → settings.")
        return Stream(url = android.net.Uri.fromFile(file).toString(), mediaSourceId = null, playSessionId = null, isTranscode = false, isHls = false)
    }

    /** The sample video gets an intro at 0:20–0:45 and credits in its last minute, to try the skip buttons. */
    override suspend fun segments(itemId: String): List<MediaSegment> =
        if (sampleVideo?.exists() == true) listOf(
            MediaSegment("intro", "Intro", 20 * 10_000_000L, 45 * 10_000_000L),
            MediaSegment("outro", "Outro", 540 * 10_000_000L, 600 * 10_000_000L),
        ) else emptyList()

    override suspend fun reportStart(report: PlaybackReport) = Unit
    override suspend fun reportProgress(report: PlaybackReport) = Unit
    override suspend fun reportStop(report: PlaybackReport) = Unit
}

package dev.mediacenter.jf.data

data class Stream(
    val url: String,
    val mediaSourceId: String?,
    val playSessionId: String?,
    val isTranscode: Boolean,
    val isHls: Boolean,
    val liveStreamId: String? = null,
    /** Subtitles fetched alongside the video (text subtitles of a converted stream, or separate subtitle files). */
    val subtitles: List<SideSubtitle> = emptyList(),
    /** The soundtrack that plays by default, e.g. "flac/6", so a format this TV mishandles is remembered. */
    val audioKey: String? = null,
)

/** How much of a file the server should convert. */
enum class StreamMode {
    /** Play it as it is where this TV can. */
    Direct,
    /** Keep the picture as it is; convert only the sound. */
    ConvertAudio,
    /** Convert everything. */
    ConvertAll,
}

/** A subtitle track loaded from its own address rather than from inside the video. */
data class SideSubtitle(val url: String, val mimeType: String, val language: String?, val label: String?, val isDefault: Boolean, val isForced: Boolean)

data class PlaybackReport(
    val itemId: String,
    val stream: Stream,
    val positionTicks: Long,
    val isPaused: Boolean,
)

/** Everything the UI needs from a media server. [JellyfinRepository] talks to a real one, [DemoRepository] fakes it. */
interface MediaRepository {
    val serverName: String
    val userName: String
    val isDemo: Boolean get() = false

    /** Downloads [bytes] of test data from the server for the speed check; null where there's no server. */
    suspend fun speedTest(bytes: Int): Long? = null

    suspend fun views(): List<BaseItem>
    suspend fun items(query: ItemQuery): List<BaseItem>
    suspend fun item(id: String): BaseItem
    suspend fun resume(): List<BaseItem>
    suspend fun nextUp(): List<BaseItem>
    suspend fun latest(parentId: String): List<BaseItem>
    suspend fun seasons(seriesId: String): List<BaseItem>
    suspend fun episodes(seriesId: String, seasonId: String?): List<BaseItem>
    suspend fun genres(parentId: String, itemType: String): List<BaseItem>
    suspend fun albumArtists(parentId: String): List<BaseItem>
    /** Every artist credited on a track (not just album artists). */
    suspend fun artists(parentId: String): List<BaseItem>
    suspend fun composers(parentId: String): List<BaseItem>
    /** People in the library, optionally only one role ("Actor", "Director", "Writer", "Producer", "Composer"). */
    suspend fun people(role: String?): List<BaseItem>
    /** Years that have items of [itemType], newest first, as items named by year. */
    suspend fun years(parentId: String, itemType: String): List<BaseItem>
    /** Tags used by items of [itemType] in a library (for "tags" in pictures). */
    suspend fun tags(parentId: String, itemType: String): List<String>
    /** Saves [ids] as a new audio playlist; returns its id. */
    suspend fun createPlaylist(name: String, ids: List<String>): String
    suspend fun similar(id: String): List<BaseItem>
    /** Titles, episodes, music and people whose names match [term]. */
    suspend fun search(term: String): List<BaseItem>
    suspend fun setPlayed(id: String, played: Boolean)
    suspend fun setFavorite(id: String, favorite: Boolean)

    // Live TV
    suspend fun channels(): List<BaseItem>
    suspend fun programs(from: java.time.Instant, to: java.time.Instant): List<BaseItem>
    suspend fun program(id: String): BaseItem
    suspend fun recordings(): List<BaseItem>
    /** Movies on TV: "now" (airing), "next" (starting within a day) or "top" (best rated this week). */
    suspend fun tvMovies(mode: String): List<BaseItem>
    /** Programs scheduled to record, as program items with timerId set. */
    suspend fun scheduled(): List<BaseItem>
    /** Start-early / stop-late minutes for a scheduled recording. */
    suspend fun recordingPadding(timerId: String): Pair<Int, Int>
    suspend fun setRecordingPadding(timerId: String, earlyMinutes: Int, lateMinutes: Int)
    suspend fun record(program: BaseItem, series: Boolean)
    suspend fun cancelRecording(program: BaseItem, series: Boolean)

    /** Uploads a client log to the server; returns the saved file name. */
    suspend fun uploadLog(text: String): String = error("Log upload needs a Jellyfin server")

    suspend fun segments(itemId: String): List<MediaSegment> = emptyList()
    suspend fun trickplay(item: BaseItem, mediaSourceId: String?): Trickplay? = null

    /** One trickplay sprite sheet (a JPEG grid of frames), fetched with the viewer's sign-in. */
    suspend fun trickplaySheet(trickplay: Trickplay, sheet: Int): ByteArray = error("No trickplay without a server")

    fun imageUrl(itemId: String, kind: ImageKind, tag: String?, maxHeight: Int): String?

    suspend fun resolveStream(item: BaseItem, mode: StreamMode = StreamMode.Direct): Stream
    suspend fun reportStart(report: PlaybackReport)
    suspend fun reportProgress(report: PlaybackReport)
    suspend fun reportStop(report: PlaybackReport)

    /**
     * The Media Center plugin's settings, notices and branding for this user; null when the server
     * doesn't have the plugin. Throws when the server can't be reached (keep what was known).
     */
    suspend fun serverControl(): ServerClientConfig? = null

    /** Tells the plugin which settings this app has, for its settings page. */
    suspend fun sendSettingsCatalog(catalog: SettingsCatalog) = Unit

    /** One of the plugin's branding files (a sound, the logo, the backdrop). */
    suspend fun serverAsset(name: String): ByteArray = error("No server")

    /** Keeps a playback session (a conversion, a live channel) alive while nothing new is being fetched. */
    suspend fun ping(playSessionId: String) = Unit
}

// Image choices that match what Media Center showed in each place.

fun MediaRepository.posterUrl(item: BaseItem, height: Int = 480): String? =
    item.imageTags["Primary"]?.let { imageUrl(item.id, ImageKind.Primary, it, height) }
        ?: item.seriesPrimaryImageTag?.let { tag -> item.seriesId?.let { imageUrl(it, ImageKind.Primary, tag, height) } }
        ?: item.albumPrimaryImageTag?.let { tag -> item.albumId?.let { imageUrl(it, ImageKind.Primary, tag, height) } }

fun MediaRepository.backdropUrl(item: BaseItem, height: Int = 720): String? =
    item.backdropImageTags.firstOrNull()?.let { imageUrl(item.id, ImageKind.Backdrop, it, height) }
        ?: item.parentBackdropImageTags.firstOrNull()?.let { tag ->
            item.parentBackdropItemId?.let { imageUrl(it, ImageKind.Backdrop, tag, height) }
        }

fun MediaRepository.thumbUrl(item: BaseItem, height: Int = 360): String? =
    (if (item.type == "Episode") item.imageTags["Primary"]?.let { imageUrl(item.id, ImageKind.Primary, it, height) } else null)
        ?: item.imageTags["Thumb"]?.let { imageUrl(item.id, ImageKind.Thumb, it, height) }
        ?: item.parentThumbImageTag?.let { tag -> item.parentThumbItemId?.let { imageUrl(it, ImageKind.Thumb, tag, height) } }
        ?: backdropUrl(item, height)
        ?: posterUrl(item, height)

fun MediaRepository.channelLogoUrl(item: BaseItem, height: Int = 120): String? =
    if (item.isChannel) item.imageTags["Primary"]?.let { imageUrl(item.id, ImageKind.Primary, it, height) }
    else item.channelPrimaryImageTag?.let { tag -> item.channelId?.let { imageUrl(it, ImageKind.Primary, tag, height) } }

package dev.mediacenter.jf.data

import kotlinx.serialization.Serializable
import java.time.Instant

/** Subset of Jellyfin's BaseItemDto. Field names map to the server's PascalCase via [JellyfinJson]. */
@Serializable
data class BaseItem(
    val id: String,
    val name: String? = null,
    val type: String? = null,
    val collectionType: String? = null,
    val mediaType: String? = null,
    val overview: String? = null,
    val productionYear: Int? = null,
    val premiereDate: String? = null,
    val dateCreated: String? = null,
    val officialRating: String? = null,
    val communityRating: Float? = null,
    val runTimeTicks: Long? = null,
    val genres: List<String> = emptyList(),
    val imageTags: Map<String, String> = emptyMap(),
    val backdropImageTags: List<String> = emptyList(),
    val parentBackdropItemId: String? = null,
    val parentBackdropImageTags: List<String> = emptyList(),
    val parentThumbItemId: String? = null,
    val parentThumbImageTag: String? = null,
    val seriesId: String? = null,
    val seriesName: String? = null,
    val seriesPrimaryImageTag: String? = null,
    val seasonId: String? = null,
    val indexNumber: Int? = null,
    val parentIndexNumber: Int? = null,
    val albumId: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val artists: List<String> = emptyList(),
    val albumPrimaryImageTag: String? = null,
    val childCount: Int? = null,
    val isFolder: Boolean = false,
    val userData: UserData? = null,
    val people: List<Person> = emptyList(),
    val primaryImageAspectRatio: Double? = null,
    // Live TV
    val channelId: String? = null,
    val channelName: String? = null,
    val channelNumber: String? = null,
    val channelPrimaryImageTag: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val episodeTitle: String? = null,
    val isHD: Boolean? = null,
    val isSeries: Boolean? = null,
    val isMovie: Boolean? = null,
    val isNews: Boolean? = null,
    val isKids: Boolean? = null,
    val isSports: Boolean? = null,
    val isLive: Boolean? = null,
    val isPremiere: Boolean? = null,
    val timerId: String? = null,
    val seriesTimerId: String? = null,
    val currentProgram: BaseItem? = null,
    // Details
    val taglines: List<String> = emptyList(),
    val studios: List<NameId> = emptyList(),
    val criticRating: Float? = null,
    val mediaStreams: List<MediaStream> = emptyList(),
    val mediaSources: List<MediaSource> = emptyList(),
    val trickplay: Map<String, Map<String, TrickplayInfo>> = emptyMap(),
    val chapters: List<Chapter> = emptyList(),
) {
    val start: Instant? by lazy { startDate?.let(::parseInstant) }
    val end: Instant? by lazy { endDate?.let(::parseInstant) }
    val isChannel get() = type == "TvChannel"
    val isProgram get() = type == "Program"

    val isVideo get() = mediaType == "Video" || type in VideoTypes
    val isAudio get() = mediaType == "Audio" || type == "Audio"
    val runtimeMinutes get() = runTimeTicks?.let { (it / TicksPerMinute).toInt() }
    val resumeTicks get() = userData?.playbackPositionTicks?.takeIf { it > 0 } ?: 0L

    companion object {
        const val TicksPerMs = 10_000L
        const val TicksPerMinute = 600_000_000L
        val VideoTypes = setOf("Movie", "Episode", "Video", "MusicVideo", "Trailer", "TvChannel", "Recording")
    }
}

private fun parseInstant(s: String): Instant? = runCatching { Instant.parse(if (s.endsWith("Z") || '+' in s.substringAfter('T')) s else s + "Z") }.getOrNull()

/** A subtitle the server's subtitle plugins (OpenSubtitles and the like) found online for a video. */
@Serializable
data class RemoteSubtitle(
    val id: String = "",
    val providerName: String? = null,
    val name: String? = null,
    val format: String? = null,
    val downloadCount: Int? = null,
    val communityRating: Float? = null,
    val isHashMatch: Boolean? = null,
    val hearingImpaired: Boolean? = null,
    val forced: Boolean? = null,
    val machineTranslated: Boolean? = null,
    val aiTranslated: Boolean? = null,
)

/** A song's lyrics as Jellyfin keeps them: lines, each with when it's sung where the lyrics are timed. */
@Serializable
data class Lyrics(val lyrics: List<LyricLine> = emptyList()) {
    val timed get() = lyrics.any { it.start != null }
}

@Serializable
data class LyricLine(val text: String = "", val start: Long? = null) {
    val startMs get() = start?.let { it / BaseItem.TicksPerMs }
}

/** A chapter of a film or episode: where it starts, and its name. */
@Serializable
data class Chapter(val startPositionTicks: Long = 0, val name: String? = null) {
    val startMs get() = startPositionTicks / BaseItem.TicksPerMs
}

@Serializable
data class UserData(
    val playbackPositionTicks: Long = 0,
    val playCount: Int = 0,
    val isFavorite: Boolean = false,
    val played: Boolean = false,
    val unplayedItemCount: Int? = null,
    val playedPercentage: Double? = null,
)

@Serializable
data class Person(
    val name: String? = null,
    val id: String? = null,
    val role: String? = null,
    val type: String? = null,
    val primaryImageTag: String? = null,
)

@Serializable
data class NameId(val name: String? = null, val id: String? = null)

@Serializable
data class MediaStream(
    val type: String? = null,
    val codec: String? = null,
    val language: String? = null,
    val displayTitle: String? = null,
    val title: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val channels: Int? = null,
    val channelLayout: String? = null,
    val videoRange: String? = null,
    val profile: String? = null,
    val isDefault: Boolean = false,
    val isExternal: Boolean = false,
    val isForced: Boolean = false,
    val bitRate: Long? = null,
    val bitDepth: Int? = null,
    val averageFrameRate: Float? = null,
    val realFrameRate: Float? = null,
    val sampleRate: Int? = null,
    val videoRangeType: String? = null,
    val aspectRatio: String? = null,
    val isInterlaced: Boolean = false,
    /** The stream's number within the file, as Jellyfin counts them. */
    val index: Int? = null,
    /** For subtitles: how the server delivers them ("Embed", "External", "Encode", "Hls") and, if external, where. */
    val deliveryMethod: String? = null,
    val deliveryUrl: String? = null,
    val level: Double? = null,
)

@Serializable
data class ItemsResult(
    val items: List<BaseItem> = emptyList(),
    val totalRecordCount: Int = 0,
)

@Serializable
data class PublicSystemInfo(
    val serverName: String? = null,
    val version: String? = null,
    val id: String? = null,
)

@Serializable
data class UserDto(
    val id: String,
    val name: String? = null,
    val primaryImageTag: String? = null,
    val hasPassword: Boolean = true,
)

@Serializable
data class AuthResult(
    val user: UserDto,
    val accessToken: String,
    val serverId: String? = null,
)

@Serializable
data class QuickConnectState(
    val secret: String,
    val code: String,
    val authenticated: Boolean = false,
)

@Serializable
data class PlaybackInfo(
    val mediaSources: List<MediaSource> = emptyList(),
    val playSessionId: String? = null,
    val errorCode: String? = null,
)

@Serializable
data class MediaSource(
    val id: String,
    val container: String? = null,
    val supportsDirectPlay: Boolean = false,
    val supportsDirectStream: Boolean = false,
    val supportsTranscoding: Boolean = false,
    val transcodingUrl: String? = null,
    val transcodingSubProtocol: String? = null,
    val liveStreamId: String? = null,
    val size: Long? = null,
    val bitrate: Long? = null,
    val mediaStreams: List<MediaStream> = emptyList(),
)

/** How a list of items should be fetched. Mirrors the query parameters of GET /Items. */
data class ItemQuery(
    val parentId: String? = null,
    val includeItemTypes: List<String> = emptyList(),
    val recursive: Boolean = true,
    val sortBy: String? = "SortName",
    val descending: Boolean = false,
    val filters: String? = null,
    val genreIds: String? = null,
    val albumArtistIds: String? = null,
    val personIds: String? = null,
    val artistIds: String? = null,
    val years: String? = null,
    val tags: String? = null,
    val mediaTypes: String? = null,
    val limit: Int? = null,
    /** Parental ratings, comma-separated ("PG-13,R"). */
    val officialRatings: String? = null,
    /** Video filters: resolution by width, 3D, 4K, and the kind of source (Dvd, BluRay, Iso). */
    val minWidth: Int? = null,
    val maxWidth: Int? = null,
    val is3D: Boolean? = null,
    val is4K: Boolean? = null,
    val videoTypes: String? = null,
    /** Only items that have these images ("Backdrop"). */
    val hasImages: String? = null,
)

enum class ImageKind { Primary, Backdrop, Thumb, Logo }

/** A marked part of a video (Jellyfin 10.10+ media segments): intro, credits, commercial, recap or preview. */
@Serializable
data class MediaSegment(
    val id: String? = null,
    val type: String = "Unknown",
    val startTicks: Long = 0,
    val endTicks: Long = 0,
) {
    val startMs get() = startTicks / BaseItem.TicksPerMs
    val endMs get() = endTicks / BaseItem.TicksPerMs
    val label
        get() = when (type) {
            "Intro" -> "skip intro"
            "Outro" -> "skip credits"
            "Commercial" -> "skip advert"
            "Recap" -> "skip recap"
            "Preview" -> "skip preview"
            else -> "skip"
        }
}

@Serializable
data class SegmentsResult(val items: List<MediaSegment> = emptyList())

@Serializable
data class TrickplayInfo(
    val width: Int = 0,
    val height: Int = 0,
    val tileWidth: Int = 10,
    val tileHeight: Int = 10,
    val thumbnailCount: Int = 0,
    val interval: Int = 10_000,
)

/** Where to find the trickplay preview for any moment of a video: which sheet, and where in it. */
class Trickplay(val itemId: String, val mediaSourceId: String?, val info: TrickplayInfo) {
    val perSheet get() = info.tileWidth * info.tileHeight

    /** The frame shown for [positionMs] (one every [TrickplayInfo.interval]). */
    fun frameIndex(positionMs: Long) = (positionMs / info.interval.coerceAtLeast(1)).toInt().coerceIn(0, (info.thumbnailCount - 1).coerceAtLeast(0))

    /** The sprite sheet holding frame [index], and the frame's column and row in it. */
    fun cell(index: Int): Triple<Int, Int, Int> {
        val cell = index % perSheet
        return Triple(index / perSheet, cell % info.tileWidth, cell / info.tileWidth)
    }
}

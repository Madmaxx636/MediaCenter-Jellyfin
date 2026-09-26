package dev.mediacenter.jf.data

import dev.mediacenter.jf.playback.AutoTune
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class JellyfinRepository(
    private val api: JellyfinApi,
    private val session: Session,
    private val deviceId: String,
    private val settings: Settings,
) : MediaRepository {

    override val serverName get() = session.serverName
    override val userName get() = session.userName

    private val user = mapOf("userId" to session.userId)

    override suspend fun views(): List<BaseItem> =
        api.get<ItemsResult>(session, "/UserViews", user).items

    override suspend fun items(query: ItemQuery): List<BaseItem> =
        api.get<ItemsResult>(
            session, "/Items", user + mapOf(
                "parentId" to query.parentId,
                "includeItemTypes" to query.includeItemTypes.joinToString(",").ifEmpty { null },
                "recursive" to query.recursive,
                "sortBy" to query.sortBy,
                "sortOrder" to if (query.descending) "Descending" else "Ascending",
                "filters" to query.filters,
                "genreIds" to query.genreIds,
                "albumArtistIds" to query.albumArtistIds,
                "personIds" to query.personIds,
                "artistIds" to query.artistIds,
                "years" to query.years,
                "tags" to query.tags,
                "mediaTypes" to query.mediaTypes,
                "limit" to query.limit,
                "officialRatings" to query.officialRatings,
                "minWidth" to query.minWidth,
                "maxWidth" to query.maxWidth,
                "is3D" to query.is3D,
                "is4K" to query.is4K,
                "videoTypes" to query.videoTypes,
                "fields" to ListFields,
                "enableImageTypes" to ImageTypes,
                "imageTypeLimit" to 1,
                "enableTotalRecordCount" to false,
            )
        ).items

    override suspend fun item(id: String): BaseItem = api.get(session, "/Items/$id", user)

    override suspend fun resume(): List<BaseItem> =
        api.get<ItemsResult>(session, "/UserItems/Resume", user + mapOf("mediaTypes" to "Video", "limit" to 24, "fields" to ListFields)).items

    override suspend fun nextUp(parentId: String?, limit: Int): List<BaseItem> =
        api.get<ItemsResult>(session, "/Shows/NextUp", user + mapOf("parentId" to parentId, "limit" to limit, "fields" to ListFields)).items

    override suspend fun latest(parentId: String): List<BaseItem> =
        api.get(session, "/Items/Latest", user + mapOf("parentId" to parentId, "limit" to 24, "fields" to ListFields))

    override suspend fun seasons(seriesId: String): List<BaseItem> =
        api.get<ItemsResult>(session, "/Shows/$seriesId/Seasons", user + mapOf("fields" to ListFields)).items

    override suspend fun episodes(seriesId: String, seasonId: String?): List<BaseItem> =
        api.get<ItemsResult>(session, "/Shows/$seriesId/Episodes", user + mapOf("seasonId" to seasonId, "fields" to "$ListFields,Overview")).items

    override suspend fun genres(parentId: String, itemType: String): List<BaseItem> =
        api.get<ItemsResult>(
            session, "/Genres",
            user + mapOf("parentId" to parentId, "includeItemTypes" to itemType, "sortBy" to "SortName")
        ).items

    override suspend fun albumArtists(parentId: String): List<BaseItem> =
        api.get<ItemsResult>(
            session, "/Artists/AlbumArtists",
            user + mapOf("parentId" to parentId, "sortBy" to "SortName", "fields" to ListFields)
        ).items

    override suspend fun search(term: String): List<BaseItem> = kotlinx.coroutines.coroutineScope {
        val items = async {
            api.get<ItemsResult>(
                session, "/Items",
                user + mapOf(
                    "searchTerm" to term, "recursive" to true, "limit" to 120,
                    "includeItemTypes" to "Movie,Series,Episode,MusicAlbum,Audio,MusicArtist,Playlist,BoxSet,Photo,PhotoAlbum,Video",
                    "fields" to "$ListFields,Overview", "enableImageTypes" to ImageTypes, "imageTypeLimit" to 1,
                    "enableTotalRecordCount" to false,
                ),
            ).items
        }
        val people = async {
            runCatching {
                api.get<ItemsResult>(session, "/Persons", user + mapOf("searchTerm" to term, "limit" to 30, "enableImageTypes" to "Primary")).items
            }.getOrDefault(emptyList())
        }
        items.await() + people.await()
    }

    override suspend fun similar(id: String): List<BaseItem> =
        api.get<ItemsResult>(session, "/Items/$id/Similar", user + mapOf("limit" to 16, "fields" to ListFields)).items

    override suspend fun artists(parentId: String): List<BaseItem> =
        api.get<ItemsResult>(session, "/Artists", user + mapOf("parentId" to parentId, "sortBy" to "SortName", "fields" to ListFields)).items

    override suspend fun people(role: String?): List<BaseItem> =
        api.get<ItemsResult>(
            session, "/Persons",
            user + mapOf("personTypes" to role, "limit" to 1500, "enableImageTypes" to "Primary", "fields" to "PrimaryImageAspectRatio"),
        ).items.map { it.copy(type = "Person") }

    override suspend fun composers(parentId: String): List<BaseItem> =
        api.get<ItemsResult>(session, "/Persons", user + mapOf("personTypes" to "Composer", "enableImageTypes" to "Primary")).items
            .map { it.copy(type = "MusicComposer") }

    override suspend fun years(parentId: String, itemType: String): List<BaseItem> =
        api.get<ItemsResult>(
            session, "/Years",
            user + mapOf("parentId" to parentId, "includeItemTypes" to itemType, "recursive" to true, "sortBy" to "SortName", "sortOrder" to "Descending"),
        ).items.map { it.copy(type = "Year") }

    override suspend fun officialRatings(parentId: String, itemType: String) = filterValues(parentId, itemType, "OfficialRatings")

    override suspend fun tags(parentId: String, itemType: String) = filterValues(parentId, itemType, "Tags")

    /** One of the lists the server keeps of what a library's items use (tags, parental ratings); empty if it can't say. */
    private suspend fun filterValues(parentId: String, itemType: String, key: String): List<String> =
        runCatching {
            api.get<JsonObject>(session, "/Items/Filters", user + mapOf("parentId" to parentId, "includeItemTypes" to itemType))[key]
                ?.let { el -> (el as? kotlinx.serialization.json.JsonArray)?.map { (it as kotlinx.serialization.json.JsonPrimitive).content } }.orEmpty()
        }.getOrDefault(emptyList())

    override suspend fun createPlaylist(name: String, ids: List<String>): String {
        val result = api.post<JsonObject>(
            session, "/Playlists",
            body = buildJsonObject {
                put("Name", name)
                put("UserId", session.userId)
                put("MediaType", "Audio")
                put("Ids", buildJsonArray { ids.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            },
        )
        return result["Id"]?.toString()?.trim('"') ?: ""
    }

    override suspend fun setPlayed(id: String, played: Boolean) {
        if (played) api.post<JsonObject>(session, "/UserPlayedItems/$id", user) else api.delete(session, "/UserPlayedItems/$id", user)
    }

    override suspend fun setFavorite(id: String, favorite: Boolean) {
        if (favorite) api.post<JsonObject>(session, "/UserFavoriteItems/$id", user) else api.delete(session, "/UserFavoriteItems/$id", user)
    }

    override suspend fun channels(): List<BaseItem> =
        api.get<ItemsResult>(
            session, "/LiveTv/Channels",
            user + mapOf(
                "addCurrentProgram" to true, "enableImages" to true, "imageTypeLimit" to 1,
                "sortBy" to "SortName", "enableFavoriteSorting" to true, "fields" to "Overview",
            ),
        ).items.sortedWith(compareBy({ it.channelNumber?.substringBefore('.')?.toIntOrNull() ?: Int.MAX_VALUE }, { it.channelNumber?.substringAfter('.', "0")?.toIntOrNull() ?: 0 }))

    override suspend fun programs(from: java.time.Instant, to: java.time.Instant): List<BaseItem> =
        api.get<ItemsResult>(
            session, "/LiveTv/Programs",
            user + mapOf(
                "minEndDate" to from.toString(), "maxStartDate" to to.toString(), "sortBy" to "StartDate",
                "enableImages" to true, "imageTypeLimit" to 1, "enableTotalRecordCount" to false, "fields" to "Overview",
            ),
        ).items

    override suspend fun program(id: String): BaseItem = api.get(session, "/LiveTv/Programs/$id", user)

    override suspend fun recordings(): List<BaseItem> =
        api.get<ItemsResult>(session, "/LiveTv/Recordings", user + mapOf("fields" to ListFields, "enableImages" to true)).items

    override suspend fun record(program: BaseItem, series: Boolean) {
        val defaults = api.get<JsonObject>(session, "/LiveTv/Timers/Defaults", mapOf("programId" to program.id))
        // Apply this device's recording defaults on top of the server's.
        val timer = JsonObject(defaults + mapOf(
            "PrePaddingSeconds" to kotlinx.serialization.json.JsonPrimitive(settings.recordStartEarly.value * 60),
            "PostPaddingSeconds" to kotlinx.serialization.json.JsonPrimitive(settings.recordStopLate.value * 60),
            "IsPrePaddingRequired" to kotlinx.serialization.json.JsonPrimitive(settings.recordStartEarly.value > 0),
            "IsPostPaddingRequired" to kotlinx.serialization.json.JsonPrimitive(settings.recordStopLate.value > 0),
            "RecordNewOnly" to kotlinx.serialization.json.JsonPrimitive(settings.recordNewOnly.value),
        ))
        api.post<Unit>(session, if (series) "/LiveTv/SeriesTimers" else "/LiveTv/Timers", body = timer)
    }

    override suspend fun tvMovies(mode: String): List<BaseItem> {
        val now = java.time.Instant.now()
        val params = when (mode) {
            "now" -> mapOf("isAiring" to true, "sortBy" to "SortName")
            "next" -> mapOf("hasAired" to false, "isAiring" to false, "minStartDate" to now.toString(),
                "maxStartDate" to now.plus(java.time.Duration.ofHours(24)).toString(), "sortBy" to "StartDate")
            else -> mapOf("minEndDate" to now.toString(), "maxStartDate" to now.plus(java.time.Duration.ofDays(7)).toString(),
                "sortBy" to "CommunityRating", "sortOrder" to "Descending")
        }
        return api.get<ItemsResult>(
            session, "/LiveTv/Programs",
            user + params + mapOf("isMovie" to true, "limit" to 150, "enableImages" to true, "imageTypeLimit" to 1, "fields" to "Overview", "enableTotalRecordCount" to false),
        ).items.distinctBy { it.name to it.channelId }
    }

    override suspend fun scheduled(): List<BaseItem> {
        val timers = api.get<JsonObject>(session, "/LiveTv/Timers", mapOf("isActive" to false))["Items"] as? kotlinx.serialization.json.JsonArray
            ?: return emptyList()
        return timers.mapNotNull { t ->
            val o = t as? JsonObject ?: return@mapNotNull null
            val id = (o["Id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val info = o["ProgramInfo"]?.let { runCatching { JellyfinJson.decodeFromJsonElement(BaseItem.serializer(), it) }.getOrNull() }
                ?: runCatching { JellyfinJson.decodeFromJsonElement(BaseItem.serializer(), o) }.getOrNull()?.copy(type = "Program")
            info?.copy(timerId = id)
        }.sortedBy { it.startDate }
    }

    override suspend fun recordingPadding(timerId: String): Pair<Int, Int> {
        val t = api.get<JsonObject>(session, "/LiveTv/Timers/$timerId")
        fun sec(k: String) = (t[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0
        return sec("PrePaddingSeconds") / 60 to sec("PostPaddingSeconds") / 60
    }

    override suspend fun setRecordingPadding(timerId: String, earlyMinutes: Int, lateMinutes: Int) {
        val t = api.get<JsonObject>(session, "/LiveTv/Timers/$timerId")
        val updated = JsonObject(t + mapOf(
            "PrePaddingSeconds" to kotlinx.serialization.json.JsonPrimitive(earlyMinutes * 60),
            "PostPaddingSeconds" to kotlinx.serialization.json.JsonPrimitive(lateMinutes * 60),
            "IsPrePaddingRequired" to kotlinx.serialization.json.JsonPrimitive(earlyMinutes > 0),
            "IsPostPaddingRequired" to kotlinx.serialization.json.JsonPrimitive(lateMinutes > 0),
        ))
        api.post<Unit>(session, "/LiveTv/Timers/$timerId", body = updated)
    }

    override suspend fun cancelRecording(program: BaseItem, series: Boolean) {
        val id = (if (series) program.seriesTimerId else program.timerId) ?: return
        api.delete(session, if (series) "/LiveTv/SeriesTimers/$id" else "/LiveTv/Timers/$id")
    }

    override suspend fun uploadLog(text: String): String {
        val response = api.postText(session, "/ClientLog/Document", text)
        return response["FileName"]?.toString()?.trim('"') ?: "(saved)"
    }

    override suspend fun segments(itemId: String): List<MediaSegment> =
        api.get<SegmentsResult>(session, "/MediaSegments/$itemId").items.sortedBy { it.startTicks }

    override suspend fun trickplay(item: BaseItem, mediaSourceId: String?): Trickplay? {
        val full = api.get<BaseItem>(session, "/Items/${item.id}", user + mapOf("fields" to "Trickplay"))
        val bySource = full.trickplay[mediaSourceId ?: item.id] ?: full.trickplay.values.firstOrNull() ?: return null
        // Pick the smallest width at least 240px, which is plenty for a preview and cheap to load.
        val info = bySource.values.filter { it.thumbnailCount > 0 }.sortedBy { it.width }.let { list ->
            list.firstOrNull { it.width >= 240 } ?: list.lastOrNull()
        } ?: return null
        return Trickplay(item.id, mediaSourceId, info)
    }

    override suspend fun trickplaySheet(trickplay: Trickplay, sheet: Int): ByteArray =
        api.bytes(session, "/Videos/${trickplay.itemId}/Trickplay/${trickplay.info.width}/$sheet.jpg", mapOf("mediaSourceId" to trickplay.mediaSourceId))

    override fun imageUrl(itemId: String, kind: ImageKind, tag: String?, maxHeight: Int): String? =
        tag?.let { "${session.serverUrl}/Items/$itemId/Images/${kind.name}?fillHeight=$maxHeight&quality=80&tag=$it" }

    override suspend fun speedTest(bytes: Int): Long = api.bitrateTest(session, bytes)

    override suspend fun resolveStream(item: BaseItem, mode: StreamMode): Stream {
        val allowDirect = settings.directPlay.value
        val transcodeOnly = mode != StreamMode.Direct || !allowDirect
        val live = item.isChannel
        // The screen and sound are checked again each time, so a different TV or a new receiver counts.
        AutoTune.rescanDevice()
        val serverKey = session.serverId.ifEmpty { session.serverUrl }
        val bitrate = (if (live) settings.liveBitrate.value else settings.maxBitrate.value).let {
            when {
                it < 0 -> AutoTune.autoBitrate(serverKey, live)
                it == 0 -> MaxBitrate
                else -> it
            }
        }
        val chosenHeight = if (live) settings.liveResolution.value else settings.maxResolution.value
        // "Automatic" doesn't limit what plays as-is: a 4K file this TV decodes looks best played
        // whole and scaled here, and costs the server nothing. Only when the server has to convert
        // anyway is the result sized to the screen (below), rather than converted larger than it shows.
        val autoHeight = chosenHeight < 0
        val maxHeight = if (autoHeight) 0 else chosenHeight
        dev.mediacenter.jf.AppLog.i(
            "Player",
            "Limits: ${AutoTune.bitrateLabel(bitrate)}, " +
                (if (autoHeight) "full size, conversions at ${AutoTune.heightLabel(AutoTune.autoMaxHeight())}" else AutoTune.heightLabel(maxHeight)) +
                ", audio ${if (stereoOutput(settings)) "stereo" else "surround"}",
        )
        val info = api.post<PlaybackInfo>(
            session, "/Items/${item.id}/PlaybackInfo", user,
            buildJsonObject {
                put("UserId", session.userId)
                put("MaxStreamingBitrate", bitrate)
                put("EnableDirectPlay", !transcodeOnly)
                put("EnableDirectStream", !transcodeOnly)
                put("EnableTranscoding", true)
                // "Convert the sound only" keeps the picture exactly as it is (no re-encoding, no load on the server).
                put("AllowVideoStreamCopy", allowDirect && mode != StreamMode.ConvertAll)
                put("AllowAudioStreamCopy", allowDirect && mode == StreamMode.Direct)
                put("AutoOpenLiveStream", true)
                put("DeviceProfile", deviceProfile(settings, bitrate, maxHeight))
            }
        )
        val source = info.mediaSources.firstOrNull() ?: error(info.errorCode ?: "No playable media")
        // What the file holds, so a conversion's reasons can be understood from the log.
        dev.mediacenter.jf.AppLog.i(
            "Player",
            "Source: ${source.container} " + source.mediaStreams.filter { it.type == "Video" || it.type == "Audio" || it.type == "Subtitle" }
                .joinToString(" | ") { st -> listOfNotNull(st.type, st.codec, st.profile, st.channels?.let { "${it}ch" }, st.language, if (st.isDefault) "default" else null).joinToString(" ") },
        )
        val base = session.serverUrl
        val direct = !transcodeOnly && (source.supportsDirectPlay || source.supportsDirectStream)
        val url = if (direct || source.transcodingUrl == null) {
            val kind = if (item.isAudio) "Audio" else "Videos"
            "$base/$kind/${item.id}/stream?static=true&mediaSourceId=${source.id}&deviceId=$deviceId" +
                "&api_key=${session.token}" + (info.playSessionId?.let { "&playSessionId=$it" } ?: "") +
                (source.liveStreamId?.let { "&liveStreamId=$it" } ?: "")
        } else {
            base + source.transcodingUrl + (if (autoHeight && !item.isAudio) screenLimit(source.transcodingUrl) else "") +
                sampleRateLimit(source, source.transcodingUrl)
        }
        val mainAudio = source.mediaStreams.filter { it.type == "Audio" }.let { a -> a.firstOrNull { it.isDefault } ?: a.firstOrNull() }
        // Text subtitles the server hands over separately (so a conversion never burns them in).
        // Only for subtitle files beside the video: one inside the file would have to be extracted by the
        // server first (reading the whole film), and the player waits for a separate subtitle before starting.
        val subtitles = source.mediaStreams
            .filter { it.type == "Subtitle" && it.deliveryMethod == "External" && it.isExternal && !it.deliveryUrl.isNullOrEmpty() }
            .mapNotNull { st ->
                val path = st.deliveryUrl!!
                val mime = subtitleMime(path.substringBefore('?').substringAfterLast('.', "")) ?: subtitleMime(st.codec.orEmpty()) ?: return@mapNotNull null
                val withKey = if ("api_key=" in path.lowercase() || "apikey=" in path.lowercase()) path
                    else path + (if ('?' in path) "&" else "?") + "api_key=${session.token}"
                SideSubtitle(
                    url = if (withKey.startsWith("http")) withKey else base + withKey,
                    mimeType = mime, language = st.language, label = st.displayTitle ?: st.title,
                    isDefault = st.isDefault, isForced = st.isForced,
                )
            }
        val video = source.mediaStreams.firstOrNull { it.type == "Video" }
        return Stream(
            url = url,
            mediaSourceId = source.id,
            playSessionId = info.playSessionId,
            isTranscode = !direct,
            isHls = !direct && (source.transcodingSubProtocol == "hls" || ".m3u8" in url),
            liveStreamId = source.liveStreamId,
            subtitles = subtitles,
            audioKey = mainAudio?.let { "${it.codec}/${it.channels ?: 0}" },
            frameRate = video?.let { it.realFrameRate ?: it.averageFrameRate }?.takeIf { it > 0f && !video.isInterlaced },
            bitrate = source.bitrate,
            videoLabel = video?.let(::videoLabel),
            audioLabel = mainAudio?.let(::audioLabel),
        )
    }

    override suspend fun reportStart(report: PlaybackReport) {
        api.post<Unit>(session, "/Sessions/Playing", body = report.toJson())
    }

    override suspend fun reportProgress(report: PlaybackReport) {
        api.post<Unit>(session, "/Sessions/Playing/Progress", body = report.toJson())
    }

    override suspend fun serverControl(): ServerClientConfig? {
        val bytes = try {
            api.bytes(session, "/MediaCenter/Client")
        } catch (e: IllegalStateException) {
            // No plugin on this server: it doesn't know the address.
            if (e.message?.startsWith("HTTP 404") == true) return null
            throw e
        }
        return JellyfinJson.decodeFromString<ServerClientConfig>(bytes.decodeToString())
    }

    override suspend fun sendSettingsCatalog(catalog: SettingsCatalog) {
        api.post<Unit>(session, "/MediaCenter/Catalog", body = JellyfinJson.encodeToJsonElement(SettingsCatalog.serializer(), catalog) as kotlinx.serialization.json.JsonObject)
    }

    override suspend fun serverAsset(name: String): ByteArray = api.bytes(session, "/MediaCenter/Assets/$name")

    override suspend fun ping(playSessionId: String) {
        api.post<Unit>(session, "/Sessions/Playing/Ping", mapOf("playSessionId" to playSessionId))
    }

    override suspend fun reportStop(report: PlaybackReport) {
        api.post<Unit>(session, "/Sessions/Playing/Stopped", body = report.toJson())
    }

    private fun PlaybackReport.toJson() = buildJsonObject {
        put("ItemId", itemId)
        stream.mediaSourceId?.let { put("MediaSourceId", it) }
        stream.playSessionId?.let { put("PlaySessionId", it) }
        stream.liveStreamId?.let { put("LiveStreamId", it) }
        put("PositionTicks", positionTicks)
        put("IsPaused", isPaused)
        put("CanSeek", true)
        put("PlayMethod", if (stream.isTranscode) "Transcode" else "DirectPlay")
    }

    companion object {
        // Galleries only need what the footer shows; details pages fetch the full item.
        const val ListFields = "DateCreated,ChildCount,PrimaryImageAspectRatio"
        const val ImageTypes = "Primary,Backdrop,Thumb"
        const val MaxBitrate = AutoTune.MaxBitrate

        /**
         * For a conversion under "automatic" resolution: caps its size at the screen's, unless the
         * server already chose smaller. The HLS address takes maxWidth / maxHeight directly.
         */
        private fun screenLimit(transcodingUrl: String): String {
            val h = AutoTune.autoMaxHeight().takeIf { it > 0 } ?: return ""
            val w = h * 16 / 9
            val query = transcodingUrl.substringAfter('?', "").lowercase()
            fun existing(key: String) = Regex("(?:^|&)$key=(\\d+)").find(query)?.groupValues?.get(1)?.toIntOrNull()
            return buildString {
                if ((existing("maxheight") ?: Int.MAX_VALUE) > h) append("&maxHeight=$h")
                if ((existing("maxwidth") ?: Int.MAX_VALUE) > w) append("&maxWidth=$w")
            }
        }

        /**
         * Stereo for conversions: when chosen, or on "automatic" when no surround reaches the
         * listener. Under automatic, soundtracks that play as they are still do (the TV mixes
         * 5.1 down itself); this only sets what the server converts audio to when it must.
         */
        fun stereoOutput(settings: Settings) = when (settings.surround.value) {
            "stereo" -> true
            "auto" -> AutoTune.autoStereo()
            else -> false
        }

        /** The highest sample rate Android plays on every TV; above it (DSD, 352.8 / 384 kHz) the server halves it. */
        const val MaxSampleRate = 192_000

        /**
         * For a conversion of audio above [MaxSampleRate]: asks for an exact half (or quarter) of the
         * original rate, which keeps the conversion as clean as possible. Other rates are left alone.
         */
        private fun sampleRateLimit(source: MediaSource, transcodingUrl: String): String {
            if ("audiosamplerate=" in transcodingUrl.lowercase()) return ""
            val rate = source.mediaStreams.filter { it.type == "Audio" }.let { a -> a.firstOrNull { it.isDefault } ?: a.firstOrNull() }?.sampleRate ?: return ""
            if (rate <= MaxSampleRate) return ""
            var target = rate
            while (target > MaxSampleRate) target /= 2
            return "&AudioSampleRate=$target"
        }

        /**
         * Stereo chosen by hand: Dolby and DTS aren't sent on as-is, but decoded on this TV and mixed
         * down. Soundtracks still play as they are, so stereo never makes the server convert a file.
         */
        fun passthroughAllowed(settings: Settings) = settings.passthrough.value && settings.surround.value != "stereo"

        /** Media3's type for a subtitle format, or null if it isn't a text format we can load. */
        /** "4K · Dolby Vision · HEVC", as the info bar shows a video. */
        fun videoLabel(v: MediaStream): String = listOfNotNull(
            v.height?.let { h -> dev.mediacenter.jf.playback.AutoTune.qualityLabel(h, v.width ?: 0) },
            (v.videoRangeType ?: v.videoRange)?.takeIf { it != "SDR" && it != "Unknown" }?.let { r ->
                when {
                    r.startsWith("DOVI") -> "Dolby Vision"
                    r == "HDR10Plus" -> "HDR10+"
                    else -> r
                }
            },
            v.codec?.uppercase(),
        ).joinToString("  \u00b7  ")

        /** "Atmos · TrueHD 7.1", as the info bar shows a soundtrack. */
        fun audioLabel(a: MediaStream): String {
            val title = (a.displayTitle ?: a.title).orEmpty()
            val codec = when (a.codec?.lowercase()) {
                "truehd" -> "TrueHD"; "eac3" -> "Dolby Digital+"; "ac3" -> "Dolby Digital"; "dts" -> if ("MA" in (a.profile ?: "")) "DTS-HD MA" else "DTS"
                null -> null; else -> a.codec.uppercase()
            }
            val layout = a.channelLayout ?: a.channels?.let { if (it == 2) "stereo" else "$it ch" }
            return listOfNotNull(if ("atmos" in title.lowercase() || a.profile?.contains("Atmos") == true) "Atmos" else null, listOfNotNull(codec, layout).joinToString(" ").ifEmpty { null })
                .joinToString("  \u00b7  ")
        }

        private fun subtitleMime(format: String): String? = when (format.lowercase()) {
            "srt", "subrip" -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
            "vtt", "webvtt" -> androidx.media3.common.MimeTypes.TEXT_VTT
            "ass", "ssa" -> androidx.media3.common.MimeTypes.TEXT_SSA
            "ttml" -> androidx.media3.common.MimeTypes.APPLICATION_TTML
            else -> null
        }

        private fun profile(vararg pairs: Pair<String, Any>) = buildJsonObject {
            pairs.forEach { (k, v) ->
                when (v) {
                    is String -> put(k, v)
                    is Int -> put(k, v)
                    is Boolean -> put(k, v)
                }
            }
        }

        /**
         * What this TV can play without help: built from what it actually decodes ([DeviceCodecs]),
         * shaped by the playback and codec settings. Anything outside it (codec, 10-bit, HDR type,
         * size, bitrate, channels) the server converts to HLS.
         */
        fun deviceProfile(settings: Settings, bitrate: Int, maxHeight: Int) = buildJsonObject {
            val caps = dev.mediacenter.jf.playback.DeviceCodecs.current
            val stereo = stereoOutput(settings)
            // Lossless (FLAC) conversions when chosen and the quality limit leaves room for them.
            val lossless = settings.losslessConversions.value && bitrate >= 8_000_000
            val ffmpeg = settings.softwareAudio.value
            val passthrough = passthroughAllowed(settings)
            val video = caps.video(settings.videoDecoding.value).filterKeys {
                when (it) {
                    "hevc" -> settings.allowHevc.value
                    "vp9" -> settings.allowVp9.value
                    "av1" -> settings.allowAv1.value
                    else -> true
                }
            }
            // AAC, MP3 and PCM always play; the rest depends on this TV and the settings.
            val audio = buildList {
                addAll(listOf("aac", "mp3", "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le"))
                listOf("ac3", "eac3", "dts", "truehd", "opus", "vorbis", "flac", "alac", "mp2", "ac4").forEach {
                    if (caps.playsAudio(it, ffmpeg, passthrough)) add(it)
                }
                if ("dts" in this) add("dca")
                if ("truehd" in this) add("mlp")
            }.distinct()
            val videoAudio = audio.joinToString(",")
            // The HDR kinds the screen can show; Dolby Vision with an HDR10 or SDR base plays that base elsewhere.
            val ranges = buildList {
                add("SDR")
                if (settings.allowHdr.value) {
                    if ("HDR10" in caps.screenHdr) add("HDR10")
                    if ("HDR10+" in caps.screenHdr) add("HDR10Plus")
                    if ("HLG" in caps.screenHdr) add("HLG")
                }
                val dolbyVision = settings.allowDolbyVision.value && settings.allowHdr.value && caps.dolbyVisionDecoder && "DOVI" in caps.screenHdr
                if (dolbyVision) addAll(listOf("DOVI", "DOVIWithHDR10", "DOVIWithHLG", "DOVIWithSDR"))
                else {
                    if ("HDR10" in this) add("DOVIWithHDR10")
                    if ("HLG" in this) add("DOVIWithHLG")
                    add("DOVIWithSDR")
                }
            }
            put("Name", "Media Center for Android TV")
            put("MaxStreamingBitrate", bitrate)
            put("MaxStaticBitrate", bitrate)
            put("MusicStreamingTranscodingBitrate", 320_000)
            put("DirectPlayProfiles", buildJsonArray {
                add(profile(
                    "Type" to "Video",
                    "Container" to "mp4,m4v,mkv,webm,mov,ts,mpegts,m2ts,avi,3gp,flv",
                    "VideoCodec" to video.keys.joinToString(","),
                    "AudioCodec" to videoAudio,
                ))
                add(profile(
                    "Type" to "Audio",
                    "Container" to "mp3,aac,m4a,m4b,flac,ogg,oga,opus,wav,webma,webm,mka,mp4",
                    "AudioCodec" to audio.joinToString(","),
                ))
            })
            put("TranscodingProfiles", buildJsonArray {
                val surroundOut = !stereo && (caps.playsAudio("ac3", ffmpeg, passthrough) || caps.playsAudio("eac3", ffmpeg, passthrough))
                add(profile(
                    "Type" to "Video", "Container" to "ts",
                    "VideoCodec" to if ("hevc" in video) "h264,hevc" else "h264",
                    "AudioCodec" to listOfNotNull(
                        "aac",
                        "ac3".takeIf { surroundOut && caps.playsAudio("ac3", ffmpeg, passthrough) },
                        "eac3".takeIf { surroundOut && caps.playsAudio("eac3", ffmpeg, passthrough) },
                        "mp3",
                    ).joinToString(","),
                    "Protocol" to "hls", "Context" to "Streaming", "MaxAudioChannels" to if (stereo) "2" else "6", "MinSegments" to 1,
                    "BreakOnNonKeyFrames" to true,
                ))
                // Music the TV can't play (APE, WavPack, DSD and the like): FLAC keeps every bit when the
                // quality setting leaves room for it; otherwise high-quality MP3.
                if (lossless) {
                    add(profile(
                        "Type" to "Audio", "Container" to "flac", "AudioCodec" to "flac",
                        "Protocol" to "http", "Context" to "Streaming", "MaxAudioChannels" to if (stereo) "2" else "8",
                    ))
                }
                add(profile(
                    "Type" to "Audio", "Container" to "mp3", "AudioCodec" to "mp3",
                    "Protocol" to "http", "Context" to "Streaming", "MaxAudioChannels" to "2",
                ))
            })
            put("ContainerProfiles", buildJsonArray {})
            put("CodecProfiles", buildJsonArray {
                // No sample-rate condition here: Jellyfin takes one as the rate to convert to (every conversion
                // would come out at 192 kHz, which AAC and MP3 can't do). Rates above 192 kHz are handled per
                // stream instead (sampleRateLimit), and only when the source really is above it.
                if (maxHeight > 0) {
                    add(buildJsonObject {
                        put("Type", "Video")
                        put("Conditions", buildJsonArray {
                            add(profile("Condition" to "LessThanEqual", "Property" to "Height", "Value" to "$maxHeight", "IsRequired" to true))
                            add(profile("Condition" to "LessThanEqual", "Property" to "Width", "Value" to "${maxHeight * 16 / 9}", "IsRequired" to true))
                        })
                    })
                }
                // Per format: the decoder's largest size, whether it does 10-bit, which profiles and HDR kinds.
                video.values.forEach { d ->
                    add(buildJsonObject {
                        put("Type", "Video")
                        put("Codec", d.codec)
                        put("Conditions", buildJsonArray {
                            add(profile("Condition" to "LessThanEqual", "Property" to "Width", "Value" to "${d.maxWidth}", "IsRequired" to false))
                            add(profile("Condition" to "LessThanEqual", "Property" to "Height", "Value" to "${d.maxHeight}", "IsRequired" to false))
                            add(profile("Condition" to "LessThanEqual", "Property" to "VideoBitDepth", "Value" to if (d.tenBit) "10" else "8", "IsRequired" to false))
                            when (d.codec) {
                                "h264" -> add(profile(
                                    "Condition" to "EqualsAny", "Property" to "VideoProfile", "IsRequired" to false,
                                    "Value" to listOfNotNull("high", "main", "baseline", "constrained baseline", "high 10".takeIf { d.tenBit }).joinToString("|"),
                                ))
                                "hevc" -> add(profile(
                                    "Condition" to "EqualsAny", "Property" to "VideoProfile", "IsRequired" to false,
                                    "Value" to if (d.tenBit) "main|main 10" else "main",
                                ))
                                else -> Unit
                            }
                            if (d.codec in setOf("hevc", "vp9", "av1")) {
                                add(profile("Condition" to "EqualsAny", "Property" to "VideoRangeType", "Value" to ranges.joinToString("|"), "IsRequired" to false))
                            } else {
                                add(profile("Condition" to "EqualsAny", "Property" to "VideoRangeType", "Value" to "SDR", "IsRequired" to false))
                            }
                        })
                    })
                }
            })
            put("SubtitleProfiles", buildJsonArray {
                // Inside the file when it plays as-is; picture subtitles too.
                listOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt", "pgs", "pgssub").forEach {
                    add(profile("Format" to it, "Method" to "Embed"))
                }
                // Text subtitles of a converted stream go into the stream itself (HLS subtitles, loaded a piece
                // at a time, so they never hold up the start); the server then never burns text into the picture,
                // which would force it to re-encode video it could have passed through.
                listOf("srt", "subrip", "vtt", "webvtt", "ass", "ssa").forEach {
                    add(profile("Format" to it, "Method" to "Hls"))
                }
                // Subtitle files beside the video (small, and read straight from disk) are fetched separately.
                listOf("srt", "subrip", "vtt", "ass", "ssa").forEach {
                    add(profile("Format" to it, "Method" to "External"))
                }
                add(profile("Format" to "dvdsub", "Method" to "Encode"))
            })
        }
    }
}

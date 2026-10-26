package cn.weibo.filedownloader

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

internal data class WeiboWorkData(
    val id: String,
    val title: String,
    val author: String,
    val media: List<MediaItem>,
    val inventory: WeiboMediaInventory = WeiboMediaInventory(),
)

/**
 * Converts the different Weibo status and video-component schemas into the
 * shared media model. Network endpoint selection stays outside this class.
 */
internal class WeiboWorkNormalizer(
    private val includeLiveCover: Boolean,
    private val metadataProbe: WeiboMediaMetadataProbe = WeiboRemoteMediaMetadataProbe(),
) {
    // All providers for one load share probes; the same CDN URL is measured once.
    private val measuredImages = HashMap<String, Pair<Int, Int>?>()
    private val measuredMedia = HashMap<String, WeiboMediaProbeResult?>()
    fun normalizeStatus(
        data: JSONObject,
        fallbackId: String,
        quality: QualityPreference,
        defaultAuthor: String,
        fallbackMedia: List<MediaItem> = emptyList(),
    ): WeiboWorkData? {
        val postId = data.optString("id", fallbackId)
        val author = data.optJSONObject("user")?.optString("screen_name", defaultAuthor) ?: defaultAuthor
        val inventory = WeiboMediaInventory.read(findMediaSource(data))
        val media = ArrayList<MediaItem>()
        parseStatusMedia(findMediaSource(data), data, quality, postId, media, HashSet())
        media += fallbackMedia.map { it.copy(postId = postId) }
        if (media.isEmpty() && inventory.references.isEmpty() && inventory.minimumCount == 0) return null
        val work = WeiboInventoryMerger.reconcile(WeiboWorkData(
            id = postId,
            title = cleanText(data.optString("text_raw", data.optString("text"))),
            author = author,
            media = media,
            inventory = inventory,
        ))
        return work.copy(media = hydrateMediaMetadata(work.media))
    }

    /** Selects only the component video when a status contains mixed media. */
    fun normalizeVideoStatus(
        data: JSONObject,
        fallbackId: String,
        objectId: String,
        quality: QualityPreference,
        defaultAuthor: String,
    ): WeiboWorkData? {
        val postId = data.optString("id", fallbackId)
        val selected = parseMedia(data, quality, postId).filter { item ->
            item.type == MediaType.VIDEO && item.videoObjectId == objectId
        }
        if (selected.isEmpty()) return null
        return WeiboWorkData(
            id = postId,
            title = cleanText(data.optString("text_raw", data.optString("text"))),
            author = data.optJSONObject("user")?.optString("screen_name", defaultAuthor) ?: defaultAuthor,
            media = hydrateMediaMetadata(selected),
        )
    }

    fun normalizeVideoComponent(
        playInfo: JSONObject,
        objectId: String,
        quality: QualityPreference,
        defaultAuthor: String,
    ): WeiboWorkData? {
        val variants = componentVideoVariants(playInfo)
        val selectedVariant = chooseMediaVariant(variants, quality)
        val videoUrl = selectedVariant?.url ?: bestComponentVideo(playInfo, quality) ?: return null
        val safeId = WeiboVideoWorkResolver.statusId(playInfo)
            ?: objectId.substringAfter(':').ifBlank { objectId.replace(':', '_') }
        val author = firstText(playInfo, "nickname", "author", "author_name", "screen_name")
            .ifBlank { defaultAuthor }
        val media = hydrateMediaMetadata(
            listOf(
                MediaItem(
                    type = MediaType.VIDEO,
                    url = videoUrl,
                    quality = videoQualityLabel(quality, true),
                    postId = safeId,
                    ordinal = 1,
                    thumbnailUrl = thumbnailFrom(playInfo),
                    variants = variants,
                    selectedVariantKey = selectedVariant?.key,
                    videoObjectId = objectId,
                ),
            ),
        )
        return WeiboWorkData(
            id = safeId,
            title = cleanText(firstText(playInfo, "title", "text", "content1", "content")),
            author = author,
            media = media,
        )
    }

    internal fun parseMedia(data: JSONObject, quality: QualityPreference, postId: String = data.optString("id")): List<MediaItem> {
        val media = ArrayList<MediaItem>()
        val seen = HashSet<String>()
        val source = findMediaSource(data)
        parseStatusMedia(source, data, quality, postId, media, seen)
        return WeiboInventoryMerger.reconcile(WeiboWorkData(postId, "", "", media, WeiboMediaInventory.read(source))).media
    }

    private fun parseStatusMedia(
        source: JSONObject,
        root: JSONObject,
        quality: QualityPreference,
        postId: String,
        result: MutableList<MediaItem>,
        seen: MutableSet<String>,
    ) {
        parsePicInfos(source, quality, postId, result, seen)
        parsePics(source, quality, postId, result, seen)
        parseMixedMedia(source, quality, postId, result, seen)
        parseMissingPictures(source, quality, postId, result, seen)

        val pageInfo = source.optJSONObject("page_info") ?: root.optJSONObject("page_info")
        parsePageInfo(pageInfo, quality, postId, result, seen)
        source.optJSONObject("media_info")?.let {
            parseMediaInfo(it, source, quality, postId, result, seen,
                WeiboMediaInventory.videoReference(source, "", "status").key)
        }
    }

    private fun parsePicInfos(
        status: JSONObject,
        quality: QualityPreference,
        postId: String,
        result: MutableList<MediaItem>,
        seen: MutableSet<String>,
    ) {
        val infos = status.optJSONObject("pic_infos") ?: return
        val orderedIds = ArrayList<String>()
        val ids = status.optJSONArray("pic_ids")
        if (ids != null) for (index in 0 until ids.length()) ids.optString(index).takeIf { it.isNotBlank() }?.let(orderedIds::add)
        infos.keys().forEach { if (it !in orderedIds) orderedIds += it }
        val liveHints = WeiboMediaInventory.liveVideoHints(status)
        orderedIds.forEachIndexed { index, id ->
            infos.optJSONObject(id)?.let { picture ->
                val hint = liveHints[index]
                if (hint.isNullOrBlank() || chooseLiveVideo(picture) != null) {
                    parsePicture(picture, quality, postId, result, seen, id)
                } else {
                    // Some mobile responses move the Live Photo clip out of
                    // pic_infos and expose it as pic_video/live_photo on the
                    // status object. Attach that hint to its matching picture
                    // before applying the normal picture parser.
                    val enriched = JSONObject(picture.toString())
                        .put("type", "livephoto")
                        .put("video", livePhotoPlaybackUrl(hint))
                    parsePicture(enriched, quality, postId, result, seen, id)
                }
            }
        }
    }

    /** Recover source-declared pictures; the inventory places them among their source neighbours. */
    private fun parseMissingPictures(
        status: JSONObject,
        quality: QualityPreference,
        postId: String,
        result: MutableList<MediaItem>,
        seen: MutableSet<String>,
    ) {
        val ids = status.optJSONArray("pic_ids") ?: return
        val present = result.asSequence().filter { it.type == MediaType.IMAGE || it.type == MediaType.LIVE_PHOTO }
            .flatMap { WeiboMediaIdentity.keys(it).asSequence() }
            .filter { it.startsWith("picture:") }.map { it.removePrefix("picture:") }.toMutableSet()
        val liveHints = WeiboMediaInventory.liveVideoHints(status)
        for (index in 0 until ids.length()) {
            val id = ids.optString(index)
            if (!WeiboMediaIdentity.pictureIdPattern.matches(id) || !present.add(id)) continue
            val picture = JSONObject().put("large", JSONObject().put("url", "https://wx1.sinaimg.cn/large/$id.jpg"))
            liveHints[index]?.let { hint ->
                picture.put("type", "livephoto").put("video", livePhotoPlaybackUrl(hint))
            }
            parsePicture(picture, quality, postId, result, seen, id)
        }
    }

    private fun parsePics(
        status: JSONObject,
        quality: QualityPreference,
        postId: String,
        result: MutableList<MediaItem>,
        seen: MutableSet<String>,
    ) {
        val pictures = status.optJSONArray("pics")
        if (pictures == null) return
        val ids = status.optJSONArray("pic_ids")
        val orderedIds = (0 until (ids?.length() ?: 0)).map { ids!!.optString(it) }
        val liveHints = WeiboMediaInventory.liveVideoHints(status)
        for (index in 0 until pictures.length()) {
            val picture = pictures.optJSONObject(index) ?: continue
            if (picture.optString("type").equals("video", true)) {
                val reference = WeiboMediaInventory.videoReference(picture,
                    status.optJSONArray("mix_media_ids")?.optString(index).orEmpty(), "pics:$index")
                val enriched = if (reference.objectId.isNotBlank()) JSONObject(picture.toString()).put("object_id", reference.objectId) else picture
                val mediaInfo = picture.optJSONObject("media_info")
                if (mediaInfo != null) parseMediaInfo(mediaInfo, enriched, quality, postId, result, seen, reference.key)
                else addDirectMedia(enriched, MediaType.VIDEO, quality, postId, result, seen, thumbnailFrom(picture), reference.key)
            } else {
                val id = picture.optString("pid").ifBlank { picture.optString("pic_id") }
                val hint = liveHints[orderedIds.indexOf(id)]
                val enriched = if (!hint.isNullOrBlank() && chooseLiveVideo(picture) == null)
                    JSONObject(picture.toString()).put("type", "livephoto").put("video", livePhotoPlaybackUrl(hint)) else picture
                parsePicture(enriched, quality, postId, result, seen, id)
            }
        }
    }

    private fun parseMixedMedia(
        status: JSONObject,
        quality: QualityPreference,
        postId: String,
        result: MutableList<MediaItem>,
        seen: MutableSet<String>,
    ) {
        val items = status.optJSONObject("mix_media_info")?.optJSONArray("items") ?: return
        val ids = status.optJSONArray("pic_ids")
        val orderedIds = (0 until (ids?.length() ?: 0)).map { ids!!.optString(it) }
        val liveHints = WeiboMediaInventory.liveVideoHints(status)
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            var data = item.optJSONObject("data") ?: item
            val type = item.optString("type", data.optString("type")).lowercase()
            val objectType = data.optString("object_type").lowercase()
            if (type == "video" || objectType == "video") {
                val reference = WeiboMediaInventory.videoReference(data, item.optString("id"), "mixed:$index")
                if (reference.objectId.isNotBlank()) data = JSONObject(data.toString()).put("object_id", reference.objectId)
                val start = result.size
                val mediaInfo = data.optJSONObject("media_info") ?: item.optJSONObject("media_info")
                if (mediaInfo != null) parseMediaInfo(mediaInfo, data, quality, postId, result, seen, reference.key)
                else addDirectMedia(data, MediaType.VIDEO, quality, postId, result, seen, thumbnailFrom(data), reference.key)
                val objectId = videoObjectId(data).ifBlank { videoObjectId(item) }
                if (objectId.isNotBlank()) for (position in start until result.size) {
                    result[position] = result[position].copy(videoObjectId = objectId)
                }
            } else {
                val id = item.optString("id").ifBlank { data.optString("pic_id") }
                val picture = data.optJSONObject("pic_info") ?: data
                val hint = liveHints[orderedIds.indexOf(id)]
                val enriched = if (!hint.isNullOrBlank() && chooseLiveVideo(picture) == null)
                    JSONObject(picture.toString()).put("type", "livephoto").put("video", livePhotoPlaybackUrl(hint)) else picture
                parsePicture(enriched, quality, postId, result, seen, id)
            }
        }
    }

    private fun parsePicture(
        picture: JSONObject,
        quality: QualityPreference,
        postId: String,
        result: MutableList<MediaItem>,
        seen: MutableSet<String>,
        pictureId: String = "",
    ) {
        val thumbnail = chooseThumbnail(picture).ifBlank { thumbnailFrom(picture) }
        val identity = WeiboMediaInventory.pictureReference(picture, pictureId).key
        val liveUrl = chooseLiveVideo(picture)
        val imageVariants = collectImageVariants(picture)
        val selectedVariant = chooseImageVariant(imageVariants, quality)
        if (liveUrl != null) {
            val cover = if (includeLiveCover) selectedVariant?.url.orEmpty() else ""
            val liveVariants = collectDirectMediaVariants(picture, MediaType.LIVE_PHOTO).ifEmpty {
                listOf(MediaVariant("live", liveUrl))
            }
            val selectedLiveVariant = chooseMediaVariant(liveVariants, quality) ?: liveVariants.first()
            add(
                result, seen, MediaType.LIVE_PHOTO, selectedLiveVariant.url, MediaQuality.LIVE_ORIGINAL,
                postId, result.size + 1, thumbnail, cover, liveVariants, selectedLiveVariant.key,
                sourceId = identity,
            )
        } else {
            selectedVariant?.let {
                add(
                    result = result,
                    seen = seen,
                    type = MediaType.IMAGE,
                    url = it.url,
                    quality = imageQualityLabel(quality),
                    id = postId,
                    order = result.size + 1,
                    thumbnailUrl = thumbnail.ifBlank { it.url },
                    variants = imageVariants,
                    selectedVariantKey = it.key,
                    sourceId = identity,
                )
            }
        }
    }

    private fun parsePageInfo(
        pageInfo: JSONObject?,
        quality: QualityPreference,
        postId: String,
        result: MutableList<MediaItem>,
        seen: MutableSet<String>,
    ) {
        if (pageInfo == null) return
        val mediaInfo = pageInfo.optJSONObject("media_info") ?: pageInfo.optJSONObject("media") ?: return
        parseMediaInfo(mediaInfo, pageInfo, quality, postId, result, seen,
            if (WeiboMediaInventory.isVideo(pageInfo)) WeiboMediaInventory.videoReference(pageInfo, "", "page").key else "")
    }

    private fun parseMediaInfo(
        mediaInfo: JSONObject,
        container: JSONObject,
        quality: QualityPreference,
        postId: String,
        result: MutableList<MediaItem>,
        seen: MutableSet<String>,
        sourceId: String = "",
    ) {
        val thumbnail = thumbnailFrom(container).ifBlank { thumbnailFrom(mediaInfo) }
        val type = container.optString("type").lowercase()
        val objectType = container.optString("object_type").lowercase()
        val audioVariants = collectDirectMediaVariants(mediaInfo, MediaType.AUDIO)
        val audioUrl = chooseMediaVariant(audioVariants, quality)?.url
            ?: firstUrl(mediaInfo, listOf("audio_url_hd", "audio_url", "mp3_url", "stream_url"))
        val isAudio = type == "audio" || objectType == "audio" || hasAny(mediaInfo, listOf("audio_url", "mp3_url", "audio_url_hd"))
        if (isAudio && audioUrl != null) {
            val selected = audioVariants.firstOrNull { it.url == audioUrl }
            add(
                result, seen, MediaType.AUDIO, audioUrl, MediaQuality.AUDIO_BEST,
                postId, result.size + 1, thumbnail, variants = audioVariants,
                selectedVariantKey = selected?.key,
            )
            return
        }
        val playlistVariants = playbackVariants(mediaInfo.optJSONArray("playback_list"))
        val directVariants = MediaVariantSelector.rank(
            MediaType.VIDEO,
            (collectDirectMediaVariants(mediaInfo, MediaType.VIDEO) +
                collectDirectMediaVariants(container, MediaType.VIDEO))
                .distinctBy(MediaVariant::url),
        )
        val videoVariants = playlistVariants.ifEmpty { directVariants }
        val selectedVideo = chooseMediaVariant(videoVariants, quality)
        val directVideo = selectedVideo?.url ?: bestDirectVideo(mediaInfo, quality)
        directVideo?.let {
            add(
                result, seen, MediaType.VIDEO, it,
                videoQualityLabel(quality, playlistVariants.isNotEmpty()), postId, result.size + 1,
                thumbnail, variants = videoVariants, selectedVariantKey = selectedVideo?.key,
                videoObjectId = videoObjectId(container).ifBlank { videoObjectId(mediaInfo) },
                sourceId = sourceId,
            )
        }
    }

    private fun findMediaSource(status: JSONObject): JSONObject = WeiboMediaInventory.source(status)

    private fun add(
        result: MutableList<MediaItem>,
        seen: MutableSet<String>,
        type: MediaType,
        url: String,
        quality: String,
        id: String,
        order: Int,
        thumbnailUrl: String = "",
        coverUrl: String = "",
        variants: List<MediaVariant> = emptyList(),
        selectedVariantKey: String? = null,
        videoObjectId: String = "",
        sourceId: String = "",
    ) {
        if (url.isNotBlank()) {
            val item = MediaItem(
                type = type,
                url = url,
                quality = quality,
                postId = id,
                ordinal = order,
                thumbnailUrl = thumbnailUrl,
                coverUrl = coverUrl,
                variants = variants,
                selectedVariantKey = selectedVariantKey,
                videoObjectId = videoObjectId,
                sourceId = sourceId,
            )
            val existing = result.indexOfFirst { it.url == url && it.type == type &&
                (it.videoObjectId.isBlank() || videoObjectId.isBlank() || it.videoObjectId == videoObjectId) }
            if (existing < 0) result += item else result[existing] = WeiboInventoryMerger.mergeItem(result[existing], item)
            seen += url
        }
    }

    private fun addDirectMedia(
        data: JSONObject,
        type: MediaType,
        quality: QualityPreference,
        postId: String,
        result: MutableList<MediaItem>,
        seen: MutableSet<String>,
        thumbnail: String,
        sourceId: String = "",
    ) {
        val variants = collectDirectMediaVariants(data, type)
        val selected = chooseMediaVariant(variants, quality)
        val fallback = if (type == MediaType.VIDEO) bestDirectVideo(data, quality) else null
        val url = selected?.url ?: fallback ?: return
        add(
            result = result,
            seen = seen,
            type = type,
            url = url,
            quality = videoQualityLabel(quality, false),
            id = postId,
            order = result.size + 1,
            thumbnailUrl = thumbnail,
            variants = variants,
            selectedVariantKey = selected?.key,
            videoObjectId = if (type == MediaType.VIDEO) videoObjectId(data) else "",
            sourceId = sourceId,
        )
    }

    private fun chooseImage(picture: JSONObject, quality: QualityPreference): String? {
        return chooseImageVariant(collectImageVariants(picture), quality)?.url
    }

    internal fun videoObjectIds(data: JSONObject): List<String> {
        val source = findMediaSource(data)
        val containers = mutableListOf(source)
        (source.optJSONObject("page_info") ?: data.optJSONObject("page_info"))?.let(containers::add)
        for (field in listOf("pics", "mix_media_info")) {
            val items = if (field == "pics") source.optJSONArray(field)
                else source.optJSONObject(field)?.optJSONArray("items")
            if (items != null) for (index in 0 until items.length()) {
                items.optJSONObject(index)?.let { item ->
                    containers += item
                    item.optJSONObject("data")?.let(containers::add)
                }
            }
        }
        return containers.flatMap { listOfNotNull(it, it.optJSONObject("media_info")) }
            .map(::videoObjectId).filter(String::isNotBlank).distinct()
    }

    private fun videoObjectId(data: JSONObject): String = sequenceOf("object_id", "fid")
        .map { data.optString(it).trim() }
        .firstOrNull { it.matches(Regex("[0-9]+:[A-Za-z0-9]+")) }.orEmpty()

    private data class RawImageVariant(
        val key: String,
        val url: String,
        val width: Int,
        val height: Int,
        val rank: Int,
    ) {
        val area: Long get() = width.toLong() * height.toLong()
    }

    private fun collectImageVariants(picture: JSONObject): List<MediaVariant> {
        val raw = ArrayList<RawImageVariant>()
        collectRawImageVariants(picture, depth = 0, raw)
        if (raw.isEmpty()) return emptyList()

        val best = raw.sortedWith(
            compareByDescending<RawImageVariant> { it.area }.thenBy { it.rank },
        ).first()
        val originalUrl = normalizeOriginalImageUrl(best.url)
        val result = ArrayList<MediaVariant>()
        // The /large/ file may be larger than the API's preview metadata, so
        // its dimensions are measured from the actual file below.
        result += MediaVariant(IMAGE_VARIANT_ORIGINAL, originalUrl)

        val seenUrls = hashSetOf(originalUrl)
        val seenKeys = hashSetOf(IMAGE_VARIANT_ORIGINAL)
        raw.sortedWith(compareByDescending<RawImageVariant> { it.area }.thenBy { it.rank }).forEach { variant ->
            if (seenUrls.add(variant.url) && seenKeys.add(variant.key)) {
                result += MediaVariant(variant.key, variant.url, variant.width, variant.height)
            }
        }
        return result
    }

    private fun collectRawImageVariants(
        data: JSONObject,
        depth: Int,
        result: MutableList<RawImageVariant>,
    ) {
        if (depth > 2) return
        data.keys().asSequence().toList().forEach { field ->
            val value = data.opt(field)
            val info = value as? JSONObject
            val url = urlValue(value)
            if (url != null && isImageVariantUrl(url)) {
                val geo = info?.optJSONObject("geo")
                val width = info?.optLong("width", 0L)?.takeIf { it > 0L }
                    ?: geo?.optLong("width", 0L)
                    ?: 0L
                val height = info?.optLong("height", 0L)?.takeIf { it > 0L }
                    ?: geo?.optLong("height", 0L)
                    ?: 0L
                result += RawImageVariant(
                    key = imageVariantKey(field, url),
                    url = url,
                    width = width.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    height = height.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    rank = result.size,
                )
            }
            when (value) {
                is JSONObject -> collectRawImageVariants(value, depth + 1, result)
                is JSONArray -> for (index in 0 until value.length()) {
                    value.optJSONObject(index)?.let { collectRawImageVariants(it, depth + 1, result) }
                }
            }
        }
    }

    private fun chooseImageVariant(variants: List<MediaVariant>, quality: QualityPreference): MediaVariant? {
        if (variants.isEmpty()) return null
        return variants.first()
    }

    private fun hydrateMediaMetadata(media: List<MediaItem>): List<MediaItem> {
        return media.map { item ->
            if (item.variants.isEmpty()) return@map item
            val variants = item.variants.map { variant ->
                if (item.type == MediaType.IMAGE) {
                    if (variant.width > 0 && variant.height > 0) return@map variant
                    val dimensions = if (measuredImages.containsKey(variant.url)) {
                        measuredImages[variant.url]
                    } else {
                        metadataProbe.imageDimensions(variant.url).also { measuredImages[variant.url] = it }
                    }
                    return@map dimensions?.let { (width, height) ->
                        variant.copy(width = width, height = height)
                    } ?: variant
                }
                val needsProbe = when (item.type) {
                    MediaType.VIDEO, MediaType.LIVE_PHOTO -> variant.width <= 0 || variant.height <= 0
                    MediaType.AUDIO -> variant.bitrate <= 0L
                    MediaType.IMAGE -> false
                }
                if (!needsProbe) return@map variant
                val probe = if (measuredMedia.containsKey(variant.url)) {
                    measuredMedia[variant.url]
                } else {
                    metadataProbe.mediaMetadata(variant.url).also { measuredMedia[variant.url] = it }
                }
                probe?.let {
                    variant.copy(
                        width = variant.width.takeIf { value -> value > 0 } ?: it.width,
                        height = variant.height.takeIf { value -> value > 0 } ?: it.height,
                        bitrate = variant.bitrate.takeIf { value -> value > 0L } ?: it.bitrate,
                    )
                } ?: variant
            }
            val rankedVariants = MediaVariantSelector.rank(item.type, variants)
            val selected = rankedVariants.firstOrNull()
            item.copy(
                url = selected?.url ?: item.url,
                variants = rankedVariants,
                selectedVariantKey = selected?.key ?: item.selectedVariantKey,
            )
        }
    }

    private data class RawMediaVariant(
        val field: String,
        val label: String,
        val url: String,
        val width: Int,
        val height: Int,
        val bitrate: Long,
        val inferredHeight: Int,
        val isOriginal: Boolean,
        val tier: Int,
        val rank: Int,
    )

    private fun collectDirectMediaVariants(data: JSONObject, type: MediaType): List<MediaVariant> {
        val raw = ArrayList<RawMediaVariant>()
        collectRawMediaVariants(data, type, depth = 0, raw)
        val seenUrls = HashSet<String>()
        val seenKeys = HashSet<String>()
        return raw.sortedWith(
            compareByDescending<RawMediaVariant> { it.isOriginal }
                .thenByDescending { maxOf(it.height, it.inferredHeight) }
                .thenByDescending { it.width.toLong() * it.height }
                .thenByDescending { it.bitrate }
                .thenByDescending { it.tier }
                .thenBy { it.rank },
        ).mapNotNull { variant ->
            val key = mediaVariantKey(variant)
            if (!seenUrls.add(variant.url) || !seenKeys.add(key)) return@mapNotNull null
            MediaVariant(key, variant.url, variant.width, variant.height, variant.bitrate)
        }
    }

    private fun collectRawMediaVariants(
        data: JSONObject,
        type: MediaType,
        depth: Int,
        result: MutableList<RawMediaVariant>,
    ) {
        if (depth > 3) return
        data.keys().asSequence().toList().forEach { field ->
            val value = data.opt(field)
            val info = value as? JSONObject
            val url = urlValue(value)
            if (url != null && isMediaVariantUrl(field, url, type)) {
                val meta = info?.optJSONObject("meta")
                val (urlWidth, urlHeight) = mediaDimensionsFromUrl(url)
                val width = positiveInt(info, meta, "width").takeIf { it > 0 } ?: urlWidth
                val height = positiveInt(info, meta, "height").takeIf { it > 0 } ?: urlHeight
                val bitrate = positiveLong(info, meta, "bitrate")
                val label = firstTextOrEmpty(info, "quality_desc", "quality_label", "quality_name", "label", "name")
                val quality = MediaVariantSelector.inferVideoQuality(field, label, url)
                result += RawMediaVariant(
                    field = field,
                    label = label,
                    url = url,
                    width = width,
                    height = height,
                    bitrate = bitrate,
                    inferredHeight = quality.height,
                    isOriginal = quality.isOriginal,
                    tier = quality.tier,
                    rank = result.size,
                )
            }
            when (value) {
                is JSONObject -> collectRawMediaVariants(value, type, depth + 1, result)
                is JSONArray -> for (index in 0 until value.length()) {
                    value.optJSONObject(index)?.let { collectRawMediaVariants(it, type, depth + 1, result) }
                }
            }
        }
    }

    private fun mediaVariantKey(variant: RawMediaVariant): String = when {
        variant.label.isNotBlank() -> variant.label
        variant.height > 0 -> "${variant.height}p"
        variant.bitrate > 0L -> "${(variant.bitrate / 1000L).coerceAtLeast(1L)} kbps"
        else -> variant.field.lowercase()
            .removeSuffix("_url")
            .removeSuffix("_mp4")
            .replace('_', ' ')
    }

    private fun mediaDimensionsFromUrl(url: String): Pair<Int, Int> {
        val match = MEDIA_DIMENSIONS_PATTERN.find(url) ?: return 0 to 0
        val width = match.groupValues[1].toIntOrNull() ?: 0
        val height = match.groupValues[2].toIntOrNull() ?: 0
        return width to height
    }

    private fun positiveInt(primary: JSONObject?, secondary: JSONObject?, key: String): Int =
        (primary?.optLong(key, 0L)?.takeIf { it > 0L }
            ?: secondary?.optLong(key, 0L)?.takeIf { it > 0L }
            ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun positiveLong(primary: JSONObject?, secondary: JSONObject?, key: String): Long =
        primary?.optLong(key, 0L)?.takeIf { it > 0L }
            ?: secondary?.optLong(key, 0L)?.takeIf { it > 0L }
            ?: 0L

    private fun firstTextOrEmpty(data: JSONObject?, vararg keys: String): String = data?.let { value ->
        keys.asSequence().map { value.optString(it).trim() }.firstOrNull { it.isNotBlank() }
    }.orEmpty()

    private fun isMediaVariantUrl(field: String, url: String, type: MediaType): Boolean {
        val normalizedField = field.lowercase()
        val path = runCatching { URL(url).path.lowercase() }.getOrDefault("")
        return when (type) {
            MediaType.AUDIO -> !MediaFileTypes.isKnownImagePath(path) &&
                !MediaFileTypes.isKnownVideoPath(path) &&
                (MediaFileTypes.isKnownAudioPath(path) || "audio" in normalizedField || "mp3" in normalizedField)
            MediaType.VIDEO, MediaType.LIVE_PHOTO -> !MediaFileTypes.isKnownImagePath(path) &&
                !MediaFileTypes.isKnownAudioPath(path) &&
                (MediaFileTypes.isKnownVideoPath(path) || "video" in normalizedField || "mp4" in normalizedField ||
                    "play" in normalizedField || "stream" in normalizedField || "live" in normalizedField)
            MediaType.IMAGE -> false
        }
    }

    private fun chooseMediaVariant(variants: List<MediaVariant>, quality: QualityPreference): MediaVariant? {
        if (variants.isEmpty()) return null
        return variants.first()
    }

    private fun imageVariantKey(field: String, url: String): String {
        val pathSpec = Regex("sinaimg\\.cn/([^/?]+)/", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)?.lowercase()
        return pathSpec?.takeIf { it.matches(Regex("[a-z][a-z0-9_-]{0,23}")) }
            ?: field.lowercase()
    }

    private fun isImageVariantUrl(url: String): Boolean {
        val path = runCatching { URL(url).path.lowercase() }.getOrDefault("")
        if (path.endsWith(".mp4") || path.endsWith(".mov") || path.endsWith(".m3u8")) return false
        return url.contains("sinaimg.cn/", ignoreCase = true) ||
            path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") ||
            path.endsWith(".webp") || path.endsWith(".gif") || path.endsWith(".heic")
    }

    /** Weibo's viewer upgrades thumbnail/mw/orj URLs to the /large/ original. */
    private fun normalizeOriginalImageUrl(url: String): String =
        if (url.contains("sinaimg.cn", ignoreCase = true)) {
            url.replace(
                Regex("/(thumbnail|thumb150|mw\\d+|orj\\d+|small|bmiddle|wap\\d+|square|woriginal|original)/", RegexOption.IGNORE_CASE),
                "/large/",
            )
        } else url

    private fun chooseThumbnail(picture: JSONObject): String = listOf("bmiddle", "pic_middle", "large", "pic_big", "thumbnail", "pic_small")
        .asSequence().mapNotNull { urlValue(picture.opt(it)) }.firstOrNull().orEmpty()

    private fun chooseLiveVideo(picture: JSONObject): String? {
        for (key in listOf("live_photo", "live_photo_url", "live_video", "video_url", "videoSrc", "video")) {
            when (val value = picture.opt(key)) {
                is String -> normalizeUrl(value)
                    ?.takeIf { isMediaVariantUrl(key, it, MediaType.LIVE_PHOTO) }
                    ?.let { return it }
                is JSONObject -> firstUrl(value, listOf("stream_url_hd", "url", "video_url"))
                    ?.takeIf { isMediaVariantUrl(key, it, MediaType.LIVE_PHOTO) }
                    ?.let { return it }
            }
        }
        if (picture.optString("type").equals("livephoto", true)) {
            picture.optString("fid").takeIf { it.isNotBlank() }?.let { fid ->
                val cleanFid = fid.removeSuffix(".mov")
                return livePhotoPlaybackUrl(cleanFid)
            }
        }
        return null
    }

    /** Builds the current Weibo Live Photo playback endpoint from a fid or source URL. */
    private fun livePhotoPlaybackUrl(value: String): String {
        val normalized = normalizeUrl(value)
        if (normalized?.contains("video.weibo.com/media/play", ignoreCase = true) == true) return normalized
        val source = if (normalized != null && normalized.endsWith(".mov", ignoreCase = true)) {
            normalized
        } else {
            "https://livephoto.us.sinaimg.cn/${value.trim().removePrefix("/").removeSuffix(".mov")}.mov"
        }
        return "https://video.weibo.com/media/play?livephoto=${URLEncoder.encode(source, "UTF-8")}"
    }

    private fun thumbnailFrom(data: JSONObject, depth: Int = 0): String {
        THUMBNAIL_FIELDS.asSequence()
            .mapNotNull { urlValue(data.opt(it)) }
            .firstOrNull(::isImageVariantUrl)
            ?.let { return it }
        if (depth >= 2) return ""
        return THUMBNAIL_CONTAINER_FIELDS.asSequence()
            .mapNotNull { data.optJSONObject(it) }
            .map { thumbnailFrom(it, depth + 1) }
            .firstOrNull(String::isNotBlank)
            .orEmpty()
    }

    private fun urlValue(value: Any?): String? = when (value) {
        is String -> normalizeUrl(value)
        is JSONObject -> normalizeUrl(value.optString("url"))
            ?: normalizeUrl(value.optString("src"))
            ?: normalizeUrl(value.optString("image_url"))
        else -> null
    }

    private fun normalizeUrl(value: String): String? = value.trim().takeIf { it.isNotBlank() }?.let {
        when {
            it.startsWith("https://", true) || it.startsWith("http://", true) -> it
            it.startsWith("//") -> "https:$it"
            else -> null
        }
    }

    private fun firstUrl(data: JSONObject, keys: List<String>): String? = keys.asSequence().mapNotNull { urlValue(data.opt(it)) }.firstOrNull()
    private fun hasAny(data: JSONObject, keys: List<String>) = keys.any { urlValue(data.opt(it)) != null }

    private data class ComponentVideoVariant(val order: Int, val label: String, val url: String, val score: Int)

    private fun componentVideoVariants(playInfo: JSONObject): List<MediaVariant> {
        val urls = playInfo.optJSONObject("urls")
        if (urls != null) {
            val raw = ArrayList<ComponentVideoVariant>()
            urls.keys().forEach { label ->
                urlValue(urls.opt(label))
                    ?.takeIf { isMediaVariantUrl(label, it, MediaType.VIDEO) }
                    ?.let { url ->
                    raw += ComponentVideoVariant(raw.size, label, url, componentQualityScore(label))
                }
            }
            if (raw.isNotEmpty()) {
                val seenUrls = HashSet<String>()
                val seenKeys = HashSet<String>()
                return raw.sortedWith(
                    compareByDescending<ComponentVideoVariant> { it.score }.thenBy { it.order },
                ).mapNotNull { variant ->
                    val key = variant.label.ifBlank { "variant ${variant.order + 1}" }
                    if (!seenUrls.add(variant.url) || !seenKeys.add(key)) return@mapNotNull null
                    MediaVariant(key, variant.url)
                }
            }
        }
        val mediaInfo = playInfo.optJSONObject("media_info") ?: playInfo
        return playbackVariants(mediaInfo.optJSONArray("playback_list"))
            .ifEmpty { collectDirectMediaVariants(mediaInfo, MediaType.VIDEO) }
    }

    private fun bestComponentVideo(playInfo: JSONObject, quality: QualityPreference): String? {
        return chooseMediaVariant(componentVideoVariants(playInfo), quality)?.url
    }

    private fun componentQualityScore(label: String): Int {
        val normalized = label.lowercase()
        Regex("(?:^|[^0-9])(4320|2160|1440|1080|720|480|360)(?:p|[^0-9]|$)")
            .find(normalized)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return when {
            "8k" in normalized || "原画" in normalized || "original" in normalized || "source" in normalized -> 4320
            "4k" in normalized -> 2160
            "2k" in normalized -> 1440
            "超清" in normalized -> 1080
            "高清" in normalized || "high" in normalized -> 720
            "标清" in normalized || "standard" in normalized -> 480
            "流畅" in normalized || "smooth" in normalized -> 360
            else -> 0
        }
    }

    private fun bestDirectVideo(data: JSONObject, quality: QualityPreference): String? = videoKeys(quality)
        .asSequence()
        .mapNotNull { key ->
            urlValue(data.opt(key))?.takeIf { isMediaVariantUrl(key, it, MediaType.VIDEO) }
        }
        .firstOrNull()

    private fun bestPlaybackUrl(list: JSONArray?, quality: QualityPreference): String? {
        return chooseMediaVariant(playbackVariants(list), quality)?.url
    }

    private fun playbackVariants(list: JSONArray?): List<MediaVariant> {
        if (list == null) return emptyList()
        data class Ranked(
            val variant: MediaVariant,
            val inferredHeight: Int,
            val isOriginal: Boolean,
            val tier: Int,
            val qualityIndex: Long,
            val order: Int,
        )
        val variants = ArrayList<Ranked>()
        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index) ?: continue
            val info = item.optJSONObject("play_info") ?: item
            val url = listOf("url", "stream_url", "play_url").asSequence()
                .mapNotNull { key ->
                    urlValue(info.opt(key))?.takeIf { isMediaVariantUrl(key, it, MediaType.VIDEO) }
                }
                .firstOrNull()
                ?: continue
            val meta = item.optJSONObject("meta")
            val qualityIndex = meta?.optLong("quality_index", item.optLong("quality_index", 0L)) ?: 0L
            val width = positiveInt(info, meta, "width")
            val height = positiveInt(info, meta, "height")
            val bitrate = positiveLong(info, meta, "bitrate")
                .takeIf { it > 0L } ?: item.optLong("bitrate", 0L)
            val label = firstTextOrEmpty(meta, "quality_desc", "quality_label", "quality_name", "label", "name")
                .ifBlank { firstTextOrEmpty(item, "quality_desc", "quality_label", "quality_name", "label", "name") }
            val key = when {
                label.isNotBlank() -> label
                height > 0 -> "${height}p"
                bitrate > 0L -> "${(bitrate / 1000L).coerceAtLeast(1L)} kbps"
                else -> "variant ${index + 1}"
            }
            val quality = MediaVariantSelector.inferVideoQuality(key, url)
            variants += Ranked(
                variant = MediaVariant(key, url, width, height, bitrate),
                inferredHeight = quality.height,
                isOriginal = quality.isOriginal,
                tier = quality.tier,
                qualityIndex = qualityIndex,
                order = index,
            )
        }
        val seenUrls = HashSet<String>()
        val seenKeys = HashSet<String>()
        return variants.sortedWith(
            compareByDescending<Ranked> { it.isOriginal }
                .thenByDescending { maxOf(it.variant.height, it.inferredHeight) }
                .thenByDescending { it.variant.pixelArea }
                .thenByDescending { it.variant.bitrate }
                .thenByDescending { it.tier }
                .thenByDescending { it.qualityIndex }
                .thenBy { it.order },
        ).mapNotNull { ranked ->
            if (!seenUrls.add(ranked.variant.url) || !seenKeys.add(ranked.variant.key)) null else ranked.variant
        }
    }

    private fun imageQualityLabel(quality: QualityPreference) = MediaQuality.ORIGINAL_BEST
    private fun videoKeys(quality: QualityPreference) = listOf(
        "mp4_2160p_mp4", "mp4_4k_mp4", "hevc_mp4_2160p", "mp4_1080p_mp4", "hevc_mp4_1080p",
        "mp4_720p_mp4", "hevc_mp4_720p", "h265_mp4_hd", "inch_5_5_mp4_hd", "inch_5_mp4_hd",
        "inch_4_mp4_hd", "mp4_hd_url", "hd_url", "stream_url_hd", "mp4_sd_url", "stream_url",
        "original_url", "play_url", "url", "videoSrc", "video",
    )
    private fun videoQualityLabel(quality: QualityPreference, fromPlaylist: Boolean) =
        if (fromPlaylist) MediaQuality.PLAYLIST_MAX else MediaQuality.HD_BEST
    private fun firstText(data: JSONObject, vararg keys: String): String = keys.asSequence()
        .map { data.optString(it).trim() }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()

    private fun cleanText(value: String) = value.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").trim()

    private companion object {
        const val IMAGE_VARIANT_ORIGINAL = "original"
        val MEDIA_DIMENSIONS_PATTERN = Regex("(?:template=|/)(\\d{2,5})x(\\d{2,5})(?:[./?&]|$)", RegexOption.IGNORE_CASE)
        val THUMBNAIL_FIELDS = listOf(
            "page_pic", "pagePic", "page_pic_url", "pagePicUrl",
            "cover_image", "coverImage", "cover_image_url", "coverImageUrl",
            "cover_url", "coverUrl", "cover", "poster", "poster_url", "posterUrl",
            "pic", "pic_url", "picUrl", "thumbnail", "thumbnail_url", "thumbnailUrl",
            "big_pic", "image", "image_url", "snapshot", "snapshot_url",
        )
        val THUMBNAIL_CONTAINER_FIELDS = listOf(
            "big_pic_info", "page_info", "media_info", "video_info", "video_details", "play_info", "data",
        )
    }
}

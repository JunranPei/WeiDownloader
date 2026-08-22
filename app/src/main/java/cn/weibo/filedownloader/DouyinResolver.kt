package cn.weibo.filedownloader

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal data class DouyinResolvedLink(
    val sourceUrl: String,
    val canonicalUrl: String,
    val postIds: List<String>,
) {
    val primaryPostId: String get() = postIds.firstOrNull().orEmpty()
}

/**
 * Treats a Douyin URL as a locator only. Route names are hints for finding an
 * aweme id; the detail response, not the route, decides the media type.
 */
internal object DouyinLinkParser {
    private val urlRegex = Regex("https?://[^\\s\\[\\]()<>]+", RegexOption.IGNORE_CASE)
    private val knownRouteIdRegex =
        Regex("/(?:share/)?(?:video|note|gallery|slides)/(\\d{17,20})(?:[/\\s?#]|$)", RegexOption.IGNORE_CASE)
    private val numericPathIdRegex = Regex("/(\\d{17,20})(?:[/\\s?#]|$)")
    private val knownQueryKeys = setOf("modal_id", "aweme_id", "item_id", "video_id", "note_id")

    fun extractUrl(value: String): String {
        val cleaned = value.trim().replace("\\u002F", "/").replace("\\/", "/")
        return (urlRegex.find(cleaned)?.value ?: cleaned)
            .trimEnd('.', ',', '!', '?', '，', '。', '！', '？', '）', ')', ']', '；', ';')
    }

    fun isSupportedShareUrl(value: String): Boolean {
        val host = runCatching { URI(extractUrl(value)).host.orEmpty().lowercase() }.getOrDefault("")
        return host == "douyin.com" || host.endsWith(".douyin.com") ||
            host == "iesdouyin.com" || host.endsWith(".iesdouyin.com")
    }

    fun extractPostIds(value: String): List<String> {
        val result = linkedSetOf<String>()
        knownRouteIdRegex.findAll(value).forEach { result += it.groupValues[1] }
        runCatching { URI(extractUrl(value)) }.getOrNull()?.let { uri ->
            uri.rawQuery.orEmpty().split('&').forEach { pair ->
                val key = pair.substringBefore('=').lowercase()
                if (key !in knownQueryKeys) return@forEach
                val decoded = runCatching {
                    URLDecoder.decode(pair.substringAfter('=', ""), StandardCharsets.UTF_8.name())
                }.getOrDefault("")
                Regex("^\\d{17,20}$").matchEntire(decoded)?.value?.let(result::add)
            }
            numericPathIdRegex.findAll(uri.path.orEmpty()).forEach { result += it.groupValues[1] }
        }
        return result.toList()
    }

    fun extractPostId(value: String): String = extractPostIds(value).firstOrNull().orEmpty()
}

internal class DouyinLinkResolver {
    fun resolve(sharedText: String): DouyinResolvedLink {
        val sourceUrl = DouyinLinkParser.extractUrl(sharedText)
        require(sourceUrl.isNotBlank() && DouyinLinkParser.isSupportedShareUrl(sourceUrl))
        val sourceIds = DouyinLinkParser.extractPostIds(sourceUrl)
        if (sourceIds.isNotEmpty()) return DouyinResolvedLink(sourceUrl, sourceUrl, sourceIds)

        val connection = (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", SHARE_USER_AGENT)
        }
        val canonicalUrl = try {
            connection.inputStream.use { input -> input.read(ByteArray(1)) }
            connection.url?.toString().orEmpty().ifBlank { sourceUrl }
        } finally {
            connection.disconnect()
        }
        val ids = DouyinLinkParser.extractPostIds(canonicalUrl)
        require(ids.isNotEmpty())
        return DouyinResolvedLink(sourceUrl, canonicalUrl, ids)
    }

    private companion object {
        const val SHARE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36"
    }
}

internal data class DouyinWorkData(
    val id: String,
    val title: String,
    val author: String,
    val media: List<MediaItem>,
)

internal interface DouyinDetailProvider {
    val name: String
    fun load(target: DouyinResolvedLink, postId: String): DouyinWorkData?
}

/** Uses the same public web-detail response as Douyin's desktop site. */
internal class DouyinWebDetailProvider(private val aid: String) : DouyinDetailProvider {
    override val name: String = "web-detail-aid-$aid"

    override fun load(target: DouyinResolvedLink, postId: String): DouyinWorkData? {
        val params = linkedMapOf(
            "aweme_id" to postId,
            "aid" to aid,
            "device_platform" to "webapp",
            "channel" to "channel_pc_web",
            "pc_client_type" to "1",
            "version_code" to "290100",
            "version_name" to "29.1.0",
            "update_version_code" to "170400",
            "cookie_enabled" to "true",
            "source" to "Googlebot-sdc",
        )
        val query = params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8.name())}=" +
                URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        }
        val connection = (URL("$DETAIL_ENDPOINT?$query").openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", WEB_USER_AGENT)
            setRequestProperty("Accept", "application/json,text/plain,*/*")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            setRequestProperty("Referer", target.canonicalUrl)
        }
        val body = try {
            BufferedInputStream(connection.inputStream).use { it.readBytes().toString(StandardCharsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
        if (body.isBlank()) return null
        val response = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val detail = response.optJSONObject("aweme_detail") ?: return null
        return DouyinWorkNormalizer.normalize(detail, postId)
    }

    private companion object {
        const val DETAIL_ENDPOINT = "https://www.douyin.com/aweme/v1/web/aweme/detail/"
        const val WEB_USER_AGENT = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
    }
}

internal object DouyinWorkNormalizer {
    fun normalize(detail: JSONObject, expectedPostId: String): DouyinWorkData? {
        val actualId = detail.stringFrom("aweme_id", "awemeId", "id")
        if (actualId.isBlank() || actualId != expectedPostId) return null

        val images = parseImages(detail, actualId)
        val media = if (images.isNotEmpty()) {
            images
        } else {
            parseVideo(detail.optObjectFrom("video", "video_info"), actualId, 1, MediaType.VIDEO)
                ?.let(::listOf)
                .orEmpty()
        }
        if (media.isEmpty()) return null
        return DouyinWorkData(
            id = actualId,
            title = detail.stringFrom("desc", "title"),
            author = detail.optJSONObject("author")?.stringFrom("nickname", "name").orEmpty(),
            media = media,
        )
    }

    private fun parseImages(detail: JSONObject, postId: String): List<MediaItem> {
        val containers = listOfNotNull(
            detail.optJSONObject("image_post_info")?.optArrayFrom("images", "image_list"),
            detail.optArrayFrom("images", "image_list"),
        )
        val source = containers.firstOrNull { it.length() > 0 } ?: return emptyList()
        return buildList {
            for (index in 0 until source.length()) {
                val raw = source.opt(index)
                val image = raw as? JSONObject
                val stillUrl = preferredImageUrl(raw) ?: continue
                val width = image?.intFrom("width", "width_px") ?: 0
                val height = image?.intFrom("height", "height_px") ?: 0
                val liveVideo = image?.optObjectFrom("video", "clip_video", "live_photo_video")
                    ?.let { parseVideo(it, postId, size + 1, MediaType.LIVE_PHOTO, stillUrl) }
                if (liveVideo != null) {
                    add(liveVideo)
                    continue
                }
                val key = if (width > 0 && height > 0) "${width}×${height}" else MediaQuality.PLATFORM_BEST
                add(
                    MediaItem(
                        type = MediaType.IMAGE,
                        url = stillUrl,
                        quality = MediaQuality.PLATFORM_BEST,
                        postId = postId,
                        ordinal = size + 1,
                        thumbnailUrl = stillUrl,
                        variants = listOf(MediaVariant(key, stillUrl, width, height)),
                        selectedVariantKey = key,
                    ),
                )
            }
        }
    }

    private fun parseVideo(
        video: JSONObject?,
        postId: String,
        ordinal: Int,
        type: MediaType,
        coverUrl: String = "",
    ): MediaItem? {
        if (video == null) return null
        val variants = buildList {
            val rates = video.optArrayFrom("bit_rate", "bitRate", "bitrate_info")
            if (rates != null) {
                for (index in 0 until rates.length()) {
                    val rate = rates.optJSONObject(index) ?: continue
                    val address = rate.optObjectFrom("play_addr", "playAddr", "play_address") ?: continue
                    val url = preferredVideoUrl(address) ?: continue
                    val width = address.intFrom("width")
                    val height = address.intFrom("height")
                    val bitrate = rate.longFrom("bit_rate", "bitRate", "bitrate")
                    val codec = when {
                        rate.intFrom("is_h265", "isH265") == 1 -> "H.265"
                        rate.stringFrom("codec_type", "codecType").contains("265", true) -> "H.265"
                        else -> "H.264"
                    }
                    val resolution = if (height > 0) "${height}p" else rate.stringFrom("gear_name", "gearName")
                        .ifBlank { "Variant ${index + 1}" }
                    val bitrateLabel = if (bitrate > 0) " · ${(bitrate / 1000L).coerceAtLeast(1L)} kbps" else ""
                    add(MediaVariant("$resolution · $codec$bitrateLabel", url, width, height, bitrate))
                }
            }
            for (field in VIDEO_ADDRESS_FIELDS) {
                val address = video.optJSONObject(field) ?: continue
                val url = preferredVideoUrl(address) ?: continue
                add(MediaVariant(field, url, address.intFrom("width"), address.intFrom("height")))
            }
        }.distinctBy { it.url }
        if (variants.isEmpty()) return null
        val ranked = MediaVariantSelector.rank(type, variants)
        val best = ranked.first()
        val poster = coverUrl.ifBlank {
            VIDEO_THUMBNAIL_FIELDS.asSequence()
                .mapNotNull { preferredThumbnailUrl(video.opt(it)) }
                .firstOrNull()
                .orEmpty()
        }
        return MediaItem(
            type = type,
            url = best.url,
            quality = if (type == MediaType.LIVE_PHOTO) MediaQuality.LIVE_ORIGINAL else MediaQuality.HD_BEST,
            postId = postId,
            ordinal = ordinal,
            thumbnailUrl = poster,
            coverUrl = coverUrl,
            variants = ranked,
            selectedVariantKey = best.key,
        )
    }

    private fun preferredImageUrl(value: Any?): String? {
        val objectValue = value as? JSONObject
        for (field in IMAGE_ADDRESS_FIELDS) {
            chooseImageUrl(urlsFrom(objectValue?.opt(field)))?.let { return it }
        }
        return chooseImageUrl(urlsFrom(value))
    }

    private fun chooseImageUrl(urls: List<String>): String? {
        val trusted = urls.filter(DouyinContentPolicy::isContentImageUrl)
        return trusted.firstOrNull { url ->
            val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
            path.endsWith(".jpeg") || path.endsWith(".jpg") || path.endsWith(".png")
        } ?: trusted.firstOrNull()
    }

    private fun preferredVideoUrl(value: Any?): String? = VIDEO_URL_FIELDS.asSequence()
        .flatMap { field -> urlsFrom((value as? JSONObject)?.opt(field)).asSequence() }
        .plus(urlsFrom(value).asSequence())
        .firstOrNull(DouyinContentPolicy::isTrustedVideoResource)

    private fun preferredThumbnailUrl(value: Any?): String? = IMAGE_ADDRESS_FIELDS.asSequence()
        .flatMap { field -> urlsFrom((value as? JSONObject)?.opt(field)).asSequence() }
        .plus(urlsFrom(value).asSequence())
        .firstOrNull(DouyinContentPolicy::isTrustedImageResource)

    private fun urlsFrom(value: Any?, depth: Int = 0): List<String> {
        if (depth > 3 || value == null || value == JSONObject.NULL) return emptyList()
        return when (value) {
            is String -> listOf(value).filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
            is JSONArray -> buildList {
                for (index in 0 until value.length()) addAll(urlsFrom(value.opt(index), depth + 1))
            }
            is JSONObject -> URL_CONTAINER_FIELDS.flatMap { urlsFrom(value.opt(it), depth + 1) }
            else -> emptyList()
        }.distinct()
    }

    private fun JSONObject.optArrayFrom(vararg fields: String): JSONArray? =
        fields.firstNotNullOfOrNull { optJSONArray(it) }

    private fun JSONObject.optObjectFrom(vararg fields: String): JSONObject? =
        fields.firstNotNullOfOrNull { optJSONObject(it) }

    private fun JSONObject.stringFrom(vararg fields: String): String =
        fields.asSequence().map(::optString).firstOrNull(String::isNotBlank).orEmpty()

    private fun JSONObject.intFrom(vararg fields: String): Int =
        fields.asSequence().map(::optInt).firstOrNull { it > 0 } ?: 0

    private fun JSONObject.longFrom(vararg fields: String): Long =
        fields.asSequence().map(::optLong).firstOrNull { it > 0L } ?: 0L

    private val IMAGE_ADDRESS_FIELDS = listOf(
        "url_list",
        "urlList",
        "display_image",
        "displayImage",
        "download_url",
        "downloadUrl",
        "download_addr",
        "downloadAddr",
        "download_url_list",
        "downloadUrlList",
    )
    private val VIDEO_ADDRESS_FIELDS = listOf(
        "play_addr_h264",
        "playAddrH264",
        "play_addr",
        "playAddr",
        "play_addr_265",
        "playAddr265",
    )
    private val VIDEO_URL_FIELDS = listOf("url_list", "urlList", "src", "url")
    private val VIDEO_THUMBNAIL_FIELDS = listOf(
        "origin_cover",
        "originCover",
        "cover_original",
        "coverOriginal",
        "cover",
        "dynamic_cover",
        "dynamicCover",
        "animated_cover",
        "animatedCover",
    )
    private val URL_CONTAINER_FIELDS = listOf(
        "url_list",
        "urlList",
        "download_url_list",
        "downloadUrlList",
        "uri",
        "url",
        "src",
    )
}

internal object DouyinContentPolicy {
    fun isScopedVideoUrl(value: String): Boolean = isTrustedVideoResource(value)

    fun isTrustedVideoResource(value: String): Boolean {
        if (!isHttp(value) || isKnownSiteAsset(value) || isImage(value)) return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty().lowercase()
        val vodHost = host == "douyinvod.com" || host.endsWith(".douyinvod.com") ||
            host == "idouyinvod.com" || host.endsWith(".idouyinvod.com")
        val playApiHost = host == "douyin.com" || host.endsWith(".douyin.com") ||
            host == "aweme.snssdk.com" || host.endsWith(".aweme.snssdk.com")
        return vodHost || (playApiHost && (path.contains("/aweme/v1/play/") || path.contains("/aweme/v1/playwm/")))
    }

    fun isContentImageUrl(value: String): Boolean {
        if (!isTrustedImageResource(value)) return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val path = uri.path.orEmpty().lowercase()
        return path.contains("tos-cn-i-") || path.contains("aweme-images") || path.contains("aweme_images")
    }

    fun isTrustedImageResource(value: String): Boolean {
        if (!isHttp(value) || isKnownSiteAsset(value)) return false
        val host = runCatching { URI(value).host.orEmpty().lowercase() }.getOrDefault("")
        return host == "douyinpic.com" || host.endsWith(".douyinpic.com") ||
            host == "byteimg.com" || host.endsWith(".byteimg.com")
    }

    private fun isKnownSiteAsset(value: String): Boolean {
        val normalized = value.lowercase()
        return normalized.contains("lf-douyin-pc-web.douyinstatic.com/obj/douyin-pc-web/") ||
            Regex("/(?:uuu|douyin_pc_client)(?:_[^/?]+)?\\.mp4(?:[?#]|$)").containsMatchIn(normalized)
    }

    private fun isImage(value: String): Boolean {
        val path = runCatching { URI(value).path.orEmpty().lowercase() }.getOrDefault("")
        return listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".heic", ".heif", ".avif").any(path::endsWith)
    }

    private fun isHttp(value: String): Boolean = value.startsWith("https://", true) || value.startsWith("http://", true)
}

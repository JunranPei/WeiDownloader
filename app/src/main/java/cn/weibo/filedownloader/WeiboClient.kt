package cn.weibo.filedownloader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Resolves public video components and posts visible to the current Weibo session. */
class WeiboClient(
    private val cookie: String,
    private val context: Context,
    private val includeLiveCover: Boolean = false,
) {
    companion object {
        private const val AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36"
    }

    fun loadPost(sharedUrl: String, quality: QualityPreference = QualityPreference.HIGHEST): WeiboPost {
        val sourceUrl = extractUrl(sharedUrl)
        val resolvedUrl = resolveRedirects(sourceUrl)
        val target = WeiboLinkParser.targetFromUrl(resolvedUrl)
            ?: WeiboLinkParser.targetFromUrl(sourceUrl)
            ?: throw IllegalArgumentException(context.getString(R.string.post_id_not_found))
        return when (target) {
            is WeiboLinkTarget.Status -> loadStatusPost(target.id, sourceUrl, quality)
            is WeiboLinkTarget.VideoObject -> runCatching {
                loadVideoObject(target.objectId, sourceUrl, quality)
            }.getOrElse { videoError ->
                target.fallbackStatusId?.let { loadStatusPost(it, sourceUrl, quality) } ?: throw videoError
            }
        }
    }

    private fun loadStatusPost(id: String, sourceUrl: String, quality: QualityPreference): WeiboPost {
        if (!cookie.contains("SUB=")) throw WeiboLoginRequiredException(context.getString(R.string.client_login_required))
        // The mobile endpoint sometimes rejects a desktop bid even though the desktop endpoint accepts it.
        val data = runCatching {
            getJson("https://m.weibo.cn/statuses/show?id=" + URLEncoder.encode(id, "UTF-8")).optJSONObject("data")
        }.getOrNull() ?: getDesktopPost(id)
        val postId = data.optString("id", id)
        val defaultAuthor = context.getString(R.string.default_author)
        val author = data.optJSONObject("user")?.optString("screen_name", defaultAuthor) ?: defaultAuthor
        val media = parseMedia(data, quality, postId)
        if (media.isEmpty()) throw WeiboException(context.getString(R.string.no_media))
        return WeiboPost(postId, cleanText(data.optString("text_raw", data.optString("text"))), author, sourceUrl, media)
    }

    private fun loadVideoObject(objectId: String, sourceUrl: String, quality: QualityPreference): WeiboPost {
        val payload = getVideoComponent(objectId)
        val playInfo = payload.optJSONObject("data")?.optJSONObject("Component_Play_Playinfo")
            ?: payload.optJSONObject("Component_Play_Playinfo")
            ?: throw WeiboException(payload.optString("msg", context.getString(R.string.no_media)))
        val videoUrl = bestComponentVideo(playInfo, quality)
            ?: throw WeiboException(context.getString(R.string.no_media))
        val safeId = objectId.substringAfter(':').ifBlank { objectId.replace(':', '_') }
        val defaultAuthor = context.getString(R.string.default_author)
        val author = firstText(playInfo, "nickname", "author", "author_name", "screen_name").ifBlank { defaultAuthor }
        val text = cleanText(firstText(playInfo, "title", "text", "content1", "content"))
        val thumbnail = thumbnailFrom(playInfo)
        val media = listOf(
            MediaItem(
                type = MediaType.VIDEO,
                url = videoUrl,
                quality = videoQualityLabel(quality, true),
                postId = safeId,
                ordinal = 1,
                thumbnailUrl = thumbnail,
            ),
        )
        return WeiboPost(safeId, text, author, sourceUrl, media)
    }

    internal fun parseMedia(data: JSONObject, quality: QualityPreference, postId: String = data.optString("id")): List<MediaItem> {
        val media = ArrayList<MediaItem>()
        val seen = HashSet<String>()
        val source = findMediaSource(data)
        parseStatusMedia(source, data, quality, postId, media, seen)
        return media
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
        parsePics(source.optJSONArray("pics"), quality, postId, result, seen)
        parseMixedMedia(source.optJSONObject("mix_media_info")?.optJSONArray("items"), quality, postId, result, seen)

        val pageInfo = source.optJSONObject("page_info") ?: root.optJSONObject("page_info")
        parsePageInfo(pageInfo, quality, postId, result, seen)
        source.optJSONObject("media_info")?.let {
            parseMediaInfo(it, source, quality, postId, result, seen)
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
        orderedIds.forEach { id -> infos.optJSONObject(id)?.let { parsePicture(it, quality, postId, result, seen) } }
    }

    private fun parsePics(
        pictures: JSONArray?,
        quality: QualityPreference,
        postId: String,
        result: MutableList<MediaItem>,
        seen: MutableSet<String>,
    ) {
        if (pictures == null) return
        for (index in 0 until pictures.length()) {
            val picture = pictures.optJSONObject(index) ?: continue
            if (picture.optString("type").equals("video", true)) {
                val mediaInfo = picture.optJSONObject("media_info")
                if (mediaInfo != null) parseMediaInfo(mediaInfo, picture, quality, postId, result, seen)
                else bestDirectVideo(picture, quality)?.let {
                    add(result, seen, MediaType.VIDEO, it, videoQualityLabel(quality, false), postId, result.size + 1, thumbnailFrom(picture))
                }
            } else {
                parsePicture(picture, quality, postId, result, seen)
            }
        }
    }

    private fun parseMixedMedia(
        items: JSONArray?,
        quality: QualityPreference,
        postId: String,
        result: MutableList<MediaItem>,
        seen: MutableSet<String>,
    ) {
        if (items == null) return
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val data = item.optJSONObject("data") ?: item
            val type = item.optString("type", data.optString("type")).lowercase()
            val objectType = data.optString("object_type").lowercase()
            if (type == "video" || objectType == "video") {
                val mediaInfo = data.optJSONObject("media_info") ?: item.optJSONObject("media_info")
                if (mediaInfo != null) parseMediaInfo(mediaInfo, data, quality, postId, result, seen)
                else bestDirectVideo(data, quality)?.let {
                    add(result, seen, MediaType.VIDEO, it, videoQualityLabel(quality, false), postId, result.size + 1, thumbnailFrom(data))
                }
            } else {
                parsePicture(data.optJSONObject("pic_info") ?: data, quality, postId, result, seen)
            }
        }
    }

    private fun parsePicture(
        picture: JSONObject,
        quality: QualityPreference,
        postId: String,
        result: MutableList<MediaItem>,
        seen: MutableSet<String>,
    ) {
        val thumbnail = chooseThumbnail(picture).ifBlank { thumbnailFrom(picture) }
        val liveUrl = chooseLiveVideo(picture)
        if (liveUrl != null) {
            val cover = if (includeLiveCover) chooseImage(picture, quality).orEmpty() else ""
            add(result, seen, MediaType.LIVE_PHOTO, liveUrl, MediaQuality.LIVE_ORIGINAL, postId, result.size + 1, thumbnail, cover)
        } else {
            chooseImage(picture, quality)?.let {
                add(result, seen, MediaType.IMAGE, it, imageQualityLabel(quality), postId, result.size + 1, thumbnail.ifBlank { it })
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
        parseMediaInfo(mediaInfo, pageInfo, quality, postId, result, seen)
    }

    private fun parseMediaInfo(
        mediaInfo: JSONObject,
        container: JSONObject,
        quality: QualityPreference,
        postId: String,
        result: MutableList<MediaItem>,
        seen: MutableSet<String>,
    ) {
        val thumbnail = thumbnailFrom(container).ifBlank { thumbnailFrom(mediaInfo) }
        val type = container.optString("type").lowercase()
        val objectType = container.optString("object_type").lowercase()
        val audioUrl = firstUrl(mediaInfo, listOf("audio_url_hd", "audio_url", "mp3_url", "stream_url"))
        val isAudio = type == "audio" || objectType == "audio" || hasAny(mediaInfo, listOf("audio_url", "mp3_url", "audio_url_hd"))
        if (isAudio && audioUrl != null) {
            add(result, seen, MediaType.AUDIO, audioUrl, MediaQuality.AUDIO_BEST, postId, result.size + 1, thumbnail)
            return
        }
        val playlistVideo = bestPlaybackUrl(mediaInfo.optJSONArray("playback_list"), quality)
        val directVideo = bestDirectVideo(mediaInfo, quality)
        (playlistVideo ?: directVideo)?.let {
            add(result, seen, MediaType.VIDEO, it, videoQualityLabel(quality, playlistVideo != null), postId, result.size + 1, thumbnail)
        }
    }

    private fun findMediaSource(status: JSONObject): JSONObject {
        if (hasMediaCollection(status)) return status
        status.optJSONObject("retweeted_status")?.let { forwarded ->
            val forwardedSource = findMediaSource(forwarded)
            if (hasMediaContainer(forwardedSource)) return forwardedSource
        }
        return status
    }

    private fun hasMediaCollection(status: JSONObject): Boolean =
        (status.optJSONObject("pic_infos")?.length() ?: 0) > 0 ||
            (status.optJSONArray("pics")?.length() ?: 0) > 0 ||
            (status.optJSONObject("mix_media_info")?.optJSONArray("items")?.length() ?: 0) > 0

    private fun hasMediaContainer(status: JSONObject): Boolean =
        hasMediaCollection(status) ||
            status.optJSONObject("page_info")?.optJSONObject("media_info") != null ||
            status.optJSONObject("media_info") != null

    private fun add(result: MutableList<MediaItem>, seen: MutableSet<String>, type: MediaType, url: String, quality: String, id: String, order: Int, thumbnailUrl: String = "", coverUrl: String = "") {
        if (url.isNotBlank() && seen.add(url)) result += MediaItem(type, url, quality, id, order, thumbnailUrl, coverUrl)
    }

    private fun getJson(address: String): JSONObject = requestJson(address)

    private fun getVideoComponent(objectId: String): JSONObject {
        val requestData = JSONObject()
            .put("Component_Play_Playinfo", JSONObject().put("oid", objectId))
            .toString()
        val form = "data=${URLEncoder.encode(requestData, "UTF-8")}".toByteArray(StandardCharsets.UTF_8)
        val endpoints = listOf(
            Triple(
                "https://h5.video.weibo.com/api/component?page=${URLEncoder.encode("/show/$objectId", "UTF-8")}",
                "https://h5.video.weibo.com/show/$objectId",
                false,
            ),
            Triple(
                "https://weibo.com/tv/api/component?page=${URLEncoder.encode("/tv/show/$objectId", "UTF-8")}",
                "https://weibo.com/tv/show/$objectId",
                true,
            ),
        )
        var lastError: Exception? = null
        endpoints.forEach { (address, referer, requiresSession) ->
            if (requiresSession && !cookie.contains("SUB=")) return@forEach
            try {
                val response = requestJson(
                    address = address,
                    method = "POST",
                    body = form,
                    referer = referer,
                    contentType = "application/x-www-form-urlencoded; charset=UTF-8",
                )
                if (response.optJSONObject("data")?.optJSONObject("Component_Play_Playinfo") != null ||
                    response.optJSONObject("Component_Play_Playinfo") != null
                ) return response
                lastError = WeiboException(response.optString("msg", context.getString(R.string.no_media)))
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw lastError ?: WeiboException(context.getString(R.string.no_media))
    }

    private fun requestJson(
        address: String,
        method: String = "GET",
        body: ByteArray? = null,
        referer: String = if (address.contains("weibo.com/ajax/")) "https://weibo.com/" else "https://m.weibo.cn/",
        contentType: String? = null,
    ): JSONObject {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 20_000; readTimeout = 30_000
            setRequestProperty("User-Agent", AGENT)
            setRequestProperty("Referer", referer)
            setRequestProperty("Cookie", cookie); setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("X-Requested-With", "XMLHttpRequest")
            xsrfToken()?.let { setRequestProperty("X-XSRF-TOKEN", it) }
            if (contentType != null) setRequestProperty("Content-Type", contentType)
            if (body != null) {
                doOutput = true
                setFixedLengthStreamingMode(body.size)
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
                ?: throw WeiboException(context.getString(R.string.weibo_http_error, status))
            val responseBody = BufferedInputStream(input).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            if (status !in 200..299) throw WeiboException(context.getString(R.string.weibo_http_error, status))
            if (responseBody.isBlank()) throw WeiboException(context.getString(R.string.weibo_empty_response))
            return JSONObject(responseBody)
        } catch (e: WeiboException) { throw e
        } catch (e: Exception) { throw WeiboException(context.getString(R.string.weibo_read_error, e.message ?: context.getString(R.string.network_error)))
        } finally { connection.disconnect() }
    }

    /** Desktop API is the reliable fallback for /uid/bid-style links. */
    private fun getDesktopPost(id: String): JSONObject {
        val address = "https://weibo.com/ajax/statuses/show?id=${URLEncoder.encode(id, "UTF-8")}&locale=zh-CN&isGetLongText=true"
        val payload = getJson(address)
        if (payload.optString("id").isBlank()) {
            throw WeiboException(payload.optString("msg", context.getString(R.string.post_not_returned)))
        }
        return payload
    }

    private fun xsrfToken(): String? = Regex("(?:^|;\\s*)XSRF-TOKEN=([^;]+)")
        .find(cookie)?.groupValues?.get(1)?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }

    /** 微博的“复制”内容可能含有提示文字；只取其中的实际 URL。 */
    private fun extractUrl(value: String): String {
        val found = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE).find(value.trim())?.value
        return found?.trimEnd('.', '。', '，', ',', '！', '!', '）', ')')
            ?: throw IllegalArgumentException(context.getString(R.string.url_not_found))
    }

    /** Resolve short links step-by-step and include the current logged-in session. */
    private fun resolveRedirects(address: String): String {
        var current = address
        repeat(8) {
            val connection = runCatching {
                (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false; connectTimeout = 15_000; readTimeout = 15_000
                    setRequestProperty("User-Agent", AGENT); setRequestProperty("Referer", "https://weibo.com/")
                    setRequestProperty("Cookie", cookie)
                }
            }.getOrElse { return current }
            try {
                val status = connection.responseCode
                val location = connection.getHeaderField("Location")
                if (status !in 300..399 || location.isNullOrBlank()) return current
                current = URL(URL(current), location).toString()
            } catch (_: Exception) { return current
            } finally { connection.disconnect() }
        }
        return current
    }

    private fun chooseImage(picture: JSONObject, quality: QualityPreference): String? {
        val keys = when (quality) {
            QualityPreference.HIGHEST -> listOf("largest", "original", "mw2000", "large", "pic_big", "bmiddle", "pic_middle", "thumbnail", "pic_small")
            QualityPreference.HIGH -> listOf("large", "mw2000", "pic_big", "bmiddle", "pic_middle", "thumbnail", "pic_small")
            QualityPreference.STANDARD -> listOf("bmiddle", "pic_middle", "thumbnail", "pic_small", "large", "pic_big")
        }
        for (key in keys) urlValue(picture.opt(key))?.let { return it }
        return urlValue(picture.opt("url"))
    }

    private fun chooseThumbnail(picture: JSONObject): String = listOf("bmiddle", "pic_middle", "large", "pic_big", "thumbnail", "pic_small")
        .asSequence().mapNotNull { urlValue(picture.opt(it)) }.firstOrNull().orEmpty()

    private fun chooseLiveVideo(picture: JSONObject): String? {
        for (key in listOf("live_photo", "live_photo_url", "live_video", "video_url", "videoSrc", "video")) {
            when (val value = picture.opt(key)) {
                is String -> normalizeUrl(value)?.let { return it }
                is JSONObject -> firstUrl(value, listOf("stream_url_hd", "url", "video_url"))?.let { return it }
            }
        }
        if (picture.optString("type").equals("livephoto", true)) {
            picture.optString("fid").takeIf { it.isNotBlank() }?.let { fid ->
                val cleanFid = fid.removeSuffix(".mov")
                return "https://video.weibo.com/media/play?livephoto=//us.sinaimg.cn/$cleanFid.mov&KID=unistore,videomovSrc"
            }
        }
        return null
    }

    private fun thumbnailFrom(data: JSONObject): String = listOf(
        "page_pic", "pagePic", "cover_image", "cover_url", "cover", "poster", "pic", "thumbnail", "big_pic",
    ).asSequence().mapNotNull { urlValue(data.opt(it)) }.firstOrNull()
        ?: data.optJSONObject("big_pic_info")?.let { chooseImage(it, QualityPreference.HIGHEST) }.orEmpty()

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

    private fun bestComponentVideo(playInfo: JSONObject, quality: QualityPreference): String? {
        val urls = playInfo.optJSONObject("urls")
        if (urls != null) {
            val raw = ArrayList<ComponentVideoVariant>()
            var order = 0
            urls.keys().forEach { label ->
                urlValue(urls.opt(label))?.let { url ->
                    raw += ComponentVideoVariant(order, label, url, componentQualityScore(label))
                    order++
                }
            }
            if (raw.isNotEmpty()) {
                // Weibo places the best stream first; quality labels provide a stronger ordering when present.
                val hasExplicitQuality = raw.any { it.score > 0 }
                val variants = raw.map { variant ->
                    if (variant.score > 0) variant else variant.copy(score = (raw.size - variant.order) * 10)
                }.sortedBy { it.score }
                return when (quality) {
                    QualityPreference.HIGHEST -> variants.last()
                    QualityPreference.HIGH -> if (hasExplicitQuality) {
                        variants.lastOrNull { it.score <= 720 } ?: variants.first()
                    } else {
                        variants[(variants.lastIndex - 1).coerceAtLeast(0)]
                    }
                    QualityPreference.STANDARD -> if (hasExplicitQuality) {
                        variants.lastOrNull { it.score <= 480 } ?: variants.first()
                    } else {
                        variants.first()
                    }
                }.url
            }
        }
        val mediaInfo = playInfo.optJSONObject("media_info") ?: playInfo
        return bestPlaybackUrl(mediaInfo.optJSONArray("playback_list"), quality)
            ?: bestDirectVideo(mediaInfo, quality)
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

    private fun bestDirectVideo(data: JSONObject, quality: QualityPreference): String? = firstUrl(data, videoKeys(quality))

    private fun bestPlaybackUrl(list: JSONArray?, quality: QualityPreference): String? {
        if (list == null) return null
        val variants = ArrayList<Pair<Long, String>>()
        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index) ?: continue
            val info = item.optJSONObject("play_info") ?: item
            val url = firstUrl(info, listOf("url", "stream_url", "play_url")) ?: continue
            val meta = item.optJSONObject("meta")
            val qualityIndex = meta?.optLong("quality_index", item.optLong("quality_index", 0L)) ?: 0L
            val pixels = info.optLong("width", 0L) * info.optLong("height", 0L)
            val bitrate = info.optLong("bitrate", item.optLong("bitrate", 0L))
            variants += (qualityIndex * 1_000_000_000_000L + pixels * 1_000L + bitrate) to url
        }
        val sorted = variants.sortedBy { it.first }
        if (sorted.isEmpty()) return null
        return when (quality) {
            QualityPreference.HIGHEST -> sorted.last()
            QualityPreference.HIGH -> sorted.getOrElse((sorted.lastIndex * 2 + 1) / 3) { sorted.last() }
            QualityPreference.STANDARD -> sorted[sorted.lastIndex / 2]
        }.second
    }

    private fun imageQualityLabel(quality: QualityPreference) = when (quality) {
        QualityPreference.HIGHEST -> MediaQuality.ORIGINAL_BEST
        QualityPreference.HIGH -> MediaQuality.HIGH
        QualityPreference.STANDARD -> MediaQuality.STANDARD
    }
    private fun videoKeys(quality: QualityPreference) = when (quality) {
        QualityPreference.HIGHEST -> listOf(
            "mp4_2160p_mp4", "mp4_4k_mp4", "hevc_mp4_2160p", "mp4_1080p_mp4", "hevc_mp4_1080p",
            "mp4_720p_mp4", "hevc_mp4_720p", "h265_mp4_hd", "inch_5_5_mp4_hd", "inch_5_mp4_hd",
            "inch_4_mp4_hd", "mp4_hd_url", "hd_url", "stream_url_hd", "mp4_sd_url", "stream_url",
            "original_url", "play_url", "url", "videoSrc", "video",
        )
        QualityPreference.HIGH -> listOf(
            "mp4_720p_mp4", "hevc_mp4_720p", "mp4_1080p_mp4", "mp4_hd_url", "stream_url_hd",
            "hd_url", "mp4_sd_url", "stream_url", "play_url", "url", "videoSrc", "video",
        )
        QualityPreference.STANDARD -> listOf(
            "mp4_sd_url", "stream_url", "mp4_hd_url", "stream_url_hd", "play_url", "url", "videoSrc", "video",
        )
    }
    private fun videoQualityLabel(quality: QualityPreference, fromPlaylist: Boolean) = when (quality) {
        QualityPreference.HIGHEST -> if (fromPlaylist) MediaQuality.PLAYLIST_MAX else MediaQuality.HD_BEST
        QualityPreference.HIGH -> MediaQuality.HIGH
        QualityPreference.STANDARD -> MediaQuality.STANDARD
    }
    private fun firstText(data: JSONObject, vararg keys: String): String = keys.asSequence()
        .map { data.optString(it).trim() }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()

    private fun cleanText(value: String) = value.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").trim()
}

class WeiboException(message: String) : RuntimeException(message)
class WeiboLoginRequiredException(message: String) : RuntimeException(message)

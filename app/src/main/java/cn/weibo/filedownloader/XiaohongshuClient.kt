package cn.weibo.filedownloader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

internal object XiaohongshuLinkParser {
    private val hosts = setOf(
        "xiaohongshu.com", "www.xiaohongshu.com",
        "xhslink.com", "www.xhslink.com",
        "xhslink.cn", "www.xhslink.cn",
    )
    private val urlRegex = Regex("https?://[^\\s<>\\\"']+", RegexOption.IGNORE_CASE)

    fun extractUrl(value: String): String = (urlRegex.find(value.trim())?.value ?: value.trim())
        .trimEnd('.', ',', '!', '?', ')', ']', '。', '！', '？', '，', '；', ';')

    fun isSupported(value: String): Boolean = runCatching {
        URI(extractUrl(value)).host.orEmpty().lowercase() in hosts
    }.getOrDefault(false)
}

internal data class XiaohongshuPostData(
    val id: String,
    val title: String,
    val author: String,
    val authorId: String,
    val avatarUrl: String,
    val media: List<MediaItem>,
)

internal object XiaohongshuPageNormalizer {
    private val pageStateMarkers = listOf(
        "window.__INITIAL_STATE__",
        "window.__SETUP_SERVER_STATE__",
    )

    fun normalize(html: String, fallbackId: String = "xhs"): XiaohongshuPostData? {
        val note = pageStateMarkers.asSequence().mapNotNull { marker ->
            val markerStart = html.indexOf(marker)
            if (markerStart < 0) return@mapNotNull null
            val stateStart = html.indexOf('{', markerStart)
            val scriptEnd = html.indexOf("</script>", stateStart)
            if (stateStart < 0 || scriptEnd < 0) return@mapNotNull null
            val stateText = html.substring(stateStart, scriptEnd).trim().removeSuffix(";")
            runCatching { JSONObject(stateText) }.getOrNull()?.let(::findNote)
        }.firstOrNull() ?: return null
        val id = note.optString("noteId").ifBlank { fallbackId }
        val user = note.optJSONObject("user") ?: JSONObject()
        val author = user.optString("nickName").ifBlank { user.optString("nickname") }
        val images = parseImages(note.optJSONArray("imageList"), id)
        val videoThumbnailUrl = images.firstOrNull()?.let { image ->
            image.thumbnailUrl.ifBlank { image.url }
        }.orEmpty()
        val videos = parseVideos(note.optJSONObject("video"), id, videoThumbnailUrl)
        val media = if (videos.isNotEmpty()) videos else images
        if (media.isEmpty()) return null
        return XiaohongshuPostData(
            id = id,
            title = note.optString("title").ifBlank { note.optString("desc") },
            author = author,
            authorId = user.optString("userId"),
            avatarUrl = secureUrl(user.optString("avatar")),
            media = media,
        )
    }

    private fun findNote(value: Any?): JSONObject? = when (value) {
        is JSONObject -> {
            if (value.has("noteId") && (value.has("imageList") || value.has("video"))) value
            else value.keys().asSequence().mapNotNull { findNote(value.opt(it)) }.firstOrNull()
        }
        is JSONArray -> (0 until value.length()).asSequence().mapNotNull { findNote(value.opt(it)) }.firstOrNull()
        else -> null
    }

    private fun parseImages(values: JSONArray?, postId: String): List<MediaItem> = buildList {
        if (values == null) return@buildList
        for (index in 0 until values.length()) {
            val image = values.optJSONObject(index) ?: continue
            val width = image.optInt("width")
            val height = image.optInt("height")
            val previewUrl = image.optJSONArray("infoList")?.let { info ->
                (0 until info.length()).asSequence()
                    .mapNotNull { info.optJSONObject(it) }
                    .firstOrNull { it.optString("imageScene") == "H5_DTL" }
                    ?.optString("url")
            }.orEmpty().ifBlank { image.optString("url") }.let(::secureUrl)
            val fileId = image.optString("fileId").trim().trimStart('/')
            val originalUrl = originalImageUrl(fileId, previewUrl)
            val selectedUrl = originalUrl.ifBlank { previewUrl }
            if (selectedUrl.isBlank()) continue
            val variants = buildList {
                if (originalUrl.isNotBlank()) add(MediaVariant("original", originalUrl, width, height))
                if (previewUrl.isNotBlank() && previewUrl != originalUrl) {
                    add(MediaVariant("web", previewUrl))
                }
            }
            val variant = variants.firstOrNull() ?: continue
            add(
                MediaItem(
                    type = MediaType.IMAGE,
                    url = selectedUrl,
                    quality = MediaQuality.ORIGINAL_BEST,
                    postId = postId,
                    ordinal = size + 1,
                    thumbnailUrl = previewUrl,
                    variants = variants,
                    selectedVariantKey = variant.key,
                ),
            )
        }
    }

    private fun originalImageUrl(fileId: String, previewUrl: String): String {
        if (fileId.isBlank()) return ""
        val host = runCatching { URI(previewUrl).host.orEmpty() }.getOrDefault("")
            .replace("sns-webpic", "sns-img")
            .takeIf { it.endsWith(".xhscdn.com") }
            ?: "sns-img-qc.xhscdn.com"
        return "https://$host/$fileId"
    }

    private fun parseVideos(video: JSONObject?, postId: String, thumbnailUrl: String): List<MediaItem> {
        if (video == null) return emptyList()
        val candidates = mutableListOf<MediaVariant>()
        collectVideoUrls(video, candidates)
        val ranked = MediaVariantSelector.rank(MediaType.VIDEO, candidates.distinctBy(MediaVariant::url))
        val selected = ranked.firstOrNull() ?: return emptyList()
        return listOf(MediaItem(MediaType.VIDEO, selected.url, MediaQuality.PLATFORM_BEST, postId, 1, thumbnailUrl = thumbnailUrl, variants = ranked, selectedVariantKey = selected.key))
    }

    private fun collectVideoUrls(value: Any?, result: MutableList<MediaVariant>, key: String = "video") {
        when (value) {
            is JSONObject -> {
                val width = value.optInt("width")
                val height = value.optInt("height")
                val bitrate = value.optLong("bitrate", value.optLong("bit_rate", 0L))
                value.keys().forEach { childKey -> collectVideoUrls(value.opt(childKey), result, childKey) }
                value.optString("url").takeIf { it.isNotBlank() }?.let { url ->
                    val normalized = secureUrl(url)
                    if (isVideoUrl(normalized, key)) result += MediaVariant(key.ifBlank { "video" }, normalized, width, height, bitrate)
                }
            }
            is JSONArray -> for (index in 0 until value.length()) collectVideoUrls(value.opt(index), result, key)
            is String -> {
                val url = secureUrl(value)
                if (url.startsWith("https://") && isVideoUrl(url, key)) {
                    result += MediaVariant(key.ifBlank { "video" }, url)
                }
            }
        }
    }

    private fun isVideoUrl(url: String, key: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        return path.endsWith(".mp4") || path.endsWith(".mov") || path.endsWith(".m4v") ||
            path.endsWith(".webm") || key.contains("video", true) || key.contains("stream", true) ||
            key.contains("play", true) || key.contains("master", true) || key.contains("h264", true)
    }

    private fun secureUrl(value: String): String = value
        .replace("\\u002F", "/")
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")
        .replace(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
}

class XiaohongshuClient(private val context: Context) {
    fun loadPost(sharedText: String): WeiboPost {
        val source = XiaohongshuLinkParser.extractUrl(sharedText)
        if (!XiaohongshuLinkParser.isSupported(source)) throw XiaohongshuException(context.getString(R.string.xiaohongshu_url_not_found))
        val connection = (URL(source).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            setRequestProperty("Referer", "https://www.xiaohongshu.com/")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw XiaohongshuException(context.getString(R.string.xiaohongshu_http_error, status))
            val finalUrl = connection.url.toString()
            val html = connection.inputStream.bufferedReader().use { it.readText() }
            val fallbackId = Regex("/(?:discovery/item|explore)/([A-Za-z0-9_-]{8,})").find(finalUrl)?.groupValues?.getOrNull(1).orEmpty().ifBlank { "xhs" }
            val data = XiaohongshuPageNormalizer.normalize(html, fallbackId)
                ?: throw XiaohongshuException(context.getString(R.string.xiaohongshu_no_media))
            val author = data.author.ifBlank { context.getString(R.string.xiaohongshu_default_author) }
            return WeiboPost(
                id = data.id,
                text = data.title.ifBlank { context.getString(R.string.xiaohongshu_default_title) },
                author = author,
                sourceUrl = finalUrl,
                media = data.media,
                creator = CreatorProfile(
                    platform = Platform.XIAOHONGSHU,
                    id = data.authorId,
                    name = author,
                    avatarUrl = data.avatarUrl,
                    profileUrl = data.authorId.takeIf(String::isNotBlank)?.let { "https://www.xiaohongshu.com/user/profile/$it" }.orEmpty(),
                ),
            )
        } catch (error: XiaohongshuException) {
            throw error
        } catch (error: Exception) {
            throw XiaohongshuException(context.getString(R.string.xiaohongshu_network_error, error.message ?: context.getString(R.string.network_error)))
        } finally {
            connection.disconnect()
        }
    }
}

class XiaohongshuException(message: String) : RuntimeException(message)

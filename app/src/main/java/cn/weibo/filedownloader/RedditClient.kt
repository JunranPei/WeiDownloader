package cn.weibo.filedownloader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

internal object RedditLinkParser {
    private val hosts = setOf("reddit.com", "www.reddit.com", "old.reddit.com", "new.reddit.com", "redd.it")
    private val urlRegex = Regex("https?://[^\\s<>\\\"']+", RegexOption.IGNORE_CASE)

    fun extractUrl(value: String): String = (urlRegex.find(value.trim())?.value ?: value.trim())
        .trimEnd('.', ',', '!', '?', ')', ']', '。', '！', '？', '，', '；', ';')

    fun isSupported(value: String): Boolean = runCatching {
        URI(extractUrl(value)).host.orEmpty().lowercase() in hosts
    }.getOrDefault(false)

    fun jsonEndpoint(value: String): String {
        val source = extractUrl(value).substringBefore('#').substringBefore('?').trimEnd('/')
        return "$source.json?raw_json=1"
    }
}

internal object RedditPostNormalizer {
    fun normalize(
        payload: JSONArray,
        sourceUrl: String,
        defaultAuthor: String = "Reddit user",
        defaultTitle: String = "Reddit post",
    ): WeiboPost? {
        val post = payload.optJSONObject(0)
            ?.optJSONObject("data")
            ?.optJSONArray("children")
            ?.optJSONObject(0)
            ?.optJSONObject("data")
            ?: return null
        val media = parseMedia(post)
        if (media.isEmpty()) return null
        val author = post.optString("author").ifBlank { defaultAuthor }
        val permalink = post.optString("permalink").takeIf(String::isNotBlank)
            ?.let { "https://www.reddit.com$it" }
            ?: sourceUrl
        return WeiboPost(
            id = post.optString("id").ifBlank { "reddit" },
            text = post.optString("title").ifBlank { defaultTitle },
            author = author,
            sourceUrl = permalink,
            media = media,
            creator = CreatorProfile(
                platform = Platform.REDDIT,
                id = author,
                name = author,
                profileUrl = "https://www.reddit.com/user/$author/",
            ),
        )
    }

    private fun parseMedia(post: JSONObject): List<MediaItem> = buildList {
        val postId = post.optString("id").ifBlank { "reddit" }
        val gallery = post.optJSONObject("gallery_data")?.optJSONArray("items")
        val metadata = post.optJSONObject("media_metadata")
        if (gallery != null && metadata != null) {
            for (index in 0 until gallery.length()) {
                val mediaId = gallery.optJSONObject(index)?.optString("media_id").orEmpty()
                val source = metadata.optJSONObject(mediaId)?.optJSONObject("s") ?: continue
                val url = decodeUrl(source.optString("u"))
                if (url.isBlank()) continue
                add(
                    MediaItem(
                        type = MediaType.IMAGE,
                        url = url,
                        quality = MediaQuality.ORIGINAL_BEST,
                        postId = postId,
                        ordinal = size + 1,
                        variants = listOf(MediaVariant("original", url, source.optInt("x"), source.optInt("y"))),
                        selectedVariantKey = "original",
                    ),
                )
            }
        }

        val preview = post.optJSONObject("preview")
            ?.optJSONArray("images")
            ?.optJSONObject(0)
            ?.optJSONObject("source")
        val previewUrl = decodeUrl(preview?.optString("url").orEmpty())
        val video = post.optJSONObject("secure_media")?.optJSONObject("reddit_video")
            ?: post.optJSONObject("media")?.optJSONObject("reddit_video")
        val videoUrl = decodeUrl(video?.optString("fallback_url").orEmpty())

        if (videoUrl.isNotBlank()) {
            add(
                MediaItem(
                    type = MediaType.VIDEO,
                    url = videoUrl,
                    quality = MediaQuality.PLATFORM_BEST,
                    postId = postId,
                    ordinal = size + 1,
                    thumbnailUrl = previewUrl,
                    variants = listOf(MediaVariant("source", videoUrl, video?.optInt("width") ?: 0, video?.optInt("height") ?: 0)),
                    selectedVariantKey = "source",
                ),
            )
        } else if (gallery == null && previewUrl.isNotBlank()) {
            add(
                MediaItem(
                    type = MediaType.IMAGE,
                    url = previewUrl,
                    quality = MediaQuality.ORIGINAL_BEST,
                    postId = postId,
                    ordinal = size + 1,
                    variants = listOf(MediaVariant("original", previewUrl, preview?.optInt("width") ?: 0, preview?.optInt("height") ?: 0)),
                    selectedVariantKey = "original",
                ),
            )
        }
    }

    private fun decodeUrl(value: String): String = value.replace("&amp;", "&")
}

class RedditClient(private val context: Context) {
    fun loadPost(sharedText: String): WeiboPost {
        val source = RedditLinkParser.extractUrl(sharedText)
        if (!RedditLinkParser.isSupported(source)) throw RedditException(context.getString(R.string.reddit_url_not_found))
        val connection = (URL(RedditLinkParser.jsonEndpoint(source)).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("User-Agent", "WeiDownloader/1.0 (Android media downloader)")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw RedditException(context.getString(R.string.reddit_http_error, status))
            val payload = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            return RedditPostNormalizer.normalize(
                payload = payload,
                sourceUrl = source,
                defaultAuthor = context.getString(R.string.reddit_default_author),
                defaultTitle = context.getString(R.string.reddit_default_title),
            ) ?: throw RedditException(context.getString(R.string.reddit_no_media))
        } catch (error: RedditException) {
            throw error
        } catch (error: Exception) {
            throw RedditException(context.getString(R.string.reddit_network_error, error.message ?: context.getString(R.string.network_error)))
        } finally {
            connection.disconnect()
        }
    }
}

class RedditException(message: String) : RuntimeException(message)

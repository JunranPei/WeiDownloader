package cn.weibo.filedownloader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

internal object RedditLinkParser {
    private val hosts = setOf("reddit.com", "www.reddit.com", "old.reddit.com", "new.reddit.com", "m.reddit.com", "np.reddit.com", "redd.it")
    private val urlRegex = Regex("https?://[^\\s<>\\\"']+", RegexOption.IGNORE_CASE)
    fun extractUrl(value: String): String = (urlRegex.find(value.trim())?.value ?: value.trim())
        .trimEnd('.', ',', '!', '?', ')', ']', '。', '！', '？', '，', '；', ';')
    fun isSupported(value: String): Boolean = isRedditUrl(extractUrl(value))
    fun isRedditUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.lowercase() in setOf("https", "http") && uri.host.orEmpty().lowercase() in hosts && uri.userInfo == null
    }.getOrDefault(false)
    fun postId(value: String): String? = runCatching {
        val uri = URI(extractUrl(value))
        if (!isRedditUrl(uri.toString())) return@runCatching null
        val path = uri.path.orEmpty().removeSuffix(".json")
        if (uri.host.equals("redd.it", true)) Regex("^/([a-z0-9]+)/?$").matchEntire(path)?.groupValues?.get(1)
        else Regex("/(?:comments|gallery)/([a-z0-9]+)(?:/|$)").find(path)?.groupValues?.get(1)
    }.getOrNull()
    fun isShareLink(value: String): Boolean = isRedditUrl(value) &&
        runCatching { Regex("^/r/[^/]+/s/[^/]+/?$").matches(URI(value).path.orEmpty()) }.getOrDefault(false)
    fun jsonEndpoint(value: String): String = "https://www.reddit.com/comments/${requireNotNull(postId(value))}.json?raw_json=1"
}

internal object RedditMediaUrls {
    fun decode(value: String): String = value.replace("&amp;", "&")
    fun usable(value: String): String = decode(value).takeIf { url ->
        runCatching {
            val uri = URI(url)
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
        }.getOrDefault(false)
    }.orEmpty()
    fun isBlurred(value: String): Boolean = runCatching {
        val uri = URI(decode(value))
        uri.host.orEmpty().startsWith("external-preview.") || uri.rawQuery.orEmpty().split('&').any {
            it.substringBefore('=').lowercase() in setOf("blur", "blurred", "nsfw", "obfuscate") &&
                it.substringAfter('=', "").lowercase() !in setOf("0", "false")
        }
    }.getOrDefault(true)
    // Only Reddit-hosted images have an original/preview pairing. Blurred previews are rejected.
    fun original(value: String): String {
        val url = usable(value)
        if (url.isBlank() || isBlurred(url)) return ""
        val uri = URI(url)
        return if (uri.host.equals("preview.redd.it", true) && MediaFileTypes.isKnownImagePath(uri.path.orEmpty())) {
            // The media viewer adds a title and a -v0- prefix to the CDN filename.
            val filename = uri.rawPath.substringAfterLast('/')
            val originalName = Regex("^.+-v0-([a-z0-9]+\\.[a-zA-Z0-9]+)$")
                .matchEntire(filename)?.groupValues?.get(1) ?: filename
            "https://i.redd.it/$originalName"
        } else url
    }
}

internal object RedditPostNormalizer {
    fun normalize(payload: JSONArray, sourceUrl: String, defaultAuthor: String = "Reddit user", defaultTitle: String = "Reddit post"): WeiboPost? {
        val raw = payload.optJSONObject(0)?.optJSONObject("data")?.optJSONArray("children")
            ?.optJSONObject(0)?.optJSONObject("data") ?: return null
        return normalizePost(raw, sourceUrl, defaultAuthor, defaultTitle)
    }
    fun normalizePost(post: JSONObject, sourceUrl: String, defaultAuthor: String = "Reddit user", defaultTitle: String = "Reddit post"): WeiboPost? {
        val id = post.optString("id").ifBlank { "reddit" }
        var media = parseMedia(post, id)
        if (media.isEmpty()) {
            post.optJSONArray("crosspost_parent_list")?.let { parents ->
                for (index in 0 until parents.length()) {
                    media = parents.optJSONObject(index)?.let { parseMedia(it, id) }.orEmpty()
                    if (media.isNotEmpty()) break
                }
            }
        }
        if (media.isEmpty()) return null
        val author = post.optString("author").ifBlank { defaultAuthor }
        val permalink = post.optString("permalink").takeIf { it.startsWith("/") && !it.startsWith("//") }
            ?.let { "https://www.reddit.com$it" } ?: sourceUrl
        return WeiboPost(id = id, text = post.optString("title").ifBlank { defaultTitle }, author = author,
            sourceUrl = permalink, media = media,
            creator = author.takeUnless { it == "[deleted]" || it == defaultAuthor }?.let {
                CreatorProfile(platform = Platform.REDDIT, id = author, name = author,
                    profileUrl = "https://www.reddit.com/user/$author/")
            })
    }
    private fun parseMedia(post: JSONObject, postId: String): List<MediaItem> = buildList {
        val gallery = post.optJSONObject("gallery_data")?.optJSONArray("items")
        val metadata = post.optJSONObject("media_metadata")
        if (gallery != null && metadata != null) {
            for (index in 0 until gallery.length()) {
                val entry = metadata.optJSONObject(gallery.optJSONObject(index)?.optString("media_id").orEmpty())
                if (entry == null) { clear(); return@buildList }
                if (entry.has("status") && entry.optString("status") != "valid") continue
                val source = entry.optJSONObject("s")
                if (source == null) { clear(); return@buildList }
                val animated = entry.optString("e") == "AnimatedImage"
                val url = if (animated) RedditMediaUrls.usable(source.optString("gif"))
                    .ifBlank { RedditMediaUrls.usable(source.optString("mp4")) }
                else RedditMediaUrls.original(source.optString("u"))
                if (url.isBlank() || RedditMediaUrls.isBlurred(url)) {
                    clear()
                    return@buildList
                }
                val type = if (animated && URI(url).path.orEmpty().endsWith(".mp4")) MediaType.VIDEO else MediaType.IMAGE
                val variant = MediaVariant("original", url, source.optInt("x"), source.optInt("y"))
                add(MediaItem(type, url, MediaQuality.ORIGINAL_BEST, postId, size + 1,
                    variants = listOf(variant), selectedVariantKey = variant.key,
                    sourceId = gallery.optJSONObject(index)?.optString("media_id").orEmpty()))
            }
            return@buildList
        }
        if (gallery != null) return@buildList
        val preview = post.optJSONObject("preview")?.optJSONArray("images")?.optJSONObject(0)?.optJSONObject("source")
        val previewUrl = RedditMediaUrls.usable(preview?.optString("url").orEmpty()).takeUnless(RedditMediaUrls::isBlurred).orEmpty()
        val video = videoData(post)
        val videoUrl = RedditMediaUrls.usable(video?.optString("fallback_url").orEmpty())
        if (videoUrl.isNotBlank()) {
            val variant = MediaVariant("source", videoUrl, video?.optInt("width") ?: 0, video?.optInt("height") ?: 0)
            add(MediaItem(MediaType.VIDEO, videoUrl, MediaQuality.PLATFORM_BEST, postId, 1,
                thumbnailUrl = previewUrl, variants = listOf(variant), selectedVariantKey = variant.key))
            return@buildList
        }
        val direct = sequenceOf(post.optString("url_overridden_by_dest"), post.optString("url"))
            .map(RedditMediaUrls::usable).firstOrNull { url ->
                url.isNotBlank() && MediaFileTypes.isKnownImagePath(URI(url).path.orEmpty()) && !RedditMediaUrls.isBlurred(url)
            }.orEmpty()
        // A link post's preview is a thumbnail of another page, not its submitted media.
        val url = RedditMediaUrls.original(direct).ifBlank {
            if (post.optString("post_hint") == "link" || post.optBoolean("is_self")) ""
            else RedditMediaUrls.original(previewUrl)
        }
        if (url.isNotBlank()) {
            val variant = MediaVariant("original", url, preview?.optInt("width") ?: 0, preview?.optInt("height") ?: 0)
            add(MediaItem(MediaType.IMAGE, url, MediaQuality.ORIGINAL_BEST, postId, 1,
                variants = listOf(variant), selectedVariantKey = variant.key))
        }
    }
    fun videoData(post: JSONObject): JSONObject? = post.optJSONObject("secure_media")?.optJSONObject("reddit_video")
        ?: post.optJSONObject("media")?.optJSONObject("reddit_video")
        ?: post.optJSONObject("preview")?.optJSONObject("reddit_video_preview")
    fun videoDataForUrl(post: JSONObject, url: String): JSONObject? {
        if (url.isBlank()) return null
        videoData(post)?.takeIf { RedditMediaUrls.usable(it.optString("fallback_url")) == url }?.let { return it }
        val parents = post.optJSONArray("crosspost_parent_list") ?: return null
        for (index in 0 until parents.length()) {
            val parent = parents.optJSONObject(index) ?: continue
            videoData(parent)?.takeIf { RedditMediaUrls.usable(it.optString("fallback_url")) == url }?.let { return it }
        }
        return null
    }
}

class RedditClient(private val context: Context) {
    private val http = RedditHttpClient(context)
    fun loadPost(sharedText: String): WeiboPost {
        val source = RedditLinkParser.extractUrl(sharedText)
        if (!RedditLinkParser.isSupported(source)) throw RedditException(context.getString(R.string.reddit_url_not_found))
        val resolved = if (RedditLinkParser.isShareLink(source)) http.resolveShare(source) else source
        if (RedditLinkParser.postId(resolved) == null) throw RedditException(context.getString(R.string.reddit_url_not_found))
        val payload = http.getJson(RedditLinkParser.jsonEndpoint(resolved)) as? JSONArray
            ?: throw RedditException(context.getString(R.string.reddit_invalid_response))
        val raw = payload.optJSONObject(0)?.optJSONObject("data")?.optJSONArray("children")?.optJSONObject(0)?.optJSONObject("data")
        val post = RedditPostNormalizer.normalize(payload, resolved,
            context.getString(R.string.reddit_default_author), context.getString(R.string.reddit_default_title))
            ?: throw RedditException(context.getString(if (raw?.optBoolean("over_18") == true)
                R.string.reddit_restricted_media else R.string.reddit_no_media))
        val video = raw?.let { RedditPostNormalizer.videoDataForUrl(it, post.media.firstOrNull { media -> media.type == MediaType.VIDEO }?.url.orEmpty()) } ?: return post
        if (video.optBoolean("is_gif")) return post
        val dashUrl = RedditMediaUrls.usable(video.optString("dash_url"))
        if (dashUrl.isBlank()) throw RedditException(context.getString(R.string.reddit_video_audio_unavailable))
        val variants = RedditDashParser.variants(http.getText(dashUrl, "application/dash+xml"), dashUrl)
        if (variants.isEmpty()) throw RedditException(context.getString(R.string.reddit_video_audio_unavailable))
        val selected = variants.first()
        return post.copy(media = post.media.map { item ->
            if (item.type != MediaType.VIDEO) item else item.copy(url = selected.url,
                audioUrl = selected.audioUrl, variants = variants, selectedVariantKey = selected.key)
        })
    }
}

internal class RedditHttpClient(private val context: Context) {
    fun getJson(url: String): Any {
        val body = try { getText(url, "application/json") } catch (error: RedditException) {
            if (error.status != 403) throw error
            RedditWebJsonLoader.load(context, url) ?: throw error
        }
        val value = runCatching { org.json.JSONTokener(body).nextValue() }.getOrNull()
        if (value !is JSONObject && value !is JSONArray) throw RedditException(context.getString(R.string.reddit_invalid_response))
        return value
    }
    fun resolveShare(url: String): String = try { request(url, "text/html", true) } catch (error: RedditException) {
        if (error.status != 403) throw error
        RedditWebJsonLoader.resolveShare(context, url) ?: throw error
    }
    fun getText(url: String, accept: String): String = request(url, accept)
    private fun request(url: String, accept: String, resolveOnly: Boolean = false): String {
        var current = url
        repeat(6) {
            if (resolveOnly && !RedditLinkParser.isRedditUrl(current)) throw RedditException(context.getString(R.string.reddit_url_not_found))
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 25_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", accept)
                setRequestProperty("Referer", "https://www.reddit.com/")
                if (RedditLinkParser.isRedditUrl(current)) {
                    RedditSession.cookieHeader(current).takeIf(String::isNotBlank)?.let { setRequestProperty("Cookie", it) }
                }
            }
            try {
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: throw RedditException(context.getString(R.string.reddit_invalid_response))
                    val next = URI(current).resolve(location).toString()
                    if (!RedditLinkParser.isRedditUrl(next)) throw RedditException(context.getString(R.string.reddit_invalid_response))
                    if (resolveOnly && RedditLinkParser.postId(next) != null) return next
                    current = next
                } else {
                    if (status !in 200..299) throw RedditException(context.getString(when (status) {
                        401, 403 -> R.string.reddit_access_denied
                        429 -> R.string.reddit_rate_limited
                        else -> R.string.reddit_http_error
                    }, status), status)
                    if (resolveOnly) return current
                    return connection.inputStream.bufferedReader().use { it.readText() }
                }
            } catch (error: RedditException) {
                throw error
            } catch (error: Exception) {
                throw RedditException(context.getString(R.string.reddit_network_error, error.message ?: context.getString(R.string.network_error)))
            } finally { connection.disconnect() }
        }
        throw RedditException(context.getString(R.string.reddit_invalid_response))
    }
    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}

class RedditException(message: String, val status: Int? = null) : RuntimeException(message)

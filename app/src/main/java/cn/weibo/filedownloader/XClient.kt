package cn.weibo.filedownloader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal data class XPostTarget(val handle: String, val id: String) {
    val canonicalUrl: String get() = "https://x.com/$handle/status/$id"
}

internal object XLinkParser {
    private val urlRegex = Regex("https?://[^\\s\\[\\]()<>]+", RegexOption.IGNORE_CASE)
    private val handleRegex = Regex("^[A-Za-z0-9_]{1,15}$")

    fun extractUrl(value: String): String = (urlRegex.find(value.trim())?.value ?: value.trim())
        .trimEnd('.', ',', '!', '?', '，', '。', '！', '？', '）', ')', ']', '；', ';')

    fun targetFromUrl(value: String): XPostTarget? {
        val uri = runCatching { URI(extractUrl(value)) }.getOrNull() ?: return null
        val host = uri.host.orEmpty().lowercase()
        if (host !in HOSTS) return null
        val parts = uri.path.orEmpty().split('/').filter(String::isNotBlank)
        val statusIndex = parts.indexOfFirst { it.equals("status", true) }
        if (statusIndex <= 0 || statusIndex + 1 >= parts.size) return null
        val handle = parts[statusIndex - 1]
        val id = parts[statusIndex + 1]
        if (!handleRegex.matches(handle) || !id.matches(Regex("\\d{5,20}"))) return null
        return XPostTarget(handle, id)
    }

    fun isSupported(value: String): Boolean = targetFromUrl(value) != null

    private val HOSTS = setOf("x.com", "www.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com")
}

/** Coordinates replaceable X metadata providers; direct media stays on X's own CDN. */
class XClient(private val context: Context) {
    private val providers: List<XPostProvider> = listOf(FxTwitterPostProvider(XHttpClient(context), context))

    fun loadPost(sharedUrl: String): WeiboPost {
        val sourceUrl = XLinkParser.extractUrl(sharedUrl)
        val target = XLinkParser.targetFromUrl(sourceUrl)
            ?: throw XException(context.getString(R.string.x_url_not_found))
        var lastFailure: Exception? = null
        providers.forEach { provider ->
            try {
                val post = provider.load(target, sourceUrl)
                if (post.media.isNotEmpty()) return post
            } catch (error: Exception) {
                lastFailure = error
            }
        }
        throw lastFailure ?: XException(context.getString(R.string.x_no_media))
    }
}

internal interface XPostProvider {
    fun load(target: XPostTarget, sourceUrl: String): WeiboPost
}

internal class FxTwitterPostProvider(
    private val http: XHttpClient,
    private val context: Context,
) : XPostProvider {
    override fun load(target: XPostTarget, sourceUrl: String): WeiboPost {
        val response = http.getJson("https://api.fxtwitter.com/2/status/${target.id}")
        if (response.optInt("code", 0) !in 200..299) {
            throw XException(context.getString(R.string.x_read_failed))
        }
        val status = response.optJSONObject("status")
            ?: throw XException(context.getString(R.string.x_read_failed))
        return XPostNormalizer.normalize(status, sourceUrl, context)
            ?: throw XException(context.getString(R.string.x_no_media))
    }
}

internal object XPostNormalizer {
    fun normalize(status: JSONObject, sourceUrl: String, context: Context? = null): WeiboPost? {
        val id = status.optString("id")
        if (id.isBlank()) return null
        val authorObject = status.optJSONObject("author") ?: JSONObject()
        val handle = authorObject.optString("screen_name")
        val author = authorObject.optString("name").ifBlank { handle }
            .ifBlank { context?.getString(R.string.x_default_author).orEmpty() }
        val rawMedia = status.optJSONObject("media")?.optJSONArray("all") ?: JSONArray()
        val media = buildList {
            for (index in 0 until rawMedia.length()) {
                val raw = rawMedia.optJSONObject(index) ?: continue
                when (raw.optString("type").lowercase()) {
                    "photo" -> parsePhoto(raw, id, size + 1)?.let(::add)
                    "video", "gif", "animated_gif" -> parseVideo(raw, id, size + 1)?.let(::add)
                }
            }
        }
        if (media.isEmpty()) return null
        return WeiboPost(
            id = id,
            text = status.optString("text").ifBlank {
                context?.getString(R.string.x_default_title).orEmpty().ifBlank { "X post" }
            },
            author = author,
            sourceUrl = sourceUrl,
            media = media,
            creator = CreatorProfile(
                platform = Platform.X,
                id = handle,
                name = author,
                avatarUrl = authorObject.optString("avatar_url"),
                profileUrl = authorObject.optString("url").ifBlank { "https://x.com/$handle" },
            ),
        )
    }

    private fun parsePhoto(raw: JSONObject, postId: String, ordinal: Int): MediaItem? {
        val url = raw.optString("url")
        if (url.isBlank()) return null
        val variant = MediaVariant(
            key = MediaQuality.ORIGINAL_BEST,
            url = url,
            width = raw.optInt("width"),
            height = raw.optInt("height"),
        )
        return MediaItem(
            type = MediaType.IMAGE,
            url = url,
            quality = MediaQuality.ORIGINAL_BEST,
            postId = postId,
            ordinal = ordinal,
            thumbnailUrl = url,
            variants = listOf(variant),
            selectedVariantKey = variant.key,
        )
    }

    private fun parseVideo(raw: JSONObject, postId: String, ordinal: Int): MediaItem? {
        val formats = raw.optJSONArray("formats") ?: JSONArray()
        val variants = buildList {
            for (index in 0 until formats.length()) {
                val format = formats.optJSONObject(index) ?: continue
                if (!format.optString("container").equals("mp4", true)) continue
                val url = format.optString("url")
                if (url.isBlank()) continue
                val dimensions = dimensionsFromUrl(url)
                add(
                    MediaVariant(
                        key = dimensions.second.takeIf { it > 0 }?.let { "${it}p" }
                            ?: "${(format.optLong("bitrate") / 1000L).coerceAtLeast(1L)} kbps",
                        url = url,
                        width = dimensions.first,
                        height = dimensions.second,
                        bitrate = format.optLong("bitrate"),
                    ),
                )
            }
        }.let { MediaVariantSelector.rank(MediaType.VIDEO, it.distinctBy(MediaVariant::url)) }
        val fallback = raw.optString("url")
        val resolvedVariants = variants.ifEmpty {
            if (fallback.isBlank()) emptyList() else {
                val dimensions = dimensionsFromUrl(fallback)
                listOf(
                    MediaVariant(
                        key = MediaQuality.PLATFORM_BEST,
                        url = fallback,
                        width = dimensions.first.takeIf { it > 0 } ?: raw.optInt("width"),
                        height = dimensions.second.takeIf { it > 0 } ?: raw.optInt("height"),
                    ),
                )
            }
        }
        val selected = resolvedVariants.firstOrNull() ?: return null
        return MediaItem(
            type = MediaType.VIDEO,
            url = selected.url,
            quality = selected.key,
            postId = postId,
            ordinal = ordinal,
            thumbnailUrl = raw.optString("thumbnail_url"),
            variants = resolvedVariants,
            selectedVariantKey = selected.key,
        )
    }

    private fun dimensionsFromUrl(url: String): Pair<Int, Int> = Regex("/(\\d{2,5})x(\\d{2,5})/")
        .find(url)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() } ?: (0 to 0)
}

internal class XHttpClient(private val context: Context) {
    fun getJson(address: String, parameters: Map<String, String> = emptyMap()): JSONObject {
        val url = if (parameters.isEmpty()) address else address + "?" + parameters.entries.joinToString("&") {
            "${encode(it.key)}=${encode(it.value)}"
        }
        return JSONObject(requestText(url))
    }

    private fun requestText(address: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "AllDownloader Android")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            val status = connection.responseCode
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = input?.let {
                BufferedInputStream(it).bufferedReader(StandardCharsets.UTF_8).use { reader -> reader.readText() }
            }.orEmpty()
            if (status !in 200..299) throw XException(context.getString(R.string.x_http_error, status))
            if (body.isBlank()) throw XException(context.getString(R.string.x_empty_response))
            return body
        } catch (error: XException) {
            throw error
        } catch (error: Exception) {
            throw XException(context.getString(R.string.x_network_error, error.message ?: context.getString(R.string.network_error)))
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}

class XException(message: String) : RuntimeException(message)

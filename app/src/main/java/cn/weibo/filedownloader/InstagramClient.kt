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

internal data class InstagramPostTarget(val shortcode: String, val kind: String) {
    val canonicalUrl: String get() = "https://www.instagram.com/$kind/$shortcode/"
}

internal object InstagramLinkParser {
    private val urlRegex = Regex("https?://[^\\s\\[\\]()<>]+", RegexOption.IGNORE_CASE)
    private val shortcodeRegex = Regex("^[A-Za-z0-9_-]{5,30}$")

    fun extractUrl(value: String): String = (urlRegex.find(value.trim())?.value ?: value.trim())
        .trimEnd('.', ',', '!', '?', '，', '。', '！', '？', '）', ')', ']', '；', ';')

    fun targetFromUrl(value: String): InstagramPostTarget? {
        val uri = runCatching { URI(extractUrl(value)) }.getOrNull() ?: return null
        val host = uri.host.orEmpty().lowercase()
        if (host !in HOSTS) return null
        val parts = uri.path.orEmpty().split('/').filter(String::isNotBlank)
        val marker = parts.indexOfFirst { it.lowercase() in CONTENT_PATHS }
        if (marker < 0 || marker + 1 >= parts.size) return null
        val shortcode = parts[marker + 1]
        if (!shortcodeRegex.matches(shortcode)) return null
        val kind = if (parts[marker].equals("p", true)) "p" else "reel"
        return InstagramPostTarget(shortcode, kind)
    }

    fun isSupported(value: String): Boolean = targetFromUrl(value) != null

    private val HOSTS = setOf("instagram.com", "www.instagram.com", "m.instagram.com", "instagr.am", "www.instagr.am")
    private val CONTENT_PATHS = setOf("p", "reel", "reels", "tv")
}

class InstagramClient(private val context: Context) {
    private val http = InstagramHttpClient(context)

    fun loadPost(sharedUrl: String): WeiboPost {
        val sourceUrl = InstagramLinkParser.extractUrl(sharedUrl)
        val target = InstagramLinkParser.targetFromUrl(sourceUrl)
            ?: throw InstagramException(context.getString(R.string.instagram_url_not_found))
        val payload = http.fetchPost(target)
        return InstagramPostNormalizer.normalize(payload, sourceUrl, context)
            ?: throw InstagramException(context.getString(R.string.instagram_no_media))
    }
}

internal object InstagramPostNormalizer {
    fun normalize(payload: JSONObject, sourceUrl: String, context: Context? = null): WeiboPost? {
        val item = findItem(payload) ?: return null
        val shortcode = item.optString("code").ifBlank {
            InstagramLinkParser.targetFromUrl(sourceUrl)?.shortcode.orEmpty()
        }
        if (shortcode.isBlank()) return null
        val user = item.optJSONObject("user") ?: JSONObject()
        val username = user.optString("username")
        val author = user.optString("full_name").ifBlank { username }
            .ifBlank { context?.getString(R.string.instagram_default_author).orEmpty() }
        val rawMedia = item.optJSONArray("carousel_media")?.takeIf { it.length() > 0 }
            ?: JSONArray().put(item)
        val media = buildList {
            for (index in 0 until rawMedia.length()) {
                normalizeMedia(rawMedia.optJSONObject(index) ?: continue, shortcode, size + 1)?.let(::add)
            }
        }
        if (media.isEmpty()) return null
        return WeiboPost(
            id = shortcode,
            text = item.optJSONObject("caption")?.optString("text").orEmpty().ifBlank {
                context?.getString(R.string.instagram_default_title).orEmpty().ifBlank { "Instagram post" }
            },
            author = author,
            sourceUrl = sourceUrl,
            media = media,
            creator = CreatorProfile(
                platform = Platform.INSTAGRAM,
                id = username,
                name = author,
                avatarUrl = user.optString("profile_pic_url"),
                profileUrl = username.takeIf(String::isNotBlank)?.let { "https://www.instagram.com/$it/" }.orEmpty(),
            ),
        )
    }

    private fun findItem(payload: JSONObject): JSONObject? {
        val graphItem = payload.optJSONObject("data")
            ?.optJSONObject("xdt_api__v1__media__shortcode__web_info")
            ?.optJSONArray("items")
            ?.optJSONObject(0)
        return graphItem ?: payload.optJSONArray("items")?.optJSONObject(0)
    }

    private fun normalizeMedia(raw: JSONObject, postId: String, ordinal: Int): MediaItem? {
        val videoVariants = candidates(raw.optJSONArray("video_versions"), MediaType.VIDEO)
        val imageVariants = candidates(raw.optJSONObject("image_versions2")?.optJSONArray("candidates"), MediaType.IMAGE)
        val type = if (videoVariants.isNotEmpty() || raw.optInt("media_type") == 2) MediaType.VIDEO else MediaType.IMAGE
        val variants = if (type == MediaType.VIDEO) videoVariants else imageVariants
        val selected = variants.firstOrNull() ?: return null
        return MediaItem(
            type = type,
            url = selected.url,
            quality = if (type == MediaType.IMAGE) MediaQuality.ORIGINAL_BEST else MediaQuality.PLATFORM_BEST,
            postId = postId,
            ordinal = ordinal,
            thumbnailUrl = imageVariants.firstOrNull()?.url.orEmpty(),
            variants = variants,
            selectedVariantKey = selected.key,
        )
    }

    private fun candidates(values: JSONArray?, type: MediaType): List<MediaVariant> {
        if (values == null) return emptyList()
        val result = buildList {
            for (index in 0 until values.length()) {
                val value = values.optJSONObject(index) ?: continue
                val url = value.optString("url")
                if (url.isBlank()) continue
                val width = value.optInt("width")
                val height = value.optInt("height")
                add(
                    MediaVariant(
                        key = if (height > 0) "${height}p" else MediaQuality.PLATFORM_BEST,
                        url = url,
                        width = width,
                        height = height,
                    ),
                )
            }
        }.distinctBy(MediaVariant::url)
        return if (type == MediaType.VIDEO) MediaVariantSelector.rank(type, result)
        else result.sortedByDescending(MediaVariant::pixelArea)
    }
}

internal class InstagramHttpClient(private val context: Context) {
    fun fetchPost(target: InstagramPostTarget): JSONObject {
        val session = bootstrapSession()
        val variables = JSONObject()
            .put("shortcode", target.shortcode)
            .put("__relay_internal__pv__PolarisAIGMMediaWebLabelEnabledrelayprovider", false)
            .toString()
        val form = "doc_id=$GRAPHQL_DOC_ID&variables=${encode(variables)}"
        val graphResult = runCatching {
            requestJson(
                address = "https://www.instagram.com/graphql/query/",
                method = "POST",
                body = form.toByteArray(StandardCharsets.UTF_8),
                target = target,
                session = session,
            )
        }
        val payload = graphResult.getOrNull()
        if ((payload?.optJSONObject("data")
                ?.optJSONObject("xdt_api__v1__media__shortcode__web_info")
                ?.optJSONArray("items")
                ?.length() ?: 0) > 0
        ) return requireNotNull(payload)

        return runCatching {
            requestJson(
                address = "https://www.instagram.com/api/v1/media/shortcode/${target.shortcode}/",
                target = target,
                session = session,
            )
        }.getOrElse { throw graphResult.exceptionOrNull() ?: it }
    }

    private fun bootstrapSession(): Session {
        val connection = open("https://www.instagram.com/", "GET", null, null, null)
        return try {
            connection.responseCode
            val cookies = connection.headerFields.entries
                .filter { it.key.equals("Set-Cookie", true) }
                .flatMap { it.value.orEmpty() }
                .map { it.substringBefore(';').trim() }
                .filter(String::isNotBlank)
                .distinctBy { it.substringBefore('=').lowercase() }
            val csrf = cookies.firstOrNull { it.startsWith("csrftoken=") }?.substringAfter('=').orEmpty()
            Session(cookies.joinToString("; "), csrf)
        } catch (_: Exception) {
            Session("", "")
        } finally {
            connection.disconnect()
        }
    }

    private fun requestJson(
        address: String,
        method: String = "GET",
        body: ByteArray? = null,
        target: InstagramPostTarget,
        session: Session,
    ): JSONObject {
        val connection = open(address, method, body, target, session)
        try {
            if (body != null) connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = input?.let {
                BufferedInputStream(it).bufferedReader(StandardCharsets.UTF_8).use { reader -> reader.readText() }
            }.orEmpty()
            if (status !in 200..299) throw InstagramException(context.getString(R.string.instagram_http_error, status))
            if (text.isBlank()) throw InstagramException(context.getString(R.string.instagram_empty_response))
            return JSONObject(text)
        } catch (error: InstagramException) {
            throw error
        } catch (error: Exception) {
            throw InstagramException(
                context.getString(R.string.instagram_network_error, error.message ?: context.getString(R.string.network_error)),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun open(
        address: String,
        method: String,
        body: ByteArray?,
        target: InstagramPostTarget?,
        session: Session?,
    ): HttpURLConnection = (URL(address).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        instanceFollowRedirects = true
        connectTimeout = 20_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Accept", "application/json,text/plain,*/*")
        setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
        setRequestProperty("Accept-Encoding", "identity")
        setRequestProperty("X-IG-App-ID", IG_APP_ID)
        target?.let { setRequestProperty("Referer", it.canonicalUrl) }
        session?.cookie?.takeIf(String::isNotBlank)?.let { setRequestProperty("Cookie", it) }
        session?.csrf?.takeIf(String::isNotBlank)?.let { setRequestProperty("X-CSRFToken", it) }
        if (body != null) {
            doOutput = true
            setFixedLengthStreamingMode(body.size)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Origin", "https://www.instagram.com")
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private data class Session(val cookie: String, val csrf: String)

    private companion object {
        const val GRAPHQL_DOC_ID = "27128499623469141"
        const val IG_APP_ID = "936619743392459"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    }
}

class InstagramException(message: String) : RuntimeException(message)

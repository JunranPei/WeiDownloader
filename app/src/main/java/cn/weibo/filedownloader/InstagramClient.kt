package cn.weibo.filedownloader

import android.content.Context
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
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
        val sources = http.fetchPost(target)
        val payload = InstagramImageQuality.enhance(sources.payload, http::imageDimensions)
        val post = InstagramPostNormalizer.normalize(payload, sourceUrl, context)
            ?: throw InstagramException(context.getString(R.string.instagram_no_media))
        val notices = post.media.filter { it.type == MediaType.IMAGE && it.quality != MediaQuality.ORIGINAL_BEST }.map { media ->
            val selected = media.variants.first()
            if (media.originalWidth > 0 && media.originalHeight > 0 &&
                !InstagramImageQuality.reachesOriginal(selected.width, selected.height, media.originalWidth, media.originalHeight)) {
                context.getString(R.string.instagram_image_reduced, media.ordinal, selected.width, selected.height,
                    media.originalWidth, media.originalHeight)
            } else context.getString(R.string.instagram_image_unverified, media.ordinal)
        }.toMutableList()
        if (notices.isNotEmpty()) {
            if (!sources.mobileAttempted) notices += context.getString(R.string.instagram_quality_login_hint)
            else if (!sources.mobileSucceeded) notices += context.getString(R.string.instagram_quality_source_failed)
        }
        return post.copy(notice = notices.joinToString("\n"))
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
                normalizeMedia(rawMedia.optJSONObject(index) ?: continue, shortcode, index + 1)?.let(::add)
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
        return InstagramPostSources.item(payload)
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
            quality = if (type == MediaType.IMAGE && selected.dimensionsVerified &&
                InstagramImageQuality.reachesOriginal(selected.width, selected.height,
                    raw.optInt("original_width"), raw.optInt("original_height"))) MediaQuality.ORIGINAL_BEST else MediaQuality.PLATFORM_BEST,
            postId = postId,
            ordinal = ordinal,
            thumbnailUrl = imageVariants.firstOrNull()?.url.orEmpty(),
            variants = variants,
            selectedVariantKey = selected.key,
            originalWidth = raw.optInt("original_width"),
            originalHeight = raw.optInt("original_height"),
            sourceId = raw.optString("pk").ifBlank { raw.optString("id") },
        )
    }

    private fun candidates(values: JSONArray?, type: MediaType): List<MediaVariant> {
        if (values == null) return emptyList()
        val result = buildList {
            for (index in 0 until values.length()) {
                val value = values.optJSONObject(index) ?: continue
                val url = value.optString("url")
                if (url.isBlank() || runCatching { URI(url).scheme !in setOf("https", "http") }.getOrDefault(true)) continue
                val width = value.optInt("width")
                val height = value.optInt("height")
                add(
                    MediaVariant(
                        key = if (type == MediaType.IMAGE && width > 0 && height > 0) "${width}×${height}"
                            else if (height > 0) "${height}p" else MediaQuality.PLATFORM_BEST,
                        url = url,
                        width = width,
                        height = height,
                        dimensionsVerified = value.optBoolean("dimensions_verified"),
                    ),
                )
            }
        }.distinctBy(MediaVariant::url)
        return if (type == MediaType.VIDEO) MediaVariantSelector.rank(type, result)
        else result.sortedByDescending(MediaVariant::pixelArea).distinctBy { it.key }
    }
}

internal class InstagramHttpClient(private val context: Context) {
    fun fetchPost(target: InstagramPostTarget): InstagramPostSources.Result {
        val saved = InstagramSession.cookieHeader()
        val session = if (InstagramRequests.hasSession(saved)) Session(saved, InstagramRequests.cookieValue(saved, "csrftoken"))
            else bootstrapSession()
        val variables = JSONObject()
            .put("shortcode", target.shortcode)
            .put("__relay_internal__pv__PolarisAIGMMediaWebLabelEnabledrelayprovider", false)
            .toString()
        val form = "doc_id=$GRAPHQL_DOC_ID&variables=${encode(variables)}"
        fun fetch(current: Session): InstagramPostSources.Result = InstagramPostSources.load(target, current.cookie) { source ->
            when (source) {
                InstagramPostSources.Source.MOBILE -> requestJson(
                    address = "https://i.instagram.com/api/v1/media/${InstagramRequests.mediaId(target.shortcode)}/info/",
                    target = target,
                    session = current.copy(cookie = InstagramRequests.mobileCookie(current.cookie)),
                    mobile = true,
                )
                InstagramPostSources.Source.WEB -> requestJson(
                    address = "https://www.instagram.com/graphql/query/",
                    method = "POST",
                    body = form.toByteArray(StandardCharsets.UTF_8),
                    target = target,
                    session = current,
                )
                InstagramPostSources.Source.SHORTCODE -> requestJson(
                    address = "https://www.instagram.com/api/v1/media/shortcode/${target.shortcode}/",
                    target = target,
                    session = current,
                )
            }
        }
        try {
            return fetch(session)
        } catch (error: InstagramException) {
            // A rejected saved login must not prevent the anonymous public-post fallback.
            if (InstagramRequests.hasSession(saved)) {
                runCatching { fetch(bootstrapSession()) }.getOrNull()?.let {
                    return it.copy(mobileAttempted = true, mobileSucceeded = false)
                }
            }
            if (error is InstagramHttpException && error.status in setOf(301, 302, 303, 307, 308, 401, 403))
                throw InstagramException(context.getString(R.string.instagram_login_required))
            throw error
        }
    }

    fun imageDimensions(address: String): InstagramImageQuality.Dimensions? = runCatching {
        val uri = URI(address)
        val host = uri.host.orEmpty().lowercase()
        if (uri.scheme != "https" || !(host.endsWith(".cdninstagram.com") || host.endsWith(".fbcdn.net"))) return null
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", InstagramRequests.WEB_USER_AGENT)
            setRequestProperty("Referer", "https://www.instagram.com/")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Range", "bytes=0-262143")
        }
        try {
            if (connection.responseCode !in 200..299 || !connection.contentType.orEmpty().startsWith("image/")) return null
            connection.inputStream.use { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (bytes.size() < 256 * 1024) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 256 * 1024 - bytes.size()))
                    if (count < 0) break
                    bytes.write(buffer, 0, count)
                    val data = bytes.toByteArray()
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(data, 0, data.size, options)
                    if (options.outWidth > 0 && options.outHeight > 0)
                        return InstagramImageQuality.Dimensions(options.outWidth, options.outHeight)
                }
            }
            null
        } finally { connection.disconnect() }
    }.getOrNull()

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
        mobile: Boolean = false,
    ): JSONObject {
        val connection = open(address, method, body, target, session, mobile)
        try {
            if (body != null) connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = input?.let {
                BufferedInputStream(it).bufferedReader(StandardCharsets.UTF_8).use { reader -> reader.readText() }
            }.orEmpty()
            if (status !in 200..299) throw InstagramHttpException(status, context.getString(R.string.instagram_http_error, status))
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
        mobile: Boolean = false,
    ): HttpURLConnection = (URL(address).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        // Auth cookies must never follow a redirect to a different host.
        instanceFollowRedirects = false
        connectTimeout = 20_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", if (mobile) InstagramRequests.MOBILE_USER_AGENT else InstagramRequests.WEB_USER_AGENT)
        setRequestProperty("Accept", "application/json,text/plain,*/*")
        setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
        setRequestProperty("Accept-Encoding", "identity")
        setRequestProperty("X-IG-App-ID", if (mobile) InstagramRequests.MOBILE_APP_ID else InstagramRequests.WEB_APP_ID)
        if (mobile) {
            setRequestProperty("X-IG-Capabilities", "36r/F/8=")
            setRequestProperty("X-IG-Connection-Type", "WiFi")
            setRequestProperty("X-IG-WWW-Claim", "0")
            setRequestProperty("X-IG-App-Locale", "en-US")
            setRequestProperty("X-IG-Device-Locale", "en-US")
            val cookie = session?.cookie.orEmpty()
            mapOf("X-MID" to "mid", "IG-U-DS-USER-ID" to "ds_user_id", "X-IG-Device-ID" to "ig_did",
                "X-IG-Family-Device-ID" to "ig_did", "IG-Intended-User-ID" to "ds_user_id").forEach { (header, name) ->
                InstagramRequests.cookieValue(cookie, name).takeIf(String::isNotBlank)?.let { setRequestProperty(header, it) }
            }
        }
        if (!mobile) target?.let { setRequestProperty("Referer", it.canonicalUrl) }
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
    }
}

open class InstagramException(message: String) : RuntimeException(message)
internal class InstagramHttpException(val status: Int, message: String) : InstagramException(message)

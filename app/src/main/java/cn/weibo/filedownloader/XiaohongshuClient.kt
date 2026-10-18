package cn.weibo.filedownloader

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object XiaohongshuLinkParser {
    private val hosts = setOf(
        "xiaohongshu.com", "www.xiaohongshu.com",
        "xhslink.com", "www.xhslink.com",
        "xhslink.cn", "www.xhslink.cn",
    )
    private val urlRegex = Regex("https?://[^\\s<>\\\"']+", RegexOption.IGNORE_CASE)
    private val noteIdRegex = Regex("/(?:discovery/item|explore)/([0-9a-fA-F]{32}|[0-9a-fA-F]{24})(?=[/?#]|$)")

    fun noteId(value: String): String = noteIdRegex.find(value)?.groupValues?.get(1).orEmpty()

    fun extractUrl(value: String): String = (urlRegex.find(value.trim())?.value ?: value.trim())
        .trimEnd('.', ',', '!', '?', ')', ']', '。', '！', '？', '，', '；', ';')

    // xhs_profile is an app navigation hint, never a website request parameter.
    fun noteUrl(value: String): String {
        val uri = URI(extractUrl(value))
        val query = uri.rawQuery.orEmpty().split('&')
            .filter { it.isNotBlank() && it.substringBefore('=') != "xhs_profile" }.joinToString("&")
        return buildString {
            append(uri.scheme).append("://").append(uri.rawAuthority).append(uri.rawPath.orEmpty())
            if (query.isNotEmpty()) append('?').append(query)
            uri.rawFragment?.let { append('#').append(it) }
        }
    }

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
    val accessToken: String,
    val profileAccessToken: String,
    val media: List<MediaItem>,
    val expectedMediaCount: Int = media.size,
    val unresolvedMediaCount: Int = 0,
)

internal object XiaohongshuPageNormalizer {
    private val pageStateMarkers = listOf(
        "window.__INITIAL_STATE__",
        "window.__SETUP_SERVER_STATE__",
    )

    fun normalize(html: String, fallbackId: String = "xhs"): XiaohongshuPostData? {
        val states = pageStateMarkers.mapNotNull { marker ->
            val markerStart = html.indexOf(marker)
            if (markerStart < 0) return@mapNotNull null
            val stateStart = html.indexOf('{', markerStart)
            val scriptEnd = html.indexOf("</script>", stateStart)
            if (stateStart < 0 || scriptEnd < 0) return@mapNotNull null
            val stateText = html.substring(stateStart, scriptEnd).trim().removeSuffix(";")
            runCatching { JSONObject(stateText) }.getOrNull()
        }
        return normalizeStates(states, fallbackId, html)
    }

    fun normalizePayload(payload: String, fallbackId: String = "xhs"): XiaohongshuPostData? {
        val state = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        return normalizeStates(listOf(state), fallbackId, "")
    }

    private fun normalizeStates(states: List<JSONObject>, expectedId: String, html: String): XiaohongshuPostData? {
        val notes = states.flatMap { findNotes(it).toList() }
        val targetId = expectedId.takeIf { it.isNotBlank() && it != "xhs" }
            ?: notes.firstOrNull()?.optString("noteId") ?: return null
        // Different state stores can contain a shell, a recommendation card,
        // and a complete detail for the same page. Only compare the target's
        // own representations; never merge attachments across unrelated notes.
        return notes.asSequence().filter { it.optString("noteId") == targetId }
            .mapNotNull { normalizeNote(it, html) }
            .maxWithOrNull(compareBy<XiaohongshuPostData> { it.expectedMediaCount }
                .thenBy { -it.unresolvedMediaCount }.thenBy { it.media.size })
    }

    private fun normalizeNote(note: JSONObject, html: String): XiaohongshuPostData? {
        val id = note.optString("noteId")
        val user = note.optJSONObject("user") ?: JSONObject()
        val author = user.optString("nickName").ifBlank { user.optString("nickname") }
        val images = parseImages(note.optJSONArray("imageList"), id)
        val videoThumbnailUrl = images.firstOrNull()?.let { image ->
            image.thumbnailUrl.ifBlank { image.url }
        }.orEmpty()
        val videos = parseVideos(note, id, videoThumbnailUrl)
        val rawImages = note.optJSONArray("imageList")
        val rawImageCount = rawImages?.length() ?: 0
        val hasLivePhotos = (0 until rawImageCount).any { rawImages?.optJSONObject(it)?.optBoolean("livePhoto") == true }
        val galleryIsContent = videoGroups(note).isEmpty() || rawImageCount > 1 || hasLivePhotos
        val unresolvedImages = if (galleryIsContent) (0 until rawImageCount).count { index ->
            val item = images.firstOrNull { it.ordinal == index + 1 }
            item == null || rawImages?.optJSONObject(index)?.optBoolean("livePhoto") == true &&
                (item.type != MediaType.LIVE_PHOTO || item.coverUrl.isBlank())
        } else 0
        val unresolvedVideos = (videoGroups(note).size - videos.size).coerceAtLeast(0)
        // A video note normally exposes one poster image, but some notes expose
        // a gallery together with a video. Keep every real attachment instead
        // of dropping the gallery just because a video field is present.
        val media = if (videos.isNotEmpty()) {
            if (images.size > 1 || images.any { it.type == MediaType.LIVE_PHOTO }) videos + images else videos
        } else images
        if (media.isEmpty()) return null
        val accessToken = findAccessToken(note)
        val profileTokenPattern = Regex("/user/profile/[^\"'?]+[^\"']*[?&]xsec_token=([^&\"']+)")
        val profileHtml = html.replace("&amp;", "&")
            .replace("\\u002F", "/")
            .replace("\\u003F", "?")
            .replace("\\u0026", "&")
        val profileAccessTokenRaw: String = profileTokenPattern
            .find(profileHtml)?.groupValues?.getOrNull(1) ?: ""
        val profileAccessToken = runCatching {
            URLDecoder.decode(profileAccessTokenRaw.replace("&amp;", "&"), "UTF-8")
        }.getOrDefault(profileAccessTokenRaw)
        return XiaohongshuPostData(
            id = id,
            title = note.optString("title").ifBlank { note.optString("desc") },
            author = author,
            authorId = user.optString("userId"),
            avatarUrl = secureUrl(user.optString("avatar")),
            accessToken = accessToken,
            profileAccessToken = profileAccessToken,
            media = media,
            expectedMediaCount = videoGroups(note).size + if (galleryIsContent) rawImageCount else 0,
            unresolvedMediaCount = unresolvedImages + unresolvedVideos,
        )
    }

    private fun findNotes(value: Any?): Sequence<JSONObject> = sequence {
        when (value) {
            is JSONObject -> {
                if (value.optString("noteId").isNotBlank() &&
                    (value.has("imageList") || (VIDEO_FIELDS + VIDEO_LIST_FIELDS).any(value::has))) yield(value)
                for (key in value.keys()) yieldAll(findNotes(value.opt(key)))
            }
            is JSONArray -> for (index in 0 until value.length()) yieldAll(findNotes(value.opt(index)))
        }
    }

    private fun findAccessToken(value: Any?): String = when (value) {
        is JSONObject -> {
            value.optString("xsecToken").ifBlank { value.optString("xsec_token") }
                .ifBlank { value.keys().asSequence().mapNotNull { findAccessToken(value.opt(it)).takeIf(String::isNotBlank) }.firstOrNull().orEmpty() }
        }
        is JSONArray -> (0 until value.length()).asSequence()
            .mapNotNull { findAccessToken(value.opt(it)).takeIf(String::isNotBlank) }
            .firstOrNull().orEmpty()
        else -> ""
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
            }.orEmpty()
                .ifBlank { image.optString("urlDefault") }
                .ifBlank { image.optString("url") }
                .ifBlank { image.optString("urlPre") }
                .let(::secureUrl)
            val originalUrls = originalImageUrls(image, previewUrl)
            val originalUrl = originalUrls.firstOrNull().orEmpty()
            val selectedUrl = originalUrl.ifBlank { previewUrl }
            val variants = buildList {
                originalUrls.forEachIndexed { index, url ->
                    add(MediaVariant(if (index == 0) "original" else "original_${index + 1}", url, width, height))
                }
                if (previewUrl.isNotBlank() && previewUrl != originalUrl) {
                    add(MediaVariant("web", previewUrl))
                }
            }
            // Live-photo clips belong to their individual gallery entries, not
            // to the note-level video (which can be a generated slideshow).
            val liveCandidates = mutableListOf<MediaVariant>()
            if (image.optBoolean("livePhoto")) {
                collectVideoUrls(image.opt("stream"), liveCandidates)
            }
            val liveVariants = rankVideoCandidates(liveCandidates.distinctBy(MediaVariant::url))
            val live = liveVariants.firstOrNull()
            if (live != null) {
                add(
                    MediaItem(
                        type = MediaType.LIVE_PHOTO,
                        url = live.url,
                        quality = MediaQuality.LIVE_ORIGINAL,
                        postId = postId,
                        ordinal = index + 1,
                        thumbnailUrl = previewUrl.ifBlank { selectedUrl },
                        coverUrl = selectedUrl,
                        coverFallbackUrls = (originalUrls.drop(1) + previewUrl)
                            .filter(String::isNotBlank).distinct().filterNot { it == selectedUrl },
                        variants = liveVariants,
                        selectedVariantKey = live.key,
                        fallbackUrls = liveVariants.drop(1).map(MediaVariant::url),
                        sourceId = image.optString("traceId").ifBlank { image.optString("fileId") },
                    ),
                )
                continue
            }
            if (selectedUrl.isBlank()) continue
            val variant = variants.firstOrNull() ?: continue
            add(
                MediaItem(
                    type = MediaType.IMAGE,
                    url = selectedUrl,
                    quality = MediaQuality.ORIGINAL_BEST,
                    postId = postId,
                    ordinal = index + 1,
                    thumbnailUrl = previewUrl,
                    variants = variants,
                    selectedVariantKey = variant.key,
                    sourceId = image.optString("traceId").ifBlank { image.optString("fileId") },
                    fallbackUrls = (originalUrls.drop(1) + previewUrl)
                        .filter(String::isNotBlank)
                        .distinct()
                        .filterNot { it == selectedUrl },
                ),
            )
        }
    }

    private fun originalImageUrls(image: JSONObject, previewUrl: String): List<String> {
        val traceId = image.optString("traceId").trim().trimStart('/')
        val fileId = image.optString("fileId").trim().trimStart('/')
        val token = traceId.ifBlank { fileId }.ifBlank { imageTokenFromUrl(previewUrl) }
        if (token.isBlank()) return emptyList()

        val previewHost = runCatching { URI(previewUrl).host.orEmpty() }.getOrDefault("")
        val derivedHost = previewHost.replace("sns-webpic", "sns-img")
            .takeIf { it.endsWith(".xhscdn.com") }
        return listOfNotNull(
            "https://ci.xiaohongshu.com/$token",
            "https://sns-img-bd.xhscdn.com/$token",
            derivedHost?.let { "https://$it/$token" },
        ).distinct()
    }

    private fun imageTokenFromUrl(value: String): String {
        if (value.isBlank()) return ""
        val path = runCatching { URI(value).rawPath.orEmpty() }.getOrDefault("")
            .trim('/').substringBefore('!')
        if (path.isBlank()) return ""
        val marker = path.indexOf("notes_pre_post/")
        if (marker >= 0) return path.substring(marker)
        val segments = path.split('/')
        if (segments.size > 2 && segments[0].matches(Regex("\\d{8,}"))) {
            return segments.drop(2).joinToString("/")
        }
        if (segments.size > 1) return path
        return segments.single().takeIf { it.length >= 20 && '.' !in it }.orEmpty()
    }

    private fun parseVideos(note: JSONObject, postId: String, thumbnailUrl: String): List<MediaItem> {
        val groups = videoGroups(note)
        return groups.mapIndexedNotNull { index, group ->
            val candidates = mutableListOf<MediaVariant>()
            collectVideoUrls(group, candidates)
            val ranked = rankVideoCandidates(candidates.distinctBy(MediaVariant::url))
            val selected = ranked.firstOrNull() ?: return@mapIndexedNotNull null
            val cover = group as? JSONObject
            val groupThumbnail = cover?.optString("cover").orEmpty().let(::secureUrl)
                .ifBlank { thumbnailUrl }
            MediaItem(
                MediaType.VIDEO,
                selected.url,
                MediaQuality.PLATFORM_BEST,
                postId,
                index + 1,
                thumbnailUrl = groupThumbnail,
                variants = ranked,
                selectedVariantKey = selected.key,
                fallbackUrls = ranked.drop(1).map(MediaVariant::url),
                sourceId = cover?.optJSONObject("consumer")?.optString("originVideoKey").orEmpty()
                    .ifBlank { cover?.optString("id").orEmpty() }
                    .ifBlank { cover?.optJSONObject("media")?.optString("videoId").orEmpty() },
            )
        }
    }

    private fun rankVideoCandidates(candidates: List<MediaVariant>): List<MediaVariant> = candidates
        .withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<MediaVariant>> { it.value.pixelArea }
                .thenByDescending { maxOf(it.value.width, it.value.height) }
                .thenByDescending { it.value.bitrate }
                .thenByDescending { MediaVariantSelector.inferVideoQuality(it.value.key, it.value.url).isOriginal }
                .thenByDescending { MediaVariantSelector.inferVideoQuality(it.value.key, it.value.url).height }
                .thenByDescending { xhsVideoSourcePriority(it.value.key) }
                .thenBy { it.index },
        )
        .map { it.value }

    private fun xhsVideoSourcePriority(key: String): Int = when {
        key.contains("origin", ignoreCase = true) -> 3
        key.contains("master", ignoreCase = true) -> 2
        key.contains("backup", ignoreCase = true) -> 1
        else -> 0
    }

    private fun videoGroups(note: JSONObject): List<Any?> {
        val value = VIDEO_FIELDS.asSequence()
            .mapNotNull(note::opt)
            .filter { value -> when (value) {
                JSONObject.NULL -> false
                is JSONObject -> value.length() > 0
                is JSONArray -> value.length() > 0
                is String -> value.isNotBlank()
                else -> false
            } }
            .firstOrNull()
        // An attachment array defines item boundaries. Codec/bitrate arrays
        // remain inside each item and are collected as variants below.
        if (value is JSONArray) {
            return (0 until value.length()).map { value.opt(it) }
        }
        val explicit = VIDEO_LIST_FIELDS
            .asSequence()
            .mapNotNull { note.optJSONArray(it) }
            .firstOrNull { it.length() > 0 }
        return if (explicit != null && explicit.length() > 0) {
            (0 until explicit.length()).map { explicit.opt(it) }
        } else {
            listOf(value).filterNot { it == null || it == JSONObject.NULL }
        }
    }

    private val VIDEO_FIELDS = listOf("video", "videoInfo", "video_info", "playInfo", "play_info", "media")
    private val VIDEO_LIST_FIELDS = listOf("videos", "videoList", "video_list", "mediaList")

    private fun collectVideoUrls(
        value: Any?,
        result: MutableList<MediaVariant>,
        key: String = "video",
        inheritedWidth: Int = 0,
        inheritedHeight: Int = 0,
        inheritedBitrate: Long = 0L,
        inheritedCodec: String = "",
    ) {
        when (value) {
            is JSONObject -> {
                val width = positiveInt(value, "width", "videoWidth", "video_width", "videoWidthPx")
                    .takeIf { it > 0 } ?: inheritedWidth
                val height = positiveInt(value, "height", "videoHeight", "video_height", "videoHeightPx")
                    .takeIf { it > 0 } ?: inheritedHeight
                val bitrate = positiveLong(value, "bitrate", "bit_rate", "avgBitrate", "avg_bitrate")
                    .takeIf { it > 0 } ?: inheritedBitrate
                val codec = MediaSourceIdentity.codec(value.optString("videoCodec").ifBlank { value.optString("video_codec") })
                    .ifBlank { MediaSourceIdentity.codec(key) }.ifBlank { inheritedCodec }
                value.keys().forEach { childKey ->
                    collectVideoUrls(value.opt(childKey), result, childKey, width, height, bitrate, codec)
                }
                value.optString("url").takeIf { it.isNotBlank() }?.let { url ->
                    addVideoVariant(url, key, width, height, bitrate, result, codec)
                }
            }
            is JSONArray -> for (index in 0 until value.length()) {
                collectVideoUrls(value.opt(index), result, key, inheritedWidth, inheritedHeight, inheritedBitrate,
                    MediaSourceIdentity.codec(key).ifBlank { inheritedCodec })
            }
            is String -> {
                val raw = value.trim()
                val url = if (raw.startsWith("http", true)) secureUrl(raw)
                else if (key.contains("origin", true) && key.contains("video", true))
                    "https://sns-video-bd.xhscdn.com/${raw.trimStart('/')}"
                else ""
                if (url.isNotBlank()) addVideoVariant(url, key, inheritedWidth, inheritedHeight, inheritedBitrate, result, inheritedCodec)
            }
        }
    }

    private fun positiveInt(value: JSONObject, vararg keys: String): Int = keys.asSequence()
        .mapNotNull { value.opt(it)?.toString()?.toIntOrNull() }
        .firstOrNull { it > 0 }
        ?: 0

    private fun positiveLong(value: JSONObject, vararg keys: String): Long = keys.asSequence()
        .mapNotNull { value.opt(it)?.toString()?.toLongOrNull() }
        .firstOrNull { it > 0L }
        ?: 0L

    private fun addVideoVariant(
        url: String,
        key: String,
        width: Int,
        height: Int,
        bitrate: Long,
        result: MutableList<MediaVariant>,
        codec: String = "",
    ) {
        val normalized = secureUrl(url)
        if (isVideoUrl(normalized, key)) result += MediaVariant(key.ifBlank { "video" }, normalized, width, height, bitrate, codec = codec)
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
    private val extractNoteScript by lazy {
        context.assets.open("xiaohongshu-note-extractor.js").bufferedReader().use { it.readText() }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun webViewRoot(activity: Activity): FrameLayout? =
        (activity.findViewById<View>(R.id.rootLayout) as? FrameLayout)
            ?: activity.findViewById(android.R.id.content)

    fun loadPost(sharedText: String): WeiboPost {
        val originalSource = XiaohongshuLinkParser.extractUrl(sharedText)
        if (!XiaohongshuLinkParser.isSupported(originalSource)) throw XiaohongshuException(context.getString(R.string.xiaohongshu_url_not_found))
        val source = XiaohongshuLinkParser.noteUrl(originalSource)
        if (source != originalSource) {
            // Author cards require the authenticated browser, with only the site's own query parameters.
            val data = loadFromWebView(source, context.getString(R.string.xiaohongshu_no_media))
            Log.d("XhsClient", "parsed id=${data.id} title=${data.title.take(80)} media=${data.media.size}")
            return buildPost(data, source)
        }
        val connection = (URL(source).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            setRequestProperty("Referer", "https://www.xiaohongshu.com/")
            XiaohongshuSession.cookieHeader().takeIf(String::isNotBlank)?.let { setRequestProperty("Cookie", it) }
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw XiaohongshuException(context.getString(R.string.xiaohongshu_http_error, status))
            val finalUrl = connection.url.toString()
            val html = connection.inputStream.bufferedReader().use { it.readText() }
            Log.d("XhsClient", "response status=$status final=${xiaohongshuLogUrl(finalUrl)} html=${html.length}")
            val fallbackId = XiaohongshuLinkParser.noteId(source)
                .ifBlank { XiaohongshuLinkParser.noteId(finalUrl) }.ifBlank { "xhs" }
            val parsed = if (fallbackId == "xhs") null
                else XiaohongshuPageNormalizer.normalize(html, fallbackId)
            val data = if (parsed != null && parsed.unresolvedMediaCount > 0) {
                // Keep usable attachments if the browser cannot fill the gaps,
                // but never present that partial result as a complete parse.
                runCatching { loadFromWebView(source, context.getString(R.string.xiaohongshu_no_media)) }
                    .getOrNull()?.takeIf { it.id == parsed.id && it.expectedMediaCount >= parsed.expectedMediaCount &&
                        it.unresolvedMediaCount < parsed.unresolvedMediaCount } ?: parsed
            } else parsed
                ?: loadFromWebView(source, context.getString(R.string.xiaohongshu_no_media))
            Log.d("XhsClient", "parsed id=${data.id} title=${data.title.take(80)} media=${data.media.size}")
            val resolvedSource = if (finalUrl.contains("/website-login/error")) source else finalUrl
            return buildPost(data, resolvedSource)
        } catch (error: XiaohongshuException) {
            throw error
        } catch (error: Exception) {
            throw XiaohongshuException(context.getString(R.string.xiaohongshu_network_error, error.message ?: context.getString(R.string.network_error)))
        } finally {
            connection.disconnect()
        }
    }

    private fun buildPost(data: XiaohongshuPostData, sourceUrl: String): WeiboPost {
        val author = data.author.ifBlank { context.getString(R.string.xiaohongshu_default_author) }
        val creatorProfile = CreatorProfile(
            platform = Platform.XIAOHONGSHU,
            id = data.authorId,
            name = author,
            avatarUrl = data.avatarUrl,
            profileUrl = data.authorId.takeIf(String::isNotBlank)?.let { authorId ->
                buildString {
                    append("https://www.xiaohongshu.com/user/profile/").append(authorId)
                    val token = data.profileAccessToken.ifBlank { data.accessToken }
                    if (token.isNotBlank()) {
                        append("?xsec_token=")
                        append(java.net.URLEncoder.encode(token, "UTF-8"))
                        append("&xsec_source=pc_user&channel_type=web_note_detail_r10&parent_page_channel_type=web_profile_board")
                    }
                }
            }.orEmpty(),
            accessToken = data.accessToken,
            seedPostId = data.id,
            seedPostTitle = data.title,
            seedPostCoverUrl = data.media.firstOrNull()?.thumbnailUrl.orEmpty()
                .ifBlank { data.media.firstOrNull()?.url.orEmpty() },
            seedPostUrl = sourceUrl,
        )
        return WeiboPost(
            id = data.id,
            text = data.title.ifBlank { context.getString(R.string.xiaohongshu_default_title) },
            author = author,
            sourceUrl = sourceUrl,
            media = data.media,
            creator = creatorProfile,
            notice = if (data.unresolvedMediaCount > 0) context.getString(
                R.string.media_parse_incomplete, data.unresolvedMediaCount) else "",
        )
    }

    private fun loadFromWebView(sourceUrl: String, failureMessage: String): XiaohongshuPostData {
        Log.d("XhsClient", "webview start source=${xiaohongshuLogUrl(sourceUrl)}")
        val latch = CountDownLatch(1)
        val run = ResolutionRequest()
        var result: XiaohongshuPostData? = null
        var failure: Throwable? = null
        var activeView: WebView? = null
        fun cleanUp() {
            activeView?.let { view ->
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.stopLoading()
                view.destroy()
            }
            activeView = null
        }
        mainHandler.post {
            if (!run.isActive()) return@post
            val activity = context as? Activity
            val root = activity?.let(::webViewRoot)
            val view = WebView(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                alpha = 0.01f
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = WEB_USER_AGENT
            }
            activeView = view
            val cookieNames = XiaohongshuSession.cookieHeader().split(';')
                .map { it.substringBefore('=').trim() }
                .filter(String::isNotBlank)
                .distinct()
                .joinToString(",")
            Log.i(
                "XhsClient",
                "detail webview ua=${view.settings.userAgentString} url=${xiaohongshuLogUrl(sourceUrl)} cookies=$cookieNames",
            )
            var attempts = 0
            fun finish(value: XiaohongshuPostData?, error: Throwable?) {
                if (!run.finish()) return
                Log.d("XhsClient", "webview finish url=${xiaohongshuLogUrl(view.url)} title=${view.title?.take(100)} parsed=${value != null} error=${error?.javaClass?.simpleName}")
                result = value
                failure = error
                cleanUp()
                latch.countDown()
            }
            fun poll() {
                if (!run.isActive()) return
                view.evaluateJavascript(extractNoteScript) { raw ->
                    if (!run.isActive()) return@evaluateJavascript
                    val encoded = runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
                    val fallbackId = XiaohongshuLinkParser.noteId(sourceUrl)
                        .ifBlank { XiaohongshuLinkParser.noteId(view.url.orEmpty()) }
                    val parsed = encoded.takeIf(String::isNotBlank)?.let { payload ->
                        XiaohongshuPageNormalizer.normalizePayload(payload, fallbackId)
                    }
                    if (attempts == 0 || parsed != null || attempts >= 20) {
                        Log.d("XhsClient", "webview poll attempt=$attempts url=${xiaohongshuLogUrl(view.url)} parsed=${parsed != null} payloadChars=${encoded.length}")
                    }
                    if (parsed != null && (parsed.unresolvedMediaCount == 0 || attempts >= 20)) finish(parsed, null)
                    else if (attempts++ < 20) mainHandler.postDelayed(::poll, 750)
                    else finish(null, XiaohongshuException(failureMessage))
                }
            }
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    Log.d("XhsClient", "webview finished url=${xiaohongshuLogUrl(url)} title=${view.title?.take(100)}")
                    if (run.startPollingOnce()) mainHandler.postDelayed(::poll, 500)
                }
            }
            val params = FrameLayout.LayoutParams(
                context.resources.displayMetrics.widthPixels,
                context.resources.displayMetrics.heightPixels,
            )
            params.leftMargin = -context.resources.displayMetrics.widthPixels
            if (root != null) root.addView(view, params)
            else view.layout(0, 0, params.width, params.height)
            view.loadUrl(sourceUrl)
        }
        try {
            if (!latch.await(35, TimeUnit.SECONDS)) {
                Log.d("XhsClient", "webview timeout source=${xiaohongshuLogUrl(sourceUrl)}")
                throw XiaohongshuException(failureMessage)
            }
            return result ?: throw (failure as? XiaohongshuException ?: XiaohongshuException(failureMessage))
        } finally {
            run.cancel()
            mainHandler.post(::cleanUp)
        }
    }

    companion object {
        private const val WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"


    }
}

class XiaohongshuException(message: String) : RuntimeException(message)

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
)

internal object XiaohongshuPageNormalizer {
    private val pageStateMarkers = listOf(
        "window.__INITIAL_STATE__",
        "window.__SETUP_SERVER_STATE__",
    )

    fun normalize(html: String, fallbackId: String = "xhs"): XiaohongshuPostData? {
        val state = pageStateMarkers.asSequence().mapNotNull { marker ->
            val markerStart = html.indexOf(marker)
            if (markerStart < 0) return@mapNotNull null
            val stateStart = html.indexOf('{', markerStart)
            val scriptEnd = html.indexOf("</script>", stateStart)
            if (stateStart < 0 || scriptEnd < 0) return@mapNotNull null
            val stateText = html.substring(stateStart, scriptEnd).trim().removeSuffix(";")
            runCatching { JSONObject(stateText) }.getOrNull()
        }.firstOrNull() ?: return null
        return normalizeState(state, fallbackId, html)
    }

    fun normalizePayload(payload: String, fallbackId: String = "xhs"): XiaohongshuPostData? {
        val state = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        return normalizeState(state, fallbackId, "")
    }

    private fun normalizeState(state: JSONObject, fallbackId: String, html: String): XiaohongshuPostData? {
        val note = findNote(state) ?: return null
        val id = note.optString("noteId").ifBlank { fallbackId }
        val user = note.optJSONObject("user") ?: JSONObject()
        val author = user.optString("nickName").ifBlank { user.optString("nickname") }
        val images = parseImages(note.optJSONArray("imageList"), id)
        val videoThumbnailUrl = images.firstOrNull()?.let { image ->
            image.thumbnailUrl.ifBlank { image.url }
        }.orEmpty()
        val videos = parseVideos(note, id, videoThumbnailUrl)
        // A video note normally exposes one poster image, but some notes expose
        // a gallery together with a video. Keep every real attachment instead
        // of dropping the gallery just because a video field is present.
        val media = if (videos.isNotEmpty()) {
            if (images.size > 1) videos + images else videos
        } else images
        if (media.isEmpty()) return null
        val accessToken = findAccessToken(note).ifBlank { findAccessToken(state) }
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
        )
    }

    private fun findNote(value: Any?): JSONObject? = when (value) {
        is JSONObject -> {
            if (value.has("noteId") && (value.has("imageList") || VIDEO_FIELDS.any(value::has))) value
            else value.keys().asSequence().mapNotNull { findNote(value.opt(it)) }.firstOrNull()
        }
        is JSONArray -> (0 until value.length()).asSequence().mapNotNull { findNote(value.opt(it)) }.firstOrNull()
        else -> null
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
            if (selectedUrl.isBlank()) continue
            val variants = buildList {
                originalUrls.forEachIndexed { index, url ->
                    add(MediaVariant(if (index == 0) "original" else "original_${index + 1}", url, width, height))
                }
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
        val value = note.opt("video") ?: VIDEO_FIELDS.asSequence()
            .drop(1)
            .mapNotNull(note::opt)
            .firstOrNull()
        if (value is JSONArray && value.length() > 1 && (0 until value.length()).all { index ->
                val item = value.optJSONObject(index) ?: return@all false
                item.has("media") || item.has("consumer") || item.has("videoId") || item.has("video_id") || item.has("id")
            }) {
            return (0 until value.length()).map { value.opt(it) }
        }
        val explicit = listOf("videos", "videoList", "video_list", "mediaList")
            .asSequence()
            .mapNotNull { note.optJSONArray(it) }
            .firstOrNull()
        return if (explicit != null && explicit.length() > 0) {
            (0 until explicit.length()).map { explicit.opt(it) }
        } else {
            listOf(value).filterNot { it == null || it == JSONObject.NULL }
        }
    }

    private val VIDEO_FIELDS = listOf("video", "videoInfo", "video_info", "playInfo", "play_info", "media")

    private fun collectVideoUrls(
        value: Any?,
        result: MutableList<MediaVariant>,
        key: String = "video",
        inheritedWidth: Int = 0,
        inheritedHeight: Int = 0,
        inheritedBitrate: Long = 0L,
    ) {
        when (value) {
            is JSONObject -> {
                val width = positiveInt(value, "width", "videoWidth", "video_width", "videoWidthPx")
                    .takeIf { it > 0 } ?: inheritedWidth
                val height = positiveInt(value, "height", "videoHeight", "video_height", "videoHeightPx")
                    .takeIf { it > 0 } ?: inheritedHeight
                val bitrate = positiveLong(value, "bitrate", "bit_rate", "avgBitrate", "avg_bitrate")
                    .takeIf { it > 0 } ?: inheritedBitrate
                value.keys().forEach { childKey ->
                    collectVideoUrls(value.opt(childKey), result, childKey, width, height, bitrate)
                }
                value.optString("url").takeIf { it.isNotBlank() }?.let { url ->
                    addVideoVariant(url, key, width, height, bitrate, result)
                }
            }
            is JSONArray -> for (index in 0 until value.length()) {
                collectVideoUrls(value.opt(index), result, key, inheritedWidth, inheritedHeight, inheritedBitrate)
            }
            is String -> {
                val raw = value.trim()
                val url = if (raw.startsWith("http", true)) secureUrl(raw)
                else if (key.contains("origin", true) && key.contains("video", true))
                    "https://sns-video-bd.xhscdn.com/${raw.trimStart('/')}"
                else ""
                if (url.isNotBlank()) addVideoVariant(url, key, inheritedWidth, inheritedHeight, inheritedBitrate, result)
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
    ) {
        val normalized = secureUrl(url)
        if (isVideoUrl(normalized, key)) result += MediaVariant(key.ifBlank { "video" }, normalized, width, height, bitrate)
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
            val fallbackId = Regex("/(?:discovery/item|explore)/((?:[0-9a-fA-F]{24}|[0-9a-fA-F]{32}))")
                .find(source)?.groupValues?.getOrNull(1)
                ?: Regex("/(?:discovery/item|explore)/((?:[0-9a-fA-F]{24}|[0-9a-fA-F]{32}))")
                    .find(finalUrl)?.groupValues?.getOrNull(1)
                ?: "xhs"
            val data = XiaohongshuPageNormalizer.normalize(html, fallbackId)
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
        )
    }

    private fun loadFromWebView(sourceUrl: String, failureMessage: String): XiaohongshuPostData {
        Log.d("XhsClient", "webview start source=${xiaohongshuLogUrl(sourceUrl)}")
        val latch = CountDownLatch(1)
        var result: XiaohongshuPostData? = null
        var failure: Throwable? = null
        mainHandler.post {
            val activity = context as? Activity
            val root = activity?.let(::webViewRoot)
            if (activity == null || root == null) {
                Log.d("XhsClient", "webview unavailable activity=${activity != null} root=${root != null}")
                failure = XiaohongshuException(failureMessage)
                latch.countDown()
                return@post
            }
            val view = WebView(activity).apply {
                setBackgroundColor(Color.TRANSPARENT)
                alpha = 0.01f
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = WEB_USER_AGENT
            }
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
                if (result != null || failure != null) return
                Log.d("XhsClient", "webview finish url=${xiaohongshuLogUrl(view.url)} title=${view.title?.take(100)} parsed=${value != null} error=${error?.javaClass?.simpleName}")
                result = value
                failure = error
                (view.parent as? FrameLayout)?.removeView(view)
                view.stopLoading()
                view.destroy()
                latch.countDown()
            }
            fun poll() {
                if (result != null || failure != null) return
                view.evaluateJavascript(EXTRACT_NOTE_SCRIPT) { raw ->
                    val encoded = runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
                    val fallbackId = Regex("/(?:discovery/item|explore)/((?:[0-9a-fA-F]{24}|[0-9a-fA-F]{32}))")
                        .find(view.url.orEmpty())?.groupValues?.getOrNull(1).orEmpty()
                    val parsed = encoded.takeIf(String::isNotBlank)?.let { payload ->
                        XiaohongshuPageNormalizer.normalizePayload(payload, fallbackId)
                    }
                    if (attempts == 0 || parsed != null || attempts >= 20) {
                        Log.d("XhsClient", "webview poll attempt=$attempts url=${xiaohongshuLogUrl(view.url)} parsed=${parsed != null} payloadChars=${encoded.length}")
                    }
                    if (parsed != null) finish(parsed, null)
                    else if (attempts++ < 20) mainHandler.postDelayed(::poll, 750)
                    else finish(null, XiaohongshuException(failureMessage))
                }
            }
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    Log.d("XhsClient", "webview finished url=${xiaohongshuLogUrl(url)} title=${view.title?.take(100)}")
                    attempts = 0
                    mainHandler.postDelayed(::poll, 500)
                }
            }
            val params = FrameLayout.LayoutParams(
                activity.resources.displayMetrics.widthPixels,
                activity.resources.displayMetrics.heightPixels,
            )
            params.leftMargin = -activity.resources.displayMetrics.widthPixels
            root.addView(view, params)
            view.loadUrl(sourceUrl)
        }
        if (!latch.await(35, TimeUnit.SECONDS)) {
            Log.d("XhsClient", "webview timeout source=${xiaohongshuLogUrl(sourceUrl)}")
            throw XiaohongshuException(failureMessage)
        }
        return result ?: throw (failure as? XiaohongshuException ?: XiaohongshuException(failureMessage))
    }

    companion object {
        private const val WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val EXTRACT_NOTE_SCRIPT = """
            (function() {
              const state = window.__INITIAL_STATE__ || {};
              const routeId = (location.pathname.match(/\/(?:explore|discovery\/item)\/([0-9a-fA-F]{24,32})/) || [])[1] || '';
              const direct = state.noteData && state.noteData.data && state.noteData.data.noteData;
              const mapped = routeId && state.note && state.note.noteDetailMap &&
                state.note.noteDetailMap[routeId] && state.note.noteDetailMap[routeId].note;
              let found = direct || mapped || null;
              const seen = new Set();
              const walk = value => {
                if (found || !value || typeof value !== 'object' || seen.has(value)) return;
                seen.add(value);
                if (String(value.noteId || '') === routeId && (value.imageList || value.video)) {
                  found = value;
                  return;
                }
                Object.keys(value).forEach(key => walk(value[key]));
              };
              if (!found) walk(state);
              try { return found && (found.noteId || found.imageList || found.video) ? JSON.stringify(found) : ''; }
              catch (_) { return ''; }
            })()
        """

    }
}

class XiaohongshuException(message: String) : RuntimeException(message)

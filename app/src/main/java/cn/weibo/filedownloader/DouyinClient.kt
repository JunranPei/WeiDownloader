package cn.weibo.filedownloader

import android.app.Activity
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONTokener
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Resolves public Douyin posts without making the Weibo client depend on
 * platform-specific behavior. The official detail response is the primary
 * source; strictly ID-scoped WebView and SEO-page parsers are fallbacks.
 */
class DouyinClient(private val activity: Activity) {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var webView: WebView? = null
    private var finished = AtomicBoolean(false)

    fun loadPost(sharedUrl: String, quality: QualityPreference, callback: (Result<WeiboPost>) -> Unit) {
        val sourceUrl = DouyinLinkParser.extractUrl(sharedUrl)
        if (sourceUrl.isBlank()) {
            callback(Result.failure(DouyinException(activity.getString(R.string.douyin_url_not_found))))
            return
        }
        finished = AtomicBoolean(false)
        executor.execute {
            val apiResult = runCatching { loadFromApi(sourceUrl) }
            handler.post {
                if (apiResult.isSuccess) {
                    if (!finished.getAndSet(true)) callback(apiResult)
                    return@post
                }
                if (finished.get()) return@post
                val id = runCatching { resolvePostId(sourceUrl) }
                    .getOrDefault(DouyinLinkParser.extractPostId(sourceUrl))
                loadWithWebView(sourceUrl, id, quality) { webResult ->
                    if (finished.getAndSet(true)) return@loadWithWebView
                    removeWebView()
                    if (webResult.isSuccess) {
                        executor.execute {
                            val post = webResult.getOrThrow()
                            val hydrated = runCatching { hydrateMediaMetadata(post) }.getOrDefault(post)
                            handler.post { callback(Result.success(hydrated)) }
                        }
                    } else {
                        executor.execute {
                            val fallback = runCatching { hydrateMediaMetadata(loadFromHtml(sourceUrl, quality)) }
                            handler.post {
                                if (fallback.isSuccess) callback(fallback)
                                else callback(Result.failure(apiResult.exceptionOrNull() ?: webResult.exceptionOrNull() ?: fallback.exceptionOrNull() ?: DouyinException(activity.getString(R.string.douyin_no_media))))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun loadFromApi(sourceUrl: String): WeiboPost {
        val postId = resolvePostId(sourceUrl)
        if (postId.isBlank()) throw DouyinException(activity.getString(R.string.douyin_url_not_found))
        val endpoint = "https://www.douyin.com/aweme/v1/web/aweme/detail/" +
            "?aweme_id=$postId&aid=6383&device_platform=webapp&source=Googlebot-sdc"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", SEO_USER_AGENT)
            setRequestProperty("Accept", "application/json,text/plain,*/*")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            setRequestProperty("Referer", "https://www.douyin.com/video/$postId")
        }
        val body = try {
            BufferedInputStream(connection.inputStream).use { it.readBytes().toString(StandardCharsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
        if (body.isBlank()) throw DouyinException(activity.getString(R.string.douyin_no_media))
        val detail = JSONObject(body).optJSONObject("aweme_detail")
            ?: throw DouyinException(activity.getString(R.string.douyin_no_media))
        if (detail.optString("aweme_id") != postId) {
            throw DouyinException(activity.getString(R.string.douyin_no_media))
        }
        val title = detail.optString("desc").ifBlank { activity.getString(R.string.douyin_default_title) }
        val author = detail.optJSONObject("author")?.optString("nickname").orEmpty()
            .ifBlank { activity.getString(R.string.douyin_default_author) }
        val imageMedia = parseApiImages(detail.optJSONArray("images"), postId)
        if (imageMedia.isNotEmpty()) {
            return WeiboPost(postId, title, author, sourceUrl, imageMedia)
        }
        val videoMedia = parseApiVideo(detail.optJSONObject("video"), postId)
            ?: throw DouyinException(activity.getString(R.string.douyin_no_media))
        return WeiboPost(postId, title, author, sourceUrl, listOf(videoMedia))
    }

    private fun parseApiImages(images: JSONArray?, postId: String): List<MediaItem> {
        if (images == null) return emptyList()
        return buildList {
            for (index in 0 until images.length()) {
                val image = images.optJSONObject(index) ?: continue
                val urls = image.optJSONArray("url_list")?.toStringList()
                    ?.filter(DouyinContentPolicy::isContentImageUrl)
                    .orEmpty()
                val url = urls.lastOrNull { runCatching { URI(it).path.orEmpty().endsWith(".jpeg", true) }.getOrDefault(false) }
                    ?: urls.firstOrNull()
                    ?: continue
                val width = image.optInt("width")
                val height = image.optInt("height")
                val key = if (width > 0 && height > 0) "${width}×${height}" else MediaQuality.PLATFORM_BEST
                add(
                    MediaItem(
                        type = MediaType.IMAGE,
                        url = url,
                        quality = MediaQuality.PLATFORM_BEST,
                        postId = postId,
                        ordinal = size + 1,
                        thumbnailUrl = url,
                        variants = listOf(MediaVariant(key, url, width, height)),
                        selectedVariantKey = key,
                    ),
                )
            }
        }
    }

    private fun parseApiVideo(video: JSONObject?, postId: String): MediaItem? {
        if (video == null) return null
        val variants = buildList {
            val rates = video.optJSONArray("bit_rate")
            if (rates != null) {
                for (index in 0 until rates.length()) {
                    val rate = rates.optJSONObject(index) ?: continue
                    val address = rate.optJSONObject("play_addr") ?: continue
                    val url = address.optJSONArray("url_list")?.toStringList()
                        ?.firstOrNull(DouyinContentPolicy::isTrustedVideoResource)
                        ?: continue
                    val codec = if (rate.optInt("is_h265") == 1) "H.265" else "H.264"
                    val height = address.optInt("height")
                    val bitrate = rate.optLong("bit_rate")
                    val resolution = if (height > 0) "${height}p" else "Variant ${index + 1}"
                    val bitrateLabel = if (bitrate > 0) " · ${(bitrate / 1000L).coerceAtLeast(1L)} kbps" else ""
                    add(
                        MediaVariant(
                            key = "$resolution · $codec$bitrateLabel",
                            url = url,
                            width = address.optInt("width"),
                            height = height,
                            bitrate = bitrate,
                        ),
                    )
                }
            }
            if (isEmpty()) {
                for (field in listOf("play_addr_h264", "play_addr", "play_addr_265")) {
                    val address = video.optJSONObject(field) ?: continue
                    val url = address.optJSONArray("url_list")?.toStringList()
                        ?.firstOrNull(DouyinContentPolicy::isTrustedVideoResource)
                        ?: continue
                    add(MediaVariant(field, url, address.optInt("width"), address.optInt("height")))
                }
            }
        }.distinctBy { it.url }
        if (variants.isEmpty()) return null
        val ranked = MediaVariantSelector.rank(MediaType.VIDEO, variants)
        val best = ranked.first()
        val poster = video.optJSONObject("cover")?.optJSONArray("url_list")?.toStringList()?.firstOrNull().orEmpty()
        return MediaItem(
            type = MediaType.VIDEO,
            url = best.url,
            quality = MediaQuality.HD_BEST,
            postId = postId,
            ordinal = 1,
            thumbnailUrl = poster,
            variants = ranked,
            selectedVariantKey = best.key,
        )
    }

    private fun resolvePostId(sourceUrl: String): String {
        DouyinLinkParser.extractPostId(sourceUrl).takeIf(String::isNotBlank)?.let { return it }
        val connection = (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", SHARE_USER_AGENT)
        }
        val finalUrl = try {
            connection.inputStream.use { input -> input.read(ByteArray(1)) }
            connection.url?.toString().orEmpty()
        } finally {
            connection.disconnect()
        }
        return DouyinLinkParser.extractPostId(finalUrl)
    }

    fun close() {
        finished.set(true)
        handler.post { removeWebView() }
        executor.shutdownNow()
    }

    private fun loadWithWebView(
        sourceUrl: String,
        postId: String,
        quality: QualityPreference,
        callback: (Result<WeiboPost>) -> Unit,
    ) {
        handler.post {
            val root = activity.findViewById<View>(R.id.rootLayout) as? FrameLayout
                ?: (activity.window.decorView.findViewById(android.R.id.content) as FrameLayout)
            val view = WebView(activity).apply {
                setBackgroundColor(Color.TRANSPARENT)
                alpha = 0.01f
                visibility = View.VISIBLE
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.userAgentString = WEB_USER_AGENT
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        pollPage(view, sourceUrl, DouyinLinkParser.extractPostId(url).ifBlank { postId }, quality, 0, callback)
                    }
                }
                webChromeClient = WebChromeClient()
            }
            webView = view
            val params = FrameLayout.LayoutParams(
                activity.resources.displayMetrics.widthPixels,
                activity.resources.displayMetrics.heightPixels,
            )
            view.translationX = -activity.resources.displayMetrics.widthPixels.toFloat()
            root.addView(view, params)
            view.loadUrl(toSharePage(sourceUrl, postId))
        }
    }

    private fun pollPage(
        view: WebView,
        sourceUrl: String,
        postId: String,
        quality: QualityPreference,
        attempt: Int,
        callback: (Result<WeiboPost>) -> Unit,
    ) {
        if (finished.get()) return
        if (postId.isBlank()) {
            if (attempt < MAX_PAGE_POLLS) {
                handler.postDelayed({ pollPage(view, sourceUrl, DouyinLinkParser.extractPostId(view.url.orEmpty()), quality, attempt + 1, callback) }, PAGE_POLL_DELAY_MS)
            } else {
                callback(Result.failure(DouyinException(activity.getString(R.string.douyin_page_no_media))))
            }
            return
        }
        view.evaluateJavascript(buildExtractScript(postId)) { raw ->
            val parsed = parseWebResult(raw, sourceUrl, postId, quality)
            if (parsed != null) {
                callback(Result.success(parsed))
            } else if (attempt < MAX_PAGE_POLLS) {
                handler.postDelayed({ pollPage(view, sourceUrl, postId, quality, attempt + 1, callback) }, PAGE_POLL_DELAY_MS)
            } else {
                callback(Result.failure(DouyinException(activity.getString(R.string.douyin_page_no_media))))
            }
        }
    }

    private fun parseWebResult(raw: String, sourceUrl: String, postId: String, quality: QualityPreference): WeiboPost? {
        val value = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: return null
        val data = runCatching { JSONObject(value) }.getOrNull() ?: return null
        if (data.optString("id") != postId) return null
        val title = data.optString("title").ifBlank { activity.getString(R.string.douyin_default_title) }
        val author = data.optString("author").ifBlank { activity.getString(R.string.douyin_default_author) }
        val images = data.optJSONArray("images")?.toStringList()
            ?.filter(DouyinContentPolicy::isContentImageUrl)
            ?.distinct()
            .orEmpty()
        if (images.isNotEmpty()) {
            return WeiboPost(
                id = postId,
                text = title,
                author = author,
                sourceUrl = sourceUrl,
                media = images.mapIndexed { index, url ->
                    MediaItem(MediaType.IMAGE, url, MediaQuality.PLATFORM_BEST, postId, index + 1, url)
                },
            )
        }
        val scopedVideo = data.optString("video")
            .takeIf(DouyinContentPolicy::isScopedVideoUrl)
        val resourceVideo = data.optJSONArray("resources")?.toStringList()
            ?.lastOrNull(DouyinContentPolicy::isTrustedVideoResource)
        val videoUrl = scopedVideo ?: resourceVideo ?: return null
        return WeiboPost(
            id = postId,
            text = title,
            author = author,
            sourceUrl = sourceUrl,
            media = listOf(MediaItem(MediaType.VIDEO, videoUrl, qualityKey(quality), postId, 1, data.optString("poster"))),
        )
    }

    private fun loadFromHtml(sourceUrl: String, @Suppress("UNUSED_PARAMETER") quality: QualityPreference): WeiboPost {
        val inputId = DouyinLinkParser.extractPostId(sourceUrl)
        val requestUrl = when {
            inputId.isBlank() -> sourceUrl
            sourceUrl.contains("/note/", true) || sourceUrl.contains("/slides/", true) ->
                "https://www.douyin.com/note/$inputId?source=Googlebot"
            else -> "https://www.douyin.com/video/$inputId?source=Googlebot"
        }
        val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", SEO_USER_AGENT)
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            setRequestProperty("Referer", "https://www.douyin.com/")
        }
        val body = try {
            BufferedInputStream(connection.inputStream).use { input ->
                input.readBytes().toString(StandardCharsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
        val id = DouyinLinkParser.extractPostId(connection.url?.toString().orEmpty())
            .ifBlank { DouyinLinkParser.extractPostId(sourceUrl) }
        if (id.isBlank() || !body.contains(id)) throw DouyinException(activity.getString(R.string.douyin_no_media))
        return parseJsonLdPost(body, sourceUrl, id)
            ?: throw DouyinException(activity.getString(R.string.douyin_no_media))
    }

    private fun parseJsonLdPost(body: String, sourceUrl: String, postId: String): WeiboPost? {
        val jsonBlocks = JSON_LD_REGEX.findAll(body).mapNotNull { match ->
            val value = decodeHtmlEntities(match.groupValues[1])
            runCatching { JSONTokener(value).nextValue() as? JSONObject }.getOrNull()
        }
        for (data in jsonBlocks) {
            if (!data.optString("url").contains(postId)) continue
            if (!data.optString("@type").equals("article", true)) continue
            val images = when (val image = data.opt("image")) {
                is JSONArray -> image.toStringList()
                is String -> listOf(image)
                else -> emptyList()
            }.filter(DouyinContentPolicy::isContentImageUrl).distinct()
            if (images.isEmpty()) continue
            val title = data.optString("headline").ifBlank { data.optString("description") }
                .ifBlank { activity.getString(R.string.douyin_default_title) }
            val author = data.optJSONObject("author")?.optString("name").orEmpty()
                .ifBlank { activity.getString(R.string.douyin_default_author) }
            return WeiboPost(
                id = postId,
                text = title,
                author = author,
                sourceUrl = sourceUrl,
                media = images.mapIndexed { index, url ->
                    MediaItem(MediaType.IMAGE, url, MediaQuality.PLATFORM_BEST, postId, index + 1, url)
                },
            )
        }
        return null
    }

    private fun hydrateMediaMetadata(post: WeiboPost): WeiboPost = post.copy(
        media = post.media.map { item ->
            if (item.type != MediaType.VIDEO && item.type != MediaType.LIVE_PHOTO && item.type != MediaType.AUDIO) {
                return@map item
            }
            val probe = probeMedia(item.url) ?: return@map item
            val key = when {
                probe.height > 0 -> "${probe.height}p"
                probe.bitrate > 0L -> "${(probe.bitrate / 1000L).coerceAtLeast(1L)} kbps"
                else -> "best available"
            }
            val variant = MediaVariant(
                key = key,
                url = item.url,
                width = probe.width,
                height = probe.height,
                bitrate = probe.bitrate,
            )
            item.copy(variants = listOf(variant), selectedVariantKey = key)
        },
    )

    private data class MediaProbe(val width: Int, val height: Int, val bitrate: Long)

    private fun probeMedia(url: String): MediaProbe? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(
                url,
                mapOf(
                    "User-Agent" to DOWNLOAD_USER_AGENT,
                    "Referer" to "https://www.douyin.com/",
                ),
            )
            MediaProbe(
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L,
            ).takeIf { it.width > 0 || it.height > 0 || it.bitrate > 0L }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun removeWebView() {
        webView?.let { view ->
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.stopLoading()
            view.destroy()
        }
        webView = null
    }

    private fun toSharePage(url: String, id: String): String = when {
        url.contains("iesdouyin.com", true) -> url
        (url.contains("/note/", true) || url.contains("/slides/", true)) && id.isNotBlank() ->
            "https://www.douyin.com/note/$id"
        id.isNotBlank() -> "https://www.douyin.com/video/$id"
        else -> url
    }

    private fun qualityKey(@Suppress("UNUSED_PARAMETER") quality: QualityPreference): String =
        MediaQuality.HD_BEST

    private fun JSONArray.toStringList(): List<String> = buildList {
        for (index in 0 until length()) optString(index).takeIf(String::isNotBlank)?.let(::add)
    }

    private fun decodeHtmlEntities(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&#34;", "\"")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    private fun buildExtractScript(postId: String): String = """
        (function() {
          const id = ${JSONObject.quote(postId)};
          const canonical = document.querySelector("link[rel='canonical']")?.href || location.href;
          const pageMatches = canonical.includes(id) || location.pathname.includes(id);
          if (!pageMatches) return JSON.stringify({id: ''});

          let article = null;
          for (const node of document.querySelectorAll("script[type='application/ld+json']")) {
            try {
              const value = JSON.parse(node.textContent || '{}');
              if (String(value?.url || '').includes(id) && String(value?.['@type'] || '').toLowerCase() === 'article') {
                article = value;
                break;
              }
            } catch (_) {}
          }
          if (article) {
            const values = Array.isArray(article.image) ? article.image : [article.image];
            const images = values.map(value => typeof value === 'string' ? value : value?.url || value?.contentUrl || '').filter(Boolean);
            const author = typeof article.author === 'object' ? article.author?.name || '' : article.author || '';
            return JSON.stringify({id, images, title: article.headline || article.description || '', author});
          }

          const exactContainer = document.querySelector('[class~="video_' + id + '"]');
          const marker = document.querySelector('[data-e2e-aweme-id="' + id + '"]');
          const scope = exactContainer || marker?.closest('[data-e2e="video-detail"]');
          const video = scope?.querySelector('video') || null;
          if (video) {
            video.muted = true;
            const promise = video.play();
            if (promise?.catch) promise.catch(() => {});
          }
          const directVideo = video?.currentSrc || video?.src || video?.querySelector('source')?.src || '';
          const resources = performance.getEntriesByType('resource')
            .map(entry => entry.name || '')
            .filter(url => /(?:^|\\.)i?douyinvod\\.com|\\/aweme\\/v1\\/(?:play|playwm)\\//i.test(url));
          const title = document.querySelector("meta[name='description']")?.content || document.title || '';
          const author = marker?.querySelector("[class*='author']")?.textContent || '';
          return JSON.stringify({id, video: directVideo, resources, poster: video?.poster || '', title, author});
        })()
    """.trimIndent()

    class DouyinException(message: String) : RuntimeException(message)

    private companion object {
        const val WEB_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        const val SEO_USER_AGENT = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
        const val SHARE_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36"
        const val DOWNLOAD_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36"
        val JSON_LD_REGEX = Regex("<script[^>]+type=[\\\"']application/ld\\+json[\\\"'][^>]*>(.*?)</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        const val PAGE_POLL_DELAY_MS = 450L
        const val MAX_PAGE_POLLS = 18
    }
}

internal object DouyinContentPolicy {
    fun isScopedVideoUrl(value: String): Boolean = isHttp(value) && !isKnownSiteAsset(value) && !isImage(value)

    fun isTrustedVideoResource(value: String): Boolean {
        if (!isHttp(value) || isKnownSiteAsset(value)) return false
        val host = runCatching { URI(value).host.orEmpty().lowercase() }.getOrDefault("")
        val path = runCatching { URI(value).path.orEmpty().lowercase() }.getOrDefault("")
        return host.endsWith(".douyinvod.com") || host == "douyinvod.com" ||
            host.endsWith(".idouyinvod.com") || host == "idouyinvod.com" ||
            (path.contains("/aweme/v1/play/") || path.contains("/aweme/v1/playwm/"))
    }

    fun isContentImageUrl(value: String): Boolean {
        if (!isHttp(value) || isKnownSiteAsset(value)) return false
        val host = runCatching { URI(value).host.orEmpty().lowercase() }.getOrDefault("")
        val path = runCatching { URI(value).path.orEmpty().lowercase() }.getOrDefault("")
        return (host.endsWith(".douyinpic.com") || host == "douyinpic.com" || host.endsWith(".byteimg.com")) &&
            (path.contains("tos-cn-i-") || path.contains("aweme-images") || path.contains("aweme_images"))
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

internal object DouyinLinkParser {
    private val urlRegex = Regex("https?://[^\\s\\[\\]()<>]+", RegexOption.IGNORE_CASE)
    private val postIdRegex = Regex("/(?:share/)?(?:video|note|slides)/(\\d+)", RegexOption.IGNORE_CASE)

    fun extractUrl(value: String): String {
        val cleaned = value.trim().replace("\\u002F", "/").replace("\\/", "/")
        return (urlRegex.find(cleaned)?.value ?: cleaned)
            .trimEnd('.', ',', '!', '?', '，', '。', '！', '？', '）', ')', ']', '；', ';')
    }

    fun extractPostId(value: String): String =
        postIdRegex.find(value)?.groupValues?.getOrNull(1).orEmpty()
}

package cn.weibo.filedownloader

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.RenderProcessGoneDetail
import android.widget.FrameLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONTokener
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Resolves public Douyin posts without making the Weibo client depend on
 * platform-specific behavior. The official detail response is the primary
 * source; strictly ID-scoped WebView and SEO-page parsers are fallbacks.
 */
class DouyinClient(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val linkResolver = DouyinLinkResolver()
    private val detailProviders: List<DouyinDetailProvider> = listOf(
        DouyinWebDetailProvider("6383"),
        DouyinWebDetailProvider("1128"),
    )
    private var webView: WebView? = null
    private var request: ResolutionRequest? = null

    fun loadPost(sharedUrl: String, quality: QualityPreference, callback: (Result<WeiboPost>) -> Unit) {
        val sourceUrl = DouyinLinkParser.extractUrl(sharedUrl)
        if (sourceUrl.isBlank()) {
            callback(Result.failure(DouyinException(context.getString(R.string.douyin_url_not_found))))
            return
        }
        request?.cancel()
        removeWebView()
        val run = ResolutionRequest().also { request = it }
        handler.postDelayed({
            if (run.finish()) {
                removeWebView()
                callback(Result.failure(DouyinException(context.getString(R.string.douyin_resolution_timeout))))
            }
        }, RESOLUTION_TIMEOUT_MS)
        executor.execute {
            val targetResult = runCatching { linkResolver.resolve(sourceUrl) }
            if (!run.isActive()) return@execute
            val apiResult = targetResult.mapCatching { hydrateMediaMetadata(loadFromDetailProviders(it)) }
            handler.post {
                if (!run.isActive()) return@post
                if (apiResult.isSuccess) {
                    if (run.finish()) callback(apiResult)
                    return@post
                }
                val target = targetResult.getOrNull()
                if (target == null) {
                    if (run.finish()) {
                        callback(Result.failure(DouyinException(context.getString(R.string.douyin_url_not_found))))
                    }
                    return@post
                }
                loadWithWebView(target, quality, run) { webResult ->
                    if (!run.isActive()) return@loadWithWebView
                    removeWebView()
                    if (webResult.isSuccess) {
                        executor.execute {
                            val post = webResult.getOrThrow()
                            val hydrated = runCatching { hydrateMediaMetadata(post) }.getOrDefault(post)
                            handler.post { if (run.finish()) callback(Result.success(hydrated)) }
                        }
                    } else {
                        executor.execute {
                            val fallback = runCatching { hydrateMediaMetadata(loadFromHtml(target, quality)) }
                            handler.post {
                                if (!run.finish()) return@post
                                if (fallback.isSuccess) callback(fallback)
                                else callback(Result.failure(DouyinException(context.getString(R.string.douyin_page_no_media))))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun loadFromDetailProviders(target: DouyinResolvedLink): WeiboPost {
        var lastFailure: Exception? = null
        for (postId in target.postIds) {
            for (provider in detailProviders) {
                val work = try {
                    provider.load(target, postId)
                } catch (error: Exception) {
                    lastFailure = error
                    null
                } ?: continue
                return WeiboPost(
                    id = work.id,
                    text = work.title.ifBlank { context.getString(R.string.douyin_default_title) },
                    author = work.author.ifBlank { context.getString(R.string.douyin_default_author) },
                    sourceUrl = target.sourceUrl,
                    media = work.media,
                    creator = work.creator,
                )
            }
        }
        throw lastFailure ?: DouyinException(context.getString(R.string.douyin_no_media))
    }

    fun close() {
        request?.cancel()
        handler.removeCallbacksAndMessages(null)
        handler.post { removeWebView() }
        executor.shutdownNow()
    }

    private fun loadWithWebView(
        target: DouyinResolvedLink,
        quality: QualityPreference,
        run: ResolutionRequest,
        callback: (Result<WeiboPost>) -> Unit,
    ) {
        handler.post {
            if (!run.isActive()) return@post
            val browserFinished = AtomicBoolean(false)
            fun finishBrowser(result: Result<WeiboPost>) {
                if (run.isActive() && browserFinished.compareAndSet(false, true)) callback(result)
            }
            val activity = context as? Activity
            val root = activity?.findViewById<View>(R.id.rootLayout) as? FrameLayout
                ?: activity?.window?.decorView?.findViewById<FrameLayout>(android.R.id.content)
            val browserUrl = browserFallbackUrl(target)
            val view = WebView(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                alpha = 0.01f
                visibility = View.VISIBLE
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.userAgentString = WEB_USER_AGENT
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.url.scheme !in listOf("http", "https")

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) {
                            Log.d("DouyinClient", "page load error=${error.errorCode} description=${error.description}")
                        }
                    }

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        Log.w("DouyinClient", "page renderer exited; crashed=${detail.didCrash()}")
                        if (webView === view) webView = null
                        (view.parent as? android.view.ViewGroup)?.removeView(view)
                        runCatching { view.stopLoading(); view.destroy() }
                        finishBrowser(Result.failure(DouyinException(context.getString(R.string.douyin_page_no_media))))
                        return true
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        if (!run.startPollingOnce()) return
                        pollPage(
                            view,
                            target.sourceUrl,
                            target.primaryPostId,
                            quality,
                            run,
                            0,
                            ::finishBrowser,
                        )
                    }
                }
                webChromeClient = WebChromeClient()
            }
            webView = view
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(
                    view,
                    detailCaptureScript,
                    setOf("https://www.douyin.com", "https://www.iesdouyin.com"),
                )
            }
            val params = FrameLayout.LayoutParams(
                context.resources.displayMetrics.widthPixels,
                context.resources.displayMetrics.heightPixels,
            )
            view.translationX = -context.resources.displayMetrics.widthPixels.toFloat()
            if (root != null) root.addView(view, params)
            else view.layout(0, 0, params.width, params.height)
            handler.postDelayed({
                finishBrowser(Result.failure(DouyinException(context.getString(R.string.douyin_page_no_media))))
            }, BROWSER_TIMEOUT_MS)
            view.loadUrl(browserUrl)
        }
    }

    private fun browserFallbackUrl(target: DouyinResolvedLink): String {
        val postId = target.primaryPostId
        if (postId.isBlank()) return target.canonicalUrl
        val path = runCatching { URL(target.canonicalUrl).path.lowercase() }.getOrDefault("")
        val route = if (path.contains("/slides/") || path.contains("/note/") || path.contains("/gallery/")) {
            "note"
        } else {
            "video"
        }
        return "https://www.douyin.com/$route/$postId"
    }

    private fun pollPage(
        view: WebView,
        sourceUrl: String,
        postId: String,
        quality: QualityPreference,
        run: ResolutionRequest,
        attempt: Int,
        callback: (Result<WeiboPost>) -> Unit,
    ) {
        if (!run.isActive() || webView !== view) return
        if (postId.isBlank()) {
            if (attempt < MAX_PAGE_POLLS) {
                handler.postDelayed({ pollPage(view, sourceUrl, postId, quality, run, attempt + 1, callback) }, PAGE_POLL_DELAY_MS)
            } else {
                callback(Result.failure(DouyinException(context.getString(R.string.douyin_page_no_media))))
            }
            return
        }
        view.evaluateJavascript(buildExtractScript(postId)) { raw ->
            if (!run.isActive() || webView !== view) return@evaluateJavascript
            val parsed = parseWebResult(raw, sourceUrl, postId, quality)
            if (parsed != null) {
                callback(Result.success(parsed))
            } else if (attempt < MAX_PAGE_POLLS) {
                handler.postDelayed({ pollPage(view, sourceUrl, postId, quality, run, attempt + 1, callback) }, PAGE_POLL_DELAY_MS)
            } else {
                callback(Result.failure(DouyinException(context.getString(R.string.douyin_page_no_media))))
            }
        }
    }

    private fun parseWebResult(raw: String, sourceUrl: String, postId: String, quality: QualityPreference): WeiboPost? {
        val value = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: return null
        val data = runCatching { JSONObject(value) }.getOrNull() ?: return null
        if (data.optString("id") != postId) return null
        data.optJSONArray("details")?.let { details ->
            (0 until details.length()).mapNotNull { index ->
                details.optJSONObject(index)?.let { DouyinWorkNormalizer.normalize(it, postId) }
            }.maxByOrNull { it.media.size }?.let { work ->
                return WeiboPost(
                    id = work.id,
                    text = work.title.ifBlank { context.getString(R.string.douyin_default_title) },
                    author = work.author.ifBlank { context.getString(R.string.douyin_default_author) },
                    sourceUrl = sourceUrl,
                    media = work.media,
                    creator = work.creator,
                )
            }
        }
        val title = data.optString("title").ifBlank { context.getString(R.string.douyin_default_title) }
        val author = data.optString("author").ifBlank { context.getString(R.string.douyin_default_author) }
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
        val videoUrl = scopedVideo ?: return null
        return WeiboPost(
            id = postId,
            text = title,
            author = author,
            sourceUrl = sourceUrl,
            media = listOf(MediaItem(MediaType.VIDEO, videoUrl, qualityKey(quality), postId, 1, data.optString("poster"))),
        )
    }

    private fun loadFromHtml(target: DouyinResolvedLink, @Suppress("UNUSED_PARAMETER") quality: QualityPreference): WeiboPost {
        val postId = target.primaryPostId
        val requestUrls = listOf(
            target.canonicalUrl,
            "https://www.douyin.com/note/$postId?source=Googlebot",
            "https://www.douyin.com/video/$postId?source=Googlebot",
        ).distinct()
        var lastFailure: Exception? = null
        for (requestUrl in requestUrls) {
            val post = try {
                val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", SEO_USER_AGENT)
                    setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                    setRequestProperty("Referer", "https://www.douyin.com/")
                    DouyinSession.cookieHeader(requestUrl).takeIf(String::isNotBlank)
                        ?.let { setRequestProperty("Cookie", it) }
                }
                val body = try {
                    BufferedInputStream(connection.inputStream).use { input ->
                        input.readBytes().toString(StandardCharsets.UTF_8)
                    }
                } finally {
                    connection.disconnect()
                }
                if (!body.contains(postId)) null else parseJsonLdPost(body, target.sourceUrl, postId)
            } catch (error: Exception) {
                lastFailure = error
                null
            }
            if (post != null) return post
        }
        throw lastFailure ?: DouyinException(context.getString(R.string.douyin_no_media))
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
                .ifBlank { context.getString(R.string.douyin_default_title) }
            val author = data.optJSONObject("author")?.optString("name").orEmpty()
                .ifBlank { context.getString(R.string.douyin_default_author) }
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
            item.withProbedMetadata(probe.width, probe.height, probe.bitrate)
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

    private val extractionTemplate by lazy {
        context.assets.open("douyin-post-extractor.js").bufferedReader().use { it.readText() }
    }

    private val detailCaptureScript by lazy {
        context.assets.open("douyin-detail-capture.js").bufferedReader().use { it.readText() }
    }

    private fun buildExtractScript(postId: String): String =
        extractionTemplate.replace("__POST_ID_JSON__", JSONObject.quote(postId))

    class DouyinException(message: String) : RuntimeException(message)

    private companion object {
        const val WEB_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        const val SEO_USER_AGENT = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
        const val DOWNLOAD_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36"
        val JSON_LD_REGEX = Regex("<script[^>]+type=[\\\"']application/ld\\+json[\\\"'][^>]*>(.*?)</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        const val PAGE_POLL_DELAY_MS = 450L
        const val MAX_PAGE_POLLS = 60
        const val BROWSER_TIMEOUT_MS = 30_000L
        const val RESOLUTION_TIMEOUT_MS = 90_000L
    }
}

package cn.weibo.filedownloader

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Resolves public Douyin share pages without making the Weibo client depend on
 * Douyin-specific behavior. The page is first allowed to render in a private
 * WebView; a small HTTP fallback handles older share pages that expose a
 * direct media URL in their HTML.
 */
class DouyinClient(private val activity: Activity) {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var webView: WebView? = null
    private var finished = AtomicBoolean(false)
    @Volatile private var capturedMediaUrl = ""

    fun loadPost(sharedUrl: String, quality: QualityPreference, callback: (Result<WeiboPost>) -> Unit) {
        val sourceUrl = normalizeInput(sharedUrl)
        if (sourceUrl.isBlank()) {
            callback(Result.failure(DouyinException(activity.getString(R.string.douyin_url_not_found))))
            return
        }
        finished = AtomicBoolean(false)
        capturedMediaUrl = ""
        val id = extractId(sourceUrl)
        loadWithWebView(sourceUrl, id, quality) { webResult ->
            if (finished.getAndSet(true)) return@loadWithWebView
            removeWebView()
            if (webResult.isSuccess) {
                callback(webResult)
            } else {
                executor.execute {
                    val fallback = runCatching { loadFromHtml(sourceUrl, quality) }
                    handler.post {
                        if (fallback.isSuccess) callback(fallback)
                        else callback(Result.failure(webResult.exceptionOrNull() ?: fallback.exceptionOrNull() ?: DouyinException(activity.getString(R.string.douyin_no_media))))
                    }
                }
            }
        }
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
                settings.userAgentString = MOBILE_USER_AGENT
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        captureMediaUrl(request.url.toString())
                        return super.shouldInterceptRequest(view, request)
                    }

                    @Suppress("DEPRECATION")
                    override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? {
                        captureMediaUrl(url)
                        return super.shouldInterceptRequest(view, url)
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        pollPage(view, sourceUrl, postId, quality, 0, callback)
                    }
                }
                webChromeClient = WebChromeClient()
            }
            webView = view
            val params = FrameLayout.LayoutParams(2, 2)
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
        view.evaluateJavascript(EXTRACT_SCRIPT) { raw ->
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
        val value = runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull() ?: return null
        val data = runCatching { JSONObject(value) }.getOrNull() ?: return null
        val videoUrl = listOf(
            data.optString("video"),
            data.optString("resource"),
            capturedMediaUrl,
        ).firstOrNull { it.startsWith("http", true) && !it.startsWith("blob:", true) }
            ?: return null
        val title = data.optString("title").ifBlank { activity.getString(R.string.douyin_default_title) }
        val author = data.optString("author").ifBlank { activity.getString(R.string.douyin_default_author) }
        val thumbnail = data.optString("poster")
        return WeiboPost(
            id = postId.ifBlank { "douyin" },
            text = title,
            author = author,
            sourceUrl = sourceUrl,
            media = listOf(MediaItem(MediaType.VIDEO, videoUrl, qualityKey(quality), postId.ifBlank { "douyin" }, 1, thumbnail)),
        )
    }

    private fun loadFromHtml(sourceUrl: String, quality: QualityPreference): WeiboPost {
        val connection = (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", MOBILE_USER_AGENT)
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
        val cleaned = decodeEscapedHtml(body)
        val candidates = Regex("https?://[^\\\"'<>\\s]+", RegexOption.IGNORE_CASE)
            .findAll(cleaned)
            .map { it.value.replace("\\u0026", "&") }
            .filter { it.contains(".mp4", true) || it.contains("video", true) }
            .map { it.trimEnd('\\', '"', '\'') }
            .toList()
        val mediaUrl = candidates.firstOrNull() ?: throw DouyinException(activity.getString(R.string.douyin_no_media))
        val id = extractId(connection.url?.toString().orEmpty()).ifBlank { extractId(sourceUrl) }
        val title = Regex("<meta[^>]+name=[\\\"']description[\\\"'][^>]+content=[\\\"']([^\\\"']+)", RegexOption.IGNORE_CASE)
            .find(cleaned)?.groupValues?.getOrNull(1)?.trim()
            .orEmpty()
            .ifBlank { activity.getString(R.string.douyin_default_title) }
        return WeiboPost(
            id = id.ifBlank { "douyin" },
            text = title,
            author = activity.getString(R.string.douyin_default_author),
            sourceUrl = sourceUrl,
            media = listOf(MediaItem(MediaType.VIDEO, mediaUrl, qualityKey(quality), id.ifBlank { "douyin" }, 1)),
        )
    }

    private fun removeWebView() {
        webView?.let { view ->
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.stopLoading()
            view.destroy()
        }
        webView = null
    }

    private fun captureMediaUrl(url: String) {
        if (url.startsWith("http", true) && (
                url.contains(".mp4", true) ||
                    url.contains(".m3u8", true) ||
                    url.contains("playwm", true) ||
                    url.contains("/video/tos/", true)
            )) {
            capturedMediaUrl = url
        }
    }

    private fun normalizeInput(value: String): String = value.trim()
        .replace("\\u002F", "/")
        .replace("\\/", "/")
        .trimEnd('.', ',', '!', '?', '，', '。', '！', '？', '）', ')', '；', ';')

    private fun toSharePage(url: String, id: String): String = when {
        url.contains("iesdouyin.com", true) -> url
        id.isNotBlank() -> "https://www.iesdouyin.com/share/video/$id"
        else -> url
    }

    private fun decodeEscapedHtml(value: String): String = runCatching {
        URLDecoder.decode(value.replace("\\u002F", "/").replace("\\/", "/"), "UTF-8")
    }.getOrDefault(value).replace("&amp;", "&")

    private fun extractId(value: String): String = Regex("(?:/video/|/share/video/|/note/)(\\d+)").find(value)?.groupValues?.getOrNull(1).orEmpty()

    private fun qualityKey(quality: QualityPreference): String = when (quality) {
        QualityPreference.HIGHEST -> MediaQuality.HD_BEST
        QualityPreference.HIGH -> MediaQuality.HIGH
        QualityPreference.STANDARD -> MediaQuality.STANDARD
    }

    class DouyinException(message: String) : RuntimeException(message)

    private companion object {
        const val MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36"
        const val PAGE_POLL_DELAY_MS = 450L
        const val MAX_PAGE_POLLS = 18
        val EXTRACT_SCRIPT = """
            (function() {
              const video = document.querySelector('video');
              const resources = performance.getEntriesByType('resource').map(e => e.name || '').filter(u => /\.mp4|video/i.test(u));
              const title = document.querySelector("meta[name='description']")?.content || document.title || '';
              const author = document.querySelector("[class*='author']")?.textContent || '';
              return JSON.stringify({video: video?.currentSrc || video?.src || '', resource: resources[0] || '', poster: video?.poster || '', title, author});
            })()
        """.trimIndent()
    }
}

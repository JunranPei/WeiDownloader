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
    private val linkResolver = DouyinLinkResolver()
    private val detailProviders: List<DouyinDetailProvider> = listOf(
        DouyinWebDetailProvider("6383"),
        DouyinWebDetailProvider("1128"),
    )
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
            val targetResult = runCatching { linkResolver.resolve(sourceUrl) }
            val apiResult = targetResult.mapCatching(::loadFromDetailProviders)
            handler.post {
                if (apiResult.isSuccess) {
                    if (!finished.getAndSet(true)) callback(apiResult)
                    return@post
                }
                if (finished.get()) return@post
                val target = targetResult.getOrNull()
                if (target == null) {
                    if (!finished.getAndSet(true)) {
                        callback(Result.failure(DouyinException(activity.getString(R.string.douyin_url_not_found))))
                    }
                    return@post
                }
                loadWithWebView(target, quality) { webResult ->
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
                            val fallback = runCatching { hydrateMediaMetadata(loadFromHtml(target, quality)) }
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
                    text = work.title.ifBlank { activity.getString(R.string.douyin_default_title) },
                    author = work.author.ifBlank { activity.getString(R.string.douyin_default_author) },
                    sourceUrl = target.sourceUrl,
                    media = work.media,
                    creator = work.creator,
                )
            }
        }
        throw lastFailure ?: DouyinException(activity.getString(R.string.douyin_no_media))
    }

    fun close() {
        finished.set(true)
        handler.post { removeWebView() }
        executor.shutdownNow()
    }

    private fun loadWithWebView(
        target: DouyinResolvedLink,
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
                        pollPage(
                            view,
                            target.sourceUrl,
                            DouyinLinkParser.extractPostId(url).ifBlank { target.primaryPostId },
                            quality,
                            0,
                            callback,
                        )
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
            view.loadUrl(target.canonicalUrl)
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
        throw lastFailure ?: DouyinException(activity.getString(R.string.douyin_no_media))
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
        const val DOWNLOAD_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36"
        val JSON_LD_REGEX = Regex("<script[^>]+type=[\\\"']application/ld\\+json[\\\"'][^>]*>(.*?)</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        const val PAGE_POLL_DELAY_MS = 450L
        const val MAX_PAGE_POLLS = 18
    }
}

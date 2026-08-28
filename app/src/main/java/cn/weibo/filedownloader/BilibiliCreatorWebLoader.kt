package cn.weibo.filedownloader

import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Uses Bilibili's own signed-in space page when direct dynamic requests are rejected. */
internal class BilibiliCreatorWebLoader(
    private val activity: Activity,
    private val creator: CreatorProfile,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var closed = false
    private var initialPollStarted = false
    private var lastCount = 0

    fun loadInitial(callback: (Result<BilibiliCreatorPage>) -> Unit) {
        closed = false
        initialPollStarted = false
        lastCount = 0
        val root = activity.findViewById<ViewGroup>(R.id.creatorRoot)
        val view = WebView(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            alpha = 0.01f
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.mediaPlaybackRequiresUserGesture = true
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageCommitVisible(view: WebView, url: String) = startPolling(view, callback)
                override fun onPageFinished(view: WebView, url: String) = startPolling(view, callback)

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    handler.post {
                        if (!closed) {
                            callback(Result.failure(IllegalStateException("Bilibili creator page stopped unexpectedly")))
                            close()
                        }
                    }
                    return true
                }
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                view,
                captureScript(),
                setOf("https://space.bilibili.com"),
            )
        }
        webView = view
        root.addView(
            view,
            0,
            ViewGroup.LayoutParams(
                maxOf(activity.resources.displayMetrics.widthPixels, 1280),
                activity.resources.displayMetrics.heightPixels,
            ),
        )
        view.loadUrl(creator.profileUrl)
        handler.postDelayed({ startPolling(view, callback) }, PAGE_FALLBACK_MS)
    }

    fun loadMore(callback: (Result<BilibiliCreatorPage>) -> Unit) {
        val view = webView
        if (closed || view == null) {
            callback(Result.failure(IllegalStateException("Bilibili creator page is not open")))
            return
        }
        val targetCount = lastCount + 1
        view.evaluateJavascript(SCROLL_SCRIPT) {
            handler.postDelayed({ poll(view, targetCount, 0, callback) }, LOAD_MORE_SETTLE_MS)
        }
    }

    fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        webView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.stopLoading()
            view.destroy()
        }
        webView = null
    }

    private fun startPolling(view: WebView, callback: (Result<BilibiliCreatorPage>) -> Unit) {
        if (closed || initialPollStarted) return
        initialPollStarted = true
        view.evaluateJavascript(bootstrapScript()) {
            poll(view, minimumCount = 1, attempt = 0, callback = callback)
        }
    }

    private fun poll(
        view: WebView,
        minimumCount: Int,
        attempt: Int,
        callback: (Result<BilibiliCreatorPage>) -> Unit,
    ) {
        if (closed) return
        view.evaluateJavascript(EXTRACT_SCRIPT) { raw ->
            val page = parsePage(raw)
            if (page != null && page.works.size >= minimumCount) {
                lastCount = page.works.size
                callback(Result.success(page))
            } else if (attempt < MAX_POLLS) {
                if (attempt > 0 && attempt % 3 == 0) view.evaluateJavascript(SCROLL_SCRIPT, null)
                handler.postDelayed(
                    { poll(view, minimumCount, attempt + 1, callback) },
                    POLL_DELAY_MS,
                )
            } else if (page != null && page.works.isNotEmpty()) {
                lastCount = page.works.size
                callback(Result.success(page.copy(hasMore = false)))
            } else {
                callback(Result.failure(IllegalStateException("Bilibili creator page exposed no media dynamics")))
            }
        }
    }

    private fun parsePage(raw: String): BilibiliCreatorPage? {
        val encoded = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: return null
        val data = runCatching { JSONObject(encoded) }.getOrNull() ?: return null
        val values = data.optJSONArray("works") ?: JSONArray()
        val works = buildList {
            for (index in 0 until values.length()) {
                val item = values.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val sourceUrl = item.optString("sourceUrl")
                if (id.isBlank() || sourceUrl.isBlank()) continue
                add(
                    DouyinCreatorWork(
                        id = id,
                        title = item.optString("title").replace(Regex("\\s+"), " ").trim(),
                        coverUrl = item.optString("coverUrl").toHttps(),
                        sourceUrl = sourceUrl.toHttps(),
                        mediaCount = item.optInt("mediaCount", 1).coerceAtLeast(1),
                        isGallery = item.optBoolean("isGallery"),
                    ),
                )
            }
        }.distinctBy { it.id }
        val pageCreator = CreatorProfile(
            platform = Platform.BILIBILI,
            id = creator.id,
            name = data.optString("creatorName").ifBlank { creator.name },
            avatarUrl = data.optString("creatorAvatar").toHttps().ifBlank { creator.avatarUrl },
            profileUrl = creator.profileUrl,
        )
        return BilibiliCreatorPage(
            creator = pageCreator,
            works = works,
            nextOffset = data.optString("offset"),
            hasMore = data.optBoolean("hasMore", works.isNotEmpty()),
        )
    }

    private fun captureScript(): String = """
        (() => {
          if (window.__weiBiliCaptureInstalled) return;
          window.__weiBiliCaptureInstalled = true;
          window.__weiBiliWorks = [];
          window.__weiBiliIds = new Set();
          window.__weiBiliHasMore = true;
          window.__weiBiliOffset = '';
          window.__weiBiliError = '';
          const expected = ${JSONObject.quote(creator.id)};
          const https = value => !value ? '' : value.startsWith('//') ? 'https:' + value : value.startsWith('/') ? 'https://www.bilibili.com' + value : value.replace(/^http:/, 'https:');
          const add = work => {
            if (!work?.id || !work?.sourceUrl || window.__weiBiliIds.has(work.id)) return;
            window.__weiBiliIds.add(work.id);
            window.__weiBiliWorks.push(work);
          };
          window.__weiCaptureBiliPage = payload => {
            if (!payload || Number(payload.code) !== 0 || !payload.data) {
              if (payload?.message) window.__weiBiliError = String(payload.message);
              return;
            }
            window.__weiBiliHasMore = !!payload.data.has_more;
            window.__weiBiliOffset = String(payload.data.offset || '');
            for (const item of (payload.data.items || [])) {
              const modules = item?.modules || {};
              const author = modules.module_author || {};
              if (String(author.mid || '') !== expected || item.type === 'DYNAMIC_TYPE_FORWARD') continue;
              window.__weiBiliCreatorName = author.name || window.__weiBiliCreatorName || '';
              window.__weiBiliCreatorAvatar = https(author.face || window.__weiBiliCreatorAvatar || '');
              const dynamic = modules.module_dynamic || {};
              const major = dynamic.major || {};
              if (major.type === 'MAJOR_TYPE_ARCHIVE' && major.archive?.bvid) {
                const value = major.archive;
                add({id: value.bvid, title: value.title || value.desc || '', coverUrl: https(value.cover || ''), sourceUrl: 'https://www.bilibili.com/video/' + value.bvid, mediaCount: 1, isGallery: false});
              } else if (major.type === 'MAJOR_TYPE_OPUS' && Array.isArray(major.opus?.pics) && major.opus.pics.length) {
                const value = major.opus;
                const id = String(value.opus_id || item.id_str || '');
                add({id, title: value.title || value.summary?.text || dynamic.desc?.text || '', coverUrl: https(value.pics[0]?.url || ''), sourceUrl: https(value.jump_url || '') || 'https://www.bilibili.com/opus/' + id, mediaCount: value.pics.length, isGallery: true});
              } else if (major.type === 'MAJOR_TYPE_DRAW' && Array.isArray(major.draw?.items) && major.draw.items.length) {
                const value = major.draw;
                const id = String(item.id_str || '');
                add({id, title: dynamic.desc?.text || '', coverUrl: https(value.items[0]?.src || ''), sourceUrl: 'https://www.bilibili.com/opus/' + id, mediaCount: value.items.length, isGallery: true});
              }
            }
          };
          const originalFetch = window.fetch;
          window.fetch = function(...args) {
            const request = originalFetch.apply(this, args);
            try {
              const url = String(args[0]?.url || args[0] || '');
              if (url.includes('/x/polymer/web-dynamic/v1/feed/space')) {
                request.then(response => response.clone().json()).then(window.__weiCaptureBiliPage).catch(error => { window.__weiBiliError = String(error); });
              }
            } catch (error) { window.__weiBiliError = String(error); }
            return request;
          };
          const originalOpen = XMLHttpRequest.prototype.open;
          XMLHttpRequest.prototype.open = function(method, url, ...rest) {
            this.__weiBiliUrl = String(url || '');
            return originalOpen.call(this, method, url, ...rest);
          };
          const originalSend = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.send = function(...args) {
            if (this.__weiBiliUrl?.includes('/x/polymer/web-dynamic/v1/feed/space')) {
              this.addEventListener('load', () => { try { window.__weiCaptureBiliPage(JSON.parse(this.responseText)); } catch (error) { window.__weiBiliError = String(error); } });
            }
            return originalSend.apply(this, args);
          };
        })();
    """.trimIndent()

    private fun bootstrapScript(): String = """
        (() => {
          if (typeof window.__weiCaptureBiliPage !== 'function') { ${captureScript()} }
          const params = new URLSearchParams({host_mid: ${JSONObject.quote(creator.id)}, platform: 'web', timezone_offset: '-480', features: 'itemOpusStyle,listOnlyfans,opusBigCover,onlyfansVote'});
          fetch('https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/space?' + params, {credentials: 'include'})
            .then(response => response.json())
            .then(window.__weiCaptureBiliPage)
            .catch(error => { window.__weiBiliError = String(error); });
          window.scrollTo(0, document.documentElement.scrollHeight);
        })();
    """.trimIndent()

    private fun String.toHttps(): String = when {
        startsWith("//") -> "https:$this"
        startsWith("/") -> "https://www.bilibili.com$this"
        startsWith("http://") -> "https://${removePrefix("http://")}" 
        else -> this
    }

    private companion object {
        const val PAGE_FALLBACK_MS = 6_000L
        const val LOAD_MORE_SETTLE_MS = 800L
        const val POLL_DELAY_MS = 500L
        const val MAX_POLLS = 30
        const val EXTRACT_SCRIPT = """
            (() => {
              const works = window.__weiBiliWorks || [];
              const ids = new Set(works.map(item => item.id));
              if (!works.length) {
                document.querySelectorAll("a[href*='/video/BV'],a[href*='/opus/']").forEach(anchor => {
                  const href = String(anchor.href || '');
                  const video = href.match(/\/video\/(BV[0-9A-Za-z]{10})/i);
                  const opus = href.match(/\/opus\/(\d{8,20})/);
                  const id = video?.[1] || opus?.[1] || '';
                  if (!id || ids.has(id)) return;
                  ids.add(id);
                  const image = anchor.querySelector('img');
                  works.push({
                    id,
                    title: image?.alt || anchor.getAttribute('title') || anchor.textContent || '',
                    coverUrl: image?.currentSrc || image?.src || '',
                    sourceUrl: video ? 'https://www.bilibili.com/video/' + id : 'https://www.bilibili.com/opus/' + id,
                    mediaCount: 1,
                    isGallery: !video
                  });
                });
              }
              return JSON.stringify({
              works,
              creatorName: window.__weiBiliCreatorName || '',
              creatorAvatar: window.__weiBiliCreatorAvatar || '',
              offset: window.__weiBiliOffset || '',
              hasMore: window.__weiBiliHasMore !== false,
              error: window.__weiBiliError || ''
              });
            })();
        """
        const val SCROLL_SCRIPT = """
            (() => {
              const target = Math.max(document.body?.scrollHeight || 0, document.documentElement?.scrollHeight || 0);
              window.scrollTo(0, target);
              document.querySelectorAll('*').forEach(node => {
                if (node.scrollHeight > node.clientHeight * 2) node.scrollTop = node.scrollHeight;
              });
            })();
        """
    }
}

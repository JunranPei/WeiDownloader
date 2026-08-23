package cn.weibo.filedownloader

import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONTokener
import org.json.JSONObject

internal class DouyinCreatorWebLoader(
    private val activity: Activity,
    private val creator: CreatorProfile,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var lastCount = 0
    private var closed = false
    private var lastDiagnostic = ""
    private var lastApiHasMore: Boolean? = null
    private var initialPollStarted = false
    private val accumulator = DouyinCreatorWorkAccumulator()

    fun loadInitial(callback: (Result<DouyinCreatorPage>) -> Unit) {
        closed = false
        initialPollStarted = false
        lastCount = 0
        lastApiHasMore = null
        accumulator.reset()
        val root = activity.findViewById<ViewGroup>(R.id.creatorRoot)
        val view = WebView(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            alpha = 0.01f
            visibility = WebView.VISIBLE
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.mediaPlaybackRequiresUserGesture = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.userAgentString = WEB_USER_AGENT
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageCommitVisible(view: WebView, url: String) {
                    startInitialPoll(view, callback)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    startInitialPoll(view, callback)
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        Log.d(LOG_TAG, "Creator page navigation error ${error.errorCode}: ${error.description}")
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse,
                ) {
                    if (request.isForMainFrame) {
                        Log.d(LOG_TAG, "Creator page HTTP ${errorResponse.statusCode}: ${request.url}")
                    }
                }
            }
        }
        val supportsDocumentStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        Log.d(LOG_TAG, "Creator response capture at document start supported=$supportsDocumentStart")
        if (supportsDocumentStart) {
            WebViewCompat.addDocumentStartJavaScript(
                view,
                buildCaptureScript(),
                setOf("https://www.douyin.com"),
            )
        }
        webView = view
        val density = activity.resources.displayMetrics.density
        val width = maxOf(activity.resources.displayMetrics.widthPixels, (1024 * density).toInt())
        val height = activity.resources.displayMetrics.heightPixels
        root.addView(view, 0, ViewGroup.LayoutParams(width, height))
        view.loadUrl(creator.profileUrl)
        handler.postDelayed(
            {
                if (!initialPollStarted) {
                    Log.d(LOG_TAG, "Creator page did not finish; parsing the committed document")
                    view.stopLoading()
                    startInitialPoll(view, callback)
                }
            },
            INITIAL_POLL_FALLBACK_MS,
        )
    }

    private fun startInitialPoll(view: WebView, callback: (Result<DouyinCreatorPage>) -> Unit) {
        if (closed || initialPollStarted) return
        initialPollStarted = true
        view.evaluateJavascript(buildBootstrapFetchScript()) {
            poll(view, minimumCount = 1, attempt = 0, callback = callback)
        }
    }

    fun loadMore(callback: (Result<DouyinCreatorPage>) -> Unit) {
        val view = webView
        if (closed || view == null) {
            callback(Result.failure(IllegalStateException("Creator page is not open")))
            return
        }
        val previousCount = lastCount
        view.evaluateJavascript(SCROLL_SCRIPT) {
            handler.postDelayed(
                { poll(view, minimumCount = previousCount + 1, attempt = 0, callback = callback) },
                LOAD_MORE_SETTLE_MS,
            )
        }
    }

    private fun poll(
        view: WebView,
        minimumCount: Int,
        attempt: Int,
        callback: (Result<DouyinCreatorPage>) -> Unit,
    ) {
        if (closed) return
        view.evaluateJavascript(buildExtractScript()) { raw ->
            val works = parseWorks(raw)
            if (works.size >= minimumCount) {
                lastCount = works.size
                callback(
                    Result.success(
                        DouyinCreatorPage(
                            creator,
                            works,
                            works.size.toLong(),
                            hasMore = lastApiHasMore != false,
                        ),
                    ),
                )
            } else if (attempt < MAX_POLLS) {
                if (attempt > 0 && attempt % 2 == 0) {
                    view.evaluateJavascript(SCROLL_SCRIPT, null)
                }
                handler.postDelayed(
                    { poll(view, minimumCount, attempt + 1, callback) },
                    POLL_DELAY_MS,
                )
            } else if (works.isNotEmpty()) {
                lastCount = works.size
                Log.d(LOG_TAG, "Creator page stopped growing: $lastDiagnostic")
                callback(Result.success(DouyinCreatorPage(creator, works, works.size.toLong(), hasMore = false)))
            } else {
                callback(Result.failure(IllegalStateException("Douyin creator page did not expose public works: $lastDiagnostic")))
            }
        }
    }

    private fun parseWorks(raw: String): List<DouyinCreatorWork> {
        val encoded = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: return emptyList()
        val data = runCatching { JSONObject(encoded) }.getOrNull() ?: return emptyList()
        lastApiHasMore = data.optString("apiHasMore")
            .takeIf(String::isNotBlank)
            ?.toBooleanStrictOrNull()
        lastDiagnostic = listOf(
            data.optString("canonical"),
            data.optString("title"),
            "profile=${data.optBoolean("hasProfile")}",
            "panel=${data.optBoolean("hasPanel")}",
            "scoped=${data.optBoolean("scoped")}",
            "cursor=${data.optString("cursor")}",
            "apiHasMore=${data.optString("apiHasMore")}",
            "fetching=${data.optBoolean("fetching")}",
            "fetchError=${data.optString("fetchError")}",
            "signed=${data.optBoolean("signed")}",
            "postRequests=${data.optInt("postRequests")}",
            "scroll=${data.optString("scroll")}",
        ).joinToString(" | ")
        val values = data.optJSONArray("works") ?: return emptyList()
        val visibleWorks = buildList {
            for (index in 0 until values.length()) {
                val item = values.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val route = item.optString("route")
                val cover = item.optString("cover").takeIf(DouyinContentPolicy::isTrustedImageResource).orEmpty()
                if (id.isBlank() || route !in setOf("video", "note")) continue
                add(
                    DouyinCreatorWork(
                        id = id,
                        title = item.optString("title")
                            .replace("&#10;", " ")
                            .replace("……版本过低，升级后可展示全部信息", "")
                            .replace("版本过低，升级后可展示全部信息", "")
                            .trim(),
                        coverUrl = cover,
                        sourceUrl = "https://www.douyin.com/$route/$id",
                        mediaCount = if (route == "note") 0 else 1,
                        isGallery = route == "note",
                    ),
                )
            }
        }.distinctBy { it.id }
        return accumulator.merge(visibleWorks)
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

    private fun buildExtractScript(): String = """
        (function() {
          const expectedCreator = ${JSONObject.quote(creator.id)};
          const canonical = document.querySelector("link[rel='canonical']")?.href || location.href;
          const profile = document.querySelector('#user_detail_element');
          const postPanel = profile?.querySelector('#semiTabPanelpost');
          if (!canonical.includes(expectedCreator) || !profile || !postPanel) {
            return JSON.stringify({works: [], scoped: false, canonical, title: document.title, hasProfile: !!profile, hasPanel: !!postPanel});
          }
          const works = [];
          const seen = new Set();
          for (const item of (window.__weiCreatorWorks || [])) {
            if (!item?.id || seen.has(item.id)) continue;
            seen.add(item.id);
            works.push(item);
          }
          for (const anchor of profile.querySelectorAll('a[href]')) {
            let url;
            try { url = new URL(anchor.href, location.href); } catch (_) { continue; }
            const match = url.pathname.match(/^\/(video|note)\/(\d{17,20})(?:\/|$)/);
            if (!match || seen.has(match[2])) continue;
            const image = anchor.querySelector('img');
            const cover = image?.currentSrc || image?.src || '';
            const title = image?.alt || anchor.querySelector('p')?.textContent || anchor.textContent || '';
            seen.add(match[2]);
            works.push({route: match[1], id: match[2], cover, title: title.trim()});
          }
          window.__weiCreatorWorks = works.slice();
          const postRequests = performance.getEntriesByType('resource')
            .filter(entry => String(entry.name || '').includes('/aweme/v1/web/aweme/post/'));
          const scrolling = document.scrollingElement;
          return JSON.stringify({
            works,
            scoped: true,
            canonical,
            title: document.title,
            hasProfile: true,
            hasPanel: true,
            cursor: String(window.__weiCreatorCursor ?? ''),
            apiHasMore: String(window.__weiCreatorHasMore ?? ''),
            fetching: !!window.__weiCreatorFetchLoading,
            fetchError: String(window.__weiCreatorFetchError || ''),
            signed: !!window.__weiCreatorLastSigned,
            postRequests: postRequests.length,
            scroll: scrolling ? [scrolling.scrollTop, scrolling.clientHeight, scrolling.scrollHeight].join('/') : ''
          });
        })()
    """.trimIndent()

    private fun buildCaptureScript(): String = """
        (function() {
          const expectedCreator = ${JSONObject.quote(creator.id)};
          window.__weiCreatorWorks = window.__weiCreatorWorks || [];
          window.__weiCreatorHasMore = window.__weiCreatorHasMore ?? true;
          window.__weiCreatorFetchError = '';
          const isPostRequest = value => String(value || '').includes('/aweme/v1/web/aweme/post/');
          const capture = data => {
            if (!data || typeof data !== 'object') return;
            const existing = new Set((window.__weiCreatorWorks || []).map(item => item.id));
            for (const item of (data.aweme_list || [])) {
              const id = String(item?.aweme_id || item?.id || '');
              if (!id || existing.has(id) || String(item?.author?.sec_uid || '') !== expectedCreator) continue;
              const images = item?.image_post_info?.images?.length ? item.image_post_info.images : item?.images;
              const isGallery = Array.isArray(images) && images.length > 0;
              const addresses = isGallery
                ? [images[0]?.url_list, images[0]?.display_image?.url_list]
                : [item?.video?.origin_cover?.url_list, item?.video?.cover?.url_list, item?.video?.dynamic_cover?.url_list];
              const cover = addresses.flatMap(value => Array.isArray(value) ? value : []).find(Boolean) || '';
              existing.add(id);
              window.__weiCreatorWorks.push({
                route: isGallery ? 'note' : 'video',
                id,
                cover,
                title: String(item?.desc || item?.title || '').trim()
              });
            }
            window.__weiCreatorCursor = Number(data?.max_cursor || data?.cursor || 0);
            window.__weiCreatorHasMore = Number(data?.has_more || 0) === 1;
          };
          window.__weiCaptureCreatorPage = capture;
          const captureResponse = response => {
            if (!isPostRequest(response?.url)) return;
            response.clone().json().then(capture).catch(error => {
              window.__weiCreatorFetchError = 'fetch response: ' + String(error?.message || error);
            });
          };
          const originalFetch = window.fetch;
          window.fetch = function() {
            return originalFetch.apply(this, arguments).then(response => {
              captureResponse(response);
              return response;
            });
          };
          const originalOpen = XMLHttpRequest.prototype.open;
          const originalSend = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.open = function(method, url) {
            this.__weiPostRequest = isPostRequest(url);
            return originalOpen.apply(this, arguments);
          };
          XMLHttpRequest.prototype.send = function() {
            if (this.__weiPostRequest) {
              this.addEventListener('load', function() {
                try { capture(JSON.parse(this.responseText)); }
                catch (error) { window.__weiCreatorFetchError = 'xhr response: ' + String(error?.message || error); }
              });
            }
            return originalSend.apply(this, arguments);
          };
          return true;
        })()
    """.trimIndent()

    /** Anonymous public pages sometimes expose the first page without allowing signed pagination. */
    private fun buildBootstrapFetchScript(): String = """
        (function() {
          const expectedCreator = ${JSONObject.quote(creator.id)};
          setTimeout(() => {
            if ((window.__weiCreatorWorks || []).length > 0 || window.__weiCreatorBootstrapLoading) return;
            window.__weiCreatorBootstrapLoading = true;
            const query = new URLSearchParams({
              aid: '6383',
              cookie_enabled: 'true',
              platform: 'PC',
              source: 'Googlebot-sdc',
              sec_user_id: expectedCreator,
              max_cursor: '0',
              count: '18'
            });
            fetch('/aweme/v1/web/aweme/post/?' + query.toString(), {credentials: 'include'})
              .then(response => response.text())
              .then(text => {
                if (!text) throw new Error('empty bootstrap response');
                const data = JSON.parse(text);
                if (Number(data?.status_code) !== 0) throw new Error('bootstrap status ' + data?.status_code);
                if (typeof window.__weiCaptureCreatorPage === 'function') {
                  window.__weiCaptureCreatorPage(data);
                  // Still let the real page attempt signed pagination after the public first page.
                  if ((window.__weiCreatorWorks || []).length > 0) window.__weiCreatorHasMore = true;
                }
              })
              .catch(error => {
                window.__weiCreatorFetchError = 'bootstrap: ' + String(error?.message || error);
              })
              .finally(() => { window.__weiCreatorBootstrapLoading = false; });
          }, 1200);
          return true;
        })()
    """.trimIndent()

    private companion object {
        const val WEB_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        const val POLL_DELAY_MS = 650L
        const val LOAD_MORE_SETTLE_MS = 900L
        const val INITIAL_POLL_FALLBACK_MS = 8_000L
        const val MAX_POLLS = 25
        const val LOG_TAG = "DouyinCreator"
        val SCROLL_SCRIPT = """
            (function() {
              const candidates = [document.scrollingElement, ...document.querySelectorAll('*')];
              for (const node of candidates) {
                if (!node || node.scrollHeight <= node.clientHeight + 80) continue;
                const overflow = getComputedStyle(node).overflowY;
                if (node === document.scrollingElement || overflow === 'auto' || overflow === 'scroll') {
                  const distance = Math.max(1800, node.clientHeight * 1.5);
                  node.scrollTop = Math.min(node.scrollHeight, node.scrollTop + distance);
                  node.dispatchEvent(new Event('scroll', {bubbles: true}));
                  node.dispatchEvent(new WheelEvent('wheel', {deltaY: distance, bubbles: true}));
                }
              }
              for (const button of document.querySelectorAll('button, [role="button"]')) {
                if (/加载更多|查看更多|展开更多/.test(button.textContent || '')) button.click();
              }
              return true;
            })()
        """.trimIndent()
    }
}

/** Keeps works already seen even when Douyin virtualizes older cards out of the page DOM. */
internal class DouyinCreatorWorkAccumulator {
    private val works = linkedMapOf<String, DouyinCreatorWork>()

    fun reset() {
        works.clear()
    }

    fun merge(values: List<DouyinCreatorWork>): List<DouyinCreatorWork> {
        values.forEach { incoming ->
            val previous = works[incoming.id]
            works[incoming.id] = if (previous == null) {
                incoming
            } else {
                previous.copy(
                    title = incoming.title.ifBlank { previous.title },
                    coverUrl = incoming.coverUrl.ifBlank { previous.coverUrl },
                    sourceUrl = incoming.sourceUrl.ifBlank { previous.sourceUrl },
                    mediaCount = maxOf(previous.mediaCount, incoming.mediaCount),
                    isGallery = previous.isGallery || incoming.isGallery,
                )
            }
        }
        return works.values.toList()
    }
}

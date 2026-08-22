package cn.weibo.filedownloader

import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
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
    private var initialPollStarted = false

    fun loadInitial(callback: (Result<DouyinCreatorPage>) -> Unit) {
        closed = false
        initialPollStarted = false
        val root = activity.findViewById<ViewGroup>(R.id.creatorRoot)
        val view = WebView(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            alpha = 0.01f
            visibility = WebView.VISIBLE
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.userAgentString = WEB_USER_AGENT
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (initialPollStarted) return
                    initialPollStarted = true
                    view.evaluateJavascript(buildFetchScript(reset = true)) {
                        poll(view, minimumCount = 1, attempt = 0, callback = callback)
                    }
                }
            }
        }
        webView = view
        val density = activity.resources.displayMetrics.density
        val width = maxOf(activity.resources.displayMetrics.widthPixels, (1024 * density).toInt())
        val height = activity.resources.displayMetrics.heightPixels
        root.addView(view, 0, ViewGroup.LayoutParams(width, height))
        view.loadUrl(creator.profileUrl)
    }

    fun loadMore(callback: (Result<DouyinCreatorPage>) -> Unit) {
        val view = webView
        if (closed || view == null) {
            callback(Result.failure(IllegalStateException("Creator page is not open")))
            return
        }
        val previousCount = lastCount
        view.evaluateJavascript(buildFetchScript(reset = false)) {
            view.evaluateJavascript(SCROLL_SCRIPT) {
                handler.postDelayed(
                    { poll(view, minimumCount = previousCount + 1, attempt = 0, callback = callback) },
                    LOAD_MORE_SETTLE_MS,
                )
            }
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
                callback(Result.success(DouyinCreatorPage(creator, works, works.size.toLong(), hasMore = true)))
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
                callback(Result.success(DouyinCreatorPage(creator, works, works.size.toLong(), hasMore = false)))
            } else {
                callback(Result.failure(IllegalStateException("Douyin creator page did not expose public works: $lastDiagnostic")))
            }
        }
    }

    private fun parseWorks(raw: String): List<DouyinCreatorWork> {
        val encoded = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: return emptyList()
        val data = runCatching { JSONObject(encoded) }.getOrNull() ?: return emptyList()
        lastDiagnostic = listOf(
            data.optString("canonical"),
            data.optString("title"),
            "profile=${data.optBoolean("hasProfile")}",
            "panel=${data.optBoolean("hasPanel")}",
            "scoped=${data.optBoolean("scoped")}",
        ).joinToString(" | ")
        val values = data.optJSONArray("works") ?: return emptyList()
        return buildList {
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
          return JSON.stringify({works, scoped: true, canonical, title: document.title, hasProfile: true, hasPanel: true});
        })()
    """.trimIndent()

    private fun buildFetchScript(reset: Boolean): String = """
        (function() {
          const expectedCreator = ${JSONObject.quote(creator.id)};
          if (${if (reset) "true" else "false"}) {
            window.__weiCreatorWorks = [];
            window.__weiCreatorCursor = 0;
            window.__weiCreatorHasMore = true;
          }
          if (window.__weiCreatorFetchLoading || window.__weiCreatorHasMore === false) return false;
          window.__weiCreatorFetchLoading = true;
          const query = new URLSearchParams({
            aid: '6383',
            cookie_enabled: 'true',
            platform: 'PC',
            source: 'Googlebot-sdc',
            sec_user_id: expectedCreator,
            max_cursor: String(window.__weiCreatorCursor || 0),
            count: '12'
          });
          fetch('/aweme/v1/web/aweme/post/?' + query.toString(), {credentials: 'include'})
            .then(response => response.text())
            .then(text => {
              if (!text) throw new Error('empty response');
              const data = JSON.parse(text);
              if (Number(data?.status_code) !== 0) throw new Error('status ' + data?.status_code);
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
              window.__weiCreatorCursor = Number(data?.max_cursor || 0);
              window.__weiCreatorHasMore = Number(data?.has_more || 0) === 1;
            })
            .catch(() => {})
            .finally(() => { window.__weiCreatorFetchLoading = false; });
          return true;
        })()
    """.trimIndent()

    private companion object {
        const val WEB_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        const val POLL_DELAY_MS = 650L
        const val LOAD_MORE_SETTLE_MS = 900L
        const val MAX_POLLS = 25
        val SCROLL_SCRIPT = """
            (function() {
              window.scrollTo(0, document.body.scrollHeight);
              const candidates = [document.scrollingElement, ...document.querySelectorAll('*')];
              for (const node of candidates) {
                if (!node || node.scrollHeight <= node.clientHeight + 80) continue;
                const overflow = getComputedStyle(node).overflowY;
                if (node === document.scrollingElement || overflow === 'auto' || overflow === 'scroll') {
                  node.scrollTop = node.scrollHeight;
                  node.dispatchEvent(new Event('scroll', {bubbles: true}));
                }
              }
              return true;
            })()
        """.trimIndent()
    }
}

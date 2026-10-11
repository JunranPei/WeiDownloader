package cn.weibo.filedownloader

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder

internal class XiaohongshuCreatorLoginRequiredException(message: String) : RuntimeException(message)
internal class XiaohongshuCreatorUnavailableException(message: String) : RuntimeException(message)

/** Keeps the authenticated website open and observes its own paginated requests. */
internal class XiaohongshuCreatorWebLoader(
    private val activity: Activity,
    private val creator: CreatorProfile,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var closed = false
    private var profileNavigationStarted = false
    private var activeProfileUrl = creator.profileUrl
    private val works = linkedMapOf<String, DouyinCreatorWork>()
    private val seenResponses = hashSetOf<String>()
    private var callback: ((Result<DouyinCreatorPage>) -> Unit)? = null
    private var generation = 0
    private var baselineCount = 0
    private var canLoadMore = true
    private var startedAt = 0L

    fun load(callback: (Result<DouyinCreatorPage>) -> Unit) {
        val root = activity.findViewById<ViewGroup>(R.id.creatorRoot)
        val view = WebView(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            alpha = 0.01f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.userAgentString = DESKTOP_USER_AGENT
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        webView = view
        root.addView(view, 0, ViewGroup.LayoutParams(
            activity.resources.displayMetrics.widthPixels, activity.resources.displayMetrics.heightPixels))
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(view, NETWORK_OBSERVER_SCRIPT,
                setOf("https://www.xiaohongshu.com"))
        }
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (closed || this@XiaohongshuCreatorWebLoader.callback == null) return
                val uri = Uri.parse(url)
                Log.i(TAG, "page path=${uri.path} serverVerified=${XiaohongshuSession.isSignedIn()}")
                if (isLoginPage(uri)) {
                    loginRequired()
                    return
                }
                if (!profileNavigationStarted) {
                    profileNavigationStarted = true
                    resolveProfileUrl(view, generation)
                }
            }
        }
        Log.i(TAG, "load creator=${creator.id} observer=v2 desktop=true serverVerified=${XiaohongshuSession.isSignedIn()}")
        begin(callback)
        profileNavigationStarted = creator.seedPostUrl.isBlank()
        view.loadUrl(creator.seedPostUrl.ifBlank { activeProfileUrl })
    }

    fun loadMore(callback: (Result<DouyinCreatorPage>) -> Unit) {
        if (closed || webView == null) {
            callback(Result.failure(IllegalStateException("Xiaohongshu profile is closed")))
            return
        }
        if (this.callback != null) return
        if (!canLoadMore) {
            callback(Result.success(DouyinCreatorPage(creator, works.values.toList(), works.size.toLong(), false)))
            return
        }
        begin(callback)
    }

    private fun begin(callback: (Result<DouyinCreatorPage>) -> Unit) {
        this.callback = callback
        baselineCount = works.size
        startedAt = SystemClock.elapsedRealtime()
        val run = ++generation
        handler.postDelayed({ poll(run, 0) }, 600)
        handler.postDelayed({
            if (isActive(run)) finish(Result.failure(XiaohongshuCreatorUnavailableException(
                "Xiaohongshu pagination timed out; loaded=${works.size} before=$baselineCount")))
        }, 55_000)
    }

    private fun isActive(run: Int) = !closed && callback != null && generation == run

    private fun isLoginPage(uri: Uri): Boolean = uri.host == "www.xiaohongshu.com" &&
        (uri.path == "/login" || uri.path.orEmpty().startsWith("/website-login/"))

    private fun loginRequired() {
        XiaohongshuSession.invalidate()
        finish(Result.failure(XiaohongshuCreatorLoginRequiredException("Website requires sign-in")))
    }

    private fun resolveProfileUrl(view: WebView, run: Int, attempt: Int = 0) {
        if (!isActive(run)) return
        view.evaluateJavascript(PROFILE_LINK_SCRIPT) { raw ->
            if (!isActive(run)) return@evaluateJavascript
            val resolved = decode(raw)
            val uri = runCatching { Uri.parse(resolved) }.getOrNull()
            val valid = uri?.host == "www.xiaohongshu.com" && uri.path == "/user/profile/${creator.id}"
            if (valid || attempt >= 8) {
                if (valid) activeProfileUrl = resolved
                view.loadUrl(activeProfileUrl.ifBlank { "https://www.xiaohongshu.com/user/profile/${creator.id}" })
            } else handler.postDelayed({ resolveProfileUrl(view, run, attempt + 1) }, 600)
        }
    }

    private fun poll(run: Int, attempt: Int) {
        if (!isActive(run)) return
        val view = webView ?: return
        val uri = Uri.parse(view.url.orEmpty())
        if (isLoginPage(uri)) { loginRequired(); return }
        if (uri.host != "www.xiaohongshu.com" || uri.path != "/user/profile/${creator.id}") {
            handler.postDelayed({ poll(run, attempt + 1) }, 800)
            return
        }
        // Fallback for older WebViews; modern versions install this before site JS.
        view.evaluateJavascript(NETWORK_OBSERVER_SCRIPT, null)
        view.evaluateJavascript(PROFILE_STATUS_SCRIPT) { raw ->
            if (!isActive(run)) return@evaluateJavascript
            val status = runCatching { JSONObject(decode(raw)) }.getOrDefault(JSONObject())
            XiaohongshuSession.recordEvidence(view.url.orEmpty(), status)
            val responses = status.optJSONArray("responses") ?: JSONArray()
            for (i in 0 until responses.length()) {
                val response = responses.optJSONObject(i) ?: continue
                if (response.optString("userId") != creator.id) continue
                val http = response.optInt("status")
                val code = response.optInt("code")
                Log.i(TAG, "user_posted status=$http code=$code requestedCursor=${response.optString("requestedCursor")} notes=${response.optJSONArray("notes")?.length() ?: 0} hasMore=${response.opt("hasMore")}")
                if (code == -101 || http == 401) { loginRequired(); return@evaluateJavascript }
                if (http !in 200..299 || response.opt("success") == false || code != 0) {
                    finish(Result.failure(XiaohongshuCreatorUnavailableException("user_posted HTTP $http code=$code")))
                    return@evaluateJavascript
                }
                val identity = response.optString("requestedCursor") + ":" + response.optString("cursor")
                if (seenResponses.add(identity)) {
                    parseWorks(response.optJSONArray("notes")?.toString().orEmpty()).forEach { works[it.id] = it }
                    if (response.opt("hasMore") is Boolean) canLoadMore = response.optBoolean("hasMore")
                }
            }
            if (status.optString("queryUserId") == creator.id && status.opt("hasMore") is Boolean) {
                canLoadMore = status.optBoolean("hasMore")
            }
            view.evaluateJavascript(EXTRACT_WORKS_SCRIPT) { items ->
                if (!isActive(run)) return@evaluateJavascript
                parseWorks(decode(items)).forEach { works[it.id] = it }
                val busy = status.optBoolean("busy")
                if (attempt % 5 == 0) Log.i(TAG, "poll=$attempt total=${works.size} before=$baselineCount busy=$busy hasMore=$canLoadMore")
                when {
                    !busy && (works.size > baselineCount || (!canLoadMore && works.isNotEmpty())) -> {
                        finish(Result.success(DouyinCreatorPage(creator, works.values.toList(), works.size.toLong(), canLoadMore)))
                    }
                    else -> {
                        if (attempt % 3 == 0 && !busy) view.evaluateJavascript(SCROLL_PROFILE_SCRIPT, null)
                        handler.postDelayed({ poll(run, attempt + 1) }, 850)
                    }
                }
            }
        }
    }

    private fun decode(raw: String?): String =
        runCatching { JSONTokener(raw.orEmpty()).nextValue() as? String }.getOrNull().orEmpty()

    private fun isUsableProfileNote(id: String, token: String): Boolean =
        id.matches(Regex("^[0-9a-fA-F]{24}$")) ||
            (id.matches(Regex("^[0-9a-fA-F]{32}$")) && token.isNotBlank())

    private fun parseWorks(encoded: String): List<DouyinCreatorWork> {
        val values = runCatching { JSONArray(encoded) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until values.length()) {
                val value = values.optJSONObject(index) ?: continue
                val id = value.optString("id")
                val title = value.optString("title").trim()
                val cover = value.optString("cover")
                val token = value.optString("token")
                if (!isUsableProfileNote(id, token) || title.isBlank() || cover.isBlank()) continue
                val source = buildString {
                    append("https://www.xiaohongshu.com/explore/").append(id)
                    var hasQuery = false
                    if (token.isNotBlank()) {
                        append("?xsec_token=").append(URLEncoder.encode(token, "UTF-8"))
                        append("&xsec_source=pc_user")
                        hasQuery = true
                    }
                    if (activeProfileUrl.isNotBlank()) {
                        append(if (hasQuery) '&' else '?')
                        append("xhs_profile=")
                        append(URLEncoder.encode(activeProfileUrl, "UTF-8"))
                    }
                }
                add(DouyinCreatorWork(id, title, cover, source, 1, value.optBoolean("video", false)))
            }
        }.distinctBy { it.id }
    }


    private fun finish(result: Result<DouyinCreatorPage>) {
        val receiver = callback ?: return
        callback = null
        handler.removeCallbacksAndMessages(null)
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        result.onSuccess { Log.i(TAG, "finish works=${it.works.size} added=${it.works.size - baselineCount} hasMore=${it.hasMore} elapsedMs=$elapsed") }
            .onFailure { Log.w(TAG, "finish ${it.javaClass.simpleName}: ${it.message} elapsedMs=$elapsed") }
        receiver(result)
    }

    fun close() {
        closed = true
        callback = null
        handler.removeCallbacksAndMessages(null)
        webView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.stopLoading()
            view.destroy()
        }
        webView = null
    }

    companion object {
        private const val TAG = "XhsWebLoader"
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private val NETWORK_OBSERVER_SCRIPT = """
            (function() {
              if (window.__weiXhsObserver) return;
              window.__weiXhsObserver = true;
              window.__weiXhsResponses = [];
              const normalize = value => {
                const c = value.note_card || value.noteCard || value;
                const cover = c.cover || {};
                const info = cover.info_list || cover.infoList || [];
                return {id: String(value.note_id || value.noteId || value.id || c.note_id || c.noteId || c.id || ''),
                  title: String(c.display_title || c.displayTitle || c.title || c.desc || '').trim(),
                  cover: typeof cover === 'string' ? cover : (cover.url_default || cover.urlDefault || cover.url_pre || cover.urlPre || cover.url || (info[0] || {}).url || ''),
                  token: String(value.xsec_token || value.xsecToken || c.xsec_token || c.xsecToken || ''),
                  video: (c.type || value.type) === 'video'};
              };
              const record = (url, status, body) => {
                try {
                  const u = new URL(url, location.href);
                  if (u.hostname !== 'edith.xiaohongshu.com' || u.pathname !== '/api/sns/web/v1/user_posted') return;
                  const json = JSON.parse(body), data = json.data || {};
                  window.__weiXhsResponses.push({status, code: json.code, success: json.success,
                    userId: u.searchParams.get('user_id') || '', requestedCursor: u.searchParams.get('cursor') || '',
                    cursor: data.cursor || '', hasMore: typeof data.has_more === 'boolean' ? data.has_more : null,
                    notes: Array.isArray(data.notes) ? data.notes.flat().map(normalize) : []});
                } catch (_) {}
              };
              const fetchOriginal = window.fetch;
              window.fetch = function() {
                return fetchOriginal.apply(this, arguments).then(response => {
                  if (response.url.includes('/api/sns/web/v1/user_posted')) response.clone().text()
                    .then(body => record(response.url, response.status, body)).catch(() => {});
                  return response;
                });
              };
              const openOriginal = XMLHttpRequest.prototype.open;
              XMLHttpRequest.prototype.open = function(method, url) {
                this.__weiXhsUrl = String(url);
                return openOriginal.apply(this, arguments);
              };
              const sendOriginal = XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.send = function() {
                this.addEventListener('load', function() {
                  try { record(this.responseURL || this.__weiXhsUrl, this.status,
                    this.responseType === 'json' ? JSON.stringify(this.response) : this.responseText); } catch (_) {}
                }, {once: true});
                return sendOriginal.apply(this, arguments);
              };
            })()
        """.trimIndent()

        private val PROFILE_STATUS_SCRIPT = """
            (function() {
              const unwrap = v => v && typeof v === 'object' ? (v.value !== undefined ? v.value : (v._value !== undefined ? v._value : v)) : v;
              const user = (window.__INITIAL_STATE__ || {}).user || {};
              const info = unwrap(user.userInfo) || {};
              const query = (unwrap(user.noteQueries) || [])[0] || {};
              const busy = (unwrap(user.isFetchingNotes) || [])[0] === true;
              const loggedIn = unwrap(user.loggedIn);
              return JSON.stringify({loggedIn: typeof loggedIn === 'boolean' ? loggedIn : null,
                guest: typeof info.guest === 'boolean' ? info.guest : null, userIdPresent: !!(info.userId || info.user_id),
                queryUserId: query.userId || '', cursor: query.cursor || '', busy,
                hasMore: typeof query.hasMore === 'boolean' ? query.hasMore : null,
                responses: (window.__weiXhsResponses || []).splice(0)});
            })()
        """.trimIndent()

        private val SCROLL_PROFILE_SCRIPT = """
            (function() {
              const roots = [document.scrollingElement, ...document.querySelectorAll('.scroll-container, .main-content, [class*="scroll-view"]')];
              for (const root of new Set(roots.filter(Boolean))) {
                if (root.scrollHeight <= root.clientHeight) continue;
                root.scrollTop = Math.max(0, root.scrollHeight - root.clientHeight - 4);
                root.dispatchEvent(new Event('scroll', {bubbles: true}));
              }
              window.dispatchEvent(new Event('scroll'));
            })()
        """.trimIndent()

        private val PROFILE_LINK_SCRIPT = """
            (function() {
              const links = Array.from(document.querySelectorAll('a[href*="/user/profile/"]'));
              const exact = links.find(a => (a.href || '').includes('xsec_token='));
              if (exact) return exact.href;
              const html = document.documentElement ? document.documentElement.innerHTML.replace(/&amp;/g, '&') : '';
              const match = html.match(/\/user\/profile\/[^"\\s]+[?&]xsec_token=[^&"\\s]+[^"\\s]*/);
              return match ? new URL(match[0], location.origin).href : '';
            })()
        """.trimIndent()

        private val EXTRACT_WORKS_SCRIPT = """
            (function() {
              const root = window.__INITIAL_STATE__ || {};
              const result = [];
              const seen = new Set();
              const unbox = value => {
                let item = value;
                for (let i = 0; i < 6 && item && typeof item === 'object'; i++) {
                  if (item.value !== undefined) item = item.value;
                  else if (item._value !== undefined) item = item._value;
                  else if (item.data !== undefined && !Array.isArray(item)) item = item.data;
                  else break;
                }
                if (typeof item === 'string') {
                  try { return JSON.parse(item); } catch (_) { return item; }
                }
                return item;
              };
              const isNoteId = value => /^(?:[0-9a-fA-F]{24}|[0-9a-fA-F]{32})$/.test(String(value || ''));
              const isUsableProfileNote = (id, token) => isNoteId(id) && (String(id).length === 24 || Boolean(token));
              const fromMarker = (source, marker) => {
                if (typeof source !== 'string') return '';
                const at = source.lastIndexOf(marker);
                const tail = at < 0 ? '' : source.slice(at + marker.length);
                const match = tail.match(/^[0-9a-fA-F]{32}/) || tail.match(/^[0-9a-fA-F]{24}/);
                const id = match ? match[0] : '';
                return isNoteId(id) ? id : '';
              };
              const findToken = value => {
                if (!value || typeof value !== 'object') return '';
                const direct = String(value.xsecToken || value.xsec_token || value.secToken || '');
                if (direct) return direct;
                for (const key of Object.keys(value)) {
                  const nested = findToken(value[key]);
                  if (nested) return nested;
                }
                return '';
              };
              const findCover = value => {
                if (!value || typeof value !== 'object') return '';
                const coverValue = value.cover || value.image || {};
                const direct = typeof coverValue === 'string' ? coverValue :
                  (coverValue.url || coverValue.urlDefault || coverValue.urlPre || '');
                if (direct) return direct;
                for (const key of Object.keys(value)) {
                  const nested = findCover(value[key]);
                  if (nested) return nested;
                }
                return '';
              };
              const normalizeUrl = value => typeof value === 'string'
                ? value.replace(/##c=vp[146]$/, '').replace(/^http:/i, 'https:') : '';

              // Prefer the rendered card URL: current profile state uses a
              // random 32-character placeholder, while an actual card link carries the
              // canonical note id and its own xsec_token.
              const collectDomLinks = () => {
                const links = Array.from(document.querySelectorAll('a[href*="/explore/"],a[href*="/discovery/item/"]'));
                links.forEach(link => {
                  try {
                    const url = new URL(link.href, location.href);
                    const match = url.pathname.match(/\/(?:explore|discovery\/item)\/([0-9a-fA-F]{32}|[0-9a-fA-F]{24})(?=\/|$)/);
                    if (!match) return;
                    const title = String(link.getAttribute('aria-label') || link.innerText || '').replace(/\s+/g, ' ').trim();
                    const image = link.querySelector('img');
                    const cover = normalizeUrl(image && (image.currentSrc || image.src || ''));
                    const token = url.searchParams.get('xsec_token') || '';
                    if (!title || !cover || !isUsableProfileNote(match[1], token) || seen.has(match[1])) return;
                    seen.add(match[1]);
                    result.push({id: match[1], title, cover, token, video: false});
                  } catch (_) {}
                });
              };
              const noteFrom = (card, wrapper) => {
                if (!card || typeof card !== 'object') return;
                const cover = card.cover && typeof card.cover === 'object' ? card.cover : {};
                const user = card.user && typeof card.user === 'object' ? card.user : {};
                let id = [card.noteId, card.note_id, card.id, wrapper && wrapper.noteId,
                  wrapper && wrapper.note_id, wrapper && wrapper.id]
                  .find(value => typeof value === 'string' && isNoteId(value)) || '';
                if (!id && (cover.urlPre || '').endsWith('##c=vp4')) id = fromMarker(cover.fileId, '%%');
                if (!id && (cover.urlDefault || '').endsWith('##c=vp6')) id = fromMarker(user.avatar, '#%');
                if (!id && (cover.urlDefault || '').endsWith('##c=vp1')) id = fromMarker(cover.traceId, '#');
                const info = Array.isArray(cover.infoList) ? cover.infoList : [];
                const image = info.find(item => item && ['WB_PRV', 'H5_DTL', 'CRP'].includes(item.imageScene)) || info[0] || {};
                const url = normalizeUrl(cover.urlDefault || cover.urlPre || cover.url || image.url || '');
                const title = String(card.displayTitle || card.title || card.desc || '').trim();
                const token = String(card.xsecToken || card.xsec_token ||
                  (wrapper && (wrapper.xsecToken || wrapper.xsec_token)) || '');
                if (id && title && url && isUsableProfileNote(id, token) && !seen.has(id)) {
                  seen.add(id);
                  result.push({id, title, cover: url, token, video: card.type === 'video'});
                }
              };
              const collectProfile = value => {
                value = unbox(value);
                if (Array.isArray(value)) { value.forEach(collectProfile); return; }
                if (!value || typeof value !== 'object') return;
                const directId = String(value.noteId || value.id || '');
                const title = String(value.displayTitle || value.title || value.desc || '').trim();
                const cover = normalizeUrl(findCover(value));
                const token = findToken(value);
                if (isUsableProfileNote(directId, token) && title && cover && !seen.has(directId)) {
                  seen.add(directId);
                  result.push({id: directId, title, cover, token, video: value.type === 'video'});
                }
                Object.keys(value).forEach(key => collectProfile(value[key]));
              };
              const walk = value => {
                value = unbox(value);
                if (!value || typeof value !== 'object') return;
                if (Array.isArray(value)) { value.forEach(walk); return; }
                if (value.noteCard && typeof value.noteCard === 'object') noteFrom(value.noteCard, value);
                else if (value.cover && (value.displayTitle || value.title || value.desc)) noteFrom(value, null);
                Object.keys(value).forEach(key => walk(value[key]));
              };
              // The profile SSR payload currently places the first page at
              // profile.noteData. Read that array directly before any broad
              // recursive walk; the latter can traverse Vue internals and
              // silently fail on large reactive objects.
              const profile = unbox(root.profile || {});
              collectDomLinks();
              const noteData = unbox(profile && (profile.noteData || profile.note_data || profile.notes));
              if (Array.isArray(noteData)) noteData.forEach(value => {
                value = unbox(value);
                if (!value || typeof value !== 'object') return;
                const card = value.noteCard || value.note_card || value;
                const id = String(card.noteId || card.note_id || card.id || value.noteId || value.note_id || value.id || '');
                const title = String(card.displayTitle || card.display_title || card.title || card.desc || value.displayTitle || value.display_title || value.title || value.desc || '').trim();
                const coverValue = card.cover || card.image || {};
                const cover = normalizeUrl(typeof coverValue === 'string' ? coverValue :
                  (coverValue.url || coverValue.urlDefault || coverValue.url_default || coverValue.urlPre || coverValue.url_pre || ''));
                const token = String(card.xsecToken || card.xsec_token || value.xsecToken || value.xsec_token || '');
                if (isUsableProfileNote(id, token) && title && cover && !seen.has(id)) {
                  seen.add(id);
                  result.push({id, title, cover, token, video: String(card.type || value.type || '').toLowerCase() === 'video'});
                }
              });
              // Keep the fallback for alternate client-side state shapes.
              if (result.length === 0) {
                collectProfile(profile && (profile.noteData || profile.note_data || profile.notes));
                walk(root.user && root.user.notes);
              }
              return JSON.stringify(result);
            })()
        """.trimIndent()

    }
}

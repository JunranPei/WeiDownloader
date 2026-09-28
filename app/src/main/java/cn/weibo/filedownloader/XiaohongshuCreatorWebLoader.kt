package cn.weibo.filedownloader

import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONTokener

internal class XiaohongshuCreatorWebLoader(
    private val activity: Activity,
    private val creator: CreatorProfile,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var closed = false
    private var profileNavigationStarted = false
    private var activeProfileUrl: String = creator.profileUrl

    fun load(callback: (Result<DouyinCreatorPage>) -> Unit) {
        val cache = activity.getSharedPreferences("xhs_profile_cache", 0)
        activeProfileUrl = cache.getString(creator.id, creator.profileUrl).orEmpty().ifBlank { creator.profileUrl }
        readCachedWorks()?.takeIf { it.size > 1 }?.let { cached ->
            callback(Result.success(DouyinCreatorPage(creator.copy(profileUrl = activeProfileUrl), cached, cached.size.toLong(), false)))
        }
        val root = activity.findViewById<ViewGroup>(R.id.creatorRoot)
        val view = WebView(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            alpha = 0.01f
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.userAgentString = DESKTOP_USER_AGENT
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    Log.d("XhsWebLoader", "finished=${url.take(180)}")
                    if (!profileNavigationStarted && creator.seedPostUrl.isNotBlank() && url != creator.profileUrl) {
                        profileNavigationStarted = true
                        resolveProfileUrl(view) { resolved ->
                            activeProfileUrl = resolved.ifBlank { activeProfileUrl }
                            if (activeProfileUrl.isNotBlank()) {
                                cache.edit().putString(creator.id, activeProfileUrl).apply()
                            }
                            if (!closed) view.loadUrl(activeProfileUrl)
                        }
                        return
                    }
                    poll(view, 0, callback)
                }
            }
        }
        webView = view
        root.addView(view, 0, ViewGroup.LayoutParams(1, 1))
        // Keep the original share URL here. Rewriting it to discovery/item with
        // the resolver's note token can turn a valid share redirect into the
        // login shell before the page exposes the dynamic author token.
        view.loadUrl(creator.seedPostUrl.ifBlank { activeProfileUrl })
        handler.postDelayed({ if (!closed) poll(view, 0, callback) }, 5_000)
    }

    private fun resolveProfileUrl(view: WebView, callback: (String) -> Unit) {
        view.evaluateJavascript(PROFILE_LINK_SCRIPT) { raw ->
            val resolved = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
            Log.d("XhsWebLoader", "resolvedProfile=${resolved.take(220)} raw=${raw.take(240)}")
            callback(resolved)
        }
    }

    private fun publicWebSeedUrl(value: String): String {
        if (value.isBlank()) return ""
        return runCatching {
            val uri = android.net.Uri.parse(value)
            val id = uri.lastPathSegment.orEmpty()
            val token = uri.getQueryParameter("xsec_token").orEmpty()
            if (id.isBlank() || token.isBlank()) value else
                "https://www.xiaohongshu.com/discovery/item/$id?xsec_token=" +
                    java.net.URLEncoder.encode(token, "UTF-8") + "&xsec_source=app_share"
        }.getOrDefault(value)
    }

    private fun poll(view: WebView, attempt: Int, callback: (Result<DouyinCreatorPage>) -> Unit) {
        if (closed) return
        view.evaluateJavascript(EXTRACT_SCRIPT) { raw ->
            val works = parseWorks(raw)
            if (attempt == 0 || works.isNotEmpty() || attempt == 20) {
                Log.d("XhsWebLoader", "poll attempt=$attempt url=${view.url?.take(180)} works=${works.size} result=${raw?.take(1800)}")
            }
            if (attempt == 0 && works.isEmpty()) {
                view.evaluateJavascript(DEBUG_SCRIPT) { debug ->
                    Log.d("XhsWebLoader", "pageDebug=${debug?.take(5000)}")
                }
            }
            if (works.isNotEmpty()) {
                if (works.size > 1) writeCachedWorks(works)
                callback(Result.success(DouyinCreatorPage(creator.copy(profileUrl = activeProfileUrl), works, works.size.toLong(), false)))
            } else if (attempt < 20) {
                handler.postDelayed({ poll(view, attempt + 1, callback) }, 750)
            } else {
                callback(Result.failure(IllegalStateException("Xiaohongshu profile did not expose public works")))
            }
        }
    }

    private fun parseWorks(raw: String): List<DouyinCreatorWork> {
        val encoded = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: return emptyList()
        val values = runCatching { JSONArray(encoded) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until values.length()) {
                val item = values.optJSONObject(index) ?: continue
                val id = item.optString("id").ifBlank { "profile-${index + 1}" }
                val token = item.optString("token")
                val source = if (id.startsWith("profile-")) activeProfileUrl else buildString {
                    append("https://www.xiaohongshu.com/explore/").append(id)
                    if (token.isNotBlank()) append("?xsec_token=").append(token).append("&xsec_source=pc_user")
                }
                add(DouyinCreatorWork(id, item.optString("title"), item.optString("cover"), source, 1, false))
            }
        }.distinctBy { it.id }
    }

    private fun readCachedWorks(): List<DouyinCreatorWork> = runCatching {
        val raw = activity.getSharedPreferences("xhs_profile_cache", 0)
            .getString("works:${creator.id}", "").orEmpty()
        if (raw.isBlank()) return emptyList()
        val values = JSONArray(raw)
        buildList {
            for (index in 0 until values.length()) {
                val item = values.optJSONObject(index) ?: continue
                add(DouyinCreatorWork(
                    id = item.optString("id"),
                    title = item.optString("title"),
                    coverUrl = item.optString("cover"),
                    sourceUrl = item.optString("source"),
                    mediaCount = item.optInt("mediaCount", 1),
                    isGallery = item.optBoolean("gallery", false),
                ))
            }
        }.distinctBy { it.id }.filter { it.id.isNotBlank() }
    }.getOrDefault(emptyList())

    private fun writeCachedWorks(works: List<DouyinCreatorWork>) {
        val values = JSONArray()
        works.distinctBy { it.id }.forEach { work ->
            values.put(org.json.JSONObject().apply {
                put("id", work.id)
                put("title", work.title)
                put("cover", work.coverUrl)
                put("source", work.sourceUrl)
                put("mediaCount", work.mediaCount)
                put("gallery", work.isGallery)
            })
        }
        activity.getSharedPreferences("xhs_profile_cache", 0)
            .edit().putString("works:${creator.id}", values.toString()).apply()
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

    companion object {
        fun saveCachedWorks(activity: Activity, creator: CreatorProfile, works: List<DouyinCreatorWork>) {
            if (works.size <= 1) return
            val values = JSONArray()
            works.distinctBy { it.id }.forEach { work ->
                values.put(org.json.JSONObject().apply {
                    put("id", work.id)
                    put("title", work.title)
                    put("cover", work.coverUrl)
                    put("source", work.sourceUrl)
                    put("mediaCount", work.mediaCount)
                    put("gallery", work.isGallery)
                })
            }
            activity.getSharedPreferences("xhs_profile_cache", 0)
                .edit().putString("works:${creator.id}", values.toString()).apply()
        }

        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; Medium_Phone) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
        private val PROFILE_LINK_SCRIPT = """
            (function() {
              const links = Array.from(document.querySelectorAll('a[href*="/user/profile/"]'));
              const exact = links.find(a => (a.href || '').includes('xsec_token='));
              if (exact) return exact.href;
              const html = document.documentElement ? document.documentElement.innerHTML.replaceAll('&amp;', '&') : '';
              const match = html.match(/\/user\/profile\/[^"\s]+[?&]xsec_token=[^&"\s]+[^"\s]*/);
              return match ? new URL(match[0], location.origin).href : '';
            })()
        """.trimIndent()
        private val EXTRACT_SCRIPT = """
            (function() {
              // The SSR state intentionally redacts noteId. Once the real
              // profile page hydrates, its cover anchors contain the actual
              // /explore/<noteId> route. Prefer those hydrated cards so a
              // click never falls back to the author profile URL.
              const dom = Array.from(document.querySelectorAll('a.cover[href], a[href*="/explore/"]'))
                .map((a, index) => {
                  const href = (a.href || '').replace(/&amp;/g, '&');
                  const match = href.match(/\/(?:explore|discovery\/item)\/([A-Za-z0-9_-]{8,})/);
                  if (!match) return null;
                  const img = a.querySelector('img');
                  const card = a.closest('.note-item, [data-index]');
                  const title = card ? (card.innerText || '').split('\n').find(x => x.trim()) || '' : '';
                  return {id: match[1], token: new URL(href).searchParams.get('xsec_token') || '', title, cover: img ? (img.currentSrc || img.src || '') : ''};
                }).filter(Boolean);
              if (dom.length > 1) return JSON.stringify(dom);
              const root = window.__INITIAL_STATE__ || {};
              const unwrap = value => value && value.value !== undefined ? value.value : value;
              const routeToken = (((root.noteData || {}).routeQuery || {}).xsec_token || '').toString();
              const profileNotes = unwrap(root.profile && root.profile.noteData);
              if (Array.isArray(profileNotes)) {
                const profileWorks = profileNotes.map(item => {
                  const id = (item && (item.id || item.noteId || '')).toString();
                  const cover = item && item.cover ? (item.cover.url || item.cover.urlDefault || '') : '';
                  const normalizedCover = cover.startsWith('//') ? 'https:' + cover :
                    (cover.startsWith('http://') ? 'https://' + cover.substring(7) : cover);
                  return {
                    id: id,
                    token: routeToken,
                    title: item && (item.title || item.displayTitle || '') || '',
                    cover: normalizedCover
                  };
                }).filter(x => /^[A-Za-z0-9_-]{8,}$/.test(x.id) && (x.title || x.cover));
                if (profileWorks.length) return JSON.stringify(profileWorks);
              }
              const ref = root.user && root.user.notes;
              const notes = ref && (ref.value !== undefined ? ref.value : (ref._value !== undefined ? ref._value : ref));
              const flat = [];
              const walk = v => { if (Array.isArray(v)) v.forEach(walk); else if (v && typeof v === 'object') flat.push(v); };
              walk(notes || []);
              return JSON.stringify(flat.map((feed, index) => {
                const card = feed.noteCard || feed;
                const cover = card.cover || {};
                return {
                  id: feed.id || card.noteId || '',
                  token: feed.xsecToken || card.xsecToken || '',
                  title: card.displayTitle || card.title || card.desc || '',
                  cover: cover.urlDefault || cover.urlPre || cover.url || ''
                };
              }).filter(x => x.title || x.id));
            })()
        """.trimIndent()
        private val DEBUG_SCRIPT = """
            (function() {
              const links = Array.from(document.querySelectorAll('a[href]'))
                .map(a => a.href || '').filter(Boolean).slice(0, 80);
              const text = (document.body && document.body.innerText || '').slice(0, 1000);
              const html = document.documentElement ? document.documentElement.innerHTML : '';
              return JSON.stringify({
                title: document.title || '',
                hrefs: links,
                text: text,
                noteCard: html.includes('noteCard'),
                htmlSize: html.length,
                initialKeys: Object.keys(window.__INITIAL_STATE__ || {}).slice(0, 40),
                noteDataKeys: Object.keys((window.__INITIAL_STATE__ || {}).noteData || {}).slice(0, 40),
                noteData: JSON.stringify((window.__INITIAL_STATE__ || {}).noteData || {}).slice(0, 4500),
                profileKeys: Object.keys((window.__INITIAL_STATE__ || {}).profile || {}).slice(0, 40),
                profileNoteData: JSON.stringify(((window.__INITIAL_STATE__ || {}).profile || {}).noteData || {}).slice(0, 3000),
                profileNotePage: JSON.stringify(((window.__INITIAL_STATE__ || {}).profile || {}).notePage || {}).slice(0, 3000),
                dataAttrs: Array.from(document.querySelectorAll('[data-note-id], [data-id], [data-index]')).slice(0, 40).map(x => ({tag: x.tagName, id: x.getAttribute('data-note-id'), dataId: x.getAttribute('data-id'), index: x.getAttribute('data-index'), html: x.outerHTML.slice(0, 500)})),
                images: Array.from(document.querySelectorAll('img')).slice(0, 40).map(x => ({src: x.currentSrc || x.src || '', alt: x.alt || '', html: x.outerHTML.slice(0, 300)}))
              });
            })()
        """.trimIndent()
    }
}

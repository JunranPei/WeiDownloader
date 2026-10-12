package cn.weibo.filedownloader

import android.webkit.CookieManager
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener

object RedditSession {
    @Volatile private var verified = false
    fun cookieHeader(url: String = "https://www.reddit.com/"): String =
        if (RedditLinkParser.isRedditUrl(url)) CookieManager.getInstance().getCookie(url).orEmpty() else ""
    fun hasSavedSession(): Boolean = cookieHeader().split(';').any {
        it.trim().substringBefore('=') in setOf("reddit_session", "token_v2") && it.substringAfter('=', "").isNotBlank()
    }
    fun isSignedIn(): Boolean = verified && hasSavedSession()

    /** A cookie's presence alone never enables the login completion button. */
    fun verifyFromPage(view: WebView, callback: (Boolean) -> Unit) {
        if (!RedditLinkParser.isRedditUrl(view.url.orEmpty())) { callback(false); return }
        view.evaluateJavascript(VERIFY_SCRIPT) { encoded ->
            val raw = runCatching { JSONTokener(encoded).nextValue() as? String }.getOrNull()
            val state = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
            verified = state?.optBoolean("verified") == true
            callback(isSignedIn())
        }
    }
    private val VERIFY_SCRIPT = """
        (() => {
          const now = Date.now();
          let state = window.__allDownloaderRedditLogin;
          if (!state || now - state.started > 5000 && !state.pending || now - state.started > 15000) {
            state = window.__allDownloaderRedditLogin = {started: now, pending: true, verified: !!(state && state.verified)};
            fetch('/api/me.json', {credentials: 'include', headers: {'Accept': 'application/json'}})
              .then(r => r.ok ? r.json() : null)
              .then(j => { state.verified = !!(j && j.data && j.data.name); state.pending = false; })
              .catch(() => { state.verified = false; state.pending = false; });
          }
          return JSON.stringify({verified: state.verified});
        })()
    """.trimIndent()
}

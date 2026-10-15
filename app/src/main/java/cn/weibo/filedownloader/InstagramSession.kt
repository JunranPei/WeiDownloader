package cn.weibo.filedownloader

import android.webkit.CookieManager
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener
import java.security.MessageDigest

object InstagramSession {
    @Volatile private var verifiedSession = ""

    fun cookieHeader(): String = CookieManager.getInstance().getCookie("https://www.instagram.com/").orEmpty()
    fun hasSavedSession(): Boolean = InstagramRequests.hasSession(cookieHeader())
    fun isSignedIn(): Boolean = InstagramRequests.cookieValue(cookieHeader(), "sessionid").let {
        it.isNotBlank() && it == verifiedSession
    }

    /** Validate the account on Instagram before enabling the login completion button. */
    fun verifyFromPage(view: WebView, callback: (Boolean) -> Unit) {
        if (!InstagramRequests.isInstagramUrl(view.url.orEmpty()) || !hasSavedSession()) {
            verifiedSession = ""
            callback(false)
            return
        }
        val session = InstagramRequests.cookieValue(cookieHeader(), "sessionid")
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(session.toByteArray())
            .joinToString("") { "%02x".format(it) }
        view.evaluateJavascript(VERIFY_SCRIPT.replace("SESSION_FINGERPRINT", fingerprint)) { encoded ->
            val text = runCatching { JSONTokener(encoded).nextValue() as? String }.getOrNull()
            val state = text?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (state?.optBoolean("verified") == true &&
                session == InstagramRequests.cookieValue(cookieHeader(), "sessionid")) verifiedSession = session
            else verifiedSession = ""
            callback(isSignedIn())
        }
    }

    private val VERIFY_SCRIPT = """
        (() => {
          const now = Date.now();
          let state = window.__allDownloaderInstagramLogin;
          if (!state || state.session !== 'SESSION_FINGERPRINT' || !state.pending && now - state.started > 5000 || now - state.started > 15000) {
            const previous = !!(state && state.session === 'SESSION_FINGERPRINT' && state.verified);
            state = window.__allDownloaderInstagramLogin = {session: 'SESSION_FINGERPRINT', started: now, pending: true, verified: previous};
            const get = async path => {
              const controller = new AbortController();
              const timeout = setTimeout(() => controller.abort(), 8000);
              try {
                const r = await fetch(path, {credentials: 'include', redirect: 'error', signal: controller.signal,
                  headers: {'Accept': 'application/json', 'X-IG-App-ID': '936619743392459'}});
                return r.ok ? await r.json() : null;
              } catch (_) { return null; }
              finally { clearTimeout(timeout); }
            };
            get('/api/v1/accounts/edit/web_form_data/').then(async j => {
              let valid = !!(j && j.status === 'ok' && j.form_data && j.form_data.username);
              if (!valid) {
                const account = await get('/api/v1/accounts/current_user/?edit=true');
                valid = !!(account && account.status === 'ok' && account.user && account.user.pk && account.user.username);
              }
              state.verified = valid;
              state.pending = false;
            }).catch(() => { state.verified = false; state.pending = false; });
          }
          return JSON.stringify({verified: state.verified});
        })()
    """.trimIndent()
}

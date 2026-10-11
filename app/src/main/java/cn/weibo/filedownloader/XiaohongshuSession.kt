package cn.weibo.filedownloader

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.security.MessageDigest

internal object XiaohongshuSession {
    private var preferences: SharedPreferences? = null
    private const val ORIGIN = "https://www.xiaohongshu.com"

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences("xhs_verified_session", Context.MODE_PRIVATE)
    }

    // CookieManager applies the destination's domain/path rules. Combining the
    // rednote and xiaohongshu jars produced conflicting values for the same name.
    fun cookieHeader(): String = CookieManager.getInstance().getCookie(ORIGIN).orEmpty()

    private fun fingerprint(): String {
        val session = cookieHeader().split(';').map(String::trim)
            .firstOrNull { it.substringBefore('=') == "web_session" }
            ?.substringAfter('=', "").orEmpty()
        if (session.isBlank()) return ""
        return MessageDigest.getInstance("SHA-256").digest(session.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun isSignedIn(): Boolean {
        val key = fingerprint()
        val saved = preferences ?: return false
        val age = System.currentTimeMillis() - saved.getLong("verified_at", 0L)
        return key.isNotBlank() && saved.getString("fingerprint", "") == key && age in 0..3_600_000L
    }

    fun invalidate() {
        preferences?.edit()?.clear()?.apply()
    }

    fun recordEvidence(url: String, evidence: JSONObject) {
        if (runCatching { URI(url).host }.getOrNull() != "www.xiaohongshu.com") return
        when (XiaohongshuAuthEvidence.authenticated(evidence)) {
            true -> {
                val key = fingerprint()
                if (key.isNotBlank()) preferences?.edit()?.putString("fingerprint", key)
                    ?.putLong("verified_at", System.currentTimeMillis())?.apply()
            }
            false -> invalidate()
            null -> Unit // A loading document is not an authentication response.
        }
    }

    fun verifyFromPage(view: WebView, callback: (Boolean) -> Unit) {
        val url = view.url.orEmpty()
        view.evaluateJavascript(PAGE_AUTH_SCRIPT) { raw ->
            if (view.url == url) {
                val encoded = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
                val evidence = encoded?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (evidence != null) recordEvidence(url, evidence)
            }
            callback(isSignedIn())
        }
    }

    val PAGE_AUTH_SCRIPT = """
        (function() {
          const unwrap = v => v && typeof v === 'object' ? (v.value !== undefined ? v.value : (v._value !== undefined ? v._value : v)) : v;
          const user = (window.__INITIAL_STATE__ || {}).user || {};
          const info = unwrap(user.userInfo) || {};
          const loggedIn = unwrap(user.loggedIn);
          return JSON.stringify({loggedIn: typeof loggedIn === 'boolean' ? loggedIn : null,
            guest: typeof info.guest === 'boolean' ? info.guest : null,
            userIdPresent: !!(info.userId || info.user_id)});
        })()
    """.trimIndent()
}

internal object XiaohongshuAuthEvidence {
    fun authenticated(value: JSONObject): Boolean? = when {
        value.opt("loggedIn") == false || value.opt("guest") == true -> false
        value.opt("loggedIn") == true && value.opt("guest") == false && value.optBoolean("userIdPresent") -> true
        else -> null
    }
}

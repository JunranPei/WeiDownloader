package cn.weibo.filedownloader

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.view.ViewGroup
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Chromium request fallback using the same browser session as the login screen. */
internal object RedditWebJsonLoader {
    fun resolveShare(context: android.content.Context, url: String): String? = load(context, url, resolveOnly = true)
    fun load(context: android.content.Context, url: String, resolveOnly: Boolean = false): String? {
        val urlToLoad = url
        val accept = if (resolveOnly) "text/html" else "application/json"
        check(Looper.myLooper() != Looper.getMainLooper())
        val activity = context as? Activity ?: return null
        val handler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val ended = AtomicBoolean(false)
        var result: String? = null
        var browser: WebView? = null
        var timeout: Runnable? = null
        fun finish(value: String?) {
            if (!ended.compareAndSet(false, true)) return
            result = value
            handler.post {
                timeout?.let(handler::removeCallbacks)
                browser?.let { view ->
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.stopLoading()
                    view.destroy()
                }
                browser = null
            }
            latch.countDown()
        }
        handler.post {
            if (ended.get() || activity.isFinishing || activity.isDestroyed) { finish(null); return@post }
            runCatching {
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                var preparingSession = false
                var preparedSession = false
                var waitingForSession = false
                fun awaitSession(view: WebView, deadline: Long) {
                    if (ended.get()) return
                    val cookies = CookieManager.getInstance().getCookie("https://www.reddit.com/").orEmpty()
                    if (cookies.split(';').any { it.trim().startsWith("loid=") } ||
                        android.os.SystemClock.elapsedRealtime() >= deadline) {
                        preparingSession = false
                        view.loadUrl(urlToLoad, mapOf("Accept" to accept))
                    } else handler.postDelayed({ awaitSession(view, deadline) }, 250)
                }
                val view = WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = RedditHttpClient.USER_AGENT
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    alpha = 0f
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            !RedditLinkParser.isRedditUrl(request.url.toString())
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) finish(null)
                        }
                        override fun onPageFinished(view: WebView, url: String) {
                            if (ended.get() || !RedditLinkParser.isRedditUrl(url)) return
                            if (preparingSession) {
                                if (!waitingForSession) {
                                    waitingForSession = true
                                    // The login page creates anonymous credentials asynchronously.
                                    // Wait for its normal cookie initialization before leaving it.
                                    awaitSession(view, android.os.SystemClock.elapsedRealtime() + 8_000)
                                }
                                return
                            }
                            if (resolveOnly && RedditLinkParser.postId(url) != null) {
                                finish(url)
                                return
                            }
                            view.evaluateJavascript("(() => { try { const s = document.body.innerText; JSON.parse(s); return s; } catch (_) { return ''; } })()") { encoded ->
                                val body = runCatching { JSONTokener(encoded).nextValue() as? String }.getOrNull()
                                if (body.isNullOrBlank() && !preparedSession) {
                                    // The anonymous JSON API also needs Reddit's browser session cookies.
                                    // Initialize them through a normal page visit; do not synthesize opt-in cookies.
                                    preparedSession = true
                                    preparingSession = true
                                    view.loadUrl("https://www.reddit.com/login/")
                                } else finish(if (resolveOnly) null else body?.takeIf(String::isNotBlank))
                            }
                        }
                    }
                }
                browser = view
                root.addView(view, ViewGroup.LayoutParams(1, 1))
                timeout = Runnable { finish(null) }.also { handler.postDelayed(it, 30_000) }
                view.loadUrl(urlToLoad, mapOf("Accept" to accept))
            }.onFailure { finish(null) }
        }
        try {
            if (!latch.await(32, TimeUnit.SECONDS)) finish(null)
        } catch (error: InterruptedException) {
            finish(null)
            Thread.currentThread().interrupt()
        }
        return result
    }
}

package cn.weibo.filedownloader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.concurrent.Executors

object WeiboSession {
    private val configUrls = listOf("https://m.weibo.cn/api/config", "https://weibo.com/ajax/config/get")
    private val state = WeiboVerifiedSession()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var pending = false
    private var lastAttempt = 0L
    private var lastFingerprint = ""
    @Volatile private var lastSeenFingerprint = ""
    private val callbacks = mutableListOf<(Boolean?) -> Unit>()

    fun cookieHeader(address: String): String = WeiboCookieRouting.headerFor(address) {
        CookieManager.getInstance().getCookie(it)
    }

    private fun fingerprint(): String {
        val sessions = configUrls.joinToString("\n") { url ->
            cookieHeader(url).split(';').map(String::trim)
                .filter { it.substringBefore('=').equals("SUB", true) }.joinToString(";")
        }
        return MessageDigest.getInstance("SHA-256").digest(sessions.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    // CookieManager can wait on Chromium initialization/disk I/O. UI callers
    // read only the last server-verified snapshot; cookie reads stay on workers.
    fun isSignedIn(): Boolean = state.authenticated(lastSeenFingerprint, System.currentTimeMillis()) == true

    fun prepareLogin(ready: () -> Unit) {
        executor.execute {
            runCatching { CookieManager.getInstance().setAcceptCookie(true) }
            mainHandler.post { ready() }
        }
    }

    fun flushCookies() { executor.execute { runCatching { CookieManager.getInstance().flush() } } }

    /** Runs off the UI thread; cookies alone never establish a verified login. */
    fun refresh(context: Context, callback: (Boolean?) -> Unit) {
        synchronized(callbacks) {
            callbacks += callback
            if (pending) return
            pending = true
        }
        val appContext = context.applicationContext
        executor.execute {
            try {
                val key = fingerprint()
                val now = System.currentTimeMillis()
                lastSeenFingerprint = key
                if (key != lastFingerprint || now - lastAttempt !in 0..15_000L) {
                    lastFingerprint = key
                    lastAttempt = now
                    val evidence = mutableListOf<Boolean?>()
                    for (url in configUrls) {
                        val authenticated = if (cookieHeader(url).isBlank()) false else runCatching {
                            WeiboAuthEvidence.authenticated(WeiboHttpClient(appContext).getJson(url))
                        }.getOrNull()
                        evidence += authenticated
                        if (authenticated == true) break
                    }
                    val verified = when {
                        evidence.any { it == true } -> true
                        evidence.all { it == false } -> false
                        else -> null
                    }
                    state.record(key, verified, System.currentTimeMillis())
                }
            } catch (_: Exception) {
                lastSeenFingerprint = ""
            } finally {
                val listeners = synchronized(callbacks) {
                    pending = false
                    callbacks.toList().also { callbacks.clear() }
                }
                mainHandler.post {
                    val current = state.authenticated(lastSeenFingerprint, System.currentTimeMillis())
                    listeners.forEach { it(current) }
                }
            }
        }
    }

    internal fun openConnection(
        address: String,
        method: String = "GET",
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
        connectTimeout: Int = 20_000,
        readTimeout: Int = 30_000,
    ): HttpURLConnection = WeiboHttpTransport.open(address, ::cookieHeader, method, body, headers, connectTimeout, readTimeout)
}

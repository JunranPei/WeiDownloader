package cn.weibo.filedownloader

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton

class LoginActivity : Activity() {
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(ThemeController.wrap(newBase)) }
    private lateinit var webView: WebView
    private lateinit var doneButton: MaterialButton
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val platformKey by lazy { intent.getStringExtra(EXTRA_PLATFORM).orEmpty() }
    private val platform by lazy {
        Platform.entries.firstOrNull { it.key == platformKey } ?: Platform.WEIBO
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        configureTransparentSystemBars()
        setContentView(R.layout.activity_login)
        doneButton = findViewById(R.id.loginDoneButton)
        webView = findViewById<WebView>(R.id.loginWebView).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            if (platform != Platform.WEIBO) {
                settings.userAgentString = DESKTOP_WEB_USER_AGENT
            }
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.textZoom = 100
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    refreshLoginState()
                }
            }
            setInitialScale(0)
            CookieManager.getInstance().also { manager ->
                manager.setAcceptCookie(true)
                manager.setAcceptThirdPartyCookies(this, true)
            }
            loadUrl(
                when (platform) {
                    Platform.WEIBO -> WEIBO_LOGIN_URL
                    Platform.DOUYIN -> DOUYIN_LOGIN_URL
                    Platform.BILIBILI -> BILIBILI_LOGIN_URL
                    Platform.TELEGRAM -> TELEGRAM_LOGIN_URL
                    Platform.X -> X_LOGIN_URL
                },
            )
        }
        doneButton.setOnClickListener {
            CookieManager.getInstance().flush()
            setResult(RESULT_OK)
            finish()
        }
        val root = findViewById<View>(R.id.loginRoot)
        val originalPadding = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val safe = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                originalPadding[0] + safe.left,
                originalPadding[1] + safe.top,
                originalPadding[2] + safe.right,
                originalPadding[3] + safe.bottom,
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
        refreshLoginState()
        handler.postDelayed(::pollLoginState, LOGIN_POLL_MS)
    }

    private fun pollLoginState() {
        if (isFinishing || isDestroyed) return
        refreshLoginState()
        handler.postDelayed(::pollLoginState, LOGIN_POLL_MS)
    }

    private fun refreshLoginState() {
        val signedIn = when (platform) {
            Platform.WEIBO -> WeiboSession.isSignedIn()
            Platform.DOUYIN -> DouyinSession.isSignedIn()
            Platform.BILIBILI -> BilibiliSession.isSignedIn()
            Platform.TELEGRAM -> false
            Platform.X -> false
        }
        doneButton.setText(if (signedIn) R.string.login_done_ready else R.string.login_done_waiting)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView.stopLoading()
        webView.webChromeClient = null
        webView.destroy()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun configureTransparentSystemBars() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        val lightSystemBars = ThemeController.isLight(this)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightSystemBars
            isAppearanceLightNavigationBars = lightSystemBars
        }
    }

    companion object {
        const val EXTRA_PLATFORM = "platform"
        private const val WEIBO_LOGIN_URL = "https://passport.weibo.com/"
        private const val DOUYIN_LOGIN_URL =
            "https://sso.douyin.com/?service=www.douyin.com%2Flogin%3Ftype%3Dstormplatform_home"
        private const val BILIBILI_LOGIN_URL = "https://passport.bilibili.com/login"
        private const val TELEGRAM_LOGIN_URL = "https://web.telegram.org/a/"
        private const val X_LOGIN_URL = "https://x.com/i/flow/login"
        private const val DESKTOP_WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val LOGIN_POLL_MS = 1_000L
    }
}

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        configureTransparentSystemBars()
        setContentView(R.layout.activity_login)
        doneButton = findViewById(R.id.loginDoneButton)
        webView = findViewById<WebView>(R.id.loginWebView).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.textZoom = 100
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            setInitialScale(0)
            loadUrl("https://passport.weibo.com/")
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
    }

    override fun onDestroy() {
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
}

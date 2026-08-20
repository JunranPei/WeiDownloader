package cn.weibo.filedownloader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

class AboutActivity : Activity() {
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(ThemeController.wrap(newBase)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        configureTransparentSystemBars()
        setContentView(R.layout.activity_about)
        findViewById<MaterialToolbar>(R.id.aboutToolbar).setNavigationOnClickListener { finish() }
        findViewById<TextView>(R.id.aboutVersion).text = getString(
            R.string.version_format,
            packageManager.getPackageInfo(packageName, 0).versionName,
        )
        findViewById<MaterialButton>(R.id.aboutGithub).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL)))
        }
        applyInsets()
    }

    private fun applyInsets() {
        val root = findViewById<View>(R.id.aboutRoot)
        val toolbar = findViewById<View>(R.id.aboutToolbar)
        val scroll = findViewById<View>(R.id.aboutScroll)
        val toolbarPadding = intArrayOf(toolbar.paddingLeft, toolbar.paddingTop, toolbar.paddingRight, toolbar.paddingBottom)
        val scrollPadding = intArrayOf(scroll.paddingLeft, scroll.paddingTop, scroll.paddingRight, scroll.paddingBottom)
        val toolbarMinHeight = toolbar.minimumHeight
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            toolbar.minimumHeight = toolbarMinHeight + safe.top
            toolbar.setPadding(toolbarPadding[0] + safe.left, toolbarPadding[1] + safe.top, toolbarPadding[2] + safe.right, toolbarPadding[3])
            scroll.setPadding(scrollPadding[0] + safe.left, scrollPadding[1], scrollPadding[2] + safe.right, scrollPadding[3] + safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
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

    private companion object {
        const val PROJECT_URL = "https://github.com/JunranPei/Weibo-Downloader"
    }
}

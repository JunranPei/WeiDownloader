package cn.weibo.filedownloader

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(ThemeController.wrap(newBase)) }

    private val loginRequest = 2001
    private val destinationRequest = 2002
    private val preferences by lazy { getSharedPreferences("downloader_settings", Context.MODE_PRIVATE) }
    private val platformKey by lazy { intent.getStringExtra(EXTRA_PLATFORM).orEmpty() }
    private val platform by lazy {
        Platform.entries.firstOrNull { it.key == platformKey } ?: Platform.WEIBO
    }
    private lateinit var loginStatus: TextView
    private lateinit var loginButton: MaterialButton
    private lateinit var themeSegments: ComposeView
    private lateinit var organizationSegments: ComposeView
    private lateinit var themeSummary: TextView
    private lateinit var organizationSummary: TextView
    private lateinit var destinationValue: TextView
    private lateinit var resetDestinationButton: MaterialButton
    private lateinit var liveCoverSwitch: MaterialSwitch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        configureTransparentSystemBars()
        setContentView(R.layout.activity_settings)
        bindViews()
        configurePlatform()
        configureInsets()
        configureLiveCover()
        configureOrganization()
        configureTheme()
        refreshDestination()
        findViewById<TextView>(R.id.versionText).text = getString(
            R.string.version_format,
            packageManager.getPackageInfo(packageName, 0).versionName,
        )
        refreshLoginState()
    }

    override fun onResume() {
        super.onResume()
        refreshLoginState()
    }

    @Deprecated("Retained for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            loginRequest -> refreshLoginState()
            destinationRequest -> if (resultCode == RESULT_OK) saveDestination(data)
        }
    }

    private fun bindViews() {
        loginStatus = findViewById(R.id.settingsLoginStatus)
        loginButton = findViewById(R.id.settingsLoginButton)
        themeSegments = findViewById(R.id.settingsThemeSegments)
        organizationSegments = findViewById(R.id.settingsOrganizationSegments)
        themeSummary = findViewById(R.id.settingsThemeSummary)
        organizationSummary = findViewById(R.id.settingsOrganizationSummary)
        destinationValue = findViewById(R.id.settingsDestinationValue)
        resetDestinationButton = findViewById(R.id.settingsDestinationReset)
        liveCoverSwitch = findViewById(R.id.settingsLiveCoverSwitch)
        findViewById<View>(R.id.settingsBackButton).setOnClickListener { finish() }
        loginButton.setOnClickListener {
            startActivityForResult(
                Intent(this, LoginActivity::class.java)
                    .putExtra(LoginActivity.EXTRA_PLATFORM, platform.key),
                loginRequest,
            )
        }
        findViewById<View>(R.id.settingsAboutCard).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        findViewById<View>(R.id.settingsDestinationChoose).setOnClickListener { openDestinationPicker() }
        resetDestinationButton.setOnClickListener {
            DownloadDestination.clear(preferences, platform)
            refreshDestination()
        }
    }

    private fun configurePlatform() {
        findViewById<TextView>(R.id.settingsTitle).text = getString(
            when (platform) {
                Platform.WEIBO -> R.string.settings
                Platform.DOUYIN -> R.string.douyin_settings_title
                Platform.BILIBILI -> R.string.bilibili_settings_title
                Platform.TELEGRAM -> R.string.telegram_settings_title
                Platform.X -> R.string.x_settings_title
                Platform.INSTAGRAM -> R.string.instagram_settings_title
            },
        )
        val accountSectionTitle = findViewById<TextView>(R.id.settingsPlatformSectionTitle)
        accountSectionTitle.text = getString(
            when (platform) {
                Platform.WEIBO -> R.string.weibo_account
                Platform.DOUYIN -> R.string.douyin_account
                Platform.BILIBILI -> R.string.bilibili_account
                Platform.TELEGRAM -> R.string.telegram_public_posts
                Platform.X -> R.string.x_public_posts
                Platform.INSTAGRAM -> R.string.instagram_public_posts
            },
        )
        val accountVisibility = if (platform in setOf(Platform.TELEGRAM, Platform.X, Platform.INSTAGRAM)) View.GONE else View.VISIBLE
        accountSectionTitle.visibility = accountVisibility
        findViewById<View>(R.id.settingsPlatformAccountCard).visibility = accountVisibility
    }
    private fun configureLiveCover() {
        liveCoverSwitch.isChecked = preferences.getBoolean("live_cover", false)
        liveCoverSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean("live_cover", checked).apply()
        }
    }

    private fun configureOrganization() {
        val stored = preferences.getString(DownloadOrganization.PREFERENCE, DownloadOrganization.SINGLE_FOLDER).orEmpty()
        val initialIndex = if (stored == DownloadOrganization.PER_POST_FOLDER) 1 else 0
        organizationSummary.text = getString(
            if (initialIndex == 0) R.string.organization_single else R.string.organization_per_post,
            platform.downloadFolderName,
        )
        organizationSegments.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        organizationSegments.setContent {
            var selectedIndex by rememberSaveable { mutableIntStateOf(initialIndex) }
            WeiSingleChoiceSegmentedControl(
                labels = listOf(
                    getString(R.string.organization_single_short),
                    getString(R.string.organization_per_post_short),
                ),
                selectedIndex = selectedIndex,
            ) { index ->
                selectedIndex = index
                val value = if (index == 0) {
                    DownloadOrganization.SINGLE_FOLDER
                } else {
                    DownloadOrganization.PER_POST_FOLDER
                }
                preferences.edit().putString(DownloadOrganization.PREFERENCE, value).apply()
                organizationSummary.text = getString(
                    if (index == 0) R.string.organization_single else R.string.organization_per_post,
                    platform.downloadFolderName,
                )
            }
        }
    }

    private fun configureTheme() {
        val stored = preferences.getString("theme_mode", ThemeController.MODE_SYSTEM).orEmpty()
        val initialIndex = when (stored) {
            ThemeController.MODE_LIGHT -> 1
            ThemeController.MODE_DARK -> 2
            else -> 0
        }
        val labels = listOf(
            getString(R.string.theme_system_short),
            getString(R.string.theme_light),
            getString(R.string.theme_dark),
        )
        themeSummary.setText(R.string.theme_mode_summary)
        themeSegments.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        themeSegments.setContent {
            var selectedIndex by rememberSaveable { mutableIntStateOf(initialIndex) }
            WeiSingleChoiceSegmentedControl(labels, selectedIndex) { index ->
                selectedIndex = index
                val mode = when (index) {
                    1 -> ThemeController.MODE_LIGHT
                    2 -> ThemeController.MODE_DARK
                    else -> ThemeController.MODE_SYSTEM
                }
                preferences.edit().putString("theme_mode", mode).apply()
                recreate()
            }
        }
    }

    private fun openDestinationPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                DownloadDestination.uri(preferences, platform)?.let {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(it))
                }
            }
        }
        startActivityForResult(intent, destinationRequest)
    }

    @SuppressLint("WrongConstant")
    private fun saveDestination(data: Intent?) {
        val uri = data?.data ?: return
        val grantedFlags = data.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val persisted = runCatching {
            contentResolver.takePersistableUriPermission(uri, grantedFlags)
        }.isSuccess
        if (!persisted || grantedFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0) {
            toast(getString(R.string.folder_permission_failed))
            return
        }
        DownloadDestination.save(preferences, platform, uri.toString())
        refreshDestination()
    }

    private fun refreshDestination() {
        val stored = DownloadDestination.uri(preferences, platform)
        val uri = stored?.let(Uri::parse)
        val hasWriteGrant = uri != null && contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        if (uri != null && !hasWriteGrant) DownloadDestination.clear(preferences, platform)
        destinationValue.text = if (uri != null && hasWriteGrant) {
            destinationDisplayName(uri)
        } else {
            getString(R.string.default_destination, platform.downloadFolderName)
        }
        resetDestinationButton.visibility = if (uri != null && hasWriteGrant) View.VISIBLE else View.GONE
    }

    private fun destinationDisplayName(uri: Uri): String {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull().orEmpty()
        if (documentId.startsWith("primary:")) {
            val relative = documentId.substringAfter(':').trim('/')
            return if (relative.isBlank()) {
                getString(R.string.internal_storage)
            } else {
                getString(R.string.internal_storage_path, relative)
            }
        }
        return DocumentFile.fromTreeUri(this, uri)?.name
            ?: documentId.replace(':', '/').ifBlank { uri.toString() }
    }

    private fun refreshLoginState() {
        if (!::loginStatus.isInitialized) return
        when (platform) {
            Platform.WEIBO -> {
                val loggedIn = WeiboSession.isSignedIn()
                loginStatus.text = getString(if (loggedIn) R.string.login_active else R.string.login_inactive)
                loginButton.text = getString(if (loggedIn) R.string.login_again else R.string.login_weibo)
            }
            Platform.DOUYIN -> {
                val loggedIn = DouyinSession.isSignedIn()
                loginStatus.text = getString(
                    if (loggedIn) R.string.douyin_login_active else R.string.douyin_login_inactive,
                )
                loginButton.text = getString(if (loggedIn) R.string.login_again else R.string.login_douyin)
            }
            Platform.BILIBILI -> {
                val loggedIn = BilibiliSession.isSignedIn()
                loginStatus.text = getString(
                    if (loggedIn) R.string.bilibili_login_active else R.string.bilibili_login_inactive,
                )
                loginButton.text = getString(if (loggedIn) R.string.login_again else R.string.login_bilibili)
            }
            Platform.TELEGRAM -> Unit
            Platform.X -> Unit
            Platform.INSTAGRAM -> Unit
        }
    }

    private fun configureInsets() {
        val root = findViewById<View>(R.id.settingsRoot)
        val toolbar = findViewById<View>(R.id.settingsToolbar)
        val scroll = findViewById<View>(R.id.settingsScroll)
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

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_PLATFORM = "platform"
    }
}

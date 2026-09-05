package cn.weibo.filedownloader

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import android.content.res.ColorStateList
import android.content.res.Configuration
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import coil.load
import coil.request.videoFrameMillis
import coil.transform.CircleCropTransformation
import cn.weibo.carousel.CarouselMediaItem
import cn.weibo.carousel.WeiboCarouselView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(ThemeController.wrap(newBase)) }
    private val executor = Executors.newSingleThreadExecutor()
    private val videoFrameImageLoader by lazy(LazyThreadSafetyMode.NONE) {
        ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
    }
    private lateinit var preferences: SharedPreferences
    private lateinit var mainPage: View
    private lateinit var mainPager: ViewPager2
    private lateinit var downloadTasksPage: DownloadTasksPageController
    private lateinit var pasteButton: MaterialButton
    private lateinit var parseButton: MaterialButton
    private lateinit var selectAllButton: MaterialButton
    private lateinit var downloadButton: MaterialButton
    private lateinit var linkInput: EditText
    private lateinit var summaryText: TextView
    private lateinit var mediaHeader: View
    private lateinit var mediaCarousel: WeiboCarouselView
    private lateinit var mediaQualitySegments: ComposeView
    private lateinit var creatorCard: View
    private lateinit var creatorAvatar: ImageView
    private lateinit var creatorName: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var contentScroll: View
    private lateinit var toolbar: View
    private lateinit var emptyTopSpacer: View
    private lateinit var bottomBar: View
    private lateinit var platformPrefix: TextView
    private lateinit var brandSwitcher: View
    private var post: WeiboPost? = null
    private var sessionCookie = ""
    private var platform = Platform.WEIBO
    private var appliedThemeMode = ThemeController.MODE_SYSTEM
    private var douyinClient: DouyinClient? = null
    private var focusedMediaIndex = 0
    private var renderedDownloadSuccessVersion = -1L
    private val selectedUrls = LinkedHashSet<String>()
    private var lastClipboardUrl = ""
    private val downloadProgressRefresh = object : Runnable {
        override fun run() {
            if (!::mediaCarousel.isInitialized) return
            refreshMediaCardDownloadProgress()
            mediaCarousel.postDelayed(this, DOWNLOAD_PROGRESS_REFRESH_INTERVAL_MS)
        }
    }
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (::linkInput.isInitialized) linkInput.post { autoFillFromClipboard() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        configureTransparentSystemBars()
        preferences = getSharedPreferences("downloader_settings", Context.MODE_PRIVATE)
        appliedThemeMode = storedThemeMode()
        platform = Platform.entries.firstOrNull { it.key == savedInstanceState?.getString(STATE_PLATFORM) }
            ?: Platform.WEIBO
        setContentView(R.layout.activity_main_pager)
        mainPager = findViewById(R.id.mainPager)
        mainPage = layoutInflater.inflate(R.layout.activity_main, mainPager, false)
        val downloadsPage = layoutInflater.inflate(R.layout.activity_download_tasks, mainPager, false)
        mainPager.adapter = StaticPagesAdapter(listOf(mainPage, downloadsPage))
        mainPager.offscreenPageLimit = 1
        mainPager.setCurrentItem(savedInstanceState?.getInt(STATE_PAGE) ?: PAGE_MAIN, false)
        bindViews()
        downloadTasksPage = DownloadTasksPageController(
            this,
            downloadsPage,
            ::openDownloadWork,
            ::updateDownloadPageAvailability,
        )
        fillFromSharedIntent(intent)
        applySystemInsets()
        refreshSession()
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 10)
        }
    }

    private fun bindMediaThumbnail(image: ImageView, thumbnailUrl: String, fallbackVideoUrl: String) {
        val requestKey = "$thumbnailUrl\n$fallbackVideoUrl"
        image.tag = requestKey
        if (thumbnailUrl.isBlank()) {
            bindVideoFrame(image, fallbackVideoUrl, requestKey)
            return
        }
        image.load(thumbnailUrl) {
            crossfade(false)
            placeholder(null)
            error(null)
            listener(
                onStart = {
                    if (image.tag == requestKey) hideMediaThumbnail(image)
                },
                onSuccess = { _, _ ->
                    if (image.tag == requestKey) image.alpha = 1f
                },
                onError = { _, _ ->
                    image.post {
                        if (image.tag == requestKey) bindVideoFrame(image, fallbackVideoUrl, requestKey)
                    }
                },
            )
        }
    }

    private fun bindVideoFrame(image: ImageView, videoUrl: String, requestKey: String) {
        if (videoUrl.isBlank()) {
            hideMediaThumbnail(image)
            return
        }
        image.load(videoUrl, videoFrameImageLoader) {
            videoFrameMillis(500)
            crossfade(false)
            placeholder(null)
            error(null)
            listener(
                onStart = {
                    if (image.tag == requestKey) hideMediaThumbnail(image)
                },
                onSuccess = { _, _ ->
                    if (image.tag == requestKey) image.alpha = 1f
                },
                onError = { _, _ ->
                    if (image.tag == requestKey) hideMediaThumbnail(image)
                },
            )
        }
    }

    private fun hideMediaThumbnail(image: ImageView) {
        image.animate().cancel()
        image.alpha = 0f
        image.setImageDrawable(null)
    }

    override fun onResume() {
        super.onResume()
        if (::preferences.isInitialized && storedThemeMode() != appliedThemeMode) {
            recreate()
            return
        }
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).addPrimaryClipChangedListener(clipboardListener)
        scheduleClipboardRead()
        refreshSession()
        mediaCarousel.removeCallbacks(downloadProgressRefresh)
        mediaCarousel.post(downloadProgressRefresh)
        downloadTasksPage.start()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) scheduleClipboardRead()
    }

    override fun onPause() {
        if (::linkInput.isInitialized) linkInput.removeCallbacks(clipboardRead)
        if (::mediaCarousel.isInitialized) mediaCarousel.removeCallbacks(downloadProgressRefresh)
        if (::downloadTasksPage.isInitialized) downloadTasksPage.stop()
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).removePrimaryClipChangedListener(clipboardListener)
        super.onPause()
    }

    override fun onDestroy() {
        douyinClient?.close()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        fillFromSharedIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PLATFORM, platform.key)
        outState.putInt(STATE_PAGE, mainPager.currentItem)
        super.onSaveInstanceState(outState)
    }

    private fun bindViews() {
        toolbar = mainPage.findViewById(R.id.toolbar)
        pasteButton = mainPage.findViewById(R.id.pasteButton)
        parseButton = mainPage.findViewById(R.id.parseButton)
        selectAllButton = mainPage.findViewById(R.id.selectAllButton)
        downloadButton = mainPage.findViewById(R.id.downloadButton)
        linkInput = mainPage.findViewById(R.id.linkInput)
        summaryText = mainPage.findViewById(R.id.summaryText)
        mediaHeader = mainPage.findViewById(R.id.mediaHeader)
        mediaCarousel = mainPage.findViewById(R.id.mediaCarousel)
        mediaQualitySegments = mainPage.findViewById(R.id.mediaQualitySegments)
        mediaQualitySegments.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        creatorCard = mainPage.findViewById(R.id.creatorCard)
        creatorAvatar = mainPage.findViewById(R.id.creatorAvatar)
        creatorName = mainPage.findViewById(R.id.creatorName)
        contentScroll = mainPage.findViewById(R.id.contentScroll)
        emptyTopSpacer = mainPage.findViewById(R.id.emptyTopSpacer)
        bottomBar = mainPage.findViewById(R.id.bottomBar)
        platformPrefix = mainPage.findViewById(R.id.platformPrefix)
        brandSwitcher = mainPage.findViewById(R.id.brandSwitcher)
        brandSwitcher.setOnClickListener {
            switchPlatform(nextPlatform())
        }
        installPlatformSwipe(toolbar)
        installPlatformSwipe(brandSwitcher)
        updatePlatformUi()
        contentScroll.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val sizeChanged = right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop
            if (sizeChanged) {
                updateContentPosition(post?.media?.isNotEmpty() == true)
                updatePasteAction(linkInput.text?.isNotEmpty() == true)
            }
        }
        contentScroll.post { updateContentPosition(hasMedia = false) }
        linkInput.doAfterTextChanged { updatePasteAction(it?.isNotEmpty() == true) }
        linkInput.setOnEditorActionListener { _, actionId, event ->
            val requested = actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (requested) parsePost()
            requested
        }
        updatePasteAction(linkInput.text?.isNotEmpty() == true)
        mediaCarousel.thumbnailBinder = ::bindMediaThumbnail
        mediaCarousel.onSelectionChanged = { item, selected ->
            if (selected) selectedUrls += item.id else selectedUrls -= item.id
            updateSelectionUi()
        }
        mediaCarousel.onFocusChanged = { _, index ->
            focusedMediaIndex = index
            post?.let { configureMediaQualitySegments(it, index) }
        }
        mediaCarousel.onItemClick = { item ->
            if (item.selectable) {
                if (selectedUrls.contains(item.id)) selectedUrls -= item.id else selectedUrls += item.id
                post?.let { showMedia(it, resetCarousel = false) }
            }
        }
        progress = mainPage.findViewById(R.id.progress)
        mainPage.findViewById<View>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PLATFORM, platform.key))
        }
        pasteButton.setOnClickListener { pasteFromClipboard() }
        parseButton.setOnClickListener { parsePost() }
        selectAllButton.setOnClickListener { toggleSelectAll() }
        downloadButton.setOnClickListener { enqueueSelectedDownloads() }
    }

    private fun installPlatformSwipe(view: View) {
        var downX = 0f
        var downY = 0f
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    downX = event.x
                    downY = event.y
                    false
                }
                android.view.MotionEvent.ACTION_UP -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (kotlin.math.abs(dx) > dpInt(56) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.25f) {
                        switchPlatform(if (dx < 0) nextPlatform() else previousPlatform())
                        true
                    } else false
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    false
                }
                else -> false
            }
        }
    }

    private fun switchPlatform(value: Platform) {
        if (platform == value) return
        platform = value
        douyinClient?.close()
        douyinClient = null
        post = null
        selectedUrls.clear()
        clearMediaPresentation()
        updateSelectionUi()
        summaryText.visibility = View.GONE
        linkInput.text?.clear()
        updatePlatformUi()
    }

    private fun nextPlatform(): Platform = Platform.entries[(platform.ordinal + 1) % Platform.entries.size]

    private fun previousPlatform(): Platform =
        Platform.entries[(platform.ordinal - 1 + Platform.entries.size) % Platform.entries.size]

    private fun clearMediaPresentation() {
        focusedMediaIndex = 0
        mediaCarousel.submitItems(emptyList())
        mediaCarousel.visibility = View.GONE
        mediaQualitySegments.visibility = View.GONE
        mediaQualitySegments.setContent {}
        creatorCard.visibility = View.GONE
        creatorCard.setOnClickListener(null)
        creatorAvatar.setImageDrawable(null)
    }

    private fun updatePlatformUi() {
        linkInput.hint = getString(
            when (platform) {
                Platform.WEIBO -> R.string.link_hint
                Platform.DOUYIN -> R.string.douyin_link_hint
                Platform.BILIBILI -> R.string.bilibili_link_hint
                Platform.TELEGRAM -> R.string.telegram_link_hint
                Platform.X -> R.string.x_link_hint
            },
        )
        val prefixColor = ContextCompat.getColor(this, platform.prefixColor)
        val actionColor = ContextCompat.getColor(this, platform.actionColor)
        val onActionColor = ContextCompat.getColor(this, platform.onActionColor)
        platformPrefix.text = platform.shortName
        val downloaderTitle = mainPage.findViewById<TextView>(R.id.downloaderTitle)
        downloaderTitle.text = getString(R.string.brand_downloader)
        if (platform == Platform.DOUYIN) {
            val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val foreground = if (night) {
                themedColor(com.google.android.material.R.attr.colorOnSurface)
            } else {
                actionColor
            }
            val shadowOffset = dpInt(-2).toFloat()
            platformPrefix.setTextColor(foreground)
            platformPrefix.setShadowLayer(0.01f, shadowOffset, 0f, prefixColor)
            downloaderTitle.setTextColor(themedColor(com.google.android.material.R.attr.colorOnSurface))
            downloaderTitle.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        } else {
            platformPrefix.setTextColor(prefixColor)
            platformPrefix.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            downloaderTitle.setTextColor(themedColor(com.google.android.material.R.attr.colorOnSurface))
            downloaderTitle.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
        brandSwitcher.contentDescription = getString(R.string.switch_platform)
        parseButton.backgroundTintList = ColorStateList.valueOf(actionColor)
        parseButton.iconTint = ColorStateList.valueOf(onActionColor)
        downloadButton.backgroundTintList = ColorStateList.valueOf(actionColor)
        downloadButton.setTextColor(onActionColor)
        val douyinStroke = if (platform == Platform.DOUYIN) dpInt(1) else 0
        parseButton.strokeWidth = douyinStroke
        parseButton.strokeColor = ColorStateList.valueOf(prefixColor)
        downloadButton.strokeWidth = douyinStroke
        downloadButton.strokeColor = ColorStateList.valueOf(prefixColor)
    }

    private fun themedColor(attribute: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attribute, value, true)
        return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
    }

    private fun storedThemeMode(): String = preferences
        .getString("theme_mode", ThemeController.MODE_SYSTEM)
        .orEmpty()
        .ifBlank { ThemeController.MODE_SYSTEM }

    private fun autoFillFromClipboard() {
        if (!::linkInput.isInitialized || !hasWindowFocus()) return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val url = firstShareUrl(clipboardText(clipboard))
        val current = linkInput.text?.toString()?.trim().orEmpty()
        val mayReplace = current.isBlank() || current == lastClipboardUrl
        if (url != null && url != current && mayReplace) {
            lastClipboardUrl = url
            adoptPlatformForUrl(url)
            linkInput.setText(url)
            linkInput.setSelection(url.length)
        }
    }

    private fun fillFromSharedIntent(intent: Intent?) {
        if (!::linkInput.isInitialized || intent == null) return
        val openCreatorWork = intent.action == ACTION_OPEN_CREATOR_WORK
        val sharedText = when {
            openCreatorWork -> intent.getStringExtra(EXTRA_CREATOR_WORK_URL)
            intent.action == Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            intent.dataString != null -> intent.dataString
            else -> null
        }.orEmpty()
        val url = firstShareUrl(sharedText) ?: return
        adoptPlatformForUrl(url)
        lastClipboardUrl = if (openCreatorWork) "" else url
        linkInput.setText(url)
        linkInput.setSelection(url.length)
        if (openCreatorWork) {
            intent.action = Intent.ACTION_MAIN
            intent.removeExtra(EXTRA_CREATOR_WORK_URL)
            mainPager.setCurrentItem(PAGE_MAIN, false)
            linkInput.post { if (linkInput.text?.toString() == url) parsePost() }
        }
    }

    private fun scheduleClipboardRead() {
        if (!::linkInput.isInitialized) return
        linkInput.removeCallbacks(clipboardRead)
        linkInput.post(clipboardRead)
        // Some Android builds expose the primary clip one frame after the
        // activity receives focus. This single retry is event-driven and does
        // not leave a polling task running in the background.
        linkInput.postDelayed(clipboardRead, 160L)
    }

    private val clipboardRead = Runnable { autoFillFromClipboard() }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val content = clipboardText(clipboard)
        if (content.isBlank()) return toast(getString(R.string.clipboard_empty))
        val url = firstShareUrl(content)
        if (url != null) {
            lastClipboardUrl = url
            adoptPlatformForUrl(url)
        }
        linkInput.setText(url ?: content)
        linkInput.setSelection(linkInput.text?.length ?: 0)
        linkInput.requestFocus()
    }

    private fun clipboardText(clipboard: ClipboardManager): String {
        val clip = runCatching { clipboard.primaryClip }.getOrNull() ?: return ""
        if (clip.itemCount == 0) return ""
        val item = clip.getItemAt(0)
        return (item.text?.toString()
            ?: item.uri?.toString()
            ?: item.intent?.dataString
            ?: runCatching { item.coerceToText(this)?.toString() }.getOrNull())
            ?.trim().orEmpty()
    }

    private fun firstShareUrl(value: String): String? {
        val normalized = value.replace("\u200B", "").replace("\uFEFF", "")
        val candidates = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .map { it.value.trimEnd('.', ',', '!', '?', '，', '。', '！', '？', '）', ')', '；', ';', '】', ']') }
        return candidates.firstOrNull { isSupportedUrl(it) }
    }

    private fun isSupportedUrl(url: String): Boolean =
        url.contains("weibo.com", true) || url.contains("weibo.cn", true) || url.contains("t.cn/", true) ||
            url.contains("douyin.com", true) || url.contains("iesdouyin.com", true) ||
            url.contains("bilibili.com", true) || url.contains("b23.tv", true) ||
            TelegramLinkParser.isSupported(url) || XLinkParser.isSupported(url)

    private fun adoptPlatformForUrl(url: String) {
        val target = when {
            XLinkParser.isSupported(url) -> Platform.X
            TelegramLinkParser.isSupported(url) -> Platform.TELEGRAM
            url.contains("bilibili.com", true) || url.contains("b23.tv", true) -> Platform.BILIBILI
            url.contains("douyin.com", true) || url.contains("iesdouyin.com", true) -> Platform.DOUYIN
            else -> Platform.WEIBO
        }
        if (target != platform) switchPlatform(target)
    }

    private fun parsePost() {
        val url = linkInput.text?.toString()?.trim().orEmpty()
        if (url.isBlank()) return toast(getString(R.string.link_required))
        adoptPlatformForUrl(url)
        refreshSession()
        post = null
        selectedUrls.clear()
        clearMediaPresentation()
        updateSelectionUi()
        busy(
            true,
            getString(
                when (platform) {
                    Platform.WEIBO -> R.string.reading_post
                    Platform.DOUYIN -> R.string.reading_douyin
                    Platform.BILIBILI -> R.string.reading_bilibili
                    Platform.TELEGRAM -> R.string.reading_telegram
                    Platform.X -> R.string.reading_x
                },
            ),
        )
        if (platform == Platform.DOUYIN) {
            douyinClient?.close()
            douyinClient = DouyinClient(this)
            douyinClient?.loadPost(url, QualityPreference.HIGHEST) { result ->
                runOnUiThread {
                    douyinClient?.close()
                    douyinClient = null
                    result.fold(
                        onSuccess = { handleLoadedPost(it) },
                        onFailure = { handleReadFailure(it) },
                    )
                }
            }
            return
        }
        executor.execute {
            try {
                val includeLiveCover = preferences.getBoolean("live_cover", false)
                val loaded = when (platform) {
                    Platform.WEIBO -> WeiboClient(sessionCookie, this, includeLiveCover).loadPost(url)
                    Platform.BILIBILI -> BilibiliClient(sessionCookie, this).loadPost(url)
                    Platform.TELEGRAM -> TelegramClient(this).loadPost(url)
                    Platform.X -> XClient(this).loadPost(url)
                    Platform.DOUYIN -> return@execute
                }
                runOnUiThread { handleLoadedPost(loaded) }
            } catch (error: WeiboLoginRequiredException) {
                runOnUiThread {
                    busy(false, error.message ?: getString(R.string.login_required_settings))
                    toast(getString(R.string.login_required_settings))
                    startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PLATFORM, platform.key))
                }
            } catch (error: Exception) {
                runOnUiThread { handleReadFailure(error) }
            }
        }
    }

    private fun handleLoadedPost(loaded: WeiboPost) {
        post = loaded
        selectedUrls.clear()
        val history = DownloadHistory(this)
        val statusStore = DownloadStatusStore(this)
        selectedUrls += loaded.media.filter {
            !history.contains(it) && statusStore.activeMediaDownload(it.stableId) == null
        }.map { it.url }
        showMedia(loaded)
        val available = selectedUrls.size
        val summary = if (
            platform in setOf(Platform.DOUYIN, Platform.BILIBILI, Platform.TELEGRAM, Platform.X) &&
            loaded.creator?.id?.isNotBlank() == true
        ) {
            getString(R.string.identified_media_format, loaded.media.size, available)
        } else {
            getString(R.string.identified_format, loaded.media.size, available, loaded.author)
        }
        busy(false, summary)
    }

    private fun openDownloadWork(work: ActiveDownloadWork) {
        val url = work.sourceUrl
        if (url.isBlank()) return
        douyinClient?.close()
        douyinClient = null
        platform = work.platform
        updatePlatformUi()
        lastClipboardUrl = ""
        linkInput.setText(url)
        linkInput.setSelection(url.length)
        val cached = ActiveDownloadPostCache.get(work.key)
        if (cached != null) {
            handleLoadedPost(cached)
        } else {
            post = null
            selectedUrls.clear()
            clearMediaPresentation()
            updateSelectionUi()
            summaryText.visibility = View.GONE
        }
        mainPager.setCurrentItem(PAGE_MAIN, true)
        if (cached == null) {
            linkInput.post { if (linkInput.text?.toString() == url) parsePost() }
        }
    }

    private fun updateDownloadPageAvailability(available: Boolean) {
        if (!available && mainPager.currentItem != PAGE_MAIN) {
            mainPager.setCurrentItem(PAGE_MAIN, true)
        }
        mainPager.post { mainPager.isUserInputEnabled = available }
    }

    private fun handleReadFailure(error: Throwable) {
        post = null
        selectedUrls.clear()
        clearMediaPresentation()
        updateSelectionUi()
        busy(
            false,
            error.message ?: getString(
                when (platform) {
                    Platform.WEIBO -> R.string.read_failed
                    Platform.DOUYIN -> R.string.douyin_read_failed
                    Platform.BILIBILI -> R.string.bilibili_read_failed
                    Platform.TELEGRAM -> R.string.telegram_read_failed
                    Platform.X -> R.string.x_read_failed
                },
            ),
        )
    }

    private fun showMedia(value: WeiboPost, resetCarousel: Boolean = true) {
        val history = DownloadHistory(this)
        val statusStore = DownloadStatusStore(this)
        renderedDownloadSuccessVersion = statusStore.successVersion()
        val carouselItems = value.media.mapIndexed { index, item ->
            val recorded = history.contains(item)
            val activeDownload = statusStore.activeMediaDownload(item.stableId)
            val qualityLabel = mediaTechnicalLabel(item)
            /* card content is supplied by the reusable carousel
            row.findViewById<TextView>(R.id.mediaTitle).text = "${index + 1}. ${getString(item.type.labelRes)} · ${getString(MediaQuality.labelRes(item.quality))}"
            row.findViewById<TextView>(R.id.mediaSubtitle).text = item.suggestedFileName()
            checkbox.isEnabled = !recorded
            checkbox.isSelected = !recorded && selectedUrls.contains(item.url)
            checkbox.setOnClickListener {
                checkbox.isSelected = !checkbox.isSelected
                val checked = checkbox.isSelected
                if (checked) selectedUrls += item.url else selectedUrls -= item.url
                updateSelectionUi()
            }
            row.setOnClickListener { if (checkbox.isEnabled) checkbox.performClick() }
            val preview = item.thumbnailUrl.ifBlank { if (item.type == MediaType.IMAGE) item.url else "" }
            row.findViewById<ShapeableImageView>(R.id.mediaThumbnail).load(preview.takeIf { it.isNotBlank() }) {
                crossfade(true)
                placeholder(R.drawable.ic_media_placeholder)
                error(R.drawable.ic_media_placeholder)
            }
            val viewport = resources.displayMetrics.widthPixels - dp(40)
            val width = viewport.coerceAtLeast(dp(280))
            row.layoutParams = LinearLayout.LayoutParams(width, width)
            */
            val preview = when {
                item.type == MediaType.IMAGE && item.url.isNotBlank() -> item.url
                item.thumbnailUrl.isNotBlank() -> item.thumbnailUrl
                else -> ""
            }
            CarouselMediaItem(
                id = item.url,
                imageUrl = preview,
                fallbackImageUrl = item.url.takeIf {
                    item.type == MediaType.VIDEO || item.type == MediaType.LIVE_PHOTO
                }.orEmpty(),
                title = "${index + 1}. ${getString(item.type.labelRes)} · $qualityLabel",
                subtitle = item.suggestedFileName(),
                selected = selectedUrls.contains(item.url),
                selectable = activeDownload == null,
                downloaded = recorded,
                downloadProgress = activeDownload?.percent?.takeIf { activeDownload.downloadedBytes > 0L }
                    ?: activeDownload?.let { INDETERMINATE_DOWNLOAD_PROGRESS },
            )
        }
        if (resetCarousel) {
            mediaCarousel.submitItems(carouselItems)
        } else {
            mediaCarousel.updateItems(carouselItems)
        }
        mediaCarousel.visibility = if (carouselItems.isEmpty()) View.GONE else View.VISIBLE
        bindCreatorPresentation(value.creator)
        configureMediaQualitySegments(value, focusedMediaIndex)
        updateSelectionUi()
    }

    private fun refreshMediaCardDownloadProgress() {
        val current = post ?: return
        val statusStore = DownloadStatusStore(this)
        if (statusStore.successVersion() != renderedDownloadSuccessVersion) {
            showMedia(current, resetCarousel = false)
            return
        }
        mediaCarousel.updateDownloadProgress(
            current.media.associate { item ->
                val activeDownload = statusStore.activeMediaDownload(item.stableId)
                item.url to (
                    activeDownload?.percent?.takeIf { activeDownload.downloadedBytes > 0L }
                        ?: activeDownload?.let { INDETERMINATE_DOWNLOAD_PROGRESS }
                    )
            },
        )
    }

    private fun bindCreatorPresentation(creator: CreatorProfile?) {
        if (platform !in setOf(Platform.DOUYIN, Platform.BILIBILI, Platform.TELEGRAM, Platform.X) || creator == null || creator.id.isBlank()) {
            creatorCard.visibility = View.GONE
            creatorCard.setOnClickListener(null)
            return
        }
        creatorName.text = creator.name
        creatorCard.contentDescription = getString(R.string.view_creator_works_description, creator.name)
        creatorAvatar.load(creator.avatarUrl.takeIf(String::isNotBlank)) {
            transformations(CircleCropTransformation())
            placeholder(R.drawable.ic_account_circle_rounded)
            error(R.drawable.ic_account_circle_rounded)
        }
        creatorCard.setOnClickListener {
            startActivity(DouyinCreatorActivity.createIntent(this, creator))
        }
        creatorCard.visibility = View.VISIBLE
    }

    private fun configureMediaQualitySegments(value: WeiboPost, focusIndex: Int) {
        val item = value.media.getOrNull(focusIndex)
        val variants = item?.variants.orEmpty().distinctBy { it.key }
        if (item == null || variants.size < 2) {
            mediaQualitySegments.visibility = View.GONE
            mediaQualitySegments.setContent {}
            return
        }
        val keys = variants.map { it.key }
        val selectedKey = item.selectedVariantKey?.takeIf { it in keys } ?: keys.first()
        val uiGroup = WeiChoiceGroup(
            title = getString(
                R.string.focused_media_options,
                focusIndex + 1,
                getString(item.type.labelRes),
            ),
            labels = keys.map(::mediaVariantLabel),
            selectedIndex = keys.indexOf(selectedKey).coerceAtLeast(0),
        )
        mediaQualitySegments.visibility = View.VISIBLE
        mediaQualitySegments.setContent {
            WeiChoiceGroups(listOf(uiGroup)) { groupIndex, itemIndex ->
                if (groupIndex == 0) {
                    keys.getOrNull(itemIndex)?.let { key ->
                        selectVariantForMedia(item.stableId, key)
                    }
                }
            }
        }
    }

    private fun selectVariantForMedia(stableId: String, key: String) {
        val current = post ?: return
        val selectedStableIds = current.media
            .filter { it.url in selectedUrls }
            .mapTo(HashSet()) { it.stableId }
        val updated = current.copy(
            media = current.media.map { item ->
                if (item.stableId == stableId) item.selectVariant(key) else item
            },
        )
        post = updated
        selectedUrls.clear()
        selectedUrls += updated.media.filter { it.stableId in selectedStableIds }.map { it.url }
        showMedia(updated, resetCarousel = false)
    }

    private fun mediaVariantLabel(key: String?): String = when (key) {
        "original" -> getString(R.string.image_variant_original)
        null -> getString(R.string.image_variant_original)
        else -> key
    }

    private fun mediaTechnicalLabel(item: MediaItem): String {
        val variant = item.variants.firstOrNull { it.key == item.selectedVariantKey }
        return when (item.type) {
            MediaType.IMAGE, MediaType.VIDEO, MediaType.LIVE_PHOTO -> {
                if (variant != null && variant.width > 0 && variant.height > 0) {
                    "${variant.width}×${variant.height}"
                } else {
                    getString(R.string.image_dimensions_unknown)
                }
            }
            MediaType.AUDIO -> {
                if (variant != null && variant.bitrate > 0L) {
                    "${(variant.bitrate / 1000L).coerceAtLeast(1L)} kbps"
                } else {
                    getString(R.string.audio_bitrate_unknown)
                }
            }
        }
    }

    /*
    private fun snapToNearestCard() {
        if (mediaList.childCount == 0) return
        val target = (0 until mediaList.childCount)
            .map { mediaList.getChildAt(it).left }
            .minByOrNull { kotlin.math.abs(it - mediaScroller.scrollX) }
            ?: return
        mediaScroller.smoothScrollTo(target, 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    */

    private fun toggleSelectAll() {
        val current = post ?: return
        val eligible = current.media.map { it.url }
        if (eligible.isNotEmpty() && selectedUrls.containsAll(eligible)) selectedUrls.removeAll(eligible.toSet()) else selectedUrls.addAll(eligible)
        showMedia(current, resetCarousel = false)
    }

    private fun updateSelectionUi() {
        val current = post
        val eligible = current?.media?.map { it.url }.orEmpty()
        selectedUrls.retainAll(eligible.toSet())
        val allSelected = eligible.isNotEmpty() && selectedUrls.containsAll(eligible)
        val hasMedia = current?.media?.isNotEmpty() == true
        mediaHeader.visibility = if (hasMedia) View.VISIBLE else View.GONE
        bottomBar.visibility = if (hasMedia) View.VISIBLE else View.GONE
        selectAllButton.visibility = if ((current?.media?.size ?: 0) > 1) View.VISIBLE else View.GONE
        selectAllButton.isEnabled = eligible.isNotEmpty()
        selectAllButton.text = getString(if (allSelected) R.string.deselect_all else R.string.select_all)
        downloadButton.isEnabled = selectedUrls.isNotEmpty()
        downloadButton.text = if (selectedUrls.isEmpty()) getString(R.string.choose_files) else getString(R.string.download_selected_count, selectedUrls.size)
        updateContentPosition(hasMedia)
    }

    private fun updateContentPosition(hasMedia: Boolean) {
        if (!::emptyTopSpacer.isInitialized || contentScroll.height == 0) return
        val controlsHeight = dpInt(64)
        val usableHeight = (contentScroll.height - contentScroll.paddingBottom).coerceAtLeast(controlsHeight)
        val targetHeight = if (hasMedia) 0 else ((usableHeight - controlsHeight) / 2 - dpInt(12)).coerceAtLeast(0)
        if (emptyTopSpacer.layoutParams.height != targetHeight) {
            emptyTopSpacer.layoutParams = emptyTopSpacer.layoutParams.apply { height = targetHeight }
        }
    }

    private fun dpInt(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun updatePasteAction(hasText: Boolean) {
        val iconOnly = hasText || resources.configuration.screenWidthDp < 360
        pasteButton.text = if (iconOnly) "" else getString(R.string.paste_once)
        pasteButton.icon = if (iconOnly) {
            AppCompatResources.getDrawable(this, R.drawable.ic_content_paste_rounded)
        } else {
            null
        }
        pasteButton.contentDescription = getString(R.string.paste_once)
    }

    private fun enqueueSelectedDownloads() {
        val current = post ?: return
        val chosen = current.media.filter { selectedUrls.contains(it.url) }
        if (chosen.isEmpty()) return toast(getString(R.string.select_at_least_one))
        val destinationTreeUri = preferences.getString("destination_uri", null)
        try {
            val result = DownloadDispatcher(
                this,
                if (platform == Platform.DOUYIN) "" else sessionCookie,
                platform,
            ).enqueueSelected(current, chosen, destinationTreeUri)
            if (result.queued == 0) return toast(getString(R.string.create_download_failed))
            selectedUrls.removeAll(chosen.map { it.url }.toSet())
            showMedia(current, resetCarousel = false)
            summaryText.text = getString(R.string.download_enqueued_format, result.queued)
            toast(getString(R.string.download_started_format, result.queued))
        } catch (error: Exception) { toast(error.message ?: getString(R.string.create_download_failed)) }
    }

    private fun refreshSession() {
        sessionCookie = when (platform) {
            Platform.WEIBO -> WeiboSession.cookieHeader()
            Platform.DOUYIN -> DouyinSession.cookieHeader()
            Platform.BILIBILI -> BilibiliSession.cookieHeader()
            Platform.TELEGRAM -> ""
            Platform.X -> ""
        }
    }


    private fun busy(active: Boolean, message: String) {
        progress.visibility = if (active) View.VISIBLE else View.GONE
        parseButton.isEnabled = !active
        pasteButton.isEnabled = !active
        summaryText.visibility = View.VISIBLE
        summaryText.text = message
    }

    private fun applySystemInsets() {
        val root = mainPage.findViewById<View>(R.id.rootLayout)
        val scroll = mainPage.findViewById<View>(R.id.contentScroll)
        val bottomBar = mainPage.findViewById<View>(R.id.bottomBar)
        val toolbarPadding = intArrayOf(toolbar.paddingLeft, toolbar.paddingTop, toolbar.paddingRight, toolbar.paddingBottom)
        val scrollPadding = intArrayOf(scroll.paddingLeft, scroll.paddingTop, scroll.paddingRight, scroll.paddingBottom)
        val bottomPadding = intArrayOf(bottomBar.paddingLeft, bottomBar.paddingTop, bottomBar.paddingRight, bottomBar.paddingBottom)
        val toolbarMinHeight = toolbar.minimumHeight
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            toolbar.minimumHeight = toolbarMinHeight + safe.top
            toolbar.setPadding(toolbarPadding[0] + safe.left, toolbarPadding[1] + safe.top, toolbarPadding[2] + safe.right, toolbarPadding[3])
            scroll.setPadding(scrollPadding[0] + safe.left, scrollPadding[1], scrollPadding[2] + safe.right, scrollPadding[3])
            bottomBar.setPadding(bottomPadding[0] + safe.left, bottomPadding[1], bottomPadding[2] + safe.right, bottomPadding[3] + safe.bottom)
            scroll.post { updateContentPosition(post?.media?.isNotEmpty() == true) }
            insets
        }
        root.doOnAttach { ViewCompat.requestApplyInsets(it) }
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
        const val ACTION_OPEN_CREATOR_WORK = "cn.weibo.filedownloader.action.OPEN_CREATOR_WORK"
        const val EXTRA_CREATOR_WORK_URL = "creator_work_url"
        private const val STATE_PLATFORM = "state_platform"
        private const val STATE_PAGE = "state_page"
        private const val PAGE_MAIN = 0
        private const val DOWNLOAD_PROGRESS_REFRESH_INTERVAL_MS = 500L
        private const val INDETERMINATE_DOWNLOAD_PROGRESS = -1
    }
}

private class StaticPagesAdapter(
    private val pages: List<View>,
) : RecyclerView.Adapter<StaticPagesAdapter.Holder>() {
    override fun getItemCount(): Int = pages.size

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
        val container = FrameLayout(parent.context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        return Holder(container)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val page = pages[position]
        (page.parent as? android.view.ViewGroup)?.removeView(page)
        holder.container.removeAllViews()
        holder.container.addView(
            page,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    class Holder(val container: FrameLayout) : RecyclerView.ViewHolder(container)
}

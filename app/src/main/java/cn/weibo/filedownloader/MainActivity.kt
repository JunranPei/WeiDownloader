package cn.weibo.filedownloader

import android.Manifest
import android.app.Activity
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
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.appcompat.content.res.AppCompatResources
import coil.load
import cn.weibo.carousel.CarouselMediaItem
import cn.weibo.carousel.WeiboCarouselView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.util.concurrent.Executors

class MainActivity : Activity() {
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(ThemeController.wrap(newBase)) }
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var preferences: SharedPreferences
    private lateinit var pasteButton: MaterialButton
    private lateinit var parseButton: MaterialButton
    private lateinit var selectAllButton: MaterialButton
    private lateinit var downloadButton: MaterialButton
    private lateinit var linkInput: EditText
    private lateinit var summaryText: TextView
    private lateinit var mediaHeader: View
    private lateinit var mediaCarousel: WeiboCarouselView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var contentScroll: View
    private lateinit var emptyTopSpacer: View
    private lateinit var bottomBar: View
    private var post: WeiboPost? = null
    private var sessionCookie = ""
    private val selectedUrls = LinkedHashSet<String>()
    private var lastClipboardUrl = ""
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (::linkInput.isInitialized) linkInput.post { autoFillFromClipboard() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        configureTransparentSystemBars()
        preferences = getSharedPreferences("downloader_settings", Context.MODE_PRIVATE)
        setContentView(R.layout.activity_main)
        bindViews()
        fillFromSharedIntent(intent)
        applySystemInsets()
        refreshSession()
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 10)
        }
    }

    override fun onResume() {
        super.onResume()
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).addPrimaryClipChangedListener(clipboardListener)
        scheduleClipboardRead()
        refreshSession()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) scheduleClipboardRead()
    }

    override fun onPause() {
        if (::linkInput.isInitialized) linkInput.removeCallbacks(clipboardRead)
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).removePrimaryClipChangedListener(clipboardListener)
        super.onPause()
    }

    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        fillFromSharedIntent(intent)
    }

    private fun bindViews() {
        pasteButton = findViewById(R.id.pasteButton)
        parseButton = findViewById(R.id.parseButton)
        selectAllButton = findViewById(R.id.selectAllButton)
        downloadButton = findViewById(R.id.downloadButton)
        linkInput = findViewById(R.id.linkInput)
        summaryText = findViewById(R.id.summaryText)
        mediaHeader = findViewById(R.id.mediaHeader)
        mediaCarousel = findViewById(R.id.mediaCarousel)
        contentScroll = findViewById(R.id.contentScroll)
        emptyTopSpacer = findViewById(R.id.emptyTopSpacer)
        bottomBar = findViewById(R.id.bottomBar)
        contentScroll.post { updateContentPosition(hasMedia = false) }
        linkInput.doAfterTextChanged { updatePasteAction(it?.isNotEmpty() == true) }
        linkInput.setOnEditorActionListener { _, actionId, event ->
            val requested = actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (requested) parsePost()
            requested
        }
        updatePasteAction(linkInput.text?.isNotEmpty() == true)
        mediaCarousel.thumbnailBinder = { image, url ->
            if (url.isBlank()) {
                image.animate().cancel()
                image.alpha = 0f
                image.setImageDrawable(null)
            } else {
                image.load(url) {
                    crossfade(false)
                    placeholder(null)
                    error(null)
                    listener(
                        onStart = {
                            image.animate().cancel()
                            image.alpha = 0f
                            image.setImageDrawable(null)
                        },
                        onSuccess = { _, _ -> image.alpha = 1f },
                        onError = { _, _ ->
                            image.setImageDrawable(null)
                            image.alpha = 0f
                        },
                    )
                }
            }
        }
        mediaCarousel.onSelectionChanged = { item, selected ->
            if (selected) selectedUrls += item.id else selectedUrls -= item.id
            updateSelectionUi()
        }
        mediaCarousel.onItemClick = { item ->
            if (item.selectable && !item.downloaded) {
                if (selectedUrls.contains(item.id)) selectedUrls -= item.id else selectedUrls += item.id
                post?.let { showMedia(it, resetCarousel = false) }
            }
        }
        progress = findViewById(R.id.progress)
        findViewById<View>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        pasteButton.setOnClickListener { pasteFromClipboard() }
        parseButton.setOnClickListener { parsePost() }
        selectAllButton.setOnClickListener { toggleSelectAll() }
        downloadButton.setOnClickListener { enqueueSelectedDownloads() }
    }

    private fun autoFillFromClipboard() {
        if (!::linkInput.isInitialized || !hasWindowFocus()) return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val url = firstShareUrl(clipboardText(clipboard))
        val current = linkInput.text?.toString()?.trim().orEmpty()
        val mayReplace = current.isBlank() || current == lastClipboardUrl
        if (url != null && url != current && mayReplace) {
            lastClipboardUrl = url
            linkInput.setText(url)
            linkInput.setSelection(url.length)
        }
    }

    private fun fillFromSharedIntent(intent: Intent?) {
        if (!::linkInput.isInitialized || intent == null) return
        val sharedText = when {
            intent.action == Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            intent.dataString != null -> intent.dataString
            else -> null
        }.orEmpty()
        val url = firstShareUrl(sharedText) ?: return
        lastClipboardUrl = url
        linkInput.setText(url)
        linkInput.setSelection(url.length)
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
        if (url != null) lastClipboardUrl = url
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
        return candidates.firstOrNull { url ->
            url.contains("weibo.com", true) || url.contains("weibo.cn", true) || url.contains("t.cn/", true)
        }
    }

    private fun parsePost() {
        val url = linkInput.text?.toString()?.trim().orEmpty()
        if (url.isBlank()) return toast(getString(R.string.link_required))
        refreshSession()
        val quality = QualityPreference.fromStored(preferences.getString("quality", QualityPreference.HIGHEST.key).orEmpty())
        busy(true, getString(R.string.reading_post))
        executor.execute {
            try {
                val includeLiveCover = preferences.getBoolean("live_cover", false)
                val loaded = WeiboClient(sessionCookie, this, includeLiveCover).loadPost(url, quality)
                runOnUiThread {
                    post = loaded
                    selectedUrls.clear()
                    val history = DownloadHistory(this)
                    selectedUrls += loaded.media.filterNot { history.contains(it) }.map { it.url }
                    showMedia(loaded)
                    val available = selectedUrls.size
                    busy(false, getString(R.string.identified_format, loaded.media.size, available, loaded.author))
                }
            } catch (error: WeiboLoginRequiredException) {
                runOnUiThread {
                    busy(false, error.message ?: getString(R.string.login_required_settings))
                    toast(getString(R.string.login_required_settings))
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
            } catch (error: Exception) {
                runOnUiThread {
                    post = null
                    selectedUrls.clear()
                    mediaCarousel.submitItems(emptyList())
                    mediaCarousel.visibility = View.GONE
                    updateSelectionUi()
                    busy(false, error.message ?: getString(R.string.read_failed))
                }
            }
        }
    }

    private fun showMedia(value: WeiboPost, resetCarousel: Boolean = true) {
        val history = DownloadHistory(this)
        val carouselItems = value.media.mapIndexed { index, item ->
            val recorded = history.contains(item)
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
                title = "${index + 1}. ${getString(item.type.labelRes)} · ${getString(MediaQuality.labelRes(item.quality))}",
                subtitle = item.suggestedFileName(),
                selected = !recorded && selectedUrls.contains(item.url),
                selectable = !recorded,
                downloaded = recorded,
            )
        }
        if (resetCarousel) {
            mediaCarousel.submitItems(carouselItems)
        } else {
            mediaCarousel.updateItems(carouselItems)
        }
        mediaCarousel.visibility = if (carouselItems.isEmpty()) View.GONE else View.VISIBLE
        updateSelectionUi()
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
        val history = DownloadHistory(this)
        val eligible = current.media.filterNot { history.contains(it) }.map { it.url }
        if (eligible.isNotEmpty() && selectedUrls.containsAll(eligible)) selectedUrls.removeAll(eligible.toSet()) else selectedUrls.addAll(eligible)
        showMedia(current, resetCarousel = false)
    }

    private fun updateSelectionUi() {
        val current = post
        val history = if (current != null) DownloadHistory(this) else null
        val eligible = current?.media?.filterNot { history?.contains(it) == true }?.map { it.url }.orEmpty()
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
            val result = DownloadDispatcher(this, sessionCookie).enqueueSelected(current, chosen, destinationTreeUri)
            selectedUrls.removeAll(chosen.map { it.url }.toSet())
            showMedia(current, resetCarousel = false)
            summaryText.text = if (result.queued > 0) {
                getString(R.string.download_enqueued_format, result.queued, result.alreadyRecorded)
            } else {
                getString(R.string.all_selected_recorded)
            }
            toast(if (result.queued > 0) getString(R.string.download_started_format, result.queued) else getString(R.string.nothing_to_repeat))
        } catch (error: Exception) { toast(error.message ?: getString(R.string.create_download_failed)) }
    }

    private fun refreshSession() {
        sessionCookie = WeiboSession.cookieHeader()
    }

    private fun busy(active: Boolean, message: String) {
        progress.visibility = if (active) View.VISIBLE else View.GONE
        parseButton.isEnabled = !active
        pasteButton.isEnabled = !active
        summaryText.visibility = View.VISIBLE
        summaryText.text = message
    }

    private fun applySystemInsets() {
        val root = findViewById<View>(R.id.rootLayout)
        val toolbar = findViewById<View>(R.id.toolbar)
        val scroll = findViewById<View>(R.id.contentScroll)
        val bottomBar = findViewById<View>(R.id.bottomBar)
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
}

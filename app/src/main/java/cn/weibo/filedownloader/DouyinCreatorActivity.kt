package cn.weibo.filedownloader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.CircularProgressIndicator
import java.util.ArrayDeque
import java.util.concurrent.Executors

class DouyinCreatorActivity : Activity() {
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(ThemeController.wrap(newBase)) }

    private val executor = Executors.newSingleThreadExecutor()
    private val douyinRepository = DouyinCreatorRepository()
    private lateinit var bilibiliRepository: BilibiliCreatorRepository
    private lateinit var telegramRepository: TelegramCreatorRepository
    private lateinit var xRepository: XCreatorRepository
    private var webLoader: DouyinCreatorWebLoader? = null
    private var bilibiliWebLoader: BilibiliCreatorWebLoader? = null
    private lateinit var creator: CreatorProfile
    private lateinit var adapter: CreatorWorksAdapter
    private lateinit var worksList: RecyclerView
    private lateinit var state: View
    private lateinit var stateText: TextView
    private lateinit var progress: CircularProgressIndicator
    private lateinit var retry: MaterialButton
    private lateinit var quickDownloadBar: View
    private lateinit var quickDownloadButton: MaterialButton
    private var quickDownloadClient: DouyinClient? = null
    private val quickDownloadQueue = ArrayDeque<DouyinCreatorWork>()
    private var quickDownloadTotal = 0
    private var quickDownloadProcessed = 0
    private var quickDownloadQueued = 0
    private var quickDownloadFailures = 0
    private var quickDownloading = false
    private var cursor = 0L
    private var bilibiliOffset = ""
    private var telegramBefore = ""
    private var xCursor = ""
    private var hasMore = true
    private var loading = false
    private var usingWebFallback = false
    private var usingBilibiliWebFallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val creatorId = intent.getStringExtra(EXTRA_CREATOR_ID).orEmpty()
        val creatorName = intent.getStringExtra(EXTRA_CREATOR_NAME).orEmpty()
        val creatorPlatform = intent.getStringExtra(EXTRA_CREATOR_PLATFORM)
            ?.let { key -> Platform.entries.firstOrNull { it.key == key } }
            ?: Platform.DOUYIN
        if (creatorId.isBlank() || creatorName.isBlank()) {
            finish()
            return
        }
        creator = CreatorProfile(
            platform = creatorPlatform,
            id = creatorId,
            name = creatorName,
            avatarUrl = intent.getStringExtra(EXTRA_CREATOR_AVATAR).orEmpty(),
            profileUrl = intent.getStringExtra(EXTRA_CREATOR_URL).orEmpty().ifBlank {
                when (creatorPlatform) {
                    Platform.BILIBILI -> "https://space.bilibili.com/$creatorId/dynamic"
                    Platform.DOUYIN -> "https://www.douyin.com/user/$creatorId"
                    Platform.TELEGRAM -> "https://t.me/$creatorId"
                    Platform.X -> "https://x.com/$creatorId"
                    Platform.WEIBO -> ""
                }
            },
        )
        if (creatorPlatform == Platform.WEIBO) {
            finish()
            return
        }
        bilibiliRepository = BilibiliCreatorRepository(this)
        telegramRepository = TelegramCreatorRepository(this)
        xRepository = XCreatorRepository(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        configureTransparentSystemBars()
        setContentView(R.layout.activity_douyin_creator)
        bindViews()
        bindCreator(creator)
        applyInsets()
        loadMore()
    }

    private fun bindViews() {
        findViewById<View>(R.id.creatorBackButton).setOnClickListener { finish() }
        worksList = findViewById(R.id.creatorWorksList)
        state = findViewById(R.id.creatorState)
        stateText = findViewById(R.id.creatorStateText)
        progress = findViewById(R.id.creatorProgress)
        retry = findViewById(R.id.creatorRetry)
        quickDownloadBar = findViewById(R.id.creatorDownloadBar)
        quickDownloadButton = findViewById(R.id.creatorQuickDownload)
        adapter = CreatorWorksAdapter(
            ::openWork,
            ::updateQuickDownloadSelection,
        ) { work ->
            DownloadHistory(this).downloadedCount(work.id, work.mediaCount)
        }
        val columns = if (resources.configuration.screenWidthDp >= 600) 3 else 2
        val layoutManager = GridLayoutManager(this, columns)
        worksList.layoutManager = layoutManager
        worksList.adapter = adapter
        worksList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || loading || !hasMore) return
                if (layoutManager.findLastVisibleItemPosition() >= adapter.itemCount - columns * 2) loadMore()
            }
        })
        retry.setOnClickListener {
            if (adapter.itemCount == 0) {
                cursor = 0L
                bilibiliOffset = ""
                telegramBefore = ""
                xCursor = ""
                hasMore = true
                if (creator.platform == Platform.BILIBILI && usingBilibiliWebFallback) {
                    loading = true
                    showLoading()
                    startBilibiliWebFallback()
                    return@setOnClickListener
                }
                if (creator.platform == Platform.DOUYIN && usingWebFallback) {
                    loading = true
                    showLoading()
                    startWebFallback()
                    return@setOnClickListener
                }
            }
            loadMore()
        }
        findViewById<MaterialButton>(R.id.openCreatorInPlatform).apply {
            setText(
                when (creator.platform) {
                    Platform.BILIBILI -> R.string.open_in_bilibili
                    Platform.TELEGRAM -> R.string.open_in_telegram
                    Platform.X -> R.string.open_in_x
                    else -> R.string.open_in_douyin
                },
            )
        }.setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(creator.profileUrl))) }
        }
        quickDownloadButton.setOnClickListener { startQuickDownload() }
    }

    private fun bindCreator(value: CreatorProfile) {
        creator = value
        findViewById<TextView>(R.id.creatorProfileName).text = value.name
        findViewById<ImageView>(R.id.creatorProfileAvatar).load(value.avatarUrl.takeIf(String::isNotBlank)) {
            transformations(CircleCropTransformation())
            placeholder(R.drawable.ic_account_circle_rounded)
            error(R.drawable.ic_account_circle_rounded)
        }
    }

    private fun loadMore() {
        if (loading || !hasMore) return
        if (creator.platform == Platform.X) {
            loadMoreFromX()
            return
        }
        if (creator.platform == Platform.TELEGRAM) {
            loadMoreFromTelegram()
            return
        }
        if (creator.platform == Platform.BILIBILI) {
            loadMoreFromBilibili()
            return
        }
        loading = true
        if (adapter.itemCount == 0) showLoading()
        if (!usingWebFallback && adapter.itemCount == 0 && DouyinSession.isSignedIn()) {
            startWebFallback()
            return
        }
        if (usingWebFallback) {
            webLoader?.loadMore(::handleWebResult)
                ?: handleWebResult(Result.failure(IllegalStateException("Creator page is not open")))
            return
        }
        val requestedCursor = cursor
        executor.execute {
            val result = runCatching { douyinRepository.loadPage(creator, requestedCursor) }
            runOnUiThread {
                result.fold(
                    onSuccess = { page ->
                        loading = false
                        applyPage(page, cursorAdvanced = page.nextCursor != requestedCursor)
                    },
                    onFailure = { error ->
                        Log.i(LOG_TAG, "Direct creator request unavailable; trying the public page: ${error.message}")
                        startWebFallback()
                    },
                )
            }
        }
    }

    private fun loadMoreFromX() {
        loading = true
        if (adapter.itemCount == 0) showLoading()
        val requestedCursor = xCursor
        executor.execute {
            val result = runCatching { xRepository.loadPage(creator, requestedCursor) }
            runOnUiThread {
                loading = false
                result.fold(
                    onSuccess = { page ->
                        bindCreator(page.creator)
                        adapter.append(page.works)
                        xCursor = page.nextCursor
                        hasMore = page.hasMore && page.nextCursor != requestedCursor
                        when {
                            adapter.itemCount > 0 -> state.visibility = View.GONE
                            else -> showMessage(R.string.creator_empty, canRetry = false)
                        }
                    },
                    onFailure = { error ->
                        Log.w(LOG_TAG, "Could not load X creator posts", error)
                        hasMore = false
                        showMessage(R.string.creator_load_failed, canRetry = true)
                    },
                )
            }
        }
    }

    private fun loadMoreFromTelegram() {
        loading = true
        if (adapter.itemCount == 0) showLoading()
        val requestedBefore = telegramBefore
        executor.execute {
            val result = runCatching { telegramRepository.loadPage(creator, requestedBefore) }
            runOnUiThread {
                loading = false
                result.fold(
                    onSuccess = { page ->
                        bindCreator(page.creator)
                        adapter.append(page.works)
                        telegramBefore = page.nextBefore
                        hasMore = page.hasMore && page.nextBefore != requestedBefore
                        when {
                            adapter.itemCount > 0 -> state.visibility = View.GONE
                            else -> showMessage(R.string.creator_empty, canRetry = false)
                        }
                    },
                    onFailure = { error ->
                        Log.w(LOG_TAG, "Could not load Telegram channel posts", error)
                        hasMore = false
                        showMessage(R.string.creator_load_failed, canRetry = true)
                    },
                )
            }
        }
    }

    private fun loadMoreFromBilibili() {
        loading = true
        if (adapter.itemCount == 0) showLoading()
        if (usingBilibiliWebFallback) {
            bilibiliWebLoader?.loadMore(::handleBilibiliWebResult)
                ?: handleBilibiliWebResult(Result.failure(IllegalStateException("Bilibili creator page is not open")))
            return
        }
        val requestedOffset = bilibiliOffset
        executor.execute {
            val result = runCatching { bilibiliRepository.loadPage(creator, requestedOffset) }
            runOnUiThread {
                loading = false
                result.fold(
                    onSuccess = { page ->
                        if (page.works.isEmpty() && adapter.itemCount == 0 && !usingBilibiliWebFallback) {
                            startBilibiliWebFallback()
                            return@fold
                        }
                        bindCreator(page.creator)
                        adapter.append(page.works)
                        bilibiliOffset = page.nextOffset
                        hasMore = page.hasMore && page.nextOffset.isNotBlank() && page.nextOffset != requestedOffset
                        when {
                            adapter.itemCount > 0 -> state.visibility = View.GONE
                            else -> showMessage(R.string.creator_empty, canRetry = false)
                        }
                    },
                    onFailure = { error ->
                        Log.i(LOG_TAG, "Direct Bilibili creator request unavailable; trying its space page: ${error.message}")
                        startBilibiliWebFallback()
                    },
                )
            }
        }
    }

    private fun startBilibiliWebFallback() {
        usingBilibiliWebFallback = true
        bilibiliWebLoader?.close()
        bilibiliWebLoader = BilibiliCreatorWebLoader(this, creator).also { loader ->
            loader.loadInitial(::handleBilibiliWebResult)
        }
    }

    private fun handleBilibiliWebResult(result: Result<BilibiliCreatorPage>) {
        loading = false
        result.fold(
            onSuccess = { page ->
                bindCreator(page.creator)
                adapter.append(page.works)
                bilibiliOffset = page.nextOffset
                hasMore = page.hasMore
                when {
                    adapter.itemCount > 0 -> state.visibility = View.GONE
                    else -> showMessage(R.string.creator_empty, canRetry = false)
                }
            },
            onFailure = { error ->
                Log.w(LOG_TAG, "Could not load creator works from the Bilibili space page", error)
                hasMore = false
                showMessage(R.string.creator_load_failed, canRetry = true)
            },
        )
    }

    private fun startWebFallback() {
        usingWebFallback = true
        webLoader?.close()
        webLoader = DouyinCreatorWebLoader(this, creator).also { loader ->
            loader.loadInitial(::handleWebResult)
        }
    }

    private fun handleWebResult(result: Result<DouyinCreatorPage>) {
        loading = false
        result.fold(
            onSuccess = { page -> applyPage(page, cursorAdvanced = true) },
            onFailure = { error ->
                Log.w(LOG_TAG, "Could not load creator works from the public page", error)
                hasMore = false
                showMessage(R.string.creator_load_failed, canRetry = true)
            },
        )
    }

    private fun applyPage(page: DouyinCreatorPage, cursorAdvanced: Boolean) {
        bindCreator(page.creator)
        adapter.append(page.works)
        cursor = page.nextCursor
        hasMore = page.hasMore && cursorAdvanced
        Log.d(LOG_TAG, "Creator works accumulated=${adapter.itemCount}, source=${page.works.size}, hasMore=$hasMore")
        when {
            adapter.itemCount > 0 -> state.visibility = View.GONE
            else -> showMessage(R.string.creator_empty, canRetry = false)
        }
    }

    private fun showLoading() {
        state.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        stateText.setText(R.string.creator_loading)
        retry.visibility = View.GONE
    }

    private fun showMessage(message: Int, canRetry: Boolean) {
        if (adapter.itemCount > 0) return
        state.visibility = View.VISIBLE
        progress.visibility = View.GONE
        stateText.setText(message)
        retry.visibility = if (canRetry) View.VISIBLE else View.GONE
    }

    private fun openWork(work: DouyinCreatorWork) {
        if (quickDownloading) return
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_CREATOR_WORK)
                .putExtra(MainActivity.EXTRA_CREATOR_WORK_URL, work.sourceUrl),
        )
    }

    private fun updateQuickDownloadSelection(count: Int) {
        if (quickDownloading) return
        quickDownloadBar.visibility = if (count > 0) View.VISIBLE else View.GONE
        quickDownloadButton.text = if (count > 0) {
            getString(R.string.creator_quick_download_count, count)
        } else {
            getString(R.string.creator_quick_download)
        }
    }

    private fun startQuickDownload() {
        if (quickDownloading) return
        val selected = adapter.selectedWorks()
        if (selected.isEmpty()) return
        quickDownloading = true
        quickDownloadQueue.clear()
        quickDownloadQueue.addAll(selected)
        quickDownloadTotal = selected.size
        quickDownloadProcessed = 0
        quickDownloadQueued = 0
        quickDownloadFailures = 0
        adapter.setInteractionsEnabled(false)
        quickDownloadButton.isEnabled = false
        resolveNextQuickDownload()
    }

    private fun resolveNextQuickDownload() {
        val work = if (quickDownloadQueue.isEmpty()) null else quickDownloadQueue.removeFirst()
        if (work == null) {
            finishQuickDownload()
            return
        }
        quickDownloadButton.text = getString(
            R.string.creator_quick_download_progress,
            quickDownloadProcessed + 1,
            quickDownloadTotal,
        )
        if (creator.platform == Platform.BILIBILI) {
            executor.execute {
                val result = runCatching {
                    BilibiliClient(BilibiliSession.cookieHeader(), this).loadPost(work.sourceUrl)
                }
                runOnUiThread { handleResolvedQuickDownload(work, result) }
            }
            return
        }
        if (creator.platform == Platform.TELEGRAM) {
            executor.execute {
                val result = runCatching { TelegramClient(this).loadPost(work.sourceUrl) }
                runOnUiThread { handleResolvedQuickDownload(work, result) }
            }
            return
        }
        if (creator.platform == Platform.X) {
            executor.execute {
                val result = runCatching { XClient(this).loadPost(work.sourceUrl) }
                runOnUiThread { handleResolvedQuickDownload(work, result) }
            }
            return
        }
        val client = DouyinClient(this)
        quickDownloadClient = client
        client.loadPost(work.sourceUrl, QualityPreference.HIGHEST) { result ->
            runOnUiThread {
                if (!quickDownloading || isFinishing || isDestroyed) {
                    client.close()
                    return@runOnUiThread
                }
                client.close()
                if (quickDownloadClient === client) quickDownloadClient = null
                handleResolvedQuickDownload(work, result)
            }
        }
    }

    private fun handleResolvedQuickDownload(work: DouyinCreatorWork, result: Result<WeiboPost>) {
        if (!quickDownloading || isFinishing || isDestroyed) return
        result.fold(
            onSuccess = ::enqueueQuickDownloadPost,
            onFailure = {
                quickDownloadFailures++
                Log.w(LOG_TAG, "Could not resolve selected work ${work.id}", it)
            },
        )
        quickDownloadProcessed++
        resolveNextQuickDownload()
    }

    private fun enqueueQuickDownloadPost(post: WeiboPost) {
        val history = DownloadHistory(this)
        val freshMedia = post.media.filterNot(history::contains)
        if (freshMedia.isEmpty()) return
        try {
            val destinationTreeUri = DownloadDestination.uri(
                getSharedPreferences("downloader_settings", MODE_PRIVATE),
                creator.platform,
            )
            val cookie = when (creator.platform) {
                Platform.DOUYIN -> DouyinSession.cookieHeader()
                Platform.BILIBILI -> BilibiliSession.cookieHeader()
                Platform.TELEGRAM -> ""
                Platform.X -> ""
                Platform.WEIBO -> ""
            }
            quickDownloadQueued += DownloadDispatcher(this, cookie, creator.platform)
                .enqueueSelected(post, freshMedia, destinationTreeUri)
                .queued
        } catch (error: Exception) {
            quickDownloadFailures++
            Log.w(LOG_TAG, "Could not enqueue selected work ${post.id}", error)
        }
    }

    private fun finishQuickDownload() {
        quickDownloading = false
        quickDownloadClient = null
        adapter.clearSelection()
        adapter.setInteractionsEnabled(true)
        quickDownloadButton.isEnabled = true
        updateQuickDownloadSelection(0)
        val message = when {
            quickDownloadQueued > 0 && quickDownloadFailures > 0 -> getString(
                R.string.creator_quick_download_partial,
                quickDownloadQueued,
                quickDownloadFailures,
            )
            quickDownloadQueued > 0 -> getString(R.string.creator_quick_download_complete, quickDownloadQueued)
            quickDownloadFailures > 0 -> getString(R.string.creator_quick_download_failed)
            else -> getString(R.string.creator_quick_download_no_new_media)
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun applyInsets() {
        val root = findViewById<View>(R.id.creatorRoot)
        val toolbar = findViewById<View>(R.id.creatorToolbar)
        val downloadBar = findViewById<View>(R.id.creatorDownloadBar)
        val toolbarPadding = intArrayOf(toolbar.paddingLeft, toolbar.paddingTop, toolbar.paddingRight, toolbar.paddingBottom)
        val listPadding = intArrayOf(worksList.paddingLeft, worksList.paddingTop, worksList.paddingRight, worksList.paddingBottom)
        val downloadPadding = intArrayOf(downloadBar.paddingLeft, downloadBar.paddingTop, downloadBar.paddingRight, downloadBar.paddingBottom)
        val toolbarMinHeight = toolbar.minimumHeight
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            toolbar.minimumHeight = toolbarMinHeight + safe.top
            toolbar.setPadding(toolbarPadding[0] + safe.left, toolbarPadding[1] + safe.top, toolbarPadding[2] + safe.right, toolbarPadding[3])
            worksList.setPadding(listPadding[0] + safe.left, listPadding[1], listPadding[2] + safe.right, listPadding[3] + safe.bottom)
            downloadBar.setPadding(downloadPadding[0] + safe.left, downloadPadding[1], downloadPadding[2] + safe.right, downloadPadding[3] + safe.bottom)
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
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = ThemeController.isLight(this@DouyinCreatorActivity)
            isAppearanceLightNavigationBars = ThemeController.isLight(this@DouyinCreatorActivity)
        }
    }

    override fun onDestroy() {
        quickDownloading = false
        quickDownloadClient?.close()
        webLoader?.close()
        bilibiliWebLoader?.close()
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val LOG_TAG = "DouyinCreator"
        private const val EXTRA_CREATOR_ID = "creator_id"
        private const val EXTRA_CREATOR_NAME = "creator_name"
        private const val EXTRA_CREATOR_AVATAR = "creator_avatar"
        private const val EXTRA_CREATOR_URL = "creator_url"
        private const val EXTRA_CREATOR_PLATFORM = "creator_platform"

        fun createIntent(context: Context, creator: CreatorProfile): Intent =
            Intent(context, DouyinCreatorActivity::class.java)
                .putExtra(EXTRA_CREATOR_PLATFORM, creator.platform.key)
                .putExtra(EXTRA_CREATOR_ID, creator.id)
                .putExtra(EXTRA_CREATOR_NAME, creator.name)
                .putExtra(EXTRA_CREATOR_AVATAR, creator.avatarUrl)
                .putExtra(EXTRA_CREATOR_URL, creator.profileUrl)
    }
}

private class CreatorWorksAdapter(
    private val onClick: (DouyinCreatorWork) -> Unit,
    private val onSelectionChanged: (Int) -> Unit,
    private val downloadedCount: (DouyinCreatorWork) -> Int,
) : RecyclerView.Adapter<CreatorWorksAdapter.Holder>() {
    private val items = mutableListOf<DouyinCreatorWork>()
    private val ids = hashSetOf<String>()
    private val selectedIds = linkedSetOf<String>()
    private var interactionsEnabled = true

    fun append(values: List<DouyinCreatorWork>) {
        val fresh = values.filter { ids.add(it.id) }
        if (fresh.isEmpty()) return
        val start = items.size
        items += fresh
        notifyItemRangeInserted(start, fresh.size)
    }

    fun selectedWorks(): List<DouyinCreatorWork> = items.filter { it.id in selectedIds }

    fun clearSelection() {
        if (selectedIds.isEmpty()) return
        selectedIds.clear()
        notifyItemRangeChanged(0, itemCount)
        onSelectionChanged(0)
    }

    fun setInteractionsEnabled(enabled: Boolean) {
        if (interactionsEnabled == enabled) return
        interactionsEnabled = enabled
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_douyin_creator_work, parent, false),
    )

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.cover.load(item.coverUrl.takeIf(String::isNotBlank)) {
            crossfade(true)
            placeholder(R.drawable.ic_media_placeholder)
            error(R.drawable.ic_media_placeholder)
        }
        holder.title.text = item.title.ifBlank { holder.itemView.context.getString(R.string.creator_untitled_work) }
        holder.type.text = if (item.isGallery) {
            if (item.mediaCount > 1) {
                holder.itemView.context.getString(R.string.creator_gallery_count, item.mediaCount)
            } else {
                holder.itemView.context.getString(R.string.creator_gallery)
            }
        } else {
            holder.itemView.context.getString(R.string.creator_video)
        }
        val downloaded = downloadedCount(item)
        val total = item.mediaCount.takeIf { it > 0 }?.toString() ?: "?"
        holder.downloaded.text = holder.itemView.context.getString(
            R.string.creator_downloaded_count,
            downloaded,
            total,
        )
        holder.downloaded.contentDescription = holder.itemView.context.getString(
            R.string.creator_downloaded_count_description,
            downloaded,
            total,
        )
        val selected = item.id in selectedIds
        renderSelection(holder, selected)
        holder.selection.isEnabled = interactionsEnabled
        holder.selection.setOnClickListener {
            if (!interactionsEnabled) return@setOnClickListener
            val nowSelected = if (selectedIds.add(item.id)) true else {
                selectedIds.remove(item.id)
                false
            }
            renderSelection(holder, nowSelected)
            holder.bindingAdapterPosition
                .takeIf { it != RecyclerView.NO_POSITION }
                ?.let(::notifyItemChanged)
            onSelectionChanged(selectedIds.size)
        }
        holder.itemView.setOnClickListener { if (interactionsEnabled) onClick(item) }
    }

    private fun renderSelection(holder: Holder, selected: Boolean) {
        holder.selection.isSelected = selected
        holder.selection.refreshDrawableState()
        holder.selection.contentDescription = holder.itemView.context.getString(
            if (selected) R.string.deselect_creator_work else R.string.select_creator_work,
        )
        holder.card.strokeWidth = if (selected) {
            (2 * holder.itemView.resources.displayMetrics.density).toInt()
        } else {
            0
        }
        holder.card.strokeColor = MaterialColors.getColor(
            holder.card,
            com.google.android.material.R.attr.colorPrimary,
        )
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.creatorWorkCard)
        val cover: ImageView = view.findViewById(R.id.creatorWorkCover)
        val title: TextView = view.findViewById(R.id.creatorWorkTitle)
        val type: TextView = view.findViewById(R.id.creatorWorkType)
        val downloaded: TextView = view.findViewById(R.id.creatorWorkDownloaded)
        val selection: ImageButton = view.findViewById(R.id.creatorWorkSelected)
    }
}

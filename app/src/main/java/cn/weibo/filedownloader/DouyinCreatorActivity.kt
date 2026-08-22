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
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import java.util.concurrent.Executors

class DouyinCreatorActivity : Activity() {
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(ThemeController.wrap(newBase)) }

    private val executor = Executors.newSingleThreadExecutor()
    private val repository = DouyinCreatorRepository()
    private var webLoader: DouyinCreatorWebLoader? = null
    private lateinit var creator: CreatorProfile
    private lateinit var adapter: CreatorWorksAdapter
    private lateinit var worksList: RecyclerView
    private lateinit var state: View
    private lateinit var stateText: TextView
    private lateinit var progress: CircularProgressIndicator
    private lateinit var retry: MaterialButton
    private var cursor = 0L
    private var hasMore = true
    private var loading = false
    private var usingWebFallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val creatorId = intent.getStringExtra(EXTRA_CREATOR_ID).orEmpty()
        val creatorName = intent.getStringExtra(EXTRA_CREATOR_NAME).orEmpty()
        if (creatorId.isBlank() || creatorName.isBlank()) {
            finish()
            return
        }
        creator = CreatorProfile(
            platform = Platform.DOUYIN,
            id = creatorId,
            name = creatorName,
            avatarUrl = intent.getStringExtra(EXTRA_CREATOR_AVATAR).orEmpty(),
            profileUrl = intent.getStringExtra(EXTRA_CREATOR_URL).orEmpty()
                .ifBlank { "https://www.douyin.com/user/$creatorId" },
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        configureTransparentSystemBars()
        setContentView(R.layout.activity_douyin_creator)
        bindViews()
        bindCreator(creator)
        applyInsets()
        loadMore()
    }

    private fun bindViews() {
        findViewById<MaterialToolbar>(R.id.creatorToolbar).setNavigationOnClickListener { finish() }
        worksList = findViewById(R.id.creatorWorksList)
        state = findViewById(R.id.creatorState)
        stateText = findViewById(R.id.creatorStateText)
        progress = findViewById(R.id.creatorProgress)
        retry = findViewById(R.id.creatorRetry)
        adapter = CreatorWorksAdapter(::openWork)
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
                hasMore = true
                if (usingWebFallback) {
                    loading = true
                    showLoading()
                    startWebFallback()
                    return@setOnClickListener
                }
            }
            loadMore()
        }
        findViewById<MaterialButton>(R.id.openCreatorInDouyin).setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(creator.profileUrl))) }
        }
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
        loading = true
        if (adapter.itemCount == 0) showLoading()
        if (usingWebFallback) {
            webLoader?.loadMore(::handleWebResult)
                ?: handleWebResult(Result.failure(IllegalStateException("Creator page is not open")))
            return
        }
        val requestedCursor = cursor
        executor.execute {
            val result = runCatching { repository.loadPage(creator, requestedCursor) }
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
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_DOUYIN_WORK)
                .putExtra(MainActivity.EXTRA_DOUYIN_WORK_URL, work.sourceUrl)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    private fun applyInsets() {
        val root = findViewById<View>(R.id.creatorRoot)
        val toolbar = findViewById<View>(R.id.creatorToolbar)
        val toolbarPadding = intArrayOf(toolbar.paddingLeft, toolbar.paddingTop, toolbar.paddingRight, toolbar.paddingBottom)
        val listPadding = intArrayOf(worksList.paddingLeft, worksList.paddingTop, worksList.paddingRight, worksList.paddingBottom)
        val toolbarMinHeight = toolbar.minimumHeight
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            toolbar.minimumHeight = toolbarMinHeight + safe.top
            toolbar.setPadding(toolbarPadding[0] + safe.left, toolbarPadding[1] + safe.top, toolbarPadding[2] + safe.right, toolbarPadding[3])
            worksList.setPadding(listPadding[0] + safe.left, listPadding[1], listPadding[2] + safe.right, listPadding[3] + safe.bottom)
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
        webLoader?.close()
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val LOG_TAG = "DouyinCreator"
        private const val EXTRA_CREATOR_ID = "creator_id"
        private const val EXTRA_CREATOR_NAME = "creator_name"
        private const val EXTRA_CREATOR_AVATAR = "creator_avatar"
        private const val EXTRA_CREATOR_URL = "creator_url"

        fun createIntent(context: Context, creator: CreatorProfile): Intent =
            Intent(context, DouyinCreatorActivity::class.java)
                .putExtra(EXTRA_CREATOR_ID, creator.id)
                .putExtra(EXTRA_CREATOR_NAME, creator.name)
                .putExtra(EXTRA_CREATOR_AVATAR, creator.avatarUrl)
                .putExtra(EXTRA_CREATOR_URL, creator.profileUrl)
    }
}

private class CreatorWorksAdapter(
    private val onClick: (DouyinCreatorWork) -> Unit,
) : RecyclerView.Adapter<CreatorWorksAdapter.Holder>() {
    private val items = mutableListOf<DouyinCreatorWork>()
    private val ids = hashSetOf<String>()

    fun append(values: List<DouyinCreatorWork>) {
        val fresh = values.filter { ids.add(it.id) }
        if (fresh.isEmpty()) return
        val start = items.size
        items += fresh
        notifyItemRangeInserted(start, fresh.size)
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
        holder.itemView.setOnClickListener { onClick(item) }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView = view.findViewById(R.id.creatorWorkCover)
        val title: TextView = view.findViewById(R.id.creatorWorkTitle)
        val type: TextView = view.findViewById(R.id.creatorWorkType)
    }
}

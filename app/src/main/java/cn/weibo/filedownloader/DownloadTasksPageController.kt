package cn.weibo.filedownloader

import android.app.Activity
import android.content.SharedPreferences
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator

internal class DownloadTasksPageController(
    private val activity: Activity,
    val root: View,
    private val onOpenWork: (ActiveDownloadWork) -> Unit,
    private val onAvailabilityChanged: (Boolean) -> Unit,
) {
    private val store = DownloadStatusStore(activity)
    private val preferences = activity.getSharedPreferences(DownloadStatusStore.PREFERENCES, Activity.MODE_PRIVATE)
    private val list: RecyclerView = root.findViewById(R.id.downloadWorksList)
    private val empty: View = root.findViewById(R.id.downloadWorksEmpty)
    private val toolbar: View = root.findViewById(R.id.downloadTasksToolbar)
    private val adapter = DownloadWorksAdapter(onOpenWork)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == DownloadStatusStore.STATE_KEY) list.post(::render)
    }

    init {
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = adapter
        applyInsets()
    }

    fun start() {
        preferences.registerOnSharedPreferenceChangeListener(listener)
        render()
    }

    fun stop() {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun render() {
        val works = store.activeWorks()
        adapter.submitList(works)
        onAvailabilityChanged(works.isNotEmpty())
        empty.visibility = if (works.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (works.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun applyInsets() {
        val toolbarPadding = intArrayOf(toolbar.paddingLeft, toolbar.paddingTop, toolbar.paddingRight, toolbar.paddingBottom)
        val listPadding = intArrayOf(list.paddingLeft, list.paddingTop, list.paddingRight, list.paddingBottom)
        val toolbarMinHeight = toolbar.minimumHeight
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            toolbar.minimumHeight = toolbarMinHeight + safe.top
            toolbar.setPadding(toolbarPadding[0] + safe.left, toolbarPadding[1] + safe.top, toolbarPadding[2] + safe.right, toolbarPadding[3])
            list.setPadding(listPadding[0] + safe.left, listPadding[1], listPadding[2] + safe.right, listPadding[3] + safe.bottom)
            insets
        }
        root.doOnAttach { ViewCompat.requestApplyInsets(it) }
    }
}

private class DownloadWorksAdapter(
    private val onClick: (ActiveDownloadWork) -> Unit,
) : ListAdapter<ActiveDownloadWork, DownloadWorksAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_download_work, parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        val context = holder.itemView.context
        holder.platform.text = item.platform.shortName
        holder.platform.setTextColor(ContextCompat.getColor(context, item.platform.prefixColor))
        holder.author.text = item.author
        holder.title.text = item.title.ifBlank {
            context.getString(R.string.download_work_fallback_title, item.author)
        }
        holder.thumbnail.load(item.thumbnailUrl.takeIf(String::isNotBlank)) {
            crossfade(false)
            placeholder(R.drawable.ic_media_placeholder)
            error(R.drawable.ic_media_placeholder)
        }
        val percent = item.percent
        holder.progress.isIndeterminate = percent == null
        if (percent != null) holder.progress.progress = percent
        holder.progress.setIndicatorColor(ContextCompat.getColor(context, item.platform.prefixColor))
        holder.progress.trackColor = MaterialColors.getColor(
            holder.progress,
            com.google.android.material.R.attr.colorSurfaceContainerHighest,
        )
        holder.status.text = if (percent == null) {
            context.getString(R.string.download_work_preparing, item.completedFiles, item.totalFiles)
        } else {
            context.getString(R.string.download_work_progress, item.completedFiles, item.totalFiles, percent)
        }
        holder.itemView.setOnClickListener { onClick(item) }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.downloadWorkThumbnail)
        val platform: TextView = view.findViewById(R.id.downloadWorkPlatform)
        val author: TextView = view.findViewById(R.id.downloadWorkAuthor)
        val title: TextView = view.findViewById(R.id.downloadWorkTitle)
        val status: TextView = view.findViewById(R.id.downloadWorkStatus)
        val progress: LinearProgressIndicator = view.findViewById(R.id.downloadWorkProgress)
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<ActiveDownloadWork>() {
            override fun areItemsTheSame(oldItem: ActiveDownloadWork, newItem: ActiveDownloadWork): Boolean =
                oldItem.key == newItem.key

            override fun areContentsTheSame(oldItem: ActiveDownloadWork, newItem: ActiveDownloadWork): Boolean =
                oldItem == newItem
        }
    }
}

package cn.weibo.filedownloader

import android.content.Context
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.UUID

class DownloadDispatcher(
    private val context: Context,
    private val cookie: String,
    private val platform: Platform = Platform.WEIBO,
) {
    data class Result(val queued: Int)

    fun enqueueAll(post: WeiboPost, destinationTreeUri: String?): Result =
        enqueue(post, post.media, destinationTreeUri)

    fun enqueueSelected(post: WeiboPost, mediaItems: List<MediaItem>, destinationTreeUri: String?): Result =
        enqueue(post, mediaItems, destinationTreeUri)

    private fun enqueue(post: WeiboPost, mediaItems: List<MediaItem>, destinationTreeUri: String?): Result {
        val folderName = DownloadOrganization.folderName(context, platform, post.author, post.id)
        val includeLiveCover = context
            .getSharedPreferences("downloader_settings", Context.MODE_PRIVATE)
            .getBoolean("live_cover", false)
        val downloads = mediaItems.flatMap { media ->
            DownloadMediaPlanner.expand(media, includeLiveCover).mapIndexed { index, download ->
                PlannedDownload(download, media.stableId.takeIf { index == 0 }.orEmpty())
            }
        }
        if (downloads.isEmpty()) return Result(0)
        val statusStore = DownloadStatusStore(context)
        val workKey = "${platform.key}:${post.id}"
        ActiveDownloadPostCache.put(workKey, post)
        val thumbnailUrl = post.media.firstNotNullOfOrNull { media ->
            media.thumbnailUrl.ifBlank { media.coverUrl }.ifBlank {
                media.url.takeIf { media.type == MediaType.IMAGE }.orEmpty()
            }.takeIf(String::isNotBlank)
        }.orEmpty()
        statusStore.registerWork(
            workKey = workKey,
            platform = platform,
            sourceUrl = post.sourceUrl,
            author = post.author,
            title = post.text.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim(),
            thumbnailUrl = thumbnailUrl,
            addedFiles = downloads.size,
        )
        var queued = 0
        downloads.forEach { planned ->
            val download = planned.media
            var registeredWorkId: String? = null
            try {
                val workId = UUID.randomUUID()
                val input = Data.Builder()
                    .putString(SafDownloadWorker.KEY_URL, download.url)
                    .putString(SafDownloadWorker.KEY_AUDIO_URL, download.audioUrl)
                    .putString(SafDownloadWorker.KEY_COOKIE, if (platform in setOf(Platform.WEIBO, Platform.REDDIT)) "" else cookie)
                    .putString(SafDownloadWorker.KEY_PLATFORM, platform.key)
                    .putString(SafDownloadWorker.KEY_TREE_URI, destinationTreeUri)
                    .putString(SafDownloadWorker.KEY_FOLDER, folderName)
                    .putString(SafDownloadWorker.KEY_FILE_NAME, download.suggestedFileName())
                    .putString(SafDownloadWorker.KEY_MEDIA_TYPE, download.type.name)
                    .putString(SafDownloadWorker.KEY_REFERER, platform.referer)
                    .putString(SafDownloadWorker.KEY_STATUS_WORK_ID, workId.toString())
                    .build()
                val request = OneTimeWorkRequestBuilder<SafDownloadWorker>()
                    .setId(workId)
                    .setInputData(input)
                    .build()
                statusStore.registerWorkerTask(
                    workId.toString(),
                    workKey,
                    DownloadHistoryKeys.stable(download),
                    planned.mediaIdentity,
                )
                registeredWorkId = workId.toString()
                WorkManager.getInstance(context).enqueue(request)
                queued++
            } catch (_: Exception) {
                registeredWorkId?.let(statusStore::failWorker) ?: statusStore.dropUnqueuedWorkItem(workKey)
            }
        }
        return Result(queued)
    }

    private data class PlannedDownload(val media: MediaItem, val mediaIdentity: String)

    private val Platform.referer: String
        get() = when (this) {
            Platform.WEIBO -> "https://weibo.com/"
            Platform.DOUYIN -> "https://www.douyin.com/"
            Platform.BILIBILI -> "https://www.bilibili.com/"
            Platform.TELEGRAM -> "https://t.me/"
            Platform.X -> "https://x.com/"
            Platform.INSTAGRAM -> "https://www.instagram.com/"
            Platform.XIAOHONGSHU -> "https://www.xiaohongshu.com/"
            Platform.REDDIT -> "https://www.reddit.com/"
        }
}

internal object DownloadMediaPlanner {
    fun expand(media: MediaItem, includeLiveCover: Boolean): List<MediaItem> = buildList {
        add(media)
        if (includeLiveCover && media.type == MediaType.LIVE_PHOTO && media.coverUrl.isNotBlank()) {
            add(
                media.copy(
                    type = MediaType.IMAGE,
                    url = media.coverUrl,
                    quality = MediaQuality.ORIGINAL_BEST,
                    coverUrl = "",
                ),
            )
        }
    }
}

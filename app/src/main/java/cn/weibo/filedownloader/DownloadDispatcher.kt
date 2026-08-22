package cn.weibo.filedownloader

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

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
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val folderName = DownloadOrganization.folderName(context, post.author, post.id)
        val defaultFolder = folderName
        val history = DownloadHistory(context)
        var queued = 0
        mediaItems.forEach { media ->
            val downloads = buildList {
                add(media)
                if (media.type == MediaType.LIVE_PHOTO && media.coverUrl.isNotBlank()) {
                    add(media.copy(type = MediaType.IMAGE, url = media.coverUrl, quality = MediaQuality.ORIGINAL_BEST, coverUrl = ""))
                }
            }
            downloads.forEach { download ->
                if (destinationTreeUri.isNullOrBlank()) {
                    val request = DownloadManager.Request(Uri.parse(download.url))
                        .setTitle(download.suggestedFileName())
                        .setDescription("${context.getString(download.type.labelRes)} · ${context.getString(MediaQuality.labelRes(download.quality))}")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setAllowedOverMetered(true)
                        .setAllowedOverRoaming(false)
                        .addRequestHeader("User-Agent", USER_AGENT)
                        .addRequestHeader("Referer", platform.referer)
                        .addRequestHeader("Cookie", cookie)
                        .setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS,
                            "$defaultFolder/${download.suggestedFileName()}",
                        )
                    manager.enqueue(request)
                } else {
                    val input = Data.Builder()
                        .putString(SafDownloadWorker.KEY_URL, download.url)
                        .putString(SafDownloadWorker.KEY_COOKIE, cookie)
                        .putString(SafDownloadWorker.KEY_TREE_URI, destinationTreeUri)
                        .putString(SafDownloadWorker.KEY_FOLDER, folderName)
                        .putString(SafDownloadWorker.KEY_FILE_NAME, download.suggestedFileName())
                        .putString(SafDownloadWorker.KEY_MEDIA_TYPE, download.type.name)
                        .putString(SafDownloadWorker.KEY_REFERER, platform.referer)
                        .build()
                    val request = OneTimeWorkRequestBuilder<SafDownloadWorker>()
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .setInputData(input)
                        .build()
                    WorkManager.getInstance(context).enqueue(request)
                }
                history.remember(download)
                queued++
            }
        }
        history.save()
        return Result(queued)
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36"
    }

    private val Platform.referer: String
        get() = when (this) {
            Platform.WEIBO -> "https://weibo.com/"
            Platform.DOUYIN -> "https://www.douyin.com/"
        }
}

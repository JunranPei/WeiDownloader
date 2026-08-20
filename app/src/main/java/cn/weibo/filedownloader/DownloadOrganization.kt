package cn.weibo.filedownloader

import android.content.Context

object DownloadOrganization {
    const val PREFERENCE = "download_organization"
    const val SINGLE_FOLDER = "single_folder"
    const val PER_POST_FOLDER = "per_post_folder"
    const val ROOT_FOLDER = "WeiDownloader"

    fun folderName(context: Context, author: String, postId: String): String {
        val mode = context.getSharedPreferences("downloader_settings", Context.MODE_PRIVATE)
            .getString(PREFERENCE, SINGLE_FOLDER)
        if (mode != PER_POST_FOLDER) return ROOT_FOLDER
        return "$ROOT_FOLDER/${safeSegment(author)}_${safeSegment(postId)}"
    }

    private fun safeSegment(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .take(40)
        .ifBlank { "Weibo User" }
}

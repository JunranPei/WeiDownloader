package cn.weibo.filedownloader

import android.content.Context

object DownloadOrganization {
    const val PREFERENCE = "download_organization"
    const val SINGLE_FOLDER = "single_folder"
    const val PER_POST_FOLDER = "per_post_folder"
    const val ROOT_FOLDER = "AllDownloader"

    fun folderName(context: Context, platform: Platform, author: String, postId: String): String {
        val mode = context.getSharedPreferences("downloader_settings", Context.MODE_PRIVATE)
            .getString(PREFERENCE, SINGLE_FOLDER)
        return folderName(platform, mode, author, postId)
    }

    internal fun folderName(platform: Platform, mode: String?, author: String, postId: String): String {
        val platformFolder = "$ROOT_FOLDER/${safeSegment(platform.downloadFolderName)}"
        if (mode != PER_POST_FOLDER) return platformFolder
        return "$platformFolder/${safeSegment(author)}_${safeSegment(postId)}"
    }

    private fun safeSegment(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .take(40)
        .ifBlank { "Unknown Creator" }
}

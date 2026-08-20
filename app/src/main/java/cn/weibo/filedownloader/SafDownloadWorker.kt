package cn.weibo.filedownloader

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class SafDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {
    override fun doWork(): Result {
        val sourceUrl = inputData.getString(KEY_URL) ?: return Result.failure()
        val treeUri = inputData.getString(KEY_TREE_URI)?.let(Uri::parse) ?: return Result.failure()
        val folderName = inputData.getString(KEY_FOLDER) ?: return Result.failure()
        val fileName = inputData.getString(KEY_FILE_NAME) ?: return Result.failure()
        val mediaType = inputData.getString(KEY_MEDIA_TYPE).orEmpty()
        val cookie = inputData.getString(KEY_COOKIE).orEmpty()
        val referer = inputData.getString(KEY_REFERER).orEmpty()

        return try {
            val root = DocumentFile.fromTreeUri(applicationContext, treeUri)
                ?.takeIf { it.canWrite() }
                ?: return Result.failure()
            val folder = folderName.split('/').filter { it.isNotBlank() }.fold(root) { parent, segment ->
                parent.findFile(segment)?.takeIf { it.isDirectory }
                    ?: parent.createDirectory(segment)
                    ?: throw IOException("Cannot create download folder")
            }
            val existing = folder.findFile(fileName)
            val target = existing ?: folder.createFile(mimeType(fileName, mediaType), fileName)
                ?: return Result.failure()
            download(sourceUrl, cookie, referer, target.uri)
            Result.success()
        } catch (error: IOException) {
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        } catch (_: SecurityException) {
            Result.failure()
        }
    }

    private fun download(sourceUrl: String, cookie: String, referer: String, destination: Uri) {
        val connection = (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Referer", referer.ifBlank { REFERER })
            if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("HTTP $status")
            connection.inputStream.use { input ->
                applicationContext.contentResolver.openOutputStream(destination, "w")?.use { output ->
                    input.copyTo(output, BUFFER_SIZE)
                } ?: throw IOException("Cannot open selected destination")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun mimeType(fileName: String, mediaType: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: when (mediaType) {
                MediaType.IMAGE.name -> "image/jpeg"
                MediaType.AUDIO.name -> "audio/mpeg"
                else -> "video/mp4"
            }
    }

    companion object {
        const val KEY_URL = "url"
        const val KEY_COOKIE = "cookie"
        const val KEY_TREE_URI = "tree_uri"
        const val KEY_FOLDER = "folder"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_MEDIA_TYPE = "media_type"
        const val KEY_REFERER = "referer"

        private const val MAX_RETRIES = 3
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val BUFFER_SIZE = 64 * 1024
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36"
        private const val REFERER = "https://weibo.com/"
    }
}

package cn.weibo.filedownloader

import android.annotation.TargetApi
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

class SafDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {
    override fun doWork(): Result {
        val statusWorkId = inputData.getString(KEY_STATUS_WORK_ID) ?: id.toString()
        val sourceUrl = inputData.getString(KEY_URL) ?: return terminalFailure(statusWorkId)
        val treeUri = inputData.getString(KEY_TREE_URI)?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        val folderName = inputData.getString(KEY_FOLDER) ?: return terminalFailure(statusWorkId)
        val fileName = inputData.getString(KEY_FILE_NAME) ?: return terminalFailure(statusWorkId)
        val mediaType = inputData.getString(KEY_MEDIA_TYPE).orEmpty()
        val cookie = inputData.getString(KEY_COOKIE).orEmpty()
        val audioUrl = inputData.getString(KEY_AUDIO_URL).orEmpty()
        val referer = inputData.getString(KEY_REFERER).orEmpty()

        var destination: DownloadDestination? = null
        return try {
            destination = createDestination(treeUri, folderName, fileName, mediaType)
            if (audioUrl.isNotBlank()) {
                downloadDash(sourceUrl, audioUrl, cookie, referer, destination, statusWorkId)
            } else {
                download(sourceUrl, cookie, referer, destination, statusWorkId)
            }
            destination.commit()
            DownloadStatusStore(applicationContext).completeWorker(statusWorkId)
            Result.success()
        } catch (error: IOException) {
            destination?.discard()
            if (runAttemptCount < MAX_RETRIES) Result.retry() else terminalFailure(statusWorkId)
        } catch (_: SecurityException) {
            destination?.discard()
            terminalFailure(statusWorkId)
        } catch (_: Exception) {
            destination?.discard()
            terminalFailure(statusWorkId)
        }
    }

    private fun createDestination(
        treeUri: Uri?,
        folderName: String,
        fileName: String,
        mediaType: String,
    ): DownloadDestination {
        val mimeType = mimeType(fileName, mediaType)
        if (treeUri != null) return createSafDestination(treeUri, folderName, fileName, mimeType)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createMediaStoreDestination(folderName, fileName, mimeType)
        } else {
            createLegacyDownloadsDestination(folderName, fileName)
        }
    }

    private fun createSafDestination(
        treeUri: Uri,
        folderName: String,
        fileName: String,
        mimeType: String,
    ): DownloadDestination {
        val root = DocumentFile.fromTreeUri(applicationContext, treeUri)
            ?.takeIf { it.canWrite() }
            ?: throw IOException("Cannot write to selected destination")
        val folder = folderName.split('/').filter { it.isNotBlank() }.fold(root) { parent, segment ->
            parent.findFile(segment)?.takeIf { it.isDirectory }
                ?: parent.createDirectory(segment)
                ?: throw IOException("Cannot create download folder")
        }
        val target = folder.findFile(fileName) ?: folder.createFile(mimeType, fileName)
            ?: throw IOException("Cannot create download file")
        return DownloadDestination(
            openOutput = {
                applicationContext.contentResolver.openOutputStream(target.uri, "w")
                    ?: throw IOException("Cannot open selected destination")
            },
        )
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun createMediaStoreDestination(
        folderName: String,
        fileName: String,
        mimeType: String,
    ): DownloadDestination {
        val resolver = applicationContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$folderName")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Cannot create download file")
        return DownloadDestination(
            openOutput = {
                resolver.openOutputStream(target, "w") ?: throw IOException("Cannot open download file")
            },
            commitAction = {
                resolver.update(
                    target,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null,
                    null,
                )
            },
            discardAction = { resolver.delete(target, null, null) },
        )
    }

    @Suppress("DEPRECATION")
    private fun createLegacyDownloadsDestination(folderName: String, fileName: String): DownloadDestination {
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val folder = File(root, folderName)
        if (!folder.exists() && !folder.mkdirs()) throw IOException("Cannot create download folder")
        val target = File(folder, fileName)
        return DownloadDestination(
            openOutput = { FileOutputStream(target, false) },
            discardAction = { target.delete() },
        )
    }

    private fun terminalFailure(statusWorkId: String): Result {
        DownloadStatusStore(applicationContext).failWorker(statusWorkId)
        return Result.failure()
    }

    private fun download(
        sourceUrl: String,
        cookie: String,
        referer: String,
        destination: DownloadDestination,
        statusWorkId: String,
        progressOffset: Long = 0L,
        moreTracks: Boolean = false,
    ): Long {
        val weiboRequest = inputData.getString(KEY_PLATFORM) == Platform.WEIBO.key ||
            inputData.getString(KEY_PLATFORM) == null && referer == REFERER
        val redditRequest = inputData.getString(KEY_PLATFORM) == Platform.REDDIT.key
        val connection = if (weiboRequest) WeiboSession.openConnection(sourceUrl,
            headers = mapOf("Referer" to referer.ifBlank { REFERER }, "Accept-Encoding" to "identity"),
            connectTimeout = CONNECT_TIMEOUT_MS, readTimeout = READ_TIMEOUT_MS)
        else (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            // Bilibili DASH URLs must use the same desktop identity as the playurl request.
            setRequestProperty("User-Agent", if (referer == BilibiliHttpClient.BILIBILI_REFERER)
                BilibiliHttpClient.USER_AGENT else USER_AGENT)
            setRequestProperty("Referer", referer.ifBlank { REFERER })
            setRequestProperty("Accept-Encoding", "identity")
            if (redditRequest) setRequestProperty("Accept", "image/*,video/*,audio/*,application/octet-stream;q=0.9")
            if (cookie.isNotBlank() && !redditRequest) setRequestProperty("Cookie", cookie)
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("HTTP $status")
            if (redditRequest && connection.contentType.orEmpty().let {
                    it.startsWith("text/", true) || it.contains("json", true)
                }) throw IOException("Reddit returned a page instead of media")
            val totalBytes = if (moreTracks || connection.contentLengthLong <= 0L) -1L
                else progressOffset + connection.contentLengthLong
            val statusStore = DownloadStatusStore(applicationContext)
            statusStore.updateWorkerTaskProgress(statusWorkId, progressOffset, totalBytes)
            connection.inputStream.use { input ->
                destination.openOutput().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var downloadedBytes = progressOffset
                    var lastReportedAt = 0L
                    while (true) {
                        if (isStopped) throw IOException("Download stopped")
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloadedBytes += count
                        val now = System.currentTimeMillis()
                        if (now - lastReportedAt >= PROGRESS_UPDATE_INTERVAL_MS) {
                            statusStore.updateWorkerTaskProgress(statusWorkId, downloadedBytes, totalBytes)
                            lastReportedAt = now
                        }
                    }
                    statusStore.updateWorkerTaskProgress(statusWorkId, downloadedBytes, totalBytes)
                    return downloadedBytes
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadDash(
        videoUrl: String,
        audioUrl: String,
        cookie: String,
        referer: String,
        destination: DownloadDestination,
        statusWorkId: String,
    ) {
        val temporary = mutableListOf<File>()
        fun temporaryFile(suffix: String): File =
            File.createTempFile("dash-", suffix, applicationContext.cacheDir).also(temporary::add)
        try {
            val video = temporaryFile("-video.m4s")
            val audio = temporaryFile("-audio.m4s")
            val merged = temporaryFile(".mp4")
            val videoBytes = download(videoUrl, cookie, referer,
                DownloadDestination(openOutput = { FileOutputStream(video) }), statusWorkId,
                moreTracks = true)
            download(audioUrl, cookie, referer,
                DownloadDestination(openOutput = { FileOutputStream(audio) }), statusWorkId,
                progressOffset = videoBytes)
            DashMediaMuxer.mux(video, audio, merged) { isStopped }
            merged.inputStream().use { input ->
                destination.openOutput().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (isStopped) throw IOException("Download stopped")
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
        } finally {
            temporary.forEach { it.delete() }
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

    private class DownloadDestination(
        val openOutput: () -> OutputStream,
        private val commitAction: () -> Unit = {},
        private val discardAction: () -> Unit = {},
    ) {
        fun commit() = commitAction()
        fun discard() = runCatching { discardAction() }.let { Unit }
    }

    companion object {
        const val KEY_URL = "url"
        const val KEY_AUDIO_URL = "audio_url"
        const val KEY_COOKIE = "cookie"
        const val KEY_PLATFORM = "platform"
        const val KEY_TREE_URI = "tree_uri"
        const val KEY_FOLDER = "folder"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_MEDIA_TYPE = "media_type"
        const val KEY_REFERER = "referer"
        const val KEY_STATUS_WORK_ID = "status_work_id"

        private const val MAX_RETRIES = 3
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_UPDATE_INTERVAL_MS = 500L
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36"
        private const val REFERER = "https://weibo.com/"
    }
}

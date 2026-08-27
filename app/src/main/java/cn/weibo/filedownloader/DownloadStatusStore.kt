package cn.weibo.filedownloader

import android.content.Context
import org.json.JSONObject

internal data class ActiveMediaDownload(
    val downloadedBytes: Long,
    val totalBytes: Long,
) {
    val percent: Int? = DownloadProgressMath.percent(downloadedBytes, totalBytes)
}

internal object DownloadProgressMath {
    fun percent(downloadedBytes: Long, totalBytes: Long): Int? {
        if (totalBytes <= 0L) return null
        return ((downloadedBytes.coerceIn(0L, totalBytes) * 100L) / totalBytes).toInt()
    }
}

/** Stores only the per-file state needed by media-card progress and download history. */
internal class DownloadStatusStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun registerWorkerTask(workId: String, historyKey: String, mediaIdentity: String) {
        synchronized(LOCK) {
            val root = readState()
            tasks(root).put(
                workerTaskKey(workId),
                JSONObject()
                    .put(KEY_HISTORY_KEY, historyKey)
                    .put(KEY_MEDIA_IDENTITY, mediaIdentity)
                    .put(KEY_CREATED_AT, System.currentTimeMillis())
                    .put(KEY_DOWNLOADED_BYTES, 0L)
                    .put(KEY_TOTAL_BYTES, -1L),
            )
            writeState(root)
        }
    }

    fun completeWorker(workId: String) = resolveWorker(workId, succeeded = true)

    fun failWorker(workId: String) = resolveWorker(workId, succeeded = false)

    fun successVersion(): Long = synchronized(LOCK) {
        readState().optLong(KEY_SUCCESS_VERSION)
    }

    fun activeMediaDownload(mediaIdentity: String): ActiveMediaDownload? = synchronized(LOCK) {
        if (mediaIdentity.isBlank()) return@synchronized null
        val taskValues = tasks(readState())
        var latest: JSONObject? = null
        for (key in taskValues.keys()) {
            val task = taskValues.optJSONObject(key) ?: continue
            if (task.optString(KEY_MEDIA_IDENTITY) != mediaIdentity) continue
            if (latest == null || task.optLong(KEY_CREATED_AT) >= latest.optLong(KEY_CREATED_AT)) latest = task
        }
        latest?.let {
            ActiveMediaDownload(
                downloadedBytes = it.optLong(KEY_DOWNLOADED_BYTES).coerceAtLeast(0L),
                totalBytes = it.optLong(KEY_TOTAL_BYTES, -1L),
            )
        }
    }

    fun updateWorkerTaskProgress(workId: String, downloadedBytes: Long, totalBytes: Long) {
        synchronized(LOCK) {
            val root = readState()
            val task = tasks(root).optJSONObject(workerTaskKey(workId)) ?: return
            val safeDownloaded = downloadedBytes.coerceAtLeast(0L)
            val safeTotal = totalBytes.takeIf { it > 0L } ?: -1L
            if (task.optLong(KEY_DOWNLOADED_BYTES) == safeDownloaded &&
                task.optLong(KEY_TOTAL_BYTES, -1L) == safeTotal
            ) return
            task.put(KEY_DOWNLOADED_BYTES, safeDownloaded)
            task.put(KEY_TOTAL_BYTES, safeTotal)
            writeState(root)
        }
    }

    private fun resolveWorker(workId: String, succeeded: Boolean) {
        synchronized(LOCK) {
            val root = readState()
            val taskValues = tasks(root)
            val taskKey = workerTaskKey(workId)
            val task = taskValues.optJSONObject(taskKey) ?: return
            if (succeeded) {
                task.optString(KEY_HISTORY_KEY).takeIf(String::isNotBlank)?.let { historyKey ->
                    DownloadHistory(appContext).apply {
                        rememberStableKey(historyKey, task.optString(KEY_MEDIA_IDENTITY))
                        save()
                    }
                }
                root.put(KEY_SUCCESS_VERSION, root.optLong(KEY_SUCCESS_VERSION) + 1L)
            }
            taskValues.remove(taskKey)
            writeState(root)
        }
    }

    private fun readState(): JSONObject = runCatching {
        JSONObject(preferences.getString(STATE_KEY, null) ?: "{}")
    }.getOrElse { JSONObject() }

    private fun writeState(root: JSONObject) {
        preferences.edit().putString(STATE_KEY, root.toString()).apply()
    }

    private fun tasks(root: JSONObject): JSONObject = root.optJSONObject(KEY_TASKS)
        ?: JSONObject().also { root.put(KEY_TASKS, it) }

    companion object {
        const val PREFERENCES = "download_status"
        const val STATE_KEY = "state"

        private const val KEY_TASKS = "tasks"
        private const val KEY_HISTORY_KEY = "history_key"
        private const val KEY_MEDIA_IDENTITY = "media_identity"
        private const val KEY_CREATED_AT = "created_at"
        private const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        private const val KEY_TOTAL_BYTES = "total_bytes"
        private const val KEY_SUCCESS_VERSION = "success_version"
        private const val WORKER_PREFIX = "worker:"
        private val LOCK = Any()

        private fun workerTaskKey(workId: String) = "$WORKER_PREFIX$workId"
    }
}

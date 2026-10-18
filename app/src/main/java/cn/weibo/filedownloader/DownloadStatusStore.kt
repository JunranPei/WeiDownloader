package cn.weibo.filedownloader

import android.content.Context
import org.json.JSONObject

internal data class ActiveMediaDownload(
    val downloadedBytes: Long,
    val totalBytes: Long,
) {
    val percent: Int? = DownloadProgressMath.percent(downloadedBytes, totalBytes)
}

internal data class ActiveDownloadWork(
    val key: String,
    val platform: Platform,
    val sourceUrl: String,
    val author: String,
    val title: String,
    val thumbnailUrl: String,
    val totalFiles: Int,
    val completedFiles: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val hasUnknownSize: Boolean,
) {
    val percent: Int? = if (hasUnknownSize) null else DownloadProgressMath.percent(downloadedBytes, totalBytes)
}

internal object DownloadProgressMath {
    fun percent(downloadedBytes: Long, totalBytes: Long): Int? {
        if (totalBytes <= 0L) return null
        return ((downloadedBytes.coerceIn(0L, totalBytes) * 100L) / totalBytes).toInt()
    }
}

/** Stores the active work and per-file state needed by download UI and history. */
internal class DownloadStatusStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun registerWork(
        workKey: String,
        platform: Platform,
        sourceUrl: String,
        author: String,
        title: String,
        thumbnailUrl: String,
        addedFiles: Int,
    ) {
        if (workKey.isBlank() || addedFiles <= 0) return
        synchronized(LOCK) {
            val root = readState()
            val workValues = works(root)
            val existing = workValues.optJSONObject(workKey)
            val work = existing ?: JSONObject()
                .put(KEY_CREATED_AT, System.currentTimeMillis())
                .put(KEY_COMPLETED_FILES, 0)
                .put(KEY_COMPLETED_BYTES, 0L)
                .put(KEY_COMPLETED_TOTAL_BYTES, 0L)
            work.put(KEY_PLATFORM, platform.key)
                .put(KEY_SOURCE_URL, sourceUrl)
                .put(KEY_AUTHOR, author)
                .put(KEY_TITLE, title)
                .put(KEY_THUMBNAIL_URL, thumbnailUrl)
                .put(KEY_TOTAL_FILES, work.optInt(KEY_TOTAL_FILES) + addedFiles)
            workValues.put(workKey, work)
            writeState(root)
        }
    }

    fun registerWorkerTask(workId: String, workKey: String, historyKey: String, mediaIdentity: String) {
        synchronized(LOCK) {
            val root = readState()
            tasks(root).put(
                workerTaskKey(workId),
                JSONObject()
                    .put(KEY_WORK_KEY, workKey)
                    .put(KEY_HISTORY_KEY, historyKey)
                    .put(KEY_MEDIA_IDENTITY, mediaIdentity)
                    .put(KEY_CREATED_AT, System.currentTimeMillis())
                    .put(KEY_DOWNLOADED_BYTES, 0L)
                    .put(KEY_TOTAL_BYTES, -1L),
            )
            writeState(root)
        }
    }

    fun dropUnqueuedWorkItem(workKey: String) {
        synchronized(LOCK) {
            val root = readState()
            val workValues = works(root)
            val work = workValues.optJSONObject(workKey) ?: return
            val total = (work.optInt(KEY_TOTAL_FILES) - 1).coerceAtLeast(0)
            work.put(KEY_TOTAL_FILES, total)
            if (total <= work.optInt(KEY_COMPLETED_FILES)) workValues.remove(workKey)
            writeState(root)
        }
    }

    fun completeWorker(workId: String) = resolveWorker(workId, succeeded = true)

    /** Recover a renewable intent for jobs queued by earlier builds, when their source record survives. */
    fun legacyLocator(workId: String, media: MediaItem): DownloadLocator? = synchronized(LOCK) {
        val root = readState()
        val task = tasks(root).optJSONObject(workerTaskKey(workId)) ?: return@synchronized null
        val workKey = task.optString(KEY_WORK_KEY)
        val work = works(root).optJSONObject(workKey) ?: return@synchronized null
        val platform = Platform.entries.firstOrNull { it.key == work.optString(KEY_PLATFORM) } ?: return@synchronized null
        val source = work.optString(KEY_SOURCE_URL).takeIf(String::isNotBlank) ?: return@synchronized null
        val postId = workKey.removePrefix("${platform.key}:").takeIf(String::isNotBlank) ?: return@synchronized null
        val history = task.optString(KEY_HISTORY_KEY)
        val stableId = history.substringBefore('|')
        val ordinal = stableId.substringAfterLast(':').toIntOrNull() ?: return@synchronized null
        val key = history.substringAfter('|', "")
        val cover = media.type == MediaType.IMAGE && task.has(KEY_MEDIA_IDENTITY) && task.optString(KEY_MEDIA_IDENTITY).isBlank()
        val identity = MediaSourceIdentity.keys(media).firstOrNull() ?: return@synchronized null
        DownloadLocator(platform, source, postId, if (cover) MediaType.LIVE_PHOTO else media.type, ordinal,
            identity, key, height = Regex("(\\d{2,5})p").find(key)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            codec = MediaSourceIdentity.codec(key), cover = cover)
    }

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
        latest?.toMediaDownload()
    }

    fun activeWorks(): List<ActiveDownloadWork> = synchronized(LOCK) {
        val root = readState()
        val taskValues = tasks(root)
        val workValues = works(root)
        val tasksByWork = mutableMapOf<String, MutableList<JSONObject>>()
        for (key in taskValues.keys()) {
            val task = taskValues.optJSONObject(key) ?: continue
            task.optString(KEY_WORK_KEY).takeIf(String::isNotBlank)?.let { workKey ->
                tasksByWork.getOrPut(workKey) { mutableListOf() } += task
            }
        }
        buildList {
            for (workKey in workValues.keys()) {
                val work = workValues.optJSONObject(workKey) ?: continue
                val activeTasks = tasksByWork[workKey].orEmpty()
                if (activeTasks.isEmpty()) continue
                val platform = Platform.entries.firstOrNull { it.key == work.optString(KEY_PLATFORM) } ?: Platform.WEIBO
                val completedBytes = work.optLong(KEY_COMPLETED_BYTES).coerceAtLeast(0L)
                val completedTotalBytes = work.optLong(KEY_COMPLETED_TOTAL_BYTES).coerceAtLeast(0L)
                val activeDownloaded = activeTasks.sumOf { it.optLong(KEY_DOWNLOADED_BYTES).coerceAtLeast(0L) }
                val knownActiveTotals = activeTasks.map { it.optLong(KEY_TOTAL_BYTES, -1L) }
                add(
                    ActiveDownloadWork(
                        key = workKey,
                        platform = platform,
                        sourceUrl = work.optString(KEY_SOURCE_URL),
                        author = work.optString(KEY_AUTHOR),
                        title = work.optString(KEY_TITLE),
                        thumbnailUrl = work.optString(KEY_THUMBNAIL_URL),
                        totalFiles = work.optInt(KEY_TOTAL_FILES).coerceAtLeast(activeTasks.size),
                        completedFiles = work.optInt(KEY_COMPLETED_FILES).coerceAtLeast(0),
                        downloadedBytes = completedBytes + activeDownloaded,
                        totalBytes = completedTotalBytes + knownActiveTotals.filter { it > 0L }.sum(),
                        hasUnknownSize = knownActiveTotals.any { it <= 0L },
                    ),
                )
            }
        }.sortedByDescending { workValues.optJSONObject(it.key)?.optLong(KEY_CREATED_AT) ?: 0L }
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
            val workKey = task.optString(KEY_WORK_KEY)
            val workValues = works(root)
            val work = workValues.optJSONObject(workKey)
            if (succeeded) {
                task.optString(KEY_HISTORY_KEY).takeIf(String::isNotBlank)?.let { historyKey ->
                    DownloadHistory(appContext).apply {
                        rememberStableKey(historyKey, task.optString(KEY_MEDIA_IDENTITY))
                        save()
                    }
                }
                if (work != null) {
                    work.put(KEY_COMPLETED_FILES, work.optInt(KEY_COMPLETED_FILES) + 1)
                    work.put(
                        KEY_COMPLETED_BYTES,
                        work.optLong(KEY_COMPLETED_BYTES) + task.optLong(KEY_DOWNLOADED_BYTES).coerceAtLeast(0L),
                    )
                    work.put(
                        KEY_COMPLETED_TOTAL_BYTES,
                        work.optLong(KEY_COMPLETED_TOTAL_BYTES) + task.optLong(KEY_TOTAL_BYTES).coerceAtLeast(0L),
                    )
                }
                root.put(KEY_SUCCESS_VERSION, root.optLong(KEY_SUCCESS_VERSION) + 1L)
            } else if (work != null) {
                work.put(KEY_TOTAL_FILES, (work.optInt(KEY_TOTAL_FILES) - 1).coerceAtLeast(0))
            }
            taskValues.remove(taskKey)
            if (work != null) {
                val hasActiveTasks = taskValues.keys().asSequence().any { key ->
                    taskValues.optJSONObject(key)?.optString(KEY_WORK_KEY) == workKey
                }
                if (!hasActiveTasks || work.optInt(KEY_COMPLETED_FILES) >= work.optInt(KEY_TOTAL_FILES)) {
                    workValues.remove(workKey)
                }
            }
            writeState(root)
        }
    }

    private fun JSONObject.toMediaDownload() = ActiveMediaDownload(
        downloadedBytes = optLong(KEY_DOWNLOADED_BYTES).coerceAtLeast(0L),
        totalBytes = optLong(KEY_TOTAL_BYTES, -1L),
    )

    private fun readState(): JSONObject = runCatching {
        JSONObject(preferences.getString(STATE_KEY, null) ?: "{}")
    }.getOrElse { JSONObject() }

    private fun writeState(root: JSONObject) {
        preferences.edit().putString(STATE_KEY, root.toString()).apply()
    }

    private fun tasks(root: JSONObject): JSONObject = root.optJSONObject(KEY_TASKS)
        ?: JSONObject().also { root.put(KEY_TASKS, it) }

    private fun works(root: JSONObject): JSONObject = root.optJSONObject(KEY_WORKS)
        ?: JSONObject().also { root.put(KEY_WORKS, it) }

    companion object {
        const val PREFERENCES = "download_status"
        const val STATE_KEY = "state"

        private const val KEY_TASKS = "tasks"
        private const val KEY_WORKS = "works"
        private const val KEY_WORK_KEY = "work_key"
        private const val KEY_PLATFORM = "platform"
        private const val KEY_SOURCE_URL = "source_url"
        private const val KEY_AUTHOR = "author"
        private const val KEY_TITLE = "title"
        private const val KEY_THUMBNAIL_URL = "thumbnail_url"
        private const val KEY_HISTORY_KEY = "history_key"
        private const val KEY_MEDIA_IDENTITY = "media_identity"
        private const val KEY_CREATED_AT = "created_at"
        private const val KEY_TOTAL_FILES = "total_files"
        private const val KEY_COMPLETED_FILES = "completed_files"
        private const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        private const val KEY_TOTAL_BYTES = "total_bytes"
        private const val KEY_COMPLETED_BYTES = "completed_bytes"
        private const val KEY_COMPLETED_TOTAL_BYTES = "completed_total_bytes"
        private const val KEY_SUCCESS_VERSION = "success_version"
        private const val WORKER_PREFIX = "worker:"
        private val LOCK = Any()

        private fun workerTaskKey(workId: String) = "$WORKER_PREFIX$workId"
    }
}

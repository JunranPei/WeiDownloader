package cn.weibo.filedownloader

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

/** Keeps a bounded local record of successfully downloaded media without storing source URLs or login data. */
class DownloadHistory(context: Context) {
    private val preferences = context.getSharedPreferences("download_history", Context.MODE_PRIVATE)
    private val key = "media_hashes"
    private val itemKey = "downloaded_items"
    private val entries = JSONObject(preferences.getString(key, "{}") ?: "{}")
    private val downloadedItems = JSONObject(preferences.getString(itemKey, "{}") ?: "{}")

    fun contains(media: MediaItem): Boolean {
        // Signed media URLs change between requests. A download therefore
        // belongs to the stable source item and selected source variant.
        if (entries.has(hash(DownloadHistoryKeys.stable(media)))) return true

        // Keep reading records written by builds that included the current
        // signed URL in their otherwise stable key.
        if (entries.has(hash(DownloadHistoryKeys.legacyComposite(media)))) return true

        // Oldest records used only the URL.
        return entries.has(hash(media.url))
    }

    fun remember(media: MediaItem) {
        rememberStableKey(DownloadHistoryKeys.stable(media), media.stableId)
    }

    internal fun rememberStableKey(stableKey: String, mediaIdentity: String = "") {
        val now = System.currentTimeMillis()
        entries.put(hash(stableKey), now)
        if (mediaIdentity.isNotBlank()) downloadedItems.put(hash(mediaIdentity), now)
    }

    fun downloadedCount(postId: String, expectedTotal: Int): Int {
        if (postId.isBlank()) return 0
        val upperBound = if (expectedTotal > 0) expectedTotal else MAX_UNKNOWN_POST_MEDIA
        return (1..upperBound).count { ordinal ->
            MediaType.entries.any { type -> downloadedItems.has(hash("${type.name}:$postId:$ordinal")) }
        }
    }

    fun save() {
        trim(entries)
        trim(downloadedItems)
        preferences.edit()
            .putString(key, entries.toString())
            .putString(itemKey, downloadedItems.toString())
            .apply()
    }

    private fun trim(values: JSONObject) {
        val all = values.keys().asSequence().toList()
        if (all.size > MAX_ENTRIES) {
            all.sortedBy { values.optLong(it) }.take(all.size - MAX_ENTRIES).forEach { values.remove(it) }
        }
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val MAX_ENTRIES = 2_000
        const val MAX_UNKNOWN_POST_MEDIA = 50
    }
}

internal object DownloadHistoryKeys {
    fun stable(media: MediaItem): String = listOf(
        media.stableId,
        media.selectedVariantKey ?: media.quality,
    ).joinToString("|")

    fun legacyComposite(media: MediaItem): String = listOf(
        media.stableId,
        media.selectedVariantKey.orEmpty(),
        media.url,
    ).joinToString("|")
}

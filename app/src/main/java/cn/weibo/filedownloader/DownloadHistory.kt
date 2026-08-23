package cn.weibo.filedownloader

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

/** Keeps a bounded local record of successfully queued media without storing source URLs or login data. */
class DownloadHistory(context: Context) {
    private val preferences = context.getSharedPreferences("download_history", Context.MODE_PRIVATE)
    private val key = "media_hashes"
    private val entries = JSONObject(preferences.getString(key, "{}") ?: "{}")

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
        entries.put(hash(DownloadHistoryKeys.stable(media)), System.currentTimeMillis())
    }

    fun save() {
        val all = entries.keys().asSequence().toList()
        if (all.size > MAX_ENTRIES) {
            all.sortedBy { entries.optLong(it) }.take(all.size - MAX_ENTRIES).forEach { entries.remove(it) }
        }
        preferences.edit().putString(key, entries.toString()).apply()
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object { const val MAX_ENTRIES = 2_000 }
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

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
        // New records distinguish the stable source item and the selected
        // source variant, so downloading mw2000 does not block the original.
        if (entries.has(hash(historyKey(media)))) return true

        // Keep reading records written by older builds. Their URL-only key
        // only matches when the currently selected URL is exactly the same.
        return entries.has(hash(media.url))
    }

    fun remember(media: MediaItem) {
        entries.put(hash(historyKey(media)), System.currentTimeMillis())
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

    private fun historyKey(media: MediaItem): String = listOf(
        media.stableId,
        media.selectedVariantKey.orEmpty(),
        media.url,
    ).joinToString("|")

    private companion object { const val MAX_ENTRIES = 2_000 }
}

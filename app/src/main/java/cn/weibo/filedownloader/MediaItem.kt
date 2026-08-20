package cn.weibo.filedownloader

import java.net.URI

enum class MediaType(val labelRes: Int) {
    IMAGE(R.string.media_image),
    LIVE_PHOTO(R.string.media_live_photo),
    VIDEO(R.string.media_video),
    AUDIO(R.string.media_audio),
}

enum class QualityPreference(val key: String, val labelRes: Int) {
    HIGHEST("highest", R.string.quality_highest),
    HIGH("high", R.string.quality_high),
    STANDARD("standard", R.string.quality_standard);

    companion object {
        fun fromStored(value: String): QualityPreference = entries.firstOrNull { it.key == value }
            ?: when (value) {
                "最高可用（推荐）", "Best available (recommended)" -> HIGHEST
                "高清", "High" -> HIGH
                "标准", "Standard" -> STANDARD
                else -> HIGHEST
            }
    }
}

object MediaQuality {
    const val ORIGINAL_BEST = "original_best"
    const val LIVE_ORIGINAL = "live_original"
    const val AUDIO_BEST = "audio_best"
    const val PLAYLIST_MAX = "playlist_max"
    const val HD_BEST = "hd_best"
    const val HIGH = "high"
    const val STANDARD = "standard"

    fun labelRes(key: String): Int = when (key) {
        ORIGINAL_BEST -> R.string.quality_original_best
        LIVE_ORIGINAL -> R.string.quality_live_original
        AUDIO_BEST -> R.string.quality_audio_best
        PLAYLIST_MAX -> R.string.quality_playlist_max
        HD_BEST -> R.string.quality_hd_best
        HIGH -> R.string.quality_high
        else -> R.string.quality_standard
    }
}

data class MediaItem(
    val type: MediaType,
    val url: String,
    val quality: String,
    val postId: String,
    val ordinal: Int,
    val thumbnailUrl: String = "",
    /** Optional still cover that belongs to a Live Photo, not a separate card. */
    val coverUrl: String = "",
) {
    fun suggestedFileName(): String {
        val extension = runCatching {
            val path = URI(url).path ?: ""
            path.substringAfterLast('.', "")
        }.getOrDefault("").lowercase()
        val safeExtension = when {
            extension.matches(Regex("[a-z0-9]{2,5}")) -> extension
            type == MediaType.IMAGE -> "jpg"
            type == MediaType.AUDIO -> "mp3"
            else -> "mp4"
        }
        val prefix = when (type) {
            MediaType.IMAGE -> "image"
            MediaType.LIVE_PHOTO -> "live"
            MediaType.VIDEO -> "video"
            MediaType.AUDIO -> "audio"
        }
        return "${prefix}_${postId}_${ordinal.toString().padStart(2, '0')}.$safeExtension"
    }
}

data class WeiboPost(
    val id: String,
    val text: String,
    val author: String,
    val sourceUrl: String,
    val media: List<MediaItem>,
)

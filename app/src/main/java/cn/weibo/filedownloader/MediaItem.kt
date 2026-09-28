package cn.weibo.filedownloader

import java.net.URI

enum class MediaType(val labelRes: Int) {
    IMAGE(R.string.media_image),
    LIVE_PHOTO(R.string.media_live_photo),
    VIDEO(R.string.media_video),
    AUDIO(R.string.media_audio),
}

enum class QualityPreference { HIGHEST }

object MediaQuality {
    const val ORIGINAL_BEST = "original_best"
    const val LIVE_ORIGINAL = "live_original"
    const val AUDIO_BEST = "audio_best"
    const val PLAYLIST_MAX = "playlist_max"
    const val HD_BEST = "hd_best"
    const val PLATFORM_BEST = "platform_best"
    fun labelRes(key: String): Int = when (key) {
        ORIGINAL_BEST -> R.string.quality_original_best
        LIVE_ORIGINAL -> R.string.quality_live_original
        AUDIO_BEST -> R.string.quality_audio_best
        PLAYLIST_MAX -> R.string.quality_playlist_max
        HD_BEST -> R.string.quality_hd_best
        PLATFORM_BEST -> R.string.quality_platform_best
        else -> R.string.quality_original_best
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
    /** Source variants returned for this image, including Weibo's /large/ original. */
    val variants: List<MediaVariant> = emptyList(),
    val selectedVariantKey: String? = null,
) {
    val stableId: String get() = "${type.name}:$postId:$ordinal"

    fun selectVariant(key: String): MediaItem {
        val variant = variants.firstOrNull { it.key == key } ?: return this
        return copy(url = variant.url, selectedVariantKey = variant.key)
    }

    fun suggestedFileName(): String {
        val extension = runCatching {
            val uri = URI(url)
            val pathExtension = uri.path.orEmpty().substringAfterLast('.', "")
            val queryFormat = Regex("(?:^|[/&])format[/=](jpg|jpeg|png|webp|heic|heif|avif)(?:[/&]|$)", RegexOption.IGNORE_CASE)
                .find(uri.rawQuery.orEmpty())
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()
            pathExtension.ifBlank { queryFormat }
        }.getOrDefault("").lowercase()
        val safeExtension = extension.takeIf { MediaFileTypes.acceptsExtension(type, it) }
            ?: MediaFileTypes.defaultExtension(type)
        val prefix = when (type) {
            MediaType.IMAGE -> "image"
            MediaType.LIVE_PHOTO -> "live"
            MediaType.VIDEO -> "video"
            MediaType.AUDIO -> "audio"
        }
        return "${prefix}_${postId}_${ordinal.toString().padStart(2, '0')}.$safeExtension"
    }
}

internal object MediaFileTypes {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "avif")
    private val videoExtensions = setOf("mp4", "mov", "m4v", "webm", "mkv", "m3u8")
    private val audioExtensions = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus")

    fun acceptsExtension(type: MediaType, extension: String): Boolean = when (type) {
        MediaType.IMAGE -> extension in imageExtensions
        MediaType.VIDEO, MediaType.LIVE_PHOTO -> extension in videoExtensions
        MediaType.AUDIO -> extension in audioExtensions
    }

    fun isKnownImagePath(path: String): Boolean = extensionOf(path) in imageExtensions
    fun isKnownVideoPath(path: String): Boolean = extensionOf(path) in videoExtensions
    fun isKnownAudioPath(path: String): Boolean = extensionOf(path) in audioExtensions

    fun defaultExtension(type: MediaType): String = when (type) {
        MediaType.IMAGE -> "jpg"
        MediaType.AUDIO -> "mp3"
        MediaType.VIDEO, MediaType.LIVE_PHOTO -> "mp4"
    }

    private fun extensionOf(path: String): String = path.substringAfterLast('.', "").lowercase()
}

data class MediaVariant(
    val key: String,
    val url: String,
    val width: Int = 0,
    val height: Int = 0,
    val bitrate: Long = 0L,
) {
    val pixelArea: Long get() = width.toLong() * height.toLong()
}

internal object MediaVariantSelector {
    data class InferredVideoQuality(
        val height: Int = 0,
        val isOriginal: Boolean = false,
        val tier: Int = 0,
    )

    fun rank(type: MediaType, variants: List<MediaVariant>): List<MediaVariant> {
        if (variants.size < 2 || type == MediaType.IMAGE) return variants
        val indexed = variants.withIndex()
        val ranked = when (type) {
            MediaType.VIDEO, MediaType.LIVE_PHOTO -> indexed.sortedWith(
                compareByDescending<IndexedValue<MediaVariant>> { it.value.pixelArea }
                    .thenByDescending { maxOf(it.value.width, it.value.height) }
                    .thenByDescending { it.value.bitrate }
                    .thenByDescending { inferVideoQuality(it.value.key, it.value.url).isOriginal }
                    .thenByDescending { inferVideoQuality(it.value.key, it.value.url).height }
                    .thenBy { it.index },
            )
            MediaType.AUDIO -> indexed.sortedWith(
                compareByDescending<IndexedValue<MediaVariant>> { it.value.bitrate }
                    .thenBy { it.index },
            )
            MediaType.IMAGE -> indexed.toList()
        }
        return ranked.map { it.value }
    }

    fun inferVideoQuality(vararg values: String): InferredVideoQuality {
        val text = values.joinToString(" ").lowercase()
        val isOriginal = listOf("original", "source", "origin", "原画", "原始").any { it in text }
        val explicitHeight = Regex("(?:^|[^0-9])(4320|2160|1440|1080|720|480|360)(?:p|[^0-9]|$)")
            .findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
            ?: when {
                Regex("(?:^|[^a-z0-9])8k(?:[^a-z0-9]|$)").containsMatchIn(text) -> 4320
                Regex("(?:^|[^a-z0-9])4k(?:[^a-z0-9]|$)").containsMatchIn(text) -> 2160
                Regex("(?:^|[^a-z0-9])2k(?:[^a-z0-9]|$)").containsMatchIn(text) -> 1440
                Regex("(?:^|[^a-z0-9])(?:fhd|full[_ -]?hd)(?:[^a-z0-9]|$)").containsMatchIn(text) || "超清" in text -> 1080
                Regex("(?:^|[^a-z0-9])hd(?:[^a-z0-9]|$)").containsMatchIn(text) || "高清" in text -> 720
                Regex("(?:^|[^a-z0-9])sd(?:[^a-z0-9]|$)").containsMatchIn(text) || "标清" in text -> 480
                "流畅" in text || "smooth" in text -> 360
                else -> 0
            }
        val tier = when {
            isOriginal -> 6
            explicitHeight >= 2160 -> 5
            explicitHeight >= 1080 -> 4
            explicitHeight >= 720 -> 3
            explicitHeight >= 480 -> 2
            explicitHeight > 0 -> 1
            else -> 0
        }
        return InferredVideoQuality(explicitHeight, isOriginal, tier)
    }
}

data class WeiboPost(
    val id: String,
    val text: String,
    val author: String,
    val sourceUrl: String,
    val media: List<MediaItem>,
    val creator: CreatorProfile? = null,
)

data class CreatorProfile(
    val platform: Platform,
    val id: String,
    val name: String,
    val avatarUrl: String = "",
    val profileUrl: String = "",
    /** Access token carried by XHS share URLs; empty for platforms that do not use one. */
    val accessToken: String = "",
    val seedPostId: String = "",
    val seedPostTitle: String = "",
    val seedPostCoverUrl: String = "",
    val seedPostUrl: String = "",
)

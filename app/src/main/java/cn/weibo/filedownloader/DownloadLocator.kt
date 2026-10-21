package cn.weibo.filedownloader

import org.json.JSONObject
import java.io.IOException
import java.net.URI

internal class DownloadIdentityException(message: String) : IOException(message)

/** Durable intent. Delivery URLs and login cookies deliberately do not belong here. */
internal data class DownloadLocator(
    val platform: Platform,
    val sourceUrl: String,
    val postId: String,
    val type: MediaType,
    val ordinal: Int,
    val identity: String,
    val variantKey: String,
    val width: Int = 0,
    val height: Int = 0,
    val codec: String = "",
    val cover: Boolean = false,
) {
    fun encode(): String = JSONObject().put("version", 1).put("platform", platform.key)
        .put("sourceUrl", sourceUrl).put("postId", postId).put("type", type.name)
        .put("ordinal", ordinal).put("identity", identity).put("variantKey", variantKey)
        .put("width", width).put("height", height).put("codec", codec).put("cover", cover).toString()

    fun select(post: WeiboPost): MediaItem {
        if (post.id != postId) throw DownloadIdentityException("Refreshed post identity changed")
        val matches = post.media.filter { it.postId == postId && it.type == type &&
            (identity in MediaSourceIdentity.keys(it) || cover && identity in MediaSourceIdentity.keys(it, true)) }
        val item = matches.singleOrNull()
            ?: throw DownloadIdentityException("Attachment is missing or ambiguous after refresh")
        if (cover) {
            return DownloadMediaPlanner.expand(item, true).getOrNull(1)
                ?: throw DownloadIdentityException("Live Photo still image is unavailable")
        }
        if (variantKey.isBlank()) return item
        val variants = item.variants.ifEmpty { listOf(MediaVariant(item.selectedVariantKey.orEmpty(), item.url,
            audioUrl = item.audioUrl)) }
        fun dimensionsMatch(v: MediaVariant): Boolean =
            (width <= 0 || v.width == width) && (height <= 0 || v.height == height) &&
                (codec.isBlank() || MediaSourceIdentity.codec(v) == codec) &&
                (!MediaVariantSelector.inferVideoQuality(variantKey).isOriginal ||
                    MediaVariantSelector.inferVideoQuality(v.key).isOriginal)
        val selected = variants.firstOrNull { it.key == variantKey && dimensionsMatch(it) }
            ?: variants.firstOrNull { (width > 0 || height > 0) && dimensionsMatch(it) }
            ?: throw DownloadIdentityException("Requested quality is unavailable after refresh")
        // Keep alternates for the chosen quality, not lower-resolution variants.
        val compatible = variants.filter { it.audioUrl == selected.audioUrl &&
            (selected.width <= 0 || it.width == selected.width) &&
            (selected.height <= 0 || it.height == selected.height) &&
            (MediaSourceIdentity.codec(selected).isBlank() ||
                MediaSourceIdentity.codec(it) == MediaSourceIdentity.codec(selected)) }
        val knownUrls = item.variants.map { it.url }.toSet()
        return item.copy(url = selected.url, audioUrl = selected.audioUrl, selectedVariantKey = selected.key,
            fallbackUrls = (compatible.map { it.url } + item.fallbackUrls.filterNot { it in knownUrls })
                .filterNot { it == selected.url }.distinct())
    }

    companion object {
        fun create(platform: Platform, post: WeiboPost, item: MediaItem, cover: Boolean): DownloadLocator {
            require(post.id == item.postId && post.sourceUrl.isNotBlank())
            val identity = MediaSourceIdentity.keys(item, false).firstOrNull()
                ?: throw DownloadIdentityException("Attachment has no renewable identity")
            val variant = item.variants.firstOrNull { it.url == item.url }
            return DownloadLocator(platform, post.sourceUrl, post.id, item.type, item.ordinal, identity,
                variant?.key ?: item.selectedVariantKey.orEmpty(), variant?.width ?: 0, variant?.height ?: 0,
                variant?.let(MediaSourceIdentity::codec).orEmpty(), cover)
        }

        fun decode(value: String): DownloadLocator {
            val o = JSONObject(value)
            require(o.getInt("version") == 1) { "Unsupported download intent version" }
            return DownloadLocator(Platform.entries.first { it.key == o.getString("platform") },
                o.getString("sourceUrl"), o.getString("postId"), MediaType.valueOf(o.getString("type")),
                o.getInt("ordinal"), o.getString("identity"), o.getString("variantKey"),
                o.optInt("width"), o.optInt("height"), o.optString("codec"), o.optBoolean("cover"))
                .also { require(it.postId.isNotBlank() && it.identity.isNotBlank() && it.sourceUrl.isNotBlank()) }
        }
    }
}

internal object MediaSourceIdentity {
    fun keys(item: MediaItem, cover: Boolean = false): Set<String> = linkedSetOf<String>().apply {
        item.sourceId.takeIf(String::isNotBlank)?.let { add("source:$it") }
        item.videoObjectId.takeIf(String::isNotBlank)?.let { add("object:$it") }
        val urls = if (cover) listOf(item.coverUrl) + item.coverFallbackUrls
            else listOf(item.url) + item.variants.map { it.url } + item.fallbackUrls
        urls.mapNotNull(::resourceKey).forEach(::add)
    }

    private fun resourceKey(value: String): String? {
        return try {
            val uri = URI(value)
            if (uri.scheme !in setOf("http", "https")) return null
            val query = uri.rawQuery.orEmpty().split('&')
                .associate { it.substringBefore('=') to it.substringAfter('=', "") }
            if (uri.path.orEmpty().contains("/aweme/v1/play")) {
                query["video_id"]?.takeIf(String::isNotBlank)?.let { return "video:$it" }
            }
            val path = uri.rawPath?.takeIf { it.isNotBlank() && it != "/" }?.substringBefore('!')
            if (path == null) return null
            // Every Weibo Live Photo is served through the same /media/play
            // path. Include its livephoto query value so multiple clips in one
            // post remain distinct when a durable download is renewed.
            if (path.endsWith("/media/play")) {
                query["livephoto"]?.takeIf { it.isNotBlank() }?.let { return "livephoto:$it" }
            }
            "path:$path"
        } catch (_: Exception) {
            null
        }
    }

    fun codec(key: String): String = when {
        key.contains("265", true) || key.contains("hevc", true) -> "h265"
        key.contains("264", true) || key.contains("avc", true) -> "h264"
        key.contains("av1", true) -> "av1"
        else -> ""
    }

    fun codec(variant: MediaVariant): String = variant.codec.ifBlank { codec(variant.key) }
}

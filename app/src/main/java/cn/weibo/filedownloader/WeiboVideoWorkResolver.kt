package cn.weibo.filedownloader

import org.json.JSONObject

/** A component response is useful only after it yields downloadable media. */
internal object WeiboVideoWorkResolver {
    fun upgradeStatus(status: WeiboWorkData, loadComponent: () -> WeiboWorkData?): WeiboWorkData =
        runCatching { mergeBest(status, loadComponent()) ?: status }.getOrDefault(status)

    fun statusId(playInfo: JSONObject): String? = playInfo.optString("mid").trim()
        .takeIf { it.matches(Regex("[1-9][0-9]{9,19}")) }

    fun resolve(
        providers: List<() -> JSONObject?>,
        resolveStatus: (String) -> WeiboWorkData?,
        normalizeComponent: (JSONObject) -> WeiboWorkData?,
    ): WeiboWorkData? {
        var lastFailure: Exception? = null
        var best: WeiboWorkData? = null
        var metadataFromStatus = false
        for (provider in providers) {
            val detail = try {
                provider()
            } catch (error: Exception) {
                lastFailure = error
                null
            } ?: continue

            // mid is the original status id, not the numeric part of the fid.
            var statusWork: WeiboWorkData? = null
            statusId(detail)?.let { mid ->
                try {
                    statusWork = resolveStatus(mid)?.takeIf { it.media.isNotEmpty() }
                } catch (error: Exception) {
                    lastFailure = error
                }
            }
            var componentWork: WeiboWorkData? = null
            try {
                componentWork = normalizeComponent(detail)?.takeIf { it.media.isNotEmpty() }
            } catch (error: Exception) {
                lastFailure = error
            }
            mergeBest(statusWork, componentWork)?.let { work ->
                best = if (statusWork != null && !metadataFromStatus) mergeBest(work, best) else mergeBest(best, work)
                if (statusWork != null) metadataFromStatus = true
            }
            // Empty or incomplete H5 responses must still try the desktop provider.
        }
        if (best == null) lastFailure?.let { throw it }
        return best
    }

    fun mergeBest(status: WeiboWorkData?, component: WeiboWorkData?): WeiboWorkData? {
        if (status == null) return component
        if (component == null) return status
        return mergePost(status, component)
    }

    fun upgradeVideos(status: WeiboWorkData, loadComponent: (String) -> WeiboWorkData?): WeiboWorkData {
        var result = status
        status.media.filter { it.type == MediaType.VIDEO }.map { it.videoObjectId }
            .filter(String::isNotBlank).distinct().forEach { objectId ->
                result = upgradeStatus(result) { loadComponent(objectId) }
            }
        return result
    }

    fun mergePost(status: WeiboWorkData, supplement: WeiboWorkData): WeiboWorkData {
        val media = status.media.map { original ->
            if (original.type != MediaType.VIDEO) return@map original
            val candidates = supplement.media.filter { candidate ->
                candidate.type == MediaType.VIDEO && if (original.videoObjectId.isNotBlank() && candidate.videoObjectId.isNotBlank()) {
                    original.videoObjectId == candidate.videoObjectId
                } else WeiboMediaIdentity.keys(original).any { it in WeiboMediaIdentity.keys(candidate) }
            }
            candidates.fold(original, ::mergeVideo)
        }
        return if (media == status.media) status else status.copy(media = media)
    }

    internal fun mergeVideo(original: MediaItem, candidate: MediaItem): MediaItem {
        val preferred = if (compareVariants(selected(candidate), selected(original)) > 0) candidate else original
        val other = if (preferred === original) candidate else original
        val variants = (variants(preferred) + variants(other)).distinctBy { it.url }.distinctBy { it.key }
            .sortedWith { a, b -> compareVariants(b, a) }
        val selected = variants.first()
        return preferred.copy(
            postId = original.postId,
            ordinal = original.ordinal,
            videoObjectId = original.videoObjectId.ifBlank { candidate.videoObjectId },
            sourceId = original.sourceId.ifBlank { candidate.sourceId },
            thumbnailUrl = original.thumbnailUrl.ifBlank { candidate.thumbnailUrl },
            coverUrl = original.coverUrl,
            url = selected.url,
            audioUrl = selected.audioUrl,
            variants = variants,
            selectedVariantKey = selected.key,
        )
    }

    private fun selected(item: MediaItem): MediaVariant = item.variants.firstOrNull { it.key == item.selectedVariantKey }
        ?: item.variants.firstOrNull { it.url == item.url }
        ?: MediaVariant(item.selectedVariantKey ?: item.quality, item.url, audioUrl = item.audioUrl)

    private fun variants(item: MediaItem): List<MediaVariant> = item.variants.ifEmpty { listOf(selected(item)) }

    private fun compareVariants(a: MediaVariant, b: MediaVariant): Int =
        compareValuesBy(a, b, { variant ->
            minOf(variant.width, variant.height).takeIf { it > 0 }
                ?: MediaVariantSelector.inferVideoQuality(variant.key, variant.url).height
        }, { it.pixelArea }, { it.bitrate })
}

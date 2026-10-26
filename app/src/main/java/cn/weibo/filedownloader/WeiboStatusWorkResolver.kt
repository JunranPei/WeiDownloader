package cn.weibo.filedownloader

import org.json.JSONObject

/** A malformed provider must not prevent the remaining status providers from loading. */
internal object WeiboStatusWorkResolver {
    fun resolve(
        providers: List<() -> JSONObject?>,
        normalize: (JSONObject) -> WeiboWorkData?,
        complete: (WeiboWorkData) -> WeiboWorkData = { it },
    ): WeiboWorkData? {
        var best: WeiboWorkData? = null
        var failure: Exception? = null
        for (provider in providers) {
            try {
                val work = provider()?.let(normalize) ?: continue
                if (best != null && best.id != work.id) throw WeiboStatusIdentityException()
                best = best?.let { WeiboInventoryMerger.merge(it, work) } ?: work
            } catch (error: WeiboStatusIdentityException) {
                throw error
            } catch (error: Exception) {
                failure = error
            }
        }
        if (best == null) failure?.let { throw it }
        best = best?.let(complete)
        best?.let { work ->
            if (work.inventory.missing(work.media).isNotEmpty() || work.media.size < work.inventory.minimumCount) {
                throw WeiboIncompleteMediaException()
            }
        }
        return best
    }

    fun completeVideos(work: WeiboWorkData, load: (WeiboMediaReference) -> MediaItem?): WeiboWorkData {
        val supplements = work.inventory.missing(work.media).filter { it.type == MediaType.VIDEO }
            .mapNotNull { reference ->
                runCatching { load(reference) }.getOrNull()?.takeIf { item ->
                    item.type == MediaType.VIDEO && (reference.objectId.isBlank() || item.videoObjectId == reference.objectId)
                }?.copy(postId = work.id, sourceId = reference.key)
            }
        return WeiboInventoryMerger.reconcile(work.copy(media = work.media + supplements))
    }
}

internal class WeiboStatusIdentityException : IllegalStateException("Conflicting Weibo status identities")

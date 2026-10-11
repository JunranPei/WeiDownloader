package cn.weibo.filedownloader

import org.json.JSONObject

/** A malformed provider must not prevent the remaining status providers from loading. */
internal object WeiboStatusWorkResolver {
    fun resolve(
        providers: List<() -> JSONObject?>,
        normalize: (JSONObject) -> WeiboWorkData?,
    ): WeiboWorkData? {
        var best: WeiboWorkData? = null
        var failure: Exception? = null
        for (provider in providers) {
            try {
                val work = provider()?.let(normalize)?.takeIf { it.media.isNotEmpty() } ?: continue
                best = best?.let { WeiboVideoWorkResolver.mergePost(it, work) } ?: work
            } catch (error: Exception) {
                failure = error
            }
        }
        if (best == null) failure?.let { throw it }
        return best
    }
}

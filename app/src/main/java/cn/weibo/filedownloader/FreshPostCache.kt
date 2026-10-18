package cn.weibo.filedownloader

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException

/** Short-lived execution cache, separate from previews and persisted queue data. */
internal class FreshPostCache(private val now: () -> Long, private val ttlMs: Long = 10_000L) {
    private data class Entry(val post: WeiboPost, val at: Long)
    private val cached = LinkedHashMap<String, Entry>(20, .75f, true)
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<WeiboPost>>()

    fun load(key: String, force: Boolean, loader: () -> WeiboPost): WeiboPost {
        if (!force) synchronized(cached) {
            cached[key]?.takeIf { now() - it.at in 0 until ttlMs }?.let { return it.post }
        }
        val own = CompletableFuture<WeiboPost>()
        val existing = inFlight.putIfAbsent(key, own)
        if (existing != null) {
            return try { existing.get() } catch (error: ExecutionException) { throw (error.cause ?: error) }
        }
        try {
            val post = loader()
            synchronized(cached) {
                cached[key] = Entry(post, now())
                while (cached.size > 20) cached.remove(cached.keys.first())
            }
            own.complete(post)
            return post
        } catch (error: Throwable) {
            own.completeExceptionally(error)
            synchronized(cached) { cached.remove(key) }
            throw error
        } finally {
            inFlight.remove(key, own)
        }
    }
}

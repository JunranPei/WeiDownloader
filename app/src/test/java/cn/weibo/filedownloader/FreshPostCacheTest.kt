package cn.weibo.filedownloader

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class FreshPostCacheTest {
    private fun post(token: String) = WeiboPost("post", token, "author", "source", emptyList())

    @Test fun queuedTimeNeverDeterminesFreshnessAndForceAlwaysReloads() {
        var now = 0L
        val cache = FreshPostCache({ now }, 100L)
        var loads = 0
        fun load(force: Boolean) = cache.load("post", force) { post((++loads).toString()) }
        assertEquals("1", load(false).text)
        assertEquals("1", load(false).text)
        assertEquals("2", load(true).text)
        now = 101L
        assertEquals("3", load(false).text)
    }

    @Test fun parallelAttachmentsShareOneInFlightResolution() {
        val cache = FreshPostCache({ 0L })
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<WeiboPost> { cache.load("post", true) { calls.incrementAndGet(); started.countDown(); release.await(); post("new") } }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val second = executor.submit<WeiboPost> { cache.load("post", false) { calls.incrementAndGet(); post("unexpected") } }
            release.countDown()
            assertEquals("new", first.get(2, TimeUnit.SECONDS).text)
            assertEquals("new", second.get(2, TimeUnit.SECONDS).text)
            assertEquals(1, calls.get())
        } finally { release.countDown(); executor.shutdownNow() }
    }
}

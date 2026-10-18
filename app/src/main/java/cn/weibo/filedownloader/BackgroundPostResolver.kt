package cn.weibo.filedownloader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Uses the same parsers as the foreground, with no Activity or preview cache dependency. */
internal object BackgroundPostResolver {
    private val cache = FreshPostCache(SystemClock::elapsedRealtime)

    fun resolve(context: Context, locator: DownloadLocator, force: Boolean): MediaItem {
        check(Looper.myLooper() != Looper.getMainLooper())
        val app = context.applicationContext
        val post = try { cache.load("${locator.platform.key}:${locator.postId}:${locator.sourceUrl}", force) {
            val loaded = when (locator.platform) {
                Platform.WEIBO -> WeiboClient(app).loadPost(locator.sourceUrl)
                Platform.DOUYIN -> loadDouyin(app, locator.sourceUrl)
                Platform.BILIBILI -> BilibiliClient(BilibiliSession.cookieHeader(), app).loadPost(locator.sourceUrl)
                Platform.XIAOHONGSHU -> XiaohongshuClient(app).loadPost(locator.sourceUrl)
                Platform.INSTAGRAM -> InstagramClient(app).loadPost(locator.sourceUrl)
                Platform.REDDIT -> RedditClient(app).loadPost(locator.sourceUrl)
                Platform.X -> XClient(app).loadPost(locator.sourceUrl)
                Platform.TELEGRAM -> TelegramClient(app).loadPost(locator.sourceUrl)
            }
            if (loaded.id != locator.postId) throw DownloadIdentityException("Post changed during source renewal")
            loaded
        } } catch (error: DownloadIdentityException) { throw error }
        catch (error: InterruptedException) { throw error }
        catch (error: Exception) { throw IOException("Unable to refresh media sources", error) }
        return locator.select(post)
    }

    private fun loadDouyin(context: Context, source: String): WeiboPost {
        val main = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val run = ResolutionRequest()
        var client: DouyinClient? = null
        var result: Result<WeiboPost>? = null
        main.post {
            if (!run.isActive()) return@post
            fun finish(value: Result<WeiboPost>) {
                if (run.finish()) {
                    result = value
                    client?.close()
                    latch.countDown()
                }
            }
            try {
                client = DouyinClient(context)
                client?.loadPost(source, QualityPreference.HIGHEST, ::finish)
            } catch (error: Exception) { finish(Result.failure(error)) }
        }
        try {
            if (!latch.await(95, TimeUnit.SECONDS)) throw IOException("Background Douyin resolution timed out")
            return result?.getOrThrow() ?: throw IOException("Background resolution returned no post")
        } finally {
            run.cancel()
            main.post { client?.close() }
        }
    }

    fun cookie(platform: Platform, address: String): String = when (platform) {
        Platform.BILIBILI, Platform.DOUYIN, Platform.XIAOHONGSHU ->
            CookieManager.getInstance().getCookie(address).orEmpty()
        // These download transports either route cookies themselves or use public CDN resources.
        else -> ""
    }
}

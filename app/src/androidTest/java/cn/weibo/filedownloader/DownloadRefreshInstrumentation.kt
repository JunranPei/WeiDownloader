package cn.weibo.filedownloader

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import kotlin.concurrent.thread

/** On-device acceptance: real WorkManager, no Activity, durable locator, an expired local source. */
class DownloadRefreshInstrumentation : Instrumentation() {
    private var scenario = "xhs-live"
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        scenario = arguments?.getString("scenario") ?: scenario
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            verify()
            result.putString("stream", "PASS background renewal $scenario\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL $scenario: ${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun verify() {
        val app = targetContext.applicationContext
        val restartFile = File(app.filesDir, "download-renewal-restart-test.json")
        if (scenario == "restart-resume") {
            val saved = JSONObject(restartFile.readText())
            val id = UUID.fromString(saved.getString("workId"))
            val manager = WorkManager.getInstance(app)
            val deadline = SystemClock.elapsedRealtime() + 150_000
            var state: WorkInfo.State? = null
            while (SystemClock.elapsedRealtime() < deadline) {
                state = manager.getWorkInfoById(id).get(3, TimeUnit.SECONDS)?.state
                if (state?.isFinished == true) break
                Thread.sleep(300)
            }
            check(state == WorkInfo.State.SUCCEEDED) { "Persisted job failed after restart: $state id=$id" }
            val file = File(saved.getString("file"))
            check(file.length() == 908828L)
            sendStatus(0, Bundle().apply { putString("stream", "Resumed persisted work=$id file=$file bytes=${file.length()}\n") })
            return
        }
        if (scenario == "douyin-web") {
            val latch = CountDownLatch(1)
            var result: Result<WeiboPost>? = null
            var client: DouyinClient? = null
            Handler(Looper.getMainLooper()).post {
                client = DouyinClient(app)
                // Test the actual headless browser fallback even when a native API happens to work.
                DouyinClient::class.java.getDeclaredField("detailProviders").apply { isAccessible = true }
                    .set(client, emptyList<DouyinDetailProvider>())
                client?.loadPost("https://v.douyin.com/mpsv0SfXFyw/", QualityPreference.HIGHEST) {
                    result = it; client?.close(); latch.countDown()
                }
            }
            check(latch.await(95, TimeUnit.SECONDS)) { "Headless browser timed out" }
            val post = requireNotNull(result).getOrThrow()
            check(post.id == "7697980119662749928" && post.media.isNotEmpty())
            sendStatus(0, Bundle().apply { putString("stream", "Headless browser parsed target=${post.id} media=${post.media.size}\n") })
            return
        }
        val douyin = scenario.startsWith("douyin")
        val cover = scenario == "xhs-cover"
        val expireDuringTransfer = scenario == "xhs-expiry"
        val legacy = scenario == "xhs-legacy"
        val prepareRestart = scenario == "restart-prepare"
        val id = if (douyin) "7697980119662749928" else "6ad0d56a000000001803ba07"
        val source = if (douyin) "https://v.douyin.com/mpsv0SfXFyw/" else "https://xhslink.cn/o/3lXvSOWpyFS"
        val platform = if (douyin) Platform.DOUYIN else Platform.XIAOHONGSHU
        val identity = if (douyin) "v0d00fg10000dbabehfog65ijbinq4t0" else
            "notes_pre_post/1040g3k0326bk77uuk6005oluuig6t1jqsqrslug"
        val locator = DownloadLocator(platform, source, id,
            if (douyin) MediaType.VIDEO else MediaType.LIVE_PHOTO,
            if (douyin) 1 else 2, "source:$identity", if (douyin) "play_addr" else "masterUrl",
            width = if (douyin) 0 else 1080, height = if (douyin) 0 else 1440,
            codec = if (douyin) "" else "h264", cover = cover)
        val staleRequests = AtomicInteger()
        ServerSocket(0).use { server ->
            server.soTimeout = 1000
            val staleUrl = "http://127.0.0.1:${server.localPort}/expired.mp4"
            var serving = true
            val serverThread = thread(isDaemon = true) {
                while (serving) try {
                    server.accept().use { socket ->
                        socket.soTimeout = 2000
                        val input = socket.getInputStream().bufferedReader()
                        while (input.readLine()?.isNotEmpty() == true) { }
                        staleRequests.incrementAndGet()
                        socket.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                } catch (_: SocketTimeoutException) { } catch (_: Exception) { break }
            }
            try {
                if (expireDuringTransfer) {
                    val stale = MediaItem(MediaType.LIVE_PHOTO, staleUrl, MediaQuality.LIVE_ORIGINAL, id, 2,
                        sourceId = identity, selectedVariantKey = "masterUrl",
                        variants = listOf(MediaVariant("masterUrl", staleUrl, 1080, 1440, codec = "h264")))
                    val cacheField = BackgroundPostResolver::class.java.getDeclaredField("cache").apply { isAccessible = true }
                    val cache = cacheField.get(BackgroundPostResolver) as FreshPostCache
                    cache.load("${platform.key}:$id:$source", true) {
                        WeiboPost(id, "expiry fixture", "", source, listOf(stale))
                    }
                }
                val folder = "AllDownloader/RefreshVerification"
                val fileName = "$scenario-${System.currentTimeMillis()}.${if (cover) "jpg" else "mp4"}"
                val input = Data.Builder()
                    // Poisoned legacy data must be ignored whenever a locator is present.
                    .putString(SafDownloadWorker.KEY_URL, if (legacy)
                        "https://sns-video-v6.xhscdn.com/stream/1/10/19/01ead0d5252f3edd01005003a13fc1eaeb_19.mp4?sign=expired&t=1" else staleUrl)
                    .putString(SafDownloadWorker.KEY_PLATFORM, platform.key)
                    .putString(SafDownloadWorker.KEY_FOLDER, folder)
                    .putString(SafDownloadWorker.KEY_FILE_NAME, fileName)
                    .putString(SafDownloadWorker.KEY_MEDIA_TYPE, if (cover) MediaType.IMAGE.name else locator.type.name)
                    .putString(SafDownloadWorker.KEY_REFERER, if (douyin) "https://www.douyin.com/" else "https://www.xiaohongshu.com/")
                if (!legacy) input.putString(SafDownloadWorker.KEY_LOCATOR, locator.encode())
                val builder = OneTimeWorkRequestBuilder<SafDownloadWorker>().setInputData(input.build())
                if (prepareRestart) builder.setInitialDelay(20, TimeUnit.SECONDS)
                val request = builder.build()
                if (legacy) {
                    DownloadStatusStore(app).apply {
                        registerWork("${platform.key}:$id", platform, source, "", "legacy renewal", "", 1)
                        registerWorkerTask(request.id.toString(), "${platform.key}:$id", "LIVE_PHOTO:$id:2|masterUrl", "LIVE_PHOTO:$id:2")
                    }
                }
                val manager = WorkManager.getInstance(app)
                manager.enqueue(request).result.get(10, TimeUnit.SECONDS)
                if (prepareRestart) {
                    restartFile.writeText(JSONObject().put("workId", request.id.toString())
                        .put("file", "/sdcard/Download/$folder/$fileName").toString())
                    sendStatus(0, Bundle().apply { putString("stream", "Persisted delayed work=${request.id}; ready for force-stop\n") })
                    return
                }
                val deadline = SystemClock.elapsedRealtime() + 150_000
                var state: WorkInfo.State? = null
                while (SystemClock.elapsedRealtime() < deadline) {
                    state = manager.getWorkInfoById(request.id).get(3, TimeUnit.SECONDS)?.state
                    if (state?.isFinished == true || state == WorkInfo.State.ENQUEUED && staleRequests.get() > 1) break
                    Thread.sleep(300)
                }
                check(state == WorkInfo.State.SUCCEEDED) { "Worker did not succeed: $state id=${request.id}" }
                check(staleRequests.get() == if (expireDuringTransfer) 1 else 0) { "Unexpected stale requests: ${staleRequests.get()}" }
                val file = File("/sdcard/Download/$folder/$fileName")
                check(file.length() > 10_000) { "Missing media file $file" }
                val prefix = ByteArray(12).also { bytes -> file.inputStream().use { it.read(bytes) } }
                check(if (cover) prefix[0] == 0xff.toByte() && prefix[1] == 0xd8.toByte()
                    else String(prefix, 4, 4) == "ftyp") { "Wrong media bytes" }
                sendStatus(0, Bundle().apply { putString("stream", "file=$file bytes=${file.length()} staleRequests=${staleRequests.get()}\n") })
            } finally {
                serving = false
                serverThread.join(1500)
            }
        }
    }
}

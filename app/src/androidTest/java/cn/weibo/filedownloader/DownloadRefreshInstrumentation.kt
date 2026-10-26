package cn.weibo.filedownloader

import android.app.Activity
import android.app.Instrumentation
import android.content.ContentResolver
import android.content.Context
import android.content.ContentUris
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import kotlin.concurrent.thread

/** On-device acceptance: real WorkManager, no Activity, durable locator, an expired local source. */
class DownloadRefreshInstrumentation : Instrumentation() {
    private var scenario = "xhs-live"
    private var options: Bundle? = null
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        options = arguments
        scenario = arguments?.getString("scenario") ?: scenario
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val originalProxySelector = java.net.ProxySelector.getDefault()
        val explicitProxy = options?.getString("networkProxy")?.let { address ->
            val uri = java.net.URI("http://$address")
            require(uri.host != null && uri.port in 1..65535 && uri.userInfo == null)
            java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(uri.host, uri.port))
        }
        java.net.ProxySelector.setDefault(object : java.net.ProxySelector() {
            override fun select(uri: java.net.URI): List<java.net.Proxy> =
                if (uri.host in setOf("127.0.0.1", "localhost", "::1")) listOf(java.net.Proxy.NO_PROXY)
                else if (explicitProxy != null) listOf(explicitProxy)
                else originalProxySelector?.select(uri) ?: listOf(java.net.Proxy.NO_PROXY)
            override fun connectFailed(uri: java.net.URI, address: java.net.SocketAddress, error: java.io.IOException) {
                originalProxySelector?.connectFailed(uri, address, error)
            }
        })
        try {
            verify()
            result.putString("stream", "PASS $scenario\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL $scenario: ${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            java.net.ProxySelector.setDefault(originalProxySelector)
        }
    }

    private fun verify() {
        val app = targetContext.applicationContext
        if (scenario == "weibo-status-inventory") {
            val id = requireNotNull(options?.getString("expectedId"))
            require(id.matches(Regex("[0-9]+")))
            val http = WeiboHttpClient(app)
            val folder = File(app.cacheDir, "weibo-gallery-20261026").apply { mkdirs() }
            for (provider in listOf(WeiboMobileStatusDetailProvider(http, app), WeiboDesktopStatusDetailProvider(http, app))) {
                val data = requireNotNull(provider.load(id))
                File(folder, "$id-${provider.name}.json").writeText(data.toString())
                val media = WeiboWorkNormalizer(false).parseMedia(data, QualityPreference.HIGHEST)
                sendStatus(0, Bundle().apply {
                    putString("stream", "provider=${provider.name} id=$id declared=${data.optInt("pic_num")} parsed=${media.size} types=${media.map { it.type }}\n")
                })
            }
            return
        }
        if (scenario == "inspect-cache-media") {
            val name = requireNotNull(options?.getString("file"))
            require(File(name).name == name)
            val file = File(app.cacheDir, name)
            val extractor = MediaExtractor()
            val retriever = android.media.MediaMetadataRetriever()
            try {
                extractor.setDataSource(file.absolutePath)
                val count = extractor.trackCount
                retriever.setDataSource(file.absolutePath)
                val frame = retriever.getFrameAtTime(1_000_000, android.media.MediaMetadataRetriever.OPTION_CLOSEST)
                sendStatus(0, Bundle().apply { putString("stream", "Cache media bytes=${file.length()} tracks=$count decodedFrame=${frame?.width}x${frame?.height}\n") })
                check(frame != null) { "Android cannot decode the original media" }
                frame.recycle()
            } finally {
                extractor.release()
                retriever.release()
            }
            return
        }
        if (scenario == "cross-platform-input") {
            val samples = listOf(
                "https://x.com/NASA/status/2040059770237849635",
                "https://www.bilibili.com/video/BV1bK411W797/",
                "https://m.weibo.cn/detail/5226684729265742",
                "https://xhslink.cn/o/2uN5D8VgpIH",
                "https://www.reddit.com/r/pics/comments/1s6d7rv/plaza_de_espana/",
                "https://www.instagram.com/p/fA9uwTtkSN/",
            )
            val activity = startActivitySync(
                Intent(Intent.ACTION_SEND)
                    .setClassName(app.packageName, MainActivity::class.java.name)
                    .putExtra(Intent.EXTRA_TEXT, samples.last())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            try {
                waitForIdleSync()
                for (source in samples) {
                    var retained = ""
                    runOnMainSync {
                        val input = activity.findViewById<android.widget.EditText>(R.id.linkInput)
                        input.setText(source)
                        activity.findViewById<android.view.View>(R.id.parseButton).performClick()
                        retained = input.text.toString()
                    }
                    check(retained == source) {
                        "Platform adoption cleared the user's source: expected=$source actual=$retained"
                    }
                }
                sendStatus(0, Bundle().apply {
                    putString("stream", "PASS cross-platform input retained across ${samples.size} platform transitions\n")
                })
            } finally {
                runOnMainSync { activity.finish() }
            }
            return
        }
        if (scenario == "platform-download" || scenario == "platform-parse" ||
            scenario == "telegram-download" || scenario == "telegram-parse" || scenario == "weibo-cover-download") {
            verifyPlatformDownload(app)
            return
        }
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
            val source = options?.getString("url") ?: "https://v.douyin.com/mpsv0SfXFyw/"
            val expectedId = options?.getString("expectedId") ?: "7697980119662749928"
            val activity = startActivitySync(
                Intent(Intent.ACTION_MAIN)
                    .setClassName(app.packageName, MainActivity::class.java.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            Handler(Looper.getMainLooper()).post {
                client = DouyinClient(activity)
                // Force the actual WebView fallback even when a native API happens to work.
                DouyinClient::class.java.getDeclaredField("detailProviders").apply { isAccessible = true }
                    .set(client, emptyList<DouyinDetailProvider>())
                client?.loadPost(source, QualityPreference.HIGHEST) {
                    result = it; client?.close(); latch.countDown()
                }
            }
            try {
                check(latch.await(95, TimeUnit.SECONDS)) { "WebView fallback timed out" }
                val post = requireNotNull(result).getOrThrow()
                check(post.id == expectedId && post.media.isNotEmpty())
                sendStatus(0, Bundle().apply { putString("stream", "WebView fallback parsed target=${post.id} media=${post.media.size}\n") })
            } finally {
                client?.close()
                runOnMainSync { activity.finish() }
            }
            return
        }
        if (scenario == "douyin-share-ui") {
            val source = options?.getString("url") ?: "https://v.douyin.com/npve5sp-teE/"
            val expectedId = options?.getString("expectedId") ?: "7699493946036397211"
            val activity = startActivitySync(
                Intent(Intent.ACTION_MAIN)
                    .setClassName(app.packageName, MainActivity::class.java.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            runOnMainSync {
                activity.findViewById<android.widget.EditText>(R.id.linkInput).setText(source)
                activity.findViewById<android.view.View>(R.id.parseButton).performClick()
            }
            val postField = MainActivity::class.java.getDeclaredField("post").apply { isAccessible = true }
            val summaryField = MainActivity::class.java.getDeclaredField("summaryText").apply { isAccessible = true }
            val parseButtonField = MainActivity::class.java.getDeclaredField("parseButton").apply { isAccessible = true }
            val deadline = SystemClock.elapsedRealtime() + 110_000
            var post: WeiboPost? = null
            var summary = ""
            var finished = false
            while (SystemClock.elapsedRealtime() < deadline) {
                runOnMainSync {
                    post = postField.get(activity) as? WeiboPost
                    summary = (summaryField.get(activity) as? android.widget.TextView)?.text?.toString().orEmpty()
                    finished = (parseButtonField.get(activity) as? android.view.View)?.isEnabled == true && summary.isNotBlank()
                }
                if (post != null || finished) break
                Thread.sleep(400)
            }
            check(post?.id == expectedId && post?.media?.isNotEmpty() == true) {
                "App UI did not resolve expected Douyin post; status=$summary"
            }
            sendStatus(0, Bundle().apply { putString("stream", "App UI resolved Douyin post=${post!!.id} media=${post!!.media.size}\n") })
            runOnMainSync { activity.finish() }
            return
        }
        if (scenario == "download-invalid-octet-stream" || scenario == "download-invalid-response-suite") {
            data class InvalidResponse(
                val name: String,
                val status: Int,
                val contentType: String,
                val body: ByteArray,
                val declaredLength: Int = body.size,
            )
            val contentResolver = app.contentResolver
            val runId = UUID.randomUUID().toString().take(8)
            val folder = "AllDownloader/EdgeTest-$runId"
            val plainText = "not an image or media file".toByteArray()
            val html = "<html>access denied</html>".toByteArray()
            val json = "{\"error\":403}".toByteArray()
            val jpegPrefix = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(28)
            val cases = if (scenario == "download-invalid-octet-stream") {
                listOf(InvalidResponse("octet-text", 200, "application/octet-stream", plainText))
            } else {
                listOf(
                    InvalidResponse("http-403", 403, "text/plain", "forbidden".toByteArray()),
                    InvalidResponse("http-404", 404, "text/html", html),
                    InvalidResponse("json-error", 200, "application/json", json),
                    InvalidResponse("wrong-mime", 200, "video/mp4", jpegPrefix),
                    InvalidResponse("empty-body", 200, "application/octet-stream", byteArrayOf()),
                    InvalidResponse("truncated-body", 200, "application/octet-stream", jpegPrefix, declaredLength = 128),
                    InvalidResponse("octet-text", 200, "application/octet-stream", plainText),
                )
            }
            val results = mutableListOf<String>()
            for (testCase in cases) {
                val fileName = "invalid-${testCase.name}-$runId.jpg"
                val requests = AtomicInteger()
                var serving = true
                ServerSocket(0).use { server ->
                    server.soTimeout = 1000
                    val serverThread = thread(isDaemon = true) {
                        while (serving) try {
                            server.accept().use { socket ->
                                socket.soTimeout = 2000
                                val input = socket.getInputStream().bufferedReader()
                                while (input.readLine()?.isNotEmpty() == true) { }
                                requests.incrementAndGet()
                                socket.getOutputStream().apply {
                                    write(("HTTP/1.1 ${testCase.status} ${if (testCase.status in 200..299) "OK" else "Injected Failure"}\r\n" +
                                        "Content-Type: ${testCase.contentType}\r\n" +
                                        "Content-Length: ${testCase.declaredLength}\r\nConnection: close\r\n\r\n").toByteArray())
                                    write(testCase.body)
                                }
                            }
                        } catch (_: SocketTimeoutException) { } catch (_: Exception) { break }
                    }
                    try {
                    val data = Data.Builder()
                        .putString(SafDownloadWorker.KEY_URL, "http://127.0.0.1:${server.localPort}/invalid.jpg")
                        .putString(SafDownloadWorker.KEY_PLATFORM, Platform.DOUYIN.key)
                        .putString(SafDownloadWorker.KEY_FOLDER, folder)
                        .putString(SafDownloadWorker.KEY_FILE_NAME, fileName)
                        .putString(SafDownloadWorker.KEY_MEDIA_TYPE, MediaType.IMAGE.name)
                        .putString(SafDownloadWorker.KEY_REFERER, "https://www.douyin.com/")
                        .build()
                    val request = OneTimeWorkRequestBuilder<SafDownloadWorker>()
                        .setInputData(data)
                        .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.SECONDS)
                        .build()
                    val manager = WorkManager.getInstance(app)
                    manager.enqueue(request).result.get(10, TimeUnit.SECONDS)
                    val deadline = SystemClock.elapsedRealtime() + 150_000
                    var state: WorkInfo.State? = null
                    var sawRunning = false
                    while (SystemClock.elapsedRealtime() < deadline) {
                        val info = manager.getWorkInfoById(request.id).get(3, TimeUnit.SECONDS)
                        state = info?.state
                        if (state == WorkInfo.State.RUNNING) sawRunning = true
                        if (state?.isFinished == true) break
                        // The injected malformed responses are intentionally retryable in the
                        // production worker. Cancel after the first retry is scheduled so this
                        // suite stays bounded while still verifying that the failed attempt
                        // discarded its pending MediaStore row.
                        if (state == WorkInfo.State.ENQUEUED && (sawRunning || (info?.runAttemptCount ?: 0) > 0)) {
                            manager.cancelWorkById(request.id).result.get(10, TimeUnit.SECONDS)
                            state = manager.getWorkInfoById(request.id).get(10, TimeUnit.SECONDS)?.state
                            break
                        }
                        Thread.sleep(300)
                    }
                    var outputRows = 0
                    val queryArgs = Bundle().apply {
                        putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DISPLAY_NAME}=?")
                        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(fileName))
                    }
                    contentResolver.query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.IS_PENDING),
                        queryArgs,
                        null,
                    ).use { cursor ->
                        outputRows = cursor?.count ?: 0
                        while (cursor?.moveToNext() == true) {
                            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                            contentResolver.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null)
                        }
                    }
                    check(requests.get() > 0) { "Injected server was never reached for ${testCase.name}; proxy or routing invalidates this case" }
                    check(outputRows == 0) {
                        "Worker left a published or pending MediaStore item; rows=$outputRows state=$state case=${testCase.name}"
                    }
                    check(state == WorkInfo.State.FAILED || state == WorkInfo.State.CANCELLED) {
                        "Unexpected worker state for ${testCase.name}: $state"
                    }
                    results += "${testCase.name}=${state.name}/rows:$outputRows/requests:${requests.get()}"
                    } finally {
                        serving = false
                        server.close()
                        serverThread.join(1500)
                    }
                }
            }
            sendStatus(0, Bundle().apply { putString("stream", "Invalid response suite: ${results.joinToString("; ")}\n") })
            return
        }
        if (scenario == "redirect-cookie-probe") {
            val redirectServer = ServerSocket(0)
            val targetServer = ServerSocket(0)
            val targetHeaders = AtomicReference("")
            val targetReached = CountDownLatch(1)
            val targetThread = thread(isDaemon = true) {
                targetServer.accept().use { socket ->
                    val lines = mutableListOf<String>()
                    val input = socket.getInputStream().bufferedReader()
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        lines += line
                    }
                    targetHeaders.set(lines.joinToString("\n"))
                    targetReached.countDown()
                    socket.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray(),
                    )
                }
            }
            val redirectThread = thread(isDaemon = true) {
                redirectServer.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (input.readLine()?.isNotEmpty() == true) { }
                    val location = "http://localhost:${targetServer.localPort}/target"
                    socket.getOutputStream().write(
                        "HTTP/1.1 302 Found\r\nLocation: $location\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
                    )
                }
            }
            try {
                val connection = SafeHttpRedirects.openConnection(
                    "http://127.0.0.1:${redirectServer.localPort}/start",
                ) {
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("Cookie", "session=edge-test-only")
                }
                val status = connection.responseCode
                connection.inputStream.use { it.readBytes() }
                connection.disconnect()
                check(status == 200 && targetReached.await(5, TimeUnit.SECONDS)) {
                    "Controlled cross-host redirect did not reach target: status=$status"
                }
                val cookieForwarded = targetHeaders.get().lines().any {
                    it.startsWith("Cookie:", ignoreCase = true) && it.contains("session=edge-test-only")
                }
                check(!cookieForwarded) { "Sensitive Cookie crossed origin during redirect" }
                sendStatus(0, Bundle().apply {
                    putString("stream", "Safe redirect followed cross-origin; test Cookie forwarded=$cookieForwarded\n")
                })
            } finally {
                redirectServer.close()
                targetServer.close()
                redirectThread.join(1500)
                targetThread.join(1500)
            }
            return
        }
        if (scenario == "douyin-slide-download") {
            val source = options?.getString("url") ?: "https://v.douyin.com/npve5sp-teE/"
            val expectedId = options?.getString("expectedId") ?: "7699493946036397211"
            val contentResolver = app.contentResolver
            val activity = startActivitySync(
                Intent(Intent.ACTION_MAIN)
                    .setClassName(app.packageName, MainActivity::class.java.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            var publishedUri: Uri? = null
            try {
                runOnMainSync {
                    activity.findViewById<android.widget.EditText>(R.id.linkInput).setText(source)
                    activity.findViewById<android.view.View>(R.id.parseButton).performClick()
                }
                val postField = MainActivity::class.java.getDeclaredField("post").apply { isAccessible = true }
                val deadline = SystemClock.elapsedRealtime() + 110_000
                var post: WeiboPost? = null
                while (SystemClock.elapsedRealtime() < deadline && post == null) {
                    runOnMainSync { post = postField.get(activity) as? WeiboPost }
                    if (post == null) Thread.sleep(400)
                }
                val resolved = requireNotNull(post) { "App UI did not resolve the test post" }
                check(resolved.id == expectedId) { "Resolved unexpected post id=${resolved.id}" }
                val image = resolved.media.firstOrNull { it.type == MediaType.IMAGE }
                    ?: error("Resolved post has no image media")
                val extension = image.url.substringBefore('?').substringAfterLast('.', "jpg")
                    .lowercase().takeIf { it in setOf("jpg", "jpeg", "png", "webp", "avif") } ?: "jpg"
                val runId = UUID.randomUUID().toString().take(8)
                val folder = "AllDownloader/EdgeTest-$runId"
                val fileName = "douyin-${resolved.id}-1.$extension"
                contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                    arrayOf(fileName),
                    null,
                ).use { check(it?.count == 0) { "Unique test filename already exists" } }
                val data = Data.Builder()
                    .putString(SafDownloadWorker.KEY_URL, image.url)
                    .putString(SafDownloadWorker.KEY_PLATFORM, Platform.DOUYIN.key)
                    .putString(SafDownloadWorker.KEY_FOLDER, folder)
                    .putString(SafDownloadWorker.KEY_FILE_NAME, fileName)
                    .putString(SafDownloadWorker.KEY_MEDIA_TYPE, MediaType.IMAGE.name)
                    .putString(SafDownloadWorker.KEY_REFERER, "https://www.douyin.com/")
                    .build()
                    val request = OneTimeWorkRequestBuilder<SafDownloadWorker>()
                        .setInputData(data)
                        .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.SECONDS)
                        .build()
                val manager = WorkManager.getInstance(app)
                manager.enqueue(request).result.get(10, TimeUnit.SECONDS)
                val workDeadline = SystemClock.elapsedRealtime() + 150_000
                var state: WorkInfo.State? = null
                while (SystemClock.elapsedRealtime() < workDeadline) {
                    state = manager.getWorkInfoById(request.id).get(3, TimeUnit.SECONDS)?.state
                    if (state?.isFinished == true) break
                    Thread.sleep(300)
                }
                check(state == WorkInfo.State.SUCCEEDED) { "Slide image download failed: $state" }
                contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.MIME_TYPE,
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        MediaStore.MediaColumns.IS_PENDING,
                        MediaStore.MediaColumns.SIZE,
                    ),
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                    arrayOf(fileName),
                    null,
                ).use { cursor ->
                    check(cursor?.count == 1) { "Expected exactly one published test file" }
                    check(cursor!!.moveToFirst())
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    val mime = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)).orEmpty()
                    val relativePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)).orEmpty()
                    val pending = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING))
                    val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))
                    publishedUri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                    check(relativePath.endsWith("$folder/")) { "Unexpected output path: $relativePath" }
                    check(pending == 0 && size > 0L) { "Published file is incomplete: pending=$pending size=$size" }
                    val prefix = ByteArray(16)
                    val count = contentResolver.openInputStream(publishedUri!!)?.use { it.read(prefix) } ?: -1
                    val jpeg = count >= 3 && prefix[0] == 0xff.toByte() && prefix[1] == 0xd8.toByte() && prefix[2] == 0xff.toByte()
                    val png = count >= 8 && prefix.take(8).map { it.toInt() and 0xff } == listOf(137, 80, 78, 71, 13, 10, 26, 10)
                    val webp = count >= 12 && String(prefix, 0, 4) == "RIFF" && String(prefix, 8, 4) == "WEBP"
                    check(jpeg || png || webp) { "Published bytes do not have a supported image signature; MIME=$mime size=$size" }
                    val deleted = contentResolver.delete(publishedUri!!, null, null)
                    check(deleted == 1) { "Could not remove the test-created MediaStore row" }
                    publishedUri = null
                    sendStatus(0, Bundle().apply {
                        putString("stream", "Downloaded one Douyin image bytes=$size mime=$mime path=$relativePath signatures=valid cleanup=removed\n")
                    })
                }
            } finally {
                publishedUri?.let { contentResolver.delete(it, null, null) }
                runOnMainSync { activity.finish() }
            }
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
        val folder = "AllDownloader/EdgeTest-${UUID.randomUUID().toString().take(8)}"
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
                if (!prepareRestart) removeRowsFromExactTestFolder(app.contentResolver, folder)
            }
        }
    }

    /** Resolves a caller-selected public sample through the app UI (or dormant Telegram client),
     * then downloads selected real media through WorkManager into an isolated MediaStore folder. */
    private fun verifyPlatformDownload(app: Context) {
        val contentResolver = app.contentResolver
        val source = requireNotNull(options?.getString("url")) { "Missing sample URL" }
        val platform = requireNotNull(Platform.entries.firstOrNull {
            it.key == options?.getString("platform")
        }) { "Unknown platform=${options?.getString("platform")}" }
        val expectedId = options?.getString("expectedId").orEmpty()
        val expectedMediaCount = options?.getString("minimumMedia")?.toIntOrNull() ?: 1
        val exactMediaCount = options?.getString("exactMedia")?.toIntOrNull()
        val expectedTypes = options?.getString("expectedTypes").orEmpty()
            .split(',').map(String::trim).filter(String::isNotBlank).map(String::uppercase).toSet()
        val indices = options?.getString("mediaIndices").orEmpty().ifBlank { "0" }
            .split(',').mapNotNull(String::toIntOrNull).distinct()
        require(indices.isNotEmpty()) { "No mediaIndices requested" }

        var activity: MainActivity? = null
        var resultPost: WeiboPost? = null
        var parseSummary = ""
        if (scenario == "weibo-cover-download") {
            require(platform == Platform.WEIBO)
            resultPost = WeiboClient(app, true).loadPost(source)
        } else if (scenario == "telegram-download" || scenario == "telegram-parse") {
            require(platform == Platform.TELEGRAM) { "telegram scenario requires Telegram" }
            resultPost = TelegramClient(app).loadPost(source)
        } else {
            activity = startActivitySync(
                Intent(Intent.ACTION_MAIN)
                    .setClassName(app.packageName, MainActivity::class.java.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            runOnMainSync {
                activity!!.findViewById<android.widget.EditText>(R.id.linkInput).setText(source)
                activity!!.findViewById<android.view.View>(R.id.parseButton).performClick()
            }
            val postField = MainActivity::class.java.getDeclaredField("post").apply { isAccessible = true }
            val summaryField = MainActivity::class.java.getDeclaredField("summaryText").apply { isAccessible = true }
            val parseButtonField = MainActivity::class.java.getDeclaredField("parseButton").apply { isAccessible = true }
            val parseTimeout = options?.getString("parseTimeoutSeconds")?.toLongOrNull() ?: 110L
            val deadline = SystemClock.elapsedRealtime() + parseTimeout.coerceIn(1L, 300L) * 1000L
            var finished = false
            while (SystemClock.elapsedRealtime() < deadline && resultPost == null) {
                runOnMainSync {
                    resultPost = postField.get(activity) as? WeiboPost
                    parseSummary = (summaryField.get(activity) as? android.widget.TextView)?.text?.toString().orEmpty()
                    finished = (parseButtonField.get(activity) as? android.view.View)?.isEnabled == true
                }
                if (resultPost == null && finished && parseSummary.isNotBlank()) break
                if (resultPost == null) Thread.sleep(400)
            }
        }

        try {
            val post = requireNotNull(resultPost) { "No parsed post; source=$source summary=$parseSummary" }
            check(post.media.size >= expectedMediaCount) {
                "Expected at least $expectedMediaCount media items; got ${post.media.size} id=${post.id}"
            }
            if (exactMediaCount != null) check(post.media.size == exactMediaCount) {
                "Expected exactly $exactMediaCount media items; got ${post.media.size} id=${post.id}"
            }
            if (expectedId.isNotBlank()) check(post.id == expectedId) {
                "Unexpected post id=${post.id}; expected=$expectedId"
            }
            if (expectedTypes.isNotEmpty()) {
                val actual = post.media.map { it.type.name }.toSet()
                check(actual.containsAll(expectedTypes)) { "Expected types=$expectedTypes actual=$actual id=${post.id}" }
            }
            options?.getString("expectedTypeOrder")?.let { expected ->
                check(post.media.map { it.type.name } == expected.split(',')) { "Unexpected attachment type order" }
            }
            options?.getString("expectedSourceIds")?.let { expected ->
                check(post.media.map { it.sourceId } == expected.split(',')) { "Unexpected attachment identity/order" }
            }
            options?.getString("expectedWeiboMediaIds")?.let { expected ->
                require(platform == Platform.WEIBO)
                val actual = post.media.map { item ->
                    if (item.type == MediaType.VIDEO) item.videoObjectId.ifBlank { Uri.parse(item.url).lastPathSegment.orEmpty() }
                    else Uri.parse(item.url).lastPathSegment.orEmpty().substringBeforeLast('.')
                }
                val expectedIds = expected.split(',').map { it.split('|') }
                check(actual.size == expectedIds.size && actual.indices.all { actual[it] in expectedIds[it] }) {
                    "Unexpected Weibo attachment identity/order: $actual"
                }
            }
            check(indices.all { it in post.media.indices }) {
                "Requested indices=$indices outside media count=${post.media.size}"
            }
            if (scenario == "platform-parse" || scenario == "telegram-parse") {
                sendStatus(0, Bundle().apply {
                    putString(
                        "stream",
                        "PASS parse platform=${platform.key} id=${post.id} media=${post.media.size} types=${post.media.map { it.type.name }} objects=${post.media.map { it.videoObjectId }} urls=${post.media.map { it.url.take(120) }}\n",
                    )
                })
                return
            }
            val runId = UUID.randomUUID().toString().take(8)
            val folder = "AllDownloader/EdgeTest-$runId"
            val manager = WorkManager.getInstance(app)
            val published = mutableListOf<Uri>()
            val results = mutableListOf<String>()
            var activeRequestId: UUID? = null
            try {
                val includeCovers = options?.getString("includeCovers") == "true"
                val downloads = indices.flatMap { index ->
                    val item = post.media[index]
                    if (includeCovers && item.type == MediaType.LIVE_PHOTO) check(item.coverUrl.isNotBlank()) {
                        "Expected a paired still image for index=$index"
                    }
                    DownloadMediaPlanner.expand(item, includeCovers).map { index to it }
                }
                for ((index, media) in downloads) {
                    val extension = media.suggestedFileName().substringAfterLast('.', "jpg")
                    val fileName = "edge-$runId-${platform.key}-${index + 1}-${media.type.name}.$extension"
                    contentResolver.query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        arrayOf(MediaStore.MediaColumns._ID),
                        "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                        arrayOf(fileName),
                        null,
                    ).use { check(it?.count == 0) { "Unique test filename already exists: $fileName" } }
                    val requestData = Data.Builder()
                        .putString(SafDownloadWorker.KEY_URL, media.url)
                        .putStringArray(SafDownloadWorker.KEY_FALLBACK_URLS, media.fallbackUrls.toTypedArray())
                        .putString(SafDownloadWorker.KEY_AUDIO_URL, media.audioUrl)
                        .putString(SafDownloadWorker.KEY_COOKIE, BackgroundPostResolver.cookie(platform, media.url))
                        .putString(SafDownloadWorker.KEY_PLATFORM, platform.key)
                        .putString(SafDownloadWorker.KEY_FOLDER, folder)
                        .putString(SafDownloadWorker.KEY_FILE_NAME, fileName)
                        .putString(SafDownloadWorker.KEY_MEDIA_TYPE, media.type.name)
                        .putString(SafDownloadWorker.KEY_REFERER, when (platform) {
                            Platform.WEIBO -> "https://weibo.com/"
                            Platform.DOUYIN -> "https://www.douyin.com/"
                            Platform.BILIBILI -> "https://www.bilibili.com/"
                            Platform.TELEGRAM -> "https://t.me/"
                            Platform.X -> "https://x.com/"
                            Platform.INSTAGRAM -> "https://www.instagram.com/"
                            Platform.XIAOHONGSHU -> "https://www.xiaohongshu.com/"
                            Platform.REDDIT -> "https://www.reddit.com/"
                        })
                        .build()
                    val request = OneTimeWorkRequestBuilder<SafDownloadWorker>()
                        .setInputData(requestData)
                        .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.SECONDS)
                        .build()
                    activeRequestId = request.id
                    manager.enqueue(request).result.get(10, TimeUnit.SECONDS)
                    val deadline = SystemClock.elapsedRealtime() + 180_000
                    var state: WorkInfo.State? = null
                    var attempts = 0
                    while (SystemClock.elapsedRealtime() < deadline) {
                        val info = manager.getWorkInfoById(request.id).get(3, TimeUnit.SECONDS)
                        state = info?.state
                        attempts = info?.runAttemptCount ?: attempts
                        if (state?.isFinished == true) break
                        // This direct-source probe deliberately does not renew a source URL. Once
                        // the worker has retried the same failing URL, retain the failure evidence
                        // and cancel its scheduled replay instead of waiting through more retries.
                        if (state == WorkInfo.State.ENQUEUED && attempts > 0) break
                        Thread.sleep(300)
                    }
                    check(state == WorkInfo.State.SUCCEEDED) {
                        "Worker did not complete platform=${platform.key} media=${media.type} index=$index state=$state attempts=$attempts host=${URL(media.url).host}"
                    }
                    activeRequestId = null
                    contentResolver.query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        arrayOf(
                            MediaStore.MediaColumns._ID,
                            MediaStore.MediaColumns.MIME_TYPE,
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            MediaStore.MediaColumns.IS_PENDING,
                            MediaStore.MediaColumns.SIZE,
                        ),
                        "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                        arrayOf(fileName),
                        null,
                    ).use { cursor ->
                        check(cursor?.count == 1) { "Expected one committed MediaStore row for $fileName" }
                        check(cursor!!.moveToFirst())
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        val mime = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)).orEmpty()
                        val relativePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)).orEmpty()
                        val pending = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING))
                        val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))
                        val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                        published += uri
                        check(relativePath.endsWith("$folder/")) { "Unexpected path=$relativePath" }
                        check(pending == 0 && size > 0L) { "Incomplete item pending=$pending size=$size" }
                        val tracks = try {
                            verifyDownloadedMedia(contentResolver, uri, media.type, mime, media.audioUrl.isNotBlank())
                        } catch (error: Exception) {
                            val evidence = File(app.cacheDir, "edge-failure-$runId-$index.$extension")
                            contentResolver.openInputStream(uri)!!.use { input ->
                                evidence.outputStream().use { output -> input.copyTo(output) }
                            }
                            throw IllegalStateException("Media verification failed bytes=$size evidence=${evidence.absolutePath}", error)
                        }
                        results += "${media.type.name}[${index + 1}] bytes=$size mime=$mime tracks=$tracks"
                        check(contentResolver.delete(uri, null, null) == 1) { "Could not remove test-created row $fileName" }
                        published.remove(uri)
                    }
                }
            } finally {
                published.forEach { contentResolver.delete(it, null, null) }
                activeRequestId?.let { manager.cancelWorkById(it).result.get(10, TimeUnit.SECONDS) }
                removeRowsFromExactTestFolder(contentResolver, folder)
            }
            sendStatus(0, Bundle().apply {
                putString("stream", "PASS platform=${platform.key} id=${post.id} media=${post.media.size} types=${post.media.map { it.type.name }} downloads=${results.joinToString(";")} cleanup=removed\n")
            })
        } finally {
            activity?.let { runOnMainSync { it.finish() } }
        }
    }

    private fun verifyDownloadedMedia(
        resolver: ContentResolver,
        uri: Uri,
        type: MediaType,
        mime: String,
        requireAudio: Boolean = false,
    ): String {
        check(mime.isNotBlank()) { "Missing MediaStore MIME type" }
        if (type == MediaType.IMAGE) {
            check(mime.startsWith("image/")) { "Image has wrong MIME=$mime" }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            check(options.outWidth > 0 && options.outHeight > 0) {
                "Image bytes cannot be decoded; dimensions=${options.outWidth}x${options.outHeight}"
            }
            return "image=${options.outWidth}x${options.outHeight}"
        }
        if (type == MediaType.AUDIO) check(mime.startsWith("audio/")) { "Audio has wrong MIME=$mime" }
        else check(mime.startsWith("video/")) { "Video has wrong MIME=$mime" }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(targetContext, uri, null)
            val tracks = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME).orEmpty() }
            check(tracks.any { if (type == MediaType.AUDIO) it.startsWith("audio/") else it.startsWith("video/") }) {
                "No playable $type track in output: $tracks"
            }
            if (requireAudio) check(tracks.any { it.startsWith("audio/") }) {
                "Separate source audio was lost during mux: $tracks"
            }
            if (options?.getString("requireDecodedFrame") == "true" && type == MediaType.VIDEO) {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(targetContext, uri)
                    val frame = retriever.getFrameAtTime(1_000_000, android.media.MediaMetadataRetriever.OPTION_CLOSEST)
                    check(frame != null) { "Video track exists but Android cannot decode a frame" }
                    frame.recycle()
                } finally { retriever.release() }
            }
            return (0 until extractor.trackCount).joinToString(",") { index ->
                val format = extractor.getTrackFormat(index)
                val trackMime = tracks[index]
                if (trackMime.startsWith("video/") && format.containsKey(android.media.MediaFormat.KEY_WIDTH) &&
                    format.containsKey(android.media.MediaFormat.KEY_HEIGHT)) {
                    "$trackMime:${format.getInteger(android.media.MediaFormat.KEY_WIDTH)}x${format.getInteger(android.media.MediaFormat.KEY_HEIGHT)}"
                } else trackMime
            }
        } finally {
            extractor.release()
        }
    }

    private fun removeRowsFromExactTestFolder(resolver: ContentResolver, folder: String) {
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            selection,
            arrayOf("Download/$folder/"),
            null,
        ).use { cursor ->
            if (cursor == null) return
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                resolver.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null)
            }
        }
    }

}

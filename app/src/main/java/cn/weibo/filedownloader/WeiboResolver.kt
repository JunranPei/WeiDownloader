package cn.weibo.filedownloader

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal data class WeiboResolvedLink(
    val sourceUrl: String,
    val canonicalUrl: String,
    val target: WeiboLinkTarget,
)

/** Expands copied text and short links before classifying the Weibo target. */
internal class WeiboLinkResolver(
    private val http: WeiboHttpClient,
    private val context: Context,
) {
    fun resolve(sharedText: String): WeiboResolvedLink {
        val sourceUrl = WeiboLinkParser.extractUrl(sharedText)
        if (sourceUrl.isBlank()) {
            throw IllegalArgumentException(context.getString(R.string.url_not_found))
        }

        // Legacy video.weibo.com URLs already carry the complete fid. They now
        // redirect through a visitor/login page, so classifying the source first
        // avoids turning a valid public-video URL into an unrelated login URL.
        WeiboLinkParser.targetFromUrl(sourceUrl)?.let { directTarget ->
            return WeiboResolvedLink(sourceUrl, sourceUrl, directTarget)
        }

        val canonicalUrl = http.resolveRedirects(sourceUrl)
        val target = WeiboLinkParser.targetFromUrl(canonicalUrl)
            ?: throw IllegalArgumentException(context.getString(R.string.post_id_not_found))
        return WeiboResolvedLink(sourceUrl, canonicalUrl, target)
    }
}

internal interface WeiboStatusDetailProvider {
    val name: String
    fun load(statusId: String): JSONObject?
}

internal class WeiboMobileStatusDetailProvider(
    private val http: WeiboHttpClient,
) : WeiboStatusDetailProvider {
    override val name: String = "mobile-status"

    override fun load(statusId: String): JSONObject? = http.getJson(
        "https://m.weibo.cn/statuses/show?id=${encode(statusId)}",
    ).optJSONObject("data")
}

internal class WeiboDesktopStatusDetailProvider(
    private val http: WeiboHttpClient,
    private val context: Context,
) : WeiboStatusDetailProvider {
    override val name: String = "desktop-status"

    override fun load(statusId: String): JSONObject? {
        val payload = http.getJson(
            "https://weibo.com/ajax/statuses/show?id=${encode(statusId)}&locale=zh-CN&isGetLongText=true",
        )
        if (payload.optString("id").isBlank()) {
            throw WeiboException(payload.optString("msg", context.getString(R.string.post_not_returned)))
        }
        return payload
    }
}

internal interface WeiboVideoDetailProvider {
    val name: String
    fun load(objectId: String): JSONObject?
}

/** One configurable provider covers both H5 and authenticated desktop video components. */
internal class WeiboComponentVideoDetailProvider private constructor(
    override val name: String,
    private val http: WeiboHttpClient,
    private val cookie: String,
    private val context: Context,
    private val endpoint: String,
    private val pagePrefix: String,
    private val refererPrefix: String,
    private val requiresSession: Boolean,
) : WeiboVideoDetailProvider {
    override fun load(objectId: String): JSONObject? {
        if (requiresSession && !cookie.contains("SUB=")) return null
        val requestData = JSONObject()
            .put("Component_Play_Playinfo", JSONObject().put("oid", objectId))
            .toString()
        val body = "data=${encode(requestData)}".toByteArray(StandardCharsets.UTF_8)
        val page = "$pagePrefix$objectId"
        val payload = http.requestJson(
            address = "$endpoint?page=${encode(page)}",
            method = "POST",
            body = body,
            referer = "$refererPrefix$objectId",
            contentType = "application/x-www-form-urlencoded; charset=UTF-8",
        )
        return payload.optJSONObject("data")?.optJSONObject("Component_Play_Playinfo")
            ?: payload.optJSONObject("Component_Play_Playinfo")
            ?: throw WeiboException(payload.optString("msg", context.getString(R.string.no_media)))
    }

    companion object {
        fun h5(http: WeiboHttpClient, cookie: String, context: Context) =
            WeiboComponentVideoDetailProvider(
                name = "h5-video-component",
                http = http,
                cookie = cookie,
                context = context,
                endpoint = "https://h5.video.weibo.com/api/component",
                pagePrefix = "/show/",
                refererPrefix = "https://h5.video.weibo.com/show/",
                requiresSession = false,
            )

        fun desktop(http: WeiboHttpClient, cookie: String, context: Context) =
            WeiboComponentVideoDetailProvider(
                name = "desktop-video-component",
                http = http,
                cookie = cookie,
                context = context,
                endpoint = "https://weibo.com/tv/api/component",
                pagePrefix = "/tv/show/",
                refererPrefix = "https://weibo.com/tv/show/",
                requiresSession = true,
            )
    }
}

/** Owns authenticated Weibo HTTP behavior shared by all detail providers. */
internal class WeiboHttpClient(
    private val cookie: String,
    private val context: Context,
) {
    fun getJson(address: String): JSONObject = requestJson(address)

    fun requestJson(
        address: String,
        method: String = "GET",
        body: ByteArray? = null,
        referer: String = if (address.contains("weibo.com/ajax/")) {
            "https://weibo.com/"
        } else {
            "https://m.weibo.cn/"
        },
        contentType: String? = null,
    ): JSONObject {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", AGENT)
            setRequestProperty("Referer", referer)
            setRequestProperty("Cookie", cookie)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("X-Requested-With", "XMLHttpRequest")
            xsrfToken()?.let { setRequestProperty("X-XSRF-TOKEN", it) }
            if (contentType != null) setRequestProperty("Content-Type", contentType)
            if (body != null) {
                doOutput = true
                setFixedLengthStreamingMode(body.size)
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
                ?: throw WeiboException(context.getString(R.string.weibo_http_error, status))
            val responseBody = BufferedInputStream(input).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            if (status !in 200..299) {
                throw WeiboException(context.getString(R.string.weibo_http_error, status))
            }
            if (responseBody.isBlank()) {
                throw WeiboException(context.getString(R.string.weibo_empty_response))
            }
            return JSONObject(responseBody)
        } catch (error: WeiboException) {
            throw error
        } catch (error: Exception) {
            throw WeiboException(
                context.getString(
                    R.string.weibo_read_error,
                    error.message ?: context.getString(R.string.network_error),
                ),
            )
        } finally {
            connection.disconnect()
        }
    }

    /** Resolve each redirect explicitly so the current Weibo session is retained. */
    fun resolveRedirects(address: String): String {
        var current = address
        repeat(8) {
            val connection = runCatching {
                (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    setRequestProperty("User-Agent", AGENT)
                    setRequestProperty("Referer", "https://weibo.com/")
                    setRequestProperty("Cookie", cookie)
                }
            }.getOrElse { return current }
            try {
                val status = connection.responseCode
                val location = connection.getHeaderField("Location")
                if (status !in 300..399 || location.isNullOrBlank()) return current
                current = URL(URL(current), location).toString()
            } catch (_: Exception) {
                return current
            } finally {
                connection.disconnect()
            }
        }
        return current
    }

    private fun xsrfToken(): String? = Regex("(?:^|;\\s*)XSRF-TOKEN=([^;]+)")
        .find(cookie)?.groupValues?.get(1)?.let {
            runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
        }

    private companion object {
        const val AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36"
    }
}

private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

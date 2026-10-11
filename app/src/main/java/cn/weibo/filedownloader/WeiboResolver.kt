package cn.weibo.filedownloader

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
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
    private val context: Context,
) : WeiboStatusDetailProvider {
    override val name: String = "mobile-status"

    override fun load(statusId: String): JSONObject? {
        val payload = http.getJson("https://m.weibo.cn/statuses/show?id=${encode(statusId)}")
        return payload.optJSONObject("data") ?: throw WeiboException(WeiboApiResponse.errorMessage(
            payload, context.getString(R.string.post_not_returned), context.getString(R.string.weibo_post_unavailable),
        ))
    }
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
            throw WeiboException(WeiboApiResponse.errorMessage(
                payload, context.getString(R.string.post_not_returned), context.getString(R.string.weibo_post_unavailable),
            ))
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
    private val context: Context,
    private val endpoint: String,
    private val pagePrefix: String,
    private val refererPrefix: String,
) : WeiboVideoDetailProvider {
    override fun load(objectId: String): JSONObject? {
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
            ?: throw WeiboException(WeiboApiResponse.errorMessage(
                payload, context.getString(R.string.no_media), context.getString(R.string.weibo_post_unavailable),
            ))
    }

    companion object {
        fun h5(http: WeiboHttpClient, context: Context) =
            WeiboComponentVideoDetailProvider(
                name = "h5-video-component",
                http = http,
                context = context,
                endpoint = "https://h5.video.weibo.com/api/component",
                pagePrefix = "/show/",
                refererPrefix = "https://h5.video.weibo.com/show/",
            )

        fun desktop(http: WeiboHttpClient, context: Context) =
            WeiboComponentVideoDetailProvider(
                name = "desktop-video-component",
                http = http,
                context = context,
                endpoint = "https://weibo.com/tv/api/component",
                pagePrefix = "/tv/show/",
                refererPrefix = "https://weibo.com/tv/show/",
            )
    }
}

/** Owns authenticated Weibo HTTP behavior shared by all detail providers. */
internal class WeiboHttpClient(
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
        var connection: HttpURLConnection? = null
        try {
            val active = WeiboSession.openConnection(address, method, body, buildMap {
                put("Referer", referer)
                put("Accept", "application/json, text/plain, */*")
                put("X-Requested-With", "XMLHttpRequest")
                contentType?.let { put("Content-Type", it) }
            }).also { connection = it }
            val status = active.responseCode
            val input = if (status in 200..299) active.inputStream else active.errorStream
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
            connection?.disconnect()
        }
    }

    /** Resolve each redirect explicitly so the current Weibo session is retained. */
    fun resolveRedirects(address: String): String {
        val connection = WeiboSession.openConnection(address, headers = mapOf("Referer" to "https://weibo.com/"))
        return try {
            connection.url.toString()
        } finally {
            connection.disconnect()
        }
    }
}

private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

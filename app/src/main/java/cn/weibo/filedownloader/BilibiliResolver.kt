package cn.weibo.filedownloader

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal sealed interface BilibiliLinkTarget {
    data class Video(
        val bvid: String = "",
        val aid: Long = 0L,
        val page: Int = 1,
    ) : BilibiliLinkTarget

    data class Opus(val id: String) : BilibiliLinkTarget
}

internal data class BilibiliResolvedLink(
    val sourceUrl: String,
    val canonicalUrl: String,
    val target: BilibiliLinkTarget,
)

internal object BilibiliLinkParser {
    private val urlRegex = Regex("https?://[^\\s\\[\\]()<>]+", RegexOption.IGNORE_CASE)
    private val bvidRegex = Regex("(?:^|/)(BV[0-9A-Za-z]{10})(?:[/\\s?#]|$)", RegexOption.IGNORE_CASE)
    private val avidRegex = Regex("(?:^|/)av(\\d+)(?:[/\\s?#]|$)", RegexOption.IGNORE_CASE)
    private val opusRegex = Regex("/(?:opus/)?(\\d{8,20})(?:[/\\s?#]|$)", RegexOption.IGNORE_CASE)

    fun extractUrl(value: String): String = (urlRegex.find(value.trim())?.value ?: value.trim())
        .trimEnd('.', ',', '!', '?', '，', '。', '！', '？', '）', ')', ']', '；', ';')

    fun isSupported(value: String): Boolean {
        val host = runCatching { URI(extractUrl(value)).host.orEmpty().lowercase() }.getOrDefault("")
        return host == "b23.tv" || host.endsWith(".b23.tv") ||
            host == "bilibili.com" || host.endsWith(".bilibili.com")
    }

    fun targetFromUrl(value: String): BilibiliLinkTarget? {
        val url = extractUrl(value)
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host.orEmpty().lowercase()
        if (host != "bilibili.com" && !host.endsWith(".bilibili.com")) return null
        val page = Regex("(?:^|&)p=(\\d+)(?:&|$)", RegexOption.IGNORE_CASE)
            .find(uri.rawQuery.orEmpty())?.groupValues?.get(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        bvidRegex.find(uri.path.orEmpty())?.groupValues?.get(1)?.let {
            return BilibiliLinkTarget.Video(bvid = "BV" + it.drop(2), page = page)
        }
        avidRegex.find(uri.path.orEmpty())?.groupValues?.get(1)?.toLongOrNull()?.let {
            return BilibiliLinkTarget.Video(aid = it, page = page)
        }
        if (host == "t.bilibili.com" || uri.path.orEmpty().startsWith("/opus/")) {
            opusRegex.find(uri.path.orEmpty())?.groupValues?.get(1)?.let {
                return BilibiliLinkTarget.Opus(it)
            }
        }
        return null
    }
}

internal class BilibiliLinkResolver(
    private val http: BilibiliHttpClient,
    private val context: Context,
) {
    fun resolve(sharedText: String): BilibiliResolvedLink {
        val sourceUrl = BilibiliLinkParser.extractUrl(sharedText)
        if (sourceUrl.isBlank() || !BilibiliLinkParser.isSupported(sourceUrl)) {
            throw BilibiliException(context.getString(R.string.bilibili_url_not_found))
        }
        val canonicalUrl = http.resolveRedirects(sourceUrl)
        val target = BilibiliLinkParser.targetFromUrl(canonicalUrl)
            ?: BilibiliLinkParser.targetFromUrl(sourceUrl)
            ?: throw BilibiliException(context.getString(R.string.bilibili_target_not_found))
        return BilibiliResolvedLink(sourceUrl, canonicalUrl, target)
    }
}

internal class BilibiliHttpClient(
    private val cookie: String,
    private val context: Context,
) {
    fun getJson(address: String, parameters: Map<String, String>): JSONObject =
        JSONObject(requestText(address + "?" + parameters.entries.joinToString("&") {
            "${encode(it.key)}=${encode(it.value)}"
        }))

    fun getPlayJson(parameters: Map<String, String>): JSONObject {
        val key = runCatching {
            BilibiliWbiSigner.mixinKey(getJson("https://api.bilibili.com/x/web-interface/nav", emptyMap()))
        }.getOrNull()
        if (key != null) {
            val signed = runCatching {
                getJson(
                    "https://api.bilibili.com/x/player/wbi/playurl",
                    BilibiliWbiSigner.sign(parameters, key, System.currentTimeMillis() / 1000L),
                )
            }.getOrNull()
            if (signed != null && signed.optInt("code", -1) == 0) return signed
        }
        // Some sessions can still use the legacy desktop endpoint; keep DASH enabled here too.
        return getJson("https://api.bilibili.com/x/player/playurl", parameters)
    }

    fun requestText(address: String, referer: String = BILIBILI_REFERER): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Referer", referer)
            setRequestProperty("Accept", "application/json,text/html,application/xhtml+xml,*/*")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Pragma", "no-cache")
            setRequestProperty("Sec-Fetch-Dest", "empty")
            setRequestProperty("Sec-Fetch-Mode", "cors")
            setRequestProperty("Sec-Fetch-Site", "same-site")
            if (address.contains("api.bilibili.com")) {
                setRequestProperty("Origin", "https://www.bilibili.com")
            }
            if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
        }
        try {
            val status = connection.responseCode
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = input?.let {
                BufferedInputStream(it).bufferedReader(StandardCharsets.UTF_8).use { reader -> reader.readText() }
            }.orEmpty()
            if (status !in 200..299) {
                throw BilibiliException(context.getString(R.string.bilibili_http_error, status))
            }
            if (body.isBlank()) throw BilibiliException(context.getString(R.string.bilibili_empty_response))
            return body
        } catch (error: BilibiliException) {
            throw error
        } catch (error: Exception) {
            throw BilibiliException(
                context.getString(
                    R.string.bilibili_network_error,
                    error.message ?: context.getString(R.string.network_error),
                ),
            )
        } finally {
            connection.disconnect()
        }
    }

    fun resolveRedirects(address: String): String {
        var current = address
        repeat(8) {
            val connection = runCatching {
                (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Referer", BILIBILI_REFERER)
                    if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
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

    companion object {
        const val BILIBILI_REFERER = "https://www.bilibili.com/"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}

class BilibiliException(message: String) : RuntimeException(message)

private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

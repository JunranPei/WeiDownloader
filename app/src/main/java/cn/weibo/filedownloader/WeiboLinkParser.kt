package cn.weibo.filedownloader

import java.net.URI
import java.net.URLDecoder

internal sealed interface WeiboLinkTarget {
    data class Status(val id: String) : WeiboLinkTarget

    data class VideoObject(
        val objectId: String,
        val fallbackStatusId: String? = null,
    ) : WeiboLinkTarget
}

/** Classifies Weibo's post, video-component, mobile, and share URL families. */
internal object WeiboLinkParser {
    private val objectIdPattern = Regex("[0-9]+:[A-Za-z0-9]+")
    private val statusIdPattern = Regex("[A-Za-z0-9]+")

    /** Weibo's copied text can contain prompts around the actual URL. */
    fun extractUrl(value: String): String {
        val found = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
            .find(value.trim())?.value.orEmpty()
        return found.trimEnd('.', '。', '，', ',', '！', '!', '）', ')')
    }

    fun targetFromUrl(address: String): WeiboLinkTarget? = runCatching {
        val uri = URI(address)
        val host = uri.host?.lowercase() ?: return@runCatching null
        if (!isWeiboHost(host)) return@runCatching null

        val parameters = queryParameters(uri.rawQuery)
        val decodedPath = decode(uri.rawPath.orEmpty())
        val objectId = listOf("fid", "object_id", "oid")
            .asSequence()
            .mapNotNull(parameters::get)
            .mapNotNull(::objectIdFrom)
            .firstOrNull()
            ?: objectIdPattern.find(decodedPath)?.value

        if (objectId != null) {
            val fallbackId = listOf("mid", "weibo_id", "segment_id", "id")
                .asSequence()
                .mapNotNull(parameters::get)
                .mapNotNull(::statusIdFrom)
                .firstOrNull()
                ?: statusIdFollowingObject(decodedPath, objectId)
            return@runCatching WeiboLinkTarget.VideoObject(objectId, fallbackId)
        }

        listOf("id", "mid", "bid", "weibo_id")
            .asSequence()
            .mapNotNull(parameters::get)
            .mapNotNull(::statusIdFrom)
            .firstOrNull()
            ?.let { return@runCatching WeiboLinkTarget.Status(it) }

        val segments = decodedPath.split('/').filter(String::isNotBlank)
        val marker = segments.indexOfFirst { it.equals("detail", true) || it.equals("status", true) }
        if (marker >= 0) {
            return@runCatching segments.getOrNull(marker + 1)
                ?.substringBefore('.')
                ?.let(::statusIdFrom)
                ?.let { WeiboLinkTarget.Status(it) }
        }

        // Desktop posts use /uid/bid. Restrict this fallback to avoid treating page names as IDs.
        if ((host == "weibo.com" || host == "www.weibo.com") &&
            segments.size >= 2 && segments.first().all(Char::isDigit)
        ) {
            return@runCatching statusIdFrom(segments.last().substringBefore('.'))?.let { WeiboLinkTarget.Status(it) }
        }
        null
    }.getOrNull()

    private fun queryParameters(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&').mapNotNull { part ->
            val name = decode(part.substringBefore('=')).lowercase()
            if (name.isBlank()) null else name to decode(part.substringAfter('=', ""))
        }.toMap()
    }

    private fun objectIdFrom(value: String): String? = objectIdPattern.find(value)?.value

    private fun statusIdFrom(value: String): String? = value.trim()
        .takeIf { it.isNotBlank() && it.matches(statusIdPattern) }

    private fun statusIdFollowingObject(path: String, objectId: String): String? {
        val tail = path.substringAfter(objectId, "").trim('/')
        return tail.substringBefore('/').substringBefore('.').let(::statusIdFrom)
    }

    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun isWeiboHost(host: String): Boolean =
        host == "weibo.com" || host.endsWith(".weibo.com") ||
            host == "weibo.cn" || host.endsWith(".weibo.cn") ||
            host == "t.cn" || host.endsWith(".t.cn")
}

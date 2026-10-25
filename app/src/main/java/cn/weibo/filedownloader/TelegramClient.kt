package cn.weibo.filedownloader

import android.content.Context
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets

internal data class TelegramPostTarget(
    val username: String,
    val messageId: Long,
) {
    val canonicalUrl: String get() = "https://t.me/$username/$messageId"
}

internal object TelegramLinkParser {
    private val urlRegex = Regex("https?://[^\\s\\[\\]()<>]+", RegexOption.IGNORE_CASE)
    private val usernameRegex = Regex("^[A-Za-z0-9_]{5,32}$")

    fun extractUrl(value: String): String = (urlRegex.find(value.trim())?.value ?: value.trim())
        .trimEnd('.', ',', '!', '?', '，', '。', '！', '？', '）', ')', ']', '；', ';')

    fun targetFromUrl(value: String): TelegramPostTarget? {
        val uri = runCatching { URI(extractUrl(value)) }.getOrNull() ?: return null
        val host = uri.host.orEmpty().lowercase()
        val parts = uri.path.orEmpty().split('/').filter(String::isNotBlank).toMutableList()
        val username = when {
            host in TELEGRAM_HOSTS -> {
                if (parts.firstOrNull().equals("s", true)) parts.removeAt(0)
                if (parts.isEmpty()) "" else parts.removeAt(0)
            }
            host.endsWith(".t.me") -> host.removeSuffix(".t.me")
            else -> return null
        }
        if (!usernameRegex.matches(username) || username.lowercase() in RESERVED_PATHS) return null
        val messageId = parts.firstOrNull()?.toLongOrNull()?.takeIf { it > 0L } ?: return null
        return TelegramPostTarget(username, messageId)
    }

    fun isSupported(value: String): Boolean = targetFromUrl(value) != null

    private val TELEGRAM_HOSTS = setOf("t.me", "www.t.me", "telegram.me", "www.telegram.me", "telegram.dog")
    private val RESERVED_PATHS = setOf("c", "joinchat", "share", "proxy", "socks", "login")
}

class TelegramClient(private val context: Context) {
    private val http = TelegramHttpClient(context)

    fun loadPost(sharedUrl: String): WeiboPost {
        val sourceUrl = TelegramLinkParser.extractUrl(sharedUrl)
        val target = TelegramLinkParser.targetFromUrl(sourceUrl)
            ?: throw TelegramException(context.getString(R.string.telegram_url_not_found))
        val html = http.requestText("${target.canonicalUrl}?embed=1&mode=tme")
        val parsed = TelegramHtmlParser.parsePost(html, target, sourceUrl)
        if (parsed.media.isEmpty()) {
            throw TelegramException(context.getString(R.string.telegram_no_media))
        }
        return parsed
    }
}

internal object TelegramHtmlParser {
    fun parsePost(html: String, target: TelegramPostTarget, sourceUrl: String = target.canonicalUrl): WeiboPost {
        val author = findGroup(
            html,
            Regex("tgme_widget_message_owner_name[^>]*>.*?<span[^>]*>(.*?)</span>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        ).cleanHtml().ifBlank { target.username }
        val avatar = findGroup(
            html,
            Regex("tgme_widget_message_user_photo[^>]*>\\s*<img[^>]+src=[\"']([^\"']+)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        ).decodeHtml().toAbsoluteTelegramUrl()
        val text = findGroup(
            html,
            Regex("<div[^>]+class=[\"'][^\"']*tgme_widget_message_text[^\"']*[\"'][^>]*>(.*?)</div>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        ).cleanHtml()
        val media = parseMedia(html, "${target.username}-${target.messageId}")
        return WeiboPost(
            id = "${target.username}-${target.messageId}",
            text = text.ifBlank { contextFreeTitle(target) },
            author = author,
            sourceUrl = sourceUrl,
            media = media,
            creator = CreatorProfile(
                platform = Platform.TELEGRAM,
                id = target.username,
                name = author,
                avatarUrl = avatar,
                profileUrl = "https://t.me/${target.username}",
            ),
        )
    }

    fun parseMedia(html: String, postId: String): List<MediaItem> {
        val values = mutableListOf<Pair<Int, RawTelegramMedia>>()
        val photoRegex = Regex(
            "<a\\s+([^>]*class=[\"'][^\"']*tgme_widget_message_photo_wrap[^\"']*[\"'][^>]*)>(.*?)</a>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        photoRegex.findAll(html).forEach { match ->
            val attributes = match.groupValues[1]
            val body = match.groupValues[2]
            val url = cssBackgroundUrl(attribute(attributes, "style")).toAbsoluteTelegramUrl()
            if (url.isBlank()) return@forEach
            val width = cssPixels(attribute(attributes, "style"), "width")
            val height = percentHeight(body, width)
            values += match.range.first to RawTelegramMedia(MediaType.IMAGE, url, url, width, height)
        }
        val videoRegex = Regex(
            "<a\\s+([^>]*class=[\"'][^\"']*tgme_widget_message_video_player[^\"']*[\"'][^>]*)>(.*?)</a>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        videoRegex.findAll(html).forEach { match ->
            val body = match.groupValues[2]
            val url = findGroup(
                body,
                Regex("<video[^>]+src=[\"']([^\"']+)", RegexOption.IGNORE_CASE),
            ).decodeHtml().toAbsoluteTelegramUrl()
            if (url.isBlank()) return@forEach
            val thumbnailTag = findGroup(
                body,
                Regex("(<i[^>]+class=[\"'][^\"']*video_thumb[^\"']*[\"'][^>]*>)", RegexOption.IGNORE_CASE),
            )
            val thumbnail = cssBackgroundUrl(attribute(thumbnailTag, "style")).toAbsoluteTelegramUrl()
            val wrapStyle = findGroup(
                body,
                Regex("video_wrap[\"'][^>]+style=[\"']([^\"']+)", RegexOption.IGNORE_CASE),
            )
            val width = cssPixels(wrapStyle, "width")
            val height = percentHeight(wrapStyle, width)
            values += match.range.first to RawTelegramMedia(MediaType.VIDEO, url, thumbnail, width, height)
        }
        return values.sortedBy { it.first }.map { it.second }.distinctBy { it.url }.mapIndexed { index, raw ->
            val variant = MediaVariant(
                key = MediaQuality.PLATFORM_BEST,
                url = raw.url,
                width = raw.width,
                height = raw.height,
            )
            MediaItem(
                type = raw.type,
                url = raw.url,
                quality = MediaQuality.PLATFORM_BEST,
                postId = postId,
                ordinal = index + 1,
                thumbnailUrl = raw.thumbnail,
                variants = listOf(variant),
                selectedVariantKey = variant.key,
            )
        }
    }

    fun splitChannelMessages(html: String, username: String): List<Pair<TelegramPostTarget, String>> {
        val marker = Regex("data-post=[\"']([^/\"']+)/(\\d+)[\"']", RegexOption.IGNORE_CASE)
        val matches = marker.findAll(html).toList()
        return matches.mapIndexedNotNull { index, match ->
            val foundUsername = match.groupValues[1]
            val messageId = match.groupValues[2].toLongOrNull() ?: return@mapIndexedNotNull null
            if (!foundUsername.equals(username, true)) return@mapIndexedNotNull null
            val end = matches.getOrNull(index + 1)?.range?.first ?: html.length
            TelegramPostTarget(foundUsername, messageId) to html.substring(match.range.first, end)
        }
    }

    fun channelName(html: String, fallback: String): String = findGroup(
        html,
        Regex("tgme_channel_info_header_title[^>]*>\\s*<span[^>]*>(.*?)</span>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
    ).cleanHtml().ifBlank { fallback }

    fun channelAvatar(html: String): String = findGroup(
        html,
        Regex("tgme_channel_info_header[\\s\\S]*?tgme_page_photo_image[^>]*>\\s*<img[^>]+src=[\"']([^\"']+)", RegexOption.IGNORE_CASE),
    ).decodeHtml().toAbsoluteTelegramUrl()

    fun nextBefore(html: String, username: String): String = Regex(
        "href=[\"']/s/${Regex.escape(username)}\\?before=(\\d+)[\"']",
        RegexOption.IGNORE_CASE,
    ).find(html)?.groupValues?.get(1).orEmpty()

    private fun contextFreeTitle(target: TelegramPostTarget): String = "Telegram ${target.messageId}"

    private fun attribute(attributes: String, name: String): String = Regex(
        "\\b${Regex.escape(name)}\\s*=\\s*([\"'])(.*?)\\1",
        RegexOption.IGNORE_CASE,
    ).find(attributes)?.groupValues?.get(2).orEmpty()

    private fun cssBackgroundUrl(style: String): String = Regex(
        "background-image\\s*:\\s*url\\(\\s*[\"']?([^\"')]+)",
        RegexOption.IGNORE_CASE,
    ).find(style)?.groupValues?.get(1).orEmpty().decodeHtml()

    private fun cssPixels(style: String, property: String): Int = Regex(
        "${Regex.escape(property)}\\s*:\\s*(\\d+(?:\\.\\d+)?)px",
        RegexOption.IGNORE_CASE,
    ).find(style)?.groupValues?.get(1)?.toDoubleOrNull()?.toInt() ?: 0

    private fun percentHeight(value: String, width: Int): Int {
        if (width <= 0) return 0
        val percent = Regex("padding-top\\s*:\\s*(\\d+(?:\\.\\d+)?)%", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.get(1)?.toDoubleOrNull() ?: return 0
        return (width * percent / 100.0).toInt()
    }

    private fun findGroup(value: String, regex: Regex): String = regex.find(value)?.groupValues?.get(1).orEmpty()

    private fun String.cleanHtml(): String = replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</(?:p|div)\\s*>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .decodeHtml()
        .replace('\u00a0', ' ')
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    private fun String.decodeHtml(): String {
        var decoded = this
            .replace("&amp;", "&", true)
            .replace("&quot;", "\"", true)
            .replace("&#39;", "'", true)
            .replace("&apos;", "'", true)
            .replace("&lt;", "<", true)
            .replace("&gt;", ">", true)
            .replace("&nbsp;", " ", true)
        decoded = Regex("&#(x?[0-9A-Fa-f]+);").replace(decoded) { match ->
            val raw = match.groupValues[1]
            val number = if (raw.startsWith("x", true)) raw.drop(1).toIntOrNull(16) else raw.toIntOrNull()
            number?.let { String(java.lang.Character.toChars(it)) } ?: match.value
        }
        return decoded
    }

    private fun String.toAbsoluteTelegramUrl(): String = when {
        startsWith("//") -> "https:$this"
        startsWith("/") -> "https://t.me$this"
        else -> this
    }

    private data class RawTelegramMedia(
        val type: MediaType,
        val url: String,
        val thumbnail: String,
        val width: Int,
        val height: Int,
    )
}

internal class TelegramHttpClient(private val context: Context) {
    fun requestText(address: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Referer", "https://t.me/")
            setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            val status = connection.responseCode
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = input?.let {
                BufferedInputStream(it).bufferedReader(StandardCharsets.UTF_8).use { reader -> reader.readText() }
            }.orEmpty()
            if (status !in 200..299) throw TelegramException(context.getString(R.string.telegram_http_error, status))
            if (body.isBlank()) throw TelegramException(context.getString(R.string.telegram_empty_response))
            return body
        } catch (error: TelegramException) {
            throw error
        } catch (error: Exception) {
            throw TelegramException(
                context.getString(R.string.telegram_network_error, error.message ?: context.getString(R.string.network_error)),
            )
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}

class TelegramException(message: String) : RuntimeException(message)

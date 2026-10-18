package cn.weibo.filedownloader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Coordinates Bilibili link resolution while keeping video and opus parsing replaceable. */
class BilibiliClient(
    cookie: String,
    private val context: Context,
) {
    private val http = BilibiliHttpClient(cookie, context)
    private val linkResolver = BilibiliLinkResolver(http, context)
    private val videoProviders: List<BilibiliVideoPostProvider> = listOf(
        BilibiliWebVideoPostProvider(http, context),
    )
    private val opusProviders: List<BilibiliOpusPostProvider> = listOf(
        BilibiliWebOpusPostProvider(http, context),
    )

    fun loadPost(sharedUrl: String): WeiboPost {
        val resolved = linkResolver.resolve(sharedUrl)
        return when (val target = resolved.target) {
            is BilibiliLinkTarget.Video -> loadFrom(videoProviders) { it.load(resolved, target) }
            is BilibiliLinkTarget.Opus -> loadFrom(opusProviders) { it.load(resolved, target) }
        }
    }

    private fun <T> loadFrom(providers: List<T>, request: (T) -> WeiboPost): WeiboPost {
        var lastFailure: Exception? = null
        providers.forEach { provider ->
            try {
                return request(provider)
            } catch (error: Exception) {
                lastFailure = error
            }
        }
        throw lastFailure ?: BilibiliException(context.getString(R.string.bilibili_read_failed))
    }
}

internal interface BilibiliVideoPostProvider {
    fun load(link: BilibiliResolvedLink, target: BilibiliLinkTarget.Video): WeiboPost
}

internal interface BilibiliOpusPostProvider {
    fun load(link: BilibiliResolvedLink, target: BilibiliLinkTarget.Opus): WeiboPost
}

/** Loads UGC metadata and authenticated DASH or progressive sources for the requested part. */
internal class BilibiliWebVideoPostProvider(
    private val http: BilibiliHttpClient,
    private val context: Context,
) : BilibiliVideoPostProvider {
    override fun load(link: BilibiliResolvedLink, target: BilibiliLinkTarget.Video): WeiboPost {
        val identity = if (target.bvid.isNotBlank()) {
            mapOf("bvid" to target.bvid)
        } else {
            mapOf("aid" to target.aid.toString())
        }
        val detail = requireData(http.getJson(VIEW_ENDPOINT, identity))
        val bvid = detail.optString("bvid").ifBlank { target.bvid }
        val aid = detail.optLong("aid", target.aid)
        val pages = detail.optJSONArray("pages") ?: JSONArray()
        val pageIndex = (target.page - 1).coerceIn(0, (pages.length() - 1).coerceAtLeast(0))
        val page = pages.optJSONObject(pageIndex)
        val cid = page?.optLong("cid")?.takeIf { it > 0L } ?: detail.optLong("cid")
        if (cid <= 0L) throw BilibiliException(context.getString(R.string.bilibili_video_source_missing))

        val dashParams = BilibiliPlayRequest.parameters(bvid, aid, cid)
        val initialDash = requireData(http.getPlayJson(dashParams))
        val dashVariants = BilibiliDashParser.variants(initialDash, qualityDescriptions(initialDash))
        val segments = LinkedHashMap<Int, MutableList<Pair<Int, MediaVariant>>>()
        if (dashVariants.isNotEmpty()) {
            segments[0] = dashVariants.mapIndexed { index, variant ->
                (dashVariants.size - index) to variant
            }.toMutableList()
        } else {
            val baseParams = dashParams + ("fnval" to "0")
            val initial = if (initialDash.optJSONArray("durl") != null) initialDash else
                requireData(http.getJson(PLAY_ENDPOINT, baseParams))
            val descriptions = qualityDescriptions(initial)
            val accepted = initial.optJSONArray("accept_quality")?.toIntList().orEmpty()
                .ifEmpty { listOf(initial.optInt("quality")) }
                .filter { it > 0 }
                .distinct()
                .sortedDescending()
            val playByQuality = LinkedHashMap<Int, JSONObject>()
            playByQuality[initial.optInt("quality")] = initial
            accepted.forEach { requested ->
                if (requested == initial.optInt("quality")) return@forEach
                val result = runCatching {
                    requireData(http.getJson(PLAY_ENDPOINT, baseParams + ("qn" to requested.toString())))
                }.getOrNull() ?: return@forEach
                playByQuality.putIfAbsent(result.optInt("quality", requested), result)
            }

            playByQuality.forEach { (quality, data) ->
                val label = descriptions[quality] ?: qualityLabel(quality)
                val dimension = page?.optJSONObject("dimension") ?: detail.optJSONObject("dimension")
                val (width, height) = dimensionsForQuality(quality, dimension)
                data.optJSONArray("durl")?.forEachObjectIndexed { index, source ->
                    val url = source.optString("url").ifBlank {
                        source.optJSONArray("backup_url")?.optString(0).orEmpty()
                    }
                    if (url.isBlank()) return@forEachObjectIndexed
                    segments.getOrPut(index) { mutableListOf() } += quality to MediaVariant(
                        key = label,
                        url = url,
                        width = width,
                        height = height,
                        bitrate = 0L,
                    )
                }
            }
            if (segments.isEmpty()) {
                throw BilibiliException(context.getString(R.string.bilibili_video_source_missing))
            }
        }
        val postId = bvid.ifBlank { "av$aid" } + if (target.page > 1) "_p${target.page}" else ""
        val thumbnail = detail.optString("pic").toHttps()
        val media = segments.entries.sortedBy { it.key }.mapIndexed { index, entry ->
            val ranked = entry.value.sortedByDescending { it.first }.map { it.second }.distinctBy { it.url }
            val selected = ranked.first()
            MediaItem(
                type = MediaType.VIDEO,
                url = selected.url,
                quality = selected.key,
                postId = postId,
                ordinal = index + 1,
                thumbnailUrl = thumbnail,
                variants = ranked,
                selectedVariantKey = selected.key,
                audioUrl = selected.audioUrl,
                sourceId = "$cid:${entry.key}",
            )
        }
        val owner = detail.optJSONObject("owner")
        val author = owner?.optString("name").orEmpty()
            .ifBlank { context.getString(R.string.bilibili_default_author) }
        val baseTitle = detail.optString("title").ifBlank { context.getString(R.string.bilibili_default_title) }
        val partTitle = page?.optString("part").orEmpty()
        val title = if (pages.length() > 1 && partTitle.isNotBlank()) "$baseTitle · $partTitle" else baseTitle
        return WeiboPost(
            id = postId,
            text = title,
            author = author,
            sourceUrl = link.sourceUrl,
            media = media,
            creator = CreatorProfile(
                platform = Platform.BILIBILI,
                id = owner?.optLong("mid")?.toString().orEmpty(),
                name = author,
                avatarUrl = owner?.optString("face").orEmpty().toHttps(),
                profileUrl = owner?.optLong("mid")?.takeIf { it > 0L }?.let { "https://space.bilibili.com/$it" }.orEmpty(),
            ),
        )
    }

    private fun qualityDescriptions(data: JSONObject): Map<Int, String> {
        val qualities = data.optJSONArray("accept_quality")?.toIntList().orEmpty()
        val descriptions = data.optJSONArray("accept_description")?.toStringList().orEmpty()
        return qualities.mapIndexedNotNull { index, quality ->
            descriptions.getOrNull(index)?.takeIf(String::isNotBlank)?.let { quality to it }
        }.toMap()
    }

    private fun qualityLabel(quality: Int): String = when (quality) {
        127 -> "8K"
        126 -> "Dolby Vision"
        125 -> "HDR"
        120 -> "4K"
        116 -> "1080P60"
        112 -> "1080P+"
        80 -> "1080P"
        74 -> "720P60"
        64 -> "720P"
        32 -> "480P"
        16 -> "360P"
        else -> "Q$quality"
    }

    private fun dimensionsForQuality(quality: Int, source: JSONObject?): Pair<Int, Int> {
        val sourceWidth = source?.optInt("width") ?: 0
        val sourceHeight = source?.optInt("height") ?: 0
        val targetShortSide = when (quality) {
            127 -> 4320
            126, 125, 120 -> 2160
            116, 112, 80 -> 1080
            74, 64 -> 720
            32 -> 480
            16 -> 360
            else -> 0
        }
        if (targetShortSide == 0) return sourceWidth to sourceHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) return (targetShortSide * 16 / 9) to targetShortSide
        return if (sourceWidth >= sourceHeight) {
            ((targetShortSide.toDouble() * sourceWidth / sourceHeight).toInt()) to targetShortSide
        } else {
            targetShortSide to ((targetShortSide.toDouble() * sourceHeight / sourceWidth).toInt())
        }
    }

    private companion object {
        const val VIEW_ENDPOINT = "https://api.bilibili.com/x/web-interface/view"
        const val PLAY_ENDPOINT = "https://api.bilibili.com/x/player/playurl"
    }
}

/** Parses the public opus page state, which contains the original image URLs and dimensions. */
internal class BilibiliWebOpusPostProvider(
    private val http: BilibiliHttpClient,
    private val context: Context,
) : BilibiliOpusPostProvider {
    override fun load(link: BilibiliResolvedLink, target: BilibiliLinkTarget.Opus): WeiboPost {
        val pageUrl = "https://www.bilibili.com/opus/${target.id}"
        val html = http.requestText(pageUrl, pageUrl)
        val state = extractAssignedJson(html, "__INITIAL_STATE__")
            ?: throw BilibiliException(context.getString(R.string.bilibili_opus_source_missing))
        val detail = state.optJSONObject("detail")
            ?: throw BilibiliException(context.getString(R.string.bilibili_opus_source_missing))
        val modules = detail.optJSONArray("modules") ?: JSONArray()
        var title = detail.optJSONObject("basic")?.optString("title").orEmpty()
            .removeSuffix(" - 哔哩哔哩")
        var author = ""
        var authorId = ""
        var avatar = ""
        val textParts = mutableListOf<String>()
        modules.forEachObject { module ->
            module.optJSONObject("module_title")?.optString("text")?.takeIf(String::isNotBlank)?.let { title = it }
            module.optJSONObject("module_author")?.let { value ->
                author = value.optString("name").ifBlank { author }
                authorId = value.optString("mid").ifBlank { authorId }
                avatar = value.optString("face").ifBlank { avatar }
            }
            module.optJSONObject("module_content")?.optJSONArray("paragraphs")?.forEachObject { paragraph ->
                paragraph.optJSONObject("text")?.optJSONArray("nodes")?.forEachObject { node ->
                    node.optJSONObject("word")?.optString("words")?.takeIf(String::isNotBlank)?.let(textParts::add)
                }
            }
        }
        val images = LinkedHashMap<String, BilibiliImageSource>()
        collectOpusImages(detail, images)
        if (images.isEmpty()) throw BilibiliException(context.getString(R.string.bilibili_opus_source_missing))
        val resolvedAuthor = author.ifBlank { context.getString(R.string.bilibili_default_author) }
        val resolvedTitle = title.ifBlank { textParts.joinToString("").trim() }
            .ifBlank { context.getString(R.string.bilibili_default_title) }
        return WeiboPost(
            id = target.id,
            text = resolvedTitle,
            author = resolvedAuthor,
            sourceUrl = link.sourceUrl,
            media = images.values.mapIndexed { index, image ->
                val variant = MediaVariant(
                    key = context.getString(R.string.image_variant_original),
                    url = image.url,
                    width = image.width,
                    height = image.height,
                )
                MediaItem(
                    type = MediaType.IMAGE,
                    url = image.url,
                    quality = MediaQuality.ORIGINAL_BEST,
                    postId = target.id,
                    ordinal = index + 1,
                    thumbnailUrl = image.url,
                    variants = listOf(variant),
                    selectedVariantKey = variant.key,
                )
            },
            creator = CreatorProfile(
                platform = Platform.BILIBILI,
                id = authorId,
                name = resolvedAuthor,
                avatarUrl = avatar.toHttps(),
                profileUrl = authorId.takeIf(String::isNotBlank)?.let { "https://space.bilibili.com/$it" }.orEmpty(),
            ),
        )
    }

    private fun collectOpusImages(value: Any?, result: LinkedHashMap<String, BilibiliImageSource>) {
        when (value) {
            is JSONObject -> {
                val url = value.optString("url").toHttps()
                if (isOpusImage(url)) {
                    result.putIfAbsent(url, BilibiliImageSource(url, value.optInt("width"), value.optInt("height")))
                }
                value.keys().forEach { key -> collectOpusImages(value.opt(key), result) }
            }
            is JSONArray -> for (index in 0 until value.length()) collectOpusImages(value.opt(index), result)
        }
    }

    private fun isOpusImage(url: String): Boolean = url.contains("hdslb.com/bfs/new_dyn/") ||
        url.contains("hdslb.com/bfs/article/")
}

private data class BilibiliImageSource(val url: String, val width: Int, val height: Int)

internal fun extractAssignedJson(page: String, marker: String): JSONObject? {
    val markerIndex = page.indexOf(marker)
    if (markerIndex < 0) return null
    val start = page.indexOf('{', markerIndex + marker.length)
    if (start < 0) return null
    var depth = 0
    var inString = false
    var escaped = false
    for (index in start until page.length) {
        val char = page[index]
        if (inString) {
            if (escaped) escaped = false
            else if (char == '\\') escaped = true
            else if (char == '"') inString = false
            continue
        }
        when (char) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return runCatching { JSONObject(page.substring(start, index + 1)) }.getOrNull()
            }
        }
    }
    return null
}

private fun requireData(response: JSONObject): JSONObject {
    if (response.optInt("code") != 0) {
        val message = response.optString("message").ifBlank { "Bilibili API ${response.optInt("code")}" }
        throw BilibiliException(message)
    }
    return response.optJSONObject("data") ?: throw BilibiliException("Bilibili returned no data")
}

private fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
    for (index in 0 until length()) optJSONObject(index)?.let(block)
}

private fun JSONArray.forEachObjectIndexed(block: (Int, JSONObject) -> Unit) {
    for (index in 0 until length()) optJSONObject(index)?.let { block(index, it) }
}

private fun JSONArray.toIntList(): List<Int> = buildList {
    for (index in 0 until length()) optInt(index).takeIf { it > 0 }?.let(::add)
}

private fun JSONArray.toStringList(): List<String> = buildList {
    for (index in 0 until length()) optString(index).takeIf(String::isNotBlank)?.let(::add)
}

private fun String.toHttps(): String = when {
    startsWith("//") -> "https:$this"
    startsWith("http://") -> "https://${removePrefix("http://")}" 
    else -> this
}

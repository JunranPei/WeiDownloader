package cn.weibo.filedownloader

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal data class DouyinCreatorWork(
    val id: String,
    val title: String,
    val coverUrl: String,
    val sourceUrl: String,
    val mediaCount: Int,
    val isGallery: Boolean,
)

internal data class DouyinCreatorPage(
    val creator: CreatorProfile,
    val works: List<DouyinCreatorWork>,
    val nextCursor: Long,
    val hasMore: Boolean,
)

internal object DouyinCreatorPageNormalizer {
    fun normalize(response: JSONObject, expectedCreator: CreatorProfile): DouyinCreatorPage? {
        if (response.optInt("status_code", -1) != 0) return null
        val rawWorks = response.optJSONArray("aweme_list") ?: JSONArray()
        var creator = expectedCreator
        val works = buildList {
            for (index in 0 until rawWorks.length()) {
                val raw = rawWorks.optJSONObject(index) ?: continue
                val itemCreator = DouyinWorkNormalizer.parseCreator(raw.optJSONObject("author"))
                if (itemCreator?.id != expectedCreator.id) continue
                creator = itemCreator
                val id = raw.optString("aweme_id").ifBlank { raw.optString("id") }
                if (id.isBlank()) continue
                val images = raw.optJSONObject("image_post_info")?.optJSONArray("images")
                    ?.takeIf { it.length() > 0 }
                    ?: raw.optJSONArray("images")?.takeIf { it.length() > 0 }
                val isGallery = images != null
                val cover = if (images != null) {
                    DouyinWorkNormalizer.previewImageUrl(images.opt(0))
                } else {
                    val video = raw.optJSONObject("video")
                    listOf("origin_cover", "cover", "dynamic_cover", "animated_cover")
                        .asSequence()
                        .mapNotNull { field -> video?.opt(field) }
                        .map(DouyinWorkNormalizer::previewImageUrl)
                        .firstOrNull(String::isNotBlank)
                        .orEmpty()
                }
                add(
                    DouyinCreatorWork(
                        id = id,
                        title = cleanTitle(raw.optString("desc").ifBlank { raw.optString("title") }),
                        coverUrl = cover,
                        sourceUrl = "https://www.douyin.com/${if (isGallery) "note" else "video"}/$id",
                        mediaCount = images?.length()?.coerceAtLeast(1) ?: 1,
                        isGallery = isGallery,
                    ),
                )
            }
        }.distinctBy { it.id }
        return DouyinCreatorPage(
            creator = creator,
            works = works,
            nextCursor = response.optLong("max_cursor", 0L),
            hasMore = response.optInt("has_more", 0) == 1,
        )
    }

    private fun cleanTitle(value: String): String = value
        .replace("……版本过低，升级后可展示全部信息", "")
        .replace("版本过低，升级后可展示全部信息", "")
        .trim()
}

internal class DouyinCreatorRepository {
    fun loadPage(creator: CreatorProfile, cursor: Long): DouyinCreatorPage {
        require(creator.platform == Platform.DOUYIN && creator.id.isNotBlank())
        var lastFailure: Exception? = null
        for (profile in REQUEST_PROFILES) {
            val page = try {
                requestPage(creator, cursor, profile)
            } catch (error: Exception) {
                lastFailure = error
                null
            }
            if (page != null) return page
        }
        throw lastFailure ?: IllegalStateException("Douyin did not return this creator's works")
    }

    private fun requestPage(creator: CreatorProfile, cursor: Long, profile: RequestProfile): DouyinCreatorPage? {
        val params = linkedMapOf<String, String>().apply {
            putAll(profile.parameters)
            putAll(
                mapOf(
                    "sec_user_id" to creator.id,
                    "max_cursor" to cursor.toString(),
                    "count" to PAGE_SIZE.toString(),
                ),
            )
        }
        val query = params.entries.joinToString("&") { (key, value) ->
            "${key.urlEncoded()}=${value.urlEncoded()}"
        }
        val connection = (URL("$POST_ENDPOINT?$query").openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", WEB_USER_AGENT)
            setRequestProperty("Accept", "application/json,text/plain,*/*")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            setRequestProperty("Referer", creator.profileUrl.ifBlank { "https://www.douyin.com/user/${creator.id}" })
        }
        val body = try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) throw IllegalStateException("${profile.name} HTTP $responseCode")
            BufferedInputStream(connection.inputStream).use { it.readBytes().toString(StandardCharsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
        val response = try {
            JSONObject(body)
        } catch (error: Exception) {
            val prefix = body.take(120).replace(Regex("\\s+"), " ")
            throw IllegalStateException("${profile.name} returned ${body.length} non-JSON characters: $prefix", error)
        }
        return DouyinCreatorPageNormalizer.normalize(response, creator)
            ?: throw IllegalStateException("${profile.name} response status ${response.optInt("status_code", -1)}")
    }

    private fun String.urlEncoded(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())

    private companion object {
        data class RequestProfile(val name: String, val parameters: Map<String, String>)

        const val POST_ENDPOINT = "https://www.douyin.com/aweme/v1/web/aweme/post/"
        const val WEB_USER_AGENT = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
        const val PAGE_SIZE = 12
        val REQUEST_PROFILES = listOf(
            RequestProfile(
                name = "pc-googlebot",
                parameters = mapOf(
                    "aid" to "6383",
                    "cookie_enabled" to "true",
                    "platform" to "PC",
                    "source" to "Googlebot-sdc",
                ),
            ),
            RequestProfile(
                name = "pc-webapp",
                parameters = mapOf(
                    "device_platform" to "webapp",
                    "aid" to "6383",
                    "channel" to "channel_pc_web",
                    "publish_video_strategy_type" to "2",
                    "locate_query" to "false",
                    "show_live_replay_strategy" to "1",
                    "need_time_list" to "1",
                    "time_list_query" to "0",
                ),
            ),
        )
    }
}

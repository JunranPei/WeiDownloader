package cn.weibo.filedownloader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class BilibiliCreatorPage(
    val creator: CreatorProfile,
    val works: List<DouyinCreatorWork>,
    val nextOffset: String,
    val hasMore: Boolean,
)

/** Reads one page of an uploader's own media-bearing dynamics. */
internal class BilibiliCreatorRepository(context: Context) {
    private val http = BilibiliHttpClient(BilibiliSession.cookieHeader(), context)

    fun loadPage(creator: CreatorProfile, offset: String): BilibiliCreatorPage {
        require(creator.platform == Platform.BILIBILI && creator.id.isNotBlank())
        val parameters = linkedMapOf(
            "host_mid" to creator.id,
            "platform" to "web",
            "timezone_offset" to "-480",
            "features" to FEATURES,
        ).apply {
            if (offset.isNotBlank()) put("offset", offset)
        }
        return BilibiliCreatorPageNormalizer.normalize(
            http.getJson(ENDPOINT, parameters),
            creator,
        ) ?: throw BilibiliException("Bilibili did not return this creator's dynamics")
    }

    private companion object {
        const val ENDPOINT = "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/space"
        const val FEATURES = "itemOpusStyle,listOnlyfans,opusBigCover,onlyfansVote"
    }
}

internal object BilibiliCreatorPageNormalizer {
    fun normalize(response: JSONObject, expectedCreator: CreatorProfile): BilibiliCreatorPage? {
        if (response.optInt("code", -1) != 0) return null
        val data = response.optJSONObject("data") ?: return null
        val items = data.optJSONArray("items") ?: JSONArray()
        var creator = expectedCreator
        val works = buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val modules = item.optJSONObject("modules") ?: continue
                val author = modules.optJSONObject("module_author") ?: continue
                if (author.optString("mid") != expectedCreator.id) continue
                creator = CreatorProfile(
                    platform = Platform.BILIBILI,
                    id = expectedCreator.id,
                    name = author.optString("name").ifBlank { creator.name },
                    avatarUrl = author.optString("face").toHttpsUrl().ifBlank { creator.avatarUrl },
                    profileUrl = expectedCreator.profileUrl.ifBlank {
                        "https://space.bilibili.com/${expectedCreator.id}/dynamic"
                    },
                )
                if (item.optString("type") == "DYNAMIC_TYPE_FORWARD") continue
                val dynamic = modules.optJSONObject("module_dynamic") ?: continue
                val major = dynamic.optJSONObject("major") ?: continue
                when (major.optString("type")) {
                    "MAJOR_TYPE_ARCHIVE" -> parseArchive(major.optJSONObject("archive"))
                    "MAJOR_TYPE_DRAW" -> parseDraw(item, dynamic, major.optJSONObject("draw"))
                    "MAJOR_TYPE_OPUS" -> parseOpus(item, dynamic, major.optJSONObject("opus"))
                    else -> null
                }?.let(::add)
            }
        }.distinctBy { it.id }
        return BilibiliCreatorPage(
            creator = creator,
            works = works,
            nextOffset = data.optString("offset"),
            hasMore = data.optBoolean("has_more", false),
        )
    }

    private fun parseArchive(archive: JSONObject?): DouyinCreatorWork? {
        archive ?: return null
        val bvid = archive.optString("bvid")
        if (bvid.isBlank()) return null
        return DouyinCreatorWork(
            id = bvid,
            title = archive.optString("title").ifBlank { archive.optString("desc") }.cleanTitle(),
            coverUrl = archive.optString("cover").toHttpsUrl(),
            sourceUrl = "https://www.bilibili.com/video/$bvid",
            mediaCount = 1,
            isGallery = false,
        )
    }

    private fun parseDraw(item: JSONObject, dynamic: JSONObject, draw: JSONObject?): DouyinCreatorWork? {
        draw ?: return null
        val pictures = draw.optJSONArray("items") ?: return null
        if (pictures.length() == 0) return null
        val id = item.optString("id_str")
        if (id.isBlank()) return null
        return DouyinCreatorWork(
            id = id,
            title = dynamic.optJSONObject("desc")?.optString("text").orEmpty().cleanTitle(),
            coverUrl = pictures.optJSONObject(0)?.optString("src").orEmpty().toHttpsUrl(),
            sourceUrl = "https://www.bilibili.com/opus/$id",
            mediaCount = pictures.length(),
            isGallery = true,
        )
    }

    private fun parseOpus(item: JSONObject, dynamic: JSONObject, opus: JSONObject?): DouyinCreatorWork? {
        opus ?: return null
        val pictures = opus.optJSONArray("pics") ?: JSONArray()
        if (pictures.length() == 0) return null
        val id = opus.optString("opus_id").ifBlank { item.optString("id_str") }
        if (id.isBlank()) return null
        val jumpUrl = opus.optString("jump_url").toHttpsUrl()
        val summary = opus.optJSONObject("summary")?.optString("text").orEmpty()
        return DouyinCreatorWork(
            id = id,
            title = opus.optString("title")
                .ifBlank { summary }
                .ifBlank { dynamic.optJSONObject("desc")?.optString("text").orEmpty() }
                .cleanTitle(),
            coverUrl = pictures.optJSONObject(0)?.optString("url").orEmpty().toHttpsUrl(),
            sourceUrl = jumpUrl.ifBlank { "https://www.bilibili.com/opus/$id" },
            mediaCount = pictures.length(),
            isGallery = true,
        )
    }

    private fun String.cleanTitle(): String = replace(Regex("\\s+"), " ").trim()

    private fun String.toHttpsUrl(): String = when {
        startsWith("//") -> "https:$this"
        startsWith("/") -> "https://www.bilibili.com$this"
        startsWith("http://") -> "https://${removePrefix("http://")}" 
        else -> this
    }
}

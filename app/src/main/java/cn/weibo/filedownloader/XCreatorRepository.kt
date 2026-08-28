package cn.weibo.filedownloader

import android.content.Context

internal data class XCreatorPage(
    val creator: CreatorProfile,
    val works: List<DouyinCreatorWork>,
    val nextCursor: String,
    val hasMore: Boolean,
)

internal class XCreatorRepository(private val context: Context) {
    private val http = XHttpClient(context)

    fun loadPage(creator: CreatorProfile, cursor: String): XCreatorPage {
        require(creator.platform == Platform.X && creator.id.isNotBlank())
        val response = http.getJson(
            "https://api.fxtwitter.com/2/profile/${creator.id}/statuses",
            buildMap {
                put("count", "30")
                if (cursor.isNotBlank()) put("cursor", cursor)
            },
        )
        if (response.optInt("code", 0) !in 200..299) throw XException(context.getString(R.string.x_read_failed))
        val results = response.optJSONArray("results") ?: org.json.JSONArray()
        var resolvedCreator = creator
        val works = buildList {
            for (index in 0 until results.length()) {
                val raw = results.optJSONObject(index) ?: continue
                val rawAuthor = raw.optJSONObject("author") ?: continue
                if (!rawAuthor.optString("screen_name").equals(creator.id, true)) continue
                val post = XPostNormalizer.normalize(raw, raw.optString("url"), context) ?: continue
                post.creator?.let { resolvedCreator = it }
                add(
                    DouyinCreatorWork(
                        id = post.id,
                        title = post.text,
                        coverUrl = post.media.firstNotNullOfOrNull { media ->
                            media.thumbnailUrl.ifBlank { media.url.takeIf { media.type == MediaType.IMAGE }.orEmpty() }
                                .takeIf(String::isNotBlank)
                        }.orEmpty(),
                        sourceUrl = post.sourceUrl,
                        mediaCount = post.media.size,
                        isGallery = post.media.size > 1 || post.media.all { it.type == MediaType.IMAGE },
                    ),
                )
            }
        }.distinctBy { it.id }
        val next = response.optJSONObject("cursor")?.optString("bottom").orEmpty()
        return XCreatorPage(resolvedCreator, works, next, next.isNotBlank())
    }
}

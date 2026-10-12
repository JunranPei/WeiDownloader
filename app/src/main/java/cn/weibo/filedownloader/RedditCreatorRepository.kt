package cn.weibo.filedownloader

import android.content.Context
import org.json.JSONObject
import java.net.URLEncoder

internal data class RedditCreatorPage(val works: List<DouyinCreatorWork>, val nextAfter: String)

internal object RedditCreatorPageNormalizer {
    fun normalize(payload: JSONObject, creator: CreatorProfile): RedditCreatorPage? {
        val data = payload.optJSONObject("data") ?: return null
        val children = data.optJSONArray("children") ?: return null
        val works = (0 until children.length()).mapNotNull { index ->
            val child = children.optJSONObject(index) ?: return@mapNotNull null
            if (child.optString("kind") != "t3") return@mapNotNull null
            val raw = child.optJSONObject("data") ?: return@mapNotNull null
            if (!raw.optString("author").equals(creator.id, true)) return@mapNotNull null
            val source = "https://www.reddit.com/comments/${raw.optString("id")}/"
            val post = RedditPostNormalizer.normalizePost(raw, source) ?: return@mapNotNull null
            DouyinCreatorWork(post.id, post.text, post.media.first().let { it.thumbnailUrl.ifBlank {
                if (it.type == MediaType.IMAGE) it.url else ""
            } }, post.sourceUrl, post.media.size, post.media.all { it.type == MediaType.IMAGE })
        }.distinctBy { it.id }
        // An empty media page can still have an after token (e.g. a page of text posts).
        return RedditCreatorPage(works, if (data.isNull("after")) "" else data.optString("after"))
    }
}

internal class RedditCreatorRepository(private val context: Context) {
    fun loadPage(creator: CreatorProfile, after: String): RedditCreatorPage {
        val user = URLEncoder.encode(creator.id, "UTF-8")
        val cursor = if (after.isBlank()) "" else "&after=${URLEncoder.encode(after, "UTF-8")}"
        val url = "https://www.reddit.com/user/$user/submitted.json?raw_json=1&sort=new&limit=100$cursor"
        val payload = RedditHttpClient(context).getJson(url) as? JSONObject
            ?: throw RedditException(context.getString(R.string.reddit_invalid_response))
        return RedditCreatorPageNormalizer.normalize(payload, creator)
            ?: throw RedditException(context.getString(R.string.reddit_invalid_response))
    }
}

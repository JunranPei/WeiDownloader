package cn.weibo.filedownloader

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

internal class XiaohongshuCreatorRepository(private val context: Context) {
    fun loadPage(creator: CreatorProfile): DouyinCreatorPage {
        val connection = (URL(creator.profileUrl.ifBlank { "https://www.xiaohongshu.com/user/profile/${creator.id}" }).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true; connectTimeout = 12_000; readTimeout = 20_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            setRequestProperty("Referer", "https://www.xiaohongshu.com/")
        }
        try {
            if (connection.responseCode !in 200..299) throw XiaohongshuException("HTTP ${connection.responseCode}")
            val html = connection.inputStream.bufferedReader().use { it.readText() }
            val works = Regex("/(?:explore|discovery/item)/([A-Za-z0-9_-]{8,})", RegexOption.IGNORE_CASE).findAll(html)
                .map { it.groupValues[1] }.distinct().map { id ->
                    DouyinCreatorWork(id, "", "", "https://www.xiaohongshu.com/explore/$id", 1, false)
                }.toList()
            return DouyinCreatorPage(creator, works, 0L, false)
        } finally { connection.disconnect() }
    }
}

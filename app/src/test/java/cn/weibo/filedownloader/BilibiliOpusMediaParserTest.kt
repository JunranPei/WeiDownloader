package cn.weibo.filedownloader

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BilibiliOpusMediaParserTest {
    @Test fun realSharedOpusKeepsAllFiveLivePairsInOrder() {
        val detail = JSONObject(javaClass.getResource("/bilibili/opus-1132756461357629440.json")!!.readText())
        val pics = detail.getJSONArray("modules").getJSONObject(0).getJSONObject("module_top")
            .getJSONObject("display").getJSONObject("album").getJSONArray("pics")
        val media = BilibiliOpusMediaParser.parse(detail, "1132756461357629440", "original")
        assertEquals(5, media.size)
        media.forEachIndexed { i, item ->
            assertEquals(MediaType.LIVE_PHOTO, item.type)
            assertEquals(pics.getJSONObject(i).getString("live_url"), item.url)
            assertEquals(pics.getJSONObject(i).getString("url").replace("http://", "https://"), item.coverUrl)
            assertEquals(item.coverUrl, item.thumbnailUrl)
            assertEquals(i + 1, item.ordinal)
            assertEquals(0, item.variants.single().width)
            assertTrue(item.suggestedFileName().endsWith(".mp4"))
            val downloads = DownloadMediaPlanner.expand(item, true)
            assertEquals(listOf(MediaType.LIVE_PHOTO, MediaType.IMAGE), downloads.map { it.type })
            assertEquals(item.coverUrl, downloads.last().url)
            assertEquals(1, DownloadMediaPlanner.expand(item, false).size)
        }
    }

    @Test fun mixedPicturesDeduplicateAndEnrichWithoutChangingOrder() {
        val detail = JSONObject("""{"pics":[
          {"url":"//i0.hdslb.com/bfs/new_dyn/live.jpg","width":3000,"height":4000},
          {"url":"//i0.hdslb.com/bfs/article/still.png","width":1200,"height":800},
          {"url":"//i0.hdslb.com/bfs/new_dyn/live.jpg","width":3000,"height":4000,"live_url":"//i0.hdslb.com/bfs/dyn_video/live.mp4"},
          {"url":"//i0.hdslb.com/bfs/new_dyn/live.jpg"},
          {"url":"//i0.hdslb.com/bfs/face/avatar.jpg"}
        ]}""")
        val media = BilibiliOpusMediaParser.parse(detail, "id", "original")
        assertEquals(listOf(MediaType.LIVE_PHOTO, MediaType.IMAGE), media.map { it.type })
        assertEquals("https://i0.hdslb.com/bfs/dyn_video/live.mp4", media.first().url)
        assertEquals(1200, media.last().variants.single().width)
        assertEquals("", media.last().coverUrl)
    }

    @Test fun absentNullEmptyAndInvalidLiveUrlsRemainStatic() {
        for (live in listOf("", ",\"live_url\":null", ",\"live_url\":\"\"", ",\"live_url\":\"null\"", ",\"live_url\":\"javascript:bad\"")) {
            val detail = JSONObject("""{"url":"https://i0.hdslb.com/bfs/new_dyn/still.jpg"$live}""")
            val item = BilibiliOpusMediaParser.parse(detail, "id", "original").single()
            assertEquals(MediaType.IMAGE, item.type)
            assertTrue(item.url.endsWith("still.jpg"))
        }
        assertTrue(BilibiliOpusMediaParser.parse(JSONObject(), "id", "original").isEmpty())
    }
}

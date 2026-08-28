package cn.weibo.filedownloader

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XParserTest {
    @Test
    fun parsesXAndLegacyTwitterLinks() {
        assertEquals(
            XPostTarget("NASA", "2040059770237849635"),
            XLinkParser.targetFromUrl("https://x.com/NASA/status/2040059770237849635/photo/1"),
        )
        assertEquals(
            XPostTarget("NASA", "2040059770237849635"),
            XLinkParser.targetFromUrl("https://mobile.twitter.com/NASA/status/2040059770237849635"),
        )
        assertFalse(XLinkParser.isSupported("https://x.com/NASA"))
    }

    @Test
    fun normalizesOriginalPhotoAndRankedVideoVariants() {
        val status = JSONObject(
            """
            {
              "id": "42",
              "url": "https://x.com/demo/status/42",
              "text": "mixed media",
              "author": {"screen_name": "demo", "name": "Demo", "avatar_url": "https://avatar"},
              "media": {"all": [
                {"type": "photo", "url": "https://pbs.twimg.com/media/a.jpg?name=orig", "width": 3000, "height": 2000},
                {"type": "video", "thumbnail_url": "https://pbs.twimg.com/thumb.jpg", "formats": [
                  {"container": "m3u8", "url": "https://video.twimg.com/list.m3u8"},
                  {"container": "mp4", "url": "https://video.twimg.com/vid/640x360/low.mp4", "bitrate": 832000},
                  {"container": "mp4", "url": "https://video.twimg.com/vid/1280x720/high.mp4", "bitrate": 2176000}
                ]}
              ]}
            }
            """.trimIndent(),
        )

        val post = requireNotNull(XPostNormalizer.normalize(status, "https://x.com/demo/status/42"))

        assertEquals(listOf(MediaType.IMAGE, MediaType.VIDEO), post.media.map { it.type })
        assertTrue(post.media.first().url.endsWith("name=orig"))
        assertEquals("720p", post.media.last().selectedVariantKey)
        assertEquals(listOf("720p", "360p"), post.media.last().variants.map { it.key })
    }
}

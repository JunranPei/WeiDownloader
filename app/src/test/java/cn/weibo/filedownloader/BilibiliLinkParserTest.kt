package cn.weibo.filedownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class BilibiliLinkParserTest {
    @Test
    fun extractsB23LinkFromCopiedText() {
        val copied = "这个视频很有意思 https://b23.tv/AbCdEf 复制本条链接"
        assertEquals("https://b23.tv/AbCdEf", BilibiliLinkParser.extractUrl(copied))
        assertTrue(BilibiliLinkParser.isSupported(copied))
    }

    @Test
    fun parsesVideoPartWithoutChangingBvidCase() {
        val target = BilibiliLinkParser.targetFromUrl(
            "https://www.bilibili.com/video/BV1bK411W797?p=3",
        ) as BilibiliLinkTarget.Video
        assertEquals("BV1bK411W797", target.bvid)
        assertEquals(3, target.page)
    }

    @Test
    fun parsesOpusAndLegacyDynamicLinks() {
        assertEquals(
            BilibiliLinkTarget.Opus("1148913372417228804"),
            BilibiliLinkParser.targetFromUrl("https://www.bilibili.com/opus/1148913372417228804"),
        )
        assertEquals(
            BilibiliLinkTarget.Opus("998134289197432852"),
            BilibiliLinkParser.targetFromUrl("https://t.bilibili.com/998134289197432852"),
        )
    }

    @Test
    fun extractsBalancedInitialStateJson() {
        val page = "<script>window.__INITIAL_STATE__={\"text\":\"a }; b\",\"nested\":{\"value\":2}};(function(){})</script>"
        val state = extractAssignedJson(page, "__INITIAL_STATE__")
        assertEquals("a }; b", state?.optString("text"))
        assertEquals(2, state?.optJSONObject("nested")?.optInt("value"))
    }

    @Test
    fun normalizesOnlyCreatorsOwnVideoAndImageDynamics() {
        val response = JSONObject(
            """
            {
              "code": 0,
              "data": {
                "has_more": true,
                "offset": "next-page",
                "items": [
                  {
                    "id_str": "video-dynamic",
                    "type": "DYNAMIC_TYPE_AV",
                    "modules": {
                      "module_author": {"mid": 42, "name": "Uploader", "face": "//face.jpg"},
                      "module_dynamic": {"major": {"type": "MAJOR_TYPE_ARCHIVE", "archive": {
                        "bvid": "BV1bK411W797", "title": "A video", "cover": "//cover.jpg"
                      }}}
                    }
                  },
                  {
                    "id_str": "123456789012345678",
                    "type": "DYNAMIC_TYPE_DRAW",
                    "modules": {
                      "module_author": {"mid": 42, "name": "Uploader", "face": "//face.jpg"},
                      "module_dynamic": {"major": {"type": "MAJOR_TYPE_OPUS", "opus": {
                        "opus_id": "123456789012345678", "title": "An image post",
                        "jump_url": "//www.bilibili.com/opus/123456789012345678",
                        "pics": [{"url": "//one.jpg"}, {"url": "//two.jpg"}]
                      }}}
                    }
                  },
                  {
                    "id_str": "forward",
                    "type": "DYNAMIC_TYPE_FORWARD",
                    "modules": {
                      "module_author": {"mid": 42, "name": "Uploader"},
                      "module_dynamic": {"major": {"type": "MAJOR_TYPE_ARCHIVE", "archive": {
                        "bvid": "BV1xx411c7mD", "title": "Forwarded", "cover": "//forward.jpg"
                      }}}
                    }
                  }
                ]
              }
            }
            """.trimIndent(),
        )
        val expected = CreatorProfile(Platform.BILIBILI, "42", "Original")

        val page = requireNotNull(BilibiliCreatorPageNormalizer.normalize(response, expected))

        assertEquals("Uploader", page.creator.name)
        assertEquals("https://face.jpg", page.creator.avatarUrl)
        assertEquals("next-page", page.nextOffset)
        assertTrue(page.hasMore)
        assertEquals(listOf("BV1bK411W797", "123456789012345678"), page.works.map { it.id })
        assertFalse(page.works.first().isGallery)
        assertEquals(2, page.works.last().mediaCount)
        assertEquals("https://www.bilibili.com/opus/123456789012345678", page.works.last().sourceUrl)
    }
}

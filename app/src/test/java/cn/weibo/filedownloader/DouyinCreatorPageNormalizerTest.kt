package cn.weibo.filedownloader

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DouyinCreatorPageNormalizerTest {
    private val creator = CreatorProfile(
        platform = Platform.DOUYIN,
        id = "creator-sec-id",
        name = "Original name",
    )

    @Test fun normalizesVideoAndGalleryPreviewsWithPagination() {
        val response = JSONObject(
            """
            {
              "status_code": 0,
              "has_more": 1,
              "max_cursor": 12345,
              "aweme_list": [
                {
                  "aweme_id": "7385481927806979362",
                  "desc": "video work",
                  "author": {
                    "nickname": "Updated name",
                    "sec_uid": "creator-sec-id",
                    "avatar_thumb": {"url_list": [
                      "https://p3-pc.douyinpic.com/aweme/100x100/avatar.jpeg"
                    ]}
                  },
                  "video": {"cover": {"url_list": [
                    "https://p3-sign.douyinpic.com/tos-cn-i-0813/video-cover.jpeg"
                  ]}}
                },
                {
                  "aweme_id": "7674552954225867688",
                  "desc": "gallery work",
                  "author": {"nickname": "Updated name", "sec_uid": "creator-sec-id"},
                  "image_post_info": {"images": [
                    {"url_list": ["https://p3-sign.douyinpic.com/tos-cn-i-0813/image-1.jpeg"]},
                    {"url_list": ["https://p3-sign.douyinpic.com/tos-cn-i-0813/image-2.jpeg"]}
                  ]}
                }
              ]
            }
            """.trimIndent(),
        )

        val page = DouyinCreatorPageNormalizer.normalize(response, creator)

        assertNotNull(page)
        assertEquals("Updated name", page?.creator?.name)
        assertEquals(2, page?.works?.size)
        assertFalse(page?.works?.first()?.isGallery ?: true)
        assertTrue(page?.works?.last()?.isGallery == true)
        assertEquals(2, page?.works?.last()?.mediaCount)
        assertTrue(page?.works?.last()?.sourceUrl.orEmpty().contains("/note/"))
        assertEquals(12345L, page?.nextCursor)
        assertTrue(page?.hasMore == true)
    }

    @Test fun rejectsErrorResponsesAndFiltersMismatchedCreators() {
        val error = JSONObject("""{"status_code": 4}""")
        assertEquals(null, DouyinCreatorPageNormalizer.normalize(error, creator))

        val response = JSONObject(
            """
            {
              "status_code": 0,
              "has_more": 0,
              "aweme_list": [{
                "aweme_id": "7385481927806979362",
                "author": {"nickname": "Someone else", "sec_uid": "another-id"},
                "video": {"cover": {"url_list": [
                  "https://p3-sign.douyinpic.com/tos-cn-i-0813/video-cover.jpeg"
                ]}}
              }]
            }
            """.trimIndent(),
        )

        val page = DouyinCreatorPageNormalizer.normalize(response, creator)

        assertNotNull(page)
        assertTrue(page?.works?.isEmpty() == true)
        assertFalse(page?.hasMore ?: true)
    }
}

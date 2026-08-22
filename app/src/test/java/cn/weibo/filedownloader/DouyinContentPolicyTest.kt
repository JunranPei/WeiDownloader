package cn.weibo.filedownloader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

class DouyinContentPolicyTest {
    @Test fun extractsShortUrlFromTheFullCopiedShareText() {
        val copied = "3.87 # 甜妹# 喵# 1 # 甜妹  https://v.douyin.com/kSG3Qqi0fks/ " +
            "复制此链接，打开抖音搜索，直接观看视频！ 12/31 BGi:/ i\\@p.Qk :1pm"
        assertTrue(DouyinLinkParser.extractUrl(copied) == "https://v.douyin.com/kSG3Qqi0fks/")
    }

    @Test fun extractsShortUrlFromMarkdownLinkFormatting() {
        val copied = "[https://v.douyin.com/kSG3Qqi0fks/](https://v.douyin.com/kSG3Qqi0fks/)"
        assertTrue(DouyinLinkParser.extractUrl(copied) == "https://v.douyin.com/kSG3Qqi0fks/")
    }

    @Test fun recognizesSlidesPostIdAfterShortLinkRedirect() {
        val redirected = "https://www.iesdouyin.com/share/slides/7674552954225867688/?is_slides=1"
        assertTrue(DouyinLinkParser.extractPostId(redirected) == "7674552954225867688")
    }

    @Test fun recognizesGalleryAndModalIdWithoutDependingOnOneRouteName() {
        assertEquals(
            "7674552954225867688",
            DouyinLinkParser.extractPostId("https://www.douyin.com/gallery/7674552954225867688"),
        )
        assertEquals(
            "7385481927806979362",
            DouyinLinkParser.extractPostId("https://www.douyin.com/discover?modal_id=7385481927806979362"),
        )
        assertEquals(
            "7674552954225867688",
            DouyinLinkParser.extractPostId("https://www.douyin.com/future-route/7674552954225867688"),
        )
    }

    @Test fun rejectsNonDouyinShareHostsBeforeFollowingThem() {
        assertFalse(DouyinLinkParser.isSupportedShareUrl("https://example.com/video/7385481927806979362"))
        assertTrue(DouyinLinkParser.isSupportedShareUrl("https://v.douyin.com/example/"))
    }

    @Test fun rejectsTheGenericDouyinPromotionalVideo() {
        assertFalse(
            DouyinContentPolicy.isScopedVideoUrl(
                "https://lf-douyin-pc-web.douyinstatic.com/obj/douyin-pc-web/uuu_265.mp4",
            ),
        )
    }

    @Test fun acceptsAPlaybackResourceFromDouyinVod() {
        assertTrue(
            DouyinContentPolicy.isTrustedVideoResource(
                "https://v3-web.douyinvod.com/video/tos/cn/tos-cn-ve-15/sample/?a=1",
            ),
        )
    }

    @Test fun doesNotTreatAnUnrelatedMp4AsAPlaybackResource() {
        assertFalse(DouyinContentPolicy.isTrustedVideoResource("https://example.com/banner.mp4"))
        assertFalse(DouyinContentPolicy.isTrustedVideoResource("https://example.com/aweme/v1/play/?video_id=1"))
    }

    @Test fun acceptsOnlyDouyinWorkImages() {
        assertTrue(
            DouyinContentPolicy.isContentImageUrl(
                "https://p3-pc-sign.douyinpic.com/tos-cn-i-0813c001/work~tplv-dy-aweme-images:q75.webp",
            ),
        )
        assertFalse(
            DouyinContentPolicy.isContentImageUrl(
                "https://p3-pc.douyinpic.com/aweme/100x100/avatar.jpeg",
            ),
        )
    }

    @Test fun normalizesGalleryFromImagePostInfoAndAvoidsWatermarkedDownloadAddress() {
        val detail = JSONObject(
            """
            {
              "aweme_id": "7674552954225867688",
              "desc": "gallery",
              "author": {"nickname": "author"},
              "image_post_info": {
                "images": [{
                  "width": 1088,
                  "height": 1440,
                  "download_addr": {"url_list": [
                    "https://p3-sign.douyinpic.com/tos-cn-i-0813/work-download.jpeg"
                  ]},
                  "url_list": [
                    "https://p3-sign.douyinpic.com/tos-cn-i-0813/work-preview.webp",
                    "https://p3-sign.douyinpic.com/tos-cn-i-0813/work-preview.jpeg"
                  ]
                }]
              }
            }
            """.trimIndent(),
        )

        val work = DouyinWorkNormalizer.normalize(detail, "7674552954225867688")

        assertNotNull(work)
        assertEquals(1, work?.media?.size)
        assertEquals(MediaType.IMAGE, work?.media?.single()?.type)
        assertTrue(work?.media?.single()?.url.orEmpty().contains("work-preview.jpeg"))
        assertEquals("1088×1440", work?.media?.single()?.selectedVariantKey)
    }

    @Test fun keepsCreatorIdentityAndAvatarFromDetailResponse() {
        val detail = JSONObject(
            """
            {
              "aweme_id": "7385481927806979362",
              "author": {
                "nickname": "creator",
                "sec_uid": "secure-creator-id",
                "avatar_thumb": {"url_list": [
                  "https://p3-pc.douyinpic.com/aweme/100x100/avatar.jpeg"
                ]}
              },
              "video": {
                "play_addr": {"url_list": [
                  "https://v3-web.douyinvod.com/video/tos/cn/source/"
                ]}
              }
            }
            """.trimIndent(),
        )

        val creator = DouyinWorkNormalizer.normalize(detail, "7385481927806979362")?.creator

        assertEquals("secure-creator-id", creator?.id)
        assertEquals("creator", creator?.name)
        assertTrue(creator?.avatarUrl.orEmpty().contains("avatar.jpeg"))
        assertEquals(Platform.DOUYIN, creator?.platform)
    }

    @Test fun normalizesLivePhotoAsOnePairedMediaItem() {
        val detail = JSONObject(
            """
            {
              "aweme_id": "7674552954225867688",
              "images": [{
                "url_list": ["https://p3-sign.douyinpic.com/tos-cn-i-0813/live-cover.jpeg"],
                "video": {
                  "play_addr": {
                    "width": 1080,
                    "height": 1440,
                    "url_list": ["https://v3-web.douyinvod.com/video/tos/cn/live-photo/"]
                  }
                }
              }]
            }
            """.trimIndent(),
        )

        val media = DouyinWorkNormalizer.normalize(detail, "7674552954225867688")?.media?.single()

        assertEquals(MediaType.LIVE_PHOTO, media?.type)
        assertTrue(media?.coverUrl.orEmpty().contains("live-cover.jpeg"))
        assertTrue(media?.url.orEmpty().contains("live-photo"))
    }

    @Test fun ranksHighestResolutionVideoAndRejectsMismatchedWorkId() {
        val detail = JSONObject(
            """
            {
              "aweme_id": "7385481927806979362",
              "video": {
                "bit_rate": [
                  {"bit_rate": 800000, "play_addr": {"width": 720, "height": 1280,
                    "url_list": ["https://v3-web.douyinvod.com/video/tos/cn/720/"]}},
                  {"bit_rate": 1800000, "play_addr": {"width": 1080, "height": 1920,
                    "url_list": ["https://v3-web.douyinvod.com/video/tos/cn/1080/"]}}
                ]
              }
            }
            """.trimIndent(),
        )

        val work = DouyinWorkNormalizer.normalize(detail, "7385481927806979362")

        assertTrue(work?.media?.single()?.url.orEmpty().contains("/1080/"))
        assertEquals(2, work?.media?.single()?.variants?.size)
        assertNull(DouyinWorkNormalizer.normalize(detail, "7674552954225867688"))
    }

    @Test fun acceptsDynamicCoverWhenStaticCoverFieldIsMissing() {
        val detail = JSONObject(
            """
            {
              "aweme_id": "7385481927806979362",
              "video": {
                "play_addr": {
                  "width": 1920,
                  "height": 1080,
                  "url_list": ["https://v3-web.douyinvod.com/video/tos/cn/source/"]
                },
                "dynamic_cover": {
                  "url_list": ["https://p3-sign.douyinpic.com/tos-cn-i-0813/video-cover.jpeg"]
                }
              }
            }
            """.trimIndent(),
        )

        val media = DouyinWorkNormalizer.normalize(detail, "7385481927806979362")?.media?.single()

        assertEquals("https://p3-sign.douyinpic.com/tos-cn-i-0813/video-cover.jpeg", media?.thumbnailUrl)
    }
}

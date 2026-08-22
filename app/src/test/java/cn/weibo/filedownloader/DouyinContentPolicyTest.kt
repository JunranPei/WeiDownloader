package cn.weibo.filedownloader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}

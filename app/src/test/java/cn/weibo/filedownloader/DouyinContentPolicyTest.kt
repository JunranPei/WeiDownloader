package cn.weibo.filedownloader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DouyinContentPolicyTest {
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

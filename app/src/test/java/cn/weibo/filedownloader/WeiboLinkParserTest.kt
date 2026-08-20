package cn.weibo.filedownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeiboLinkParserTest {
    @Test
    fun recognizesVideoComponentFamilies() {
        assertVideo(
            "https://video.weibo.com/show?fid=1034:5210889180348492",
            "1034:5210889180348492",
        )
        assertVideo(
            "https://video.weibo.com/show?fid=1034%3A5210889180348492",
            "1034:5210889180348492",
        )
        assertVideo(
            "https://weibo.com/tv/show/1034:5210889180348492",
            "1034:5210889180348492",
        )
        assertVideo(
            "https://h5.video.weibo.com/show/1034:5210889180348492",
            "1034:5210889180348492",
        )
        assertVideo(
            "https://card.weibo.com/video/h5/detail?object_id=1034%3A5210889180348492",
            "1034:5210889180348492",
        )
    }

    @Test
    fun keepsStatusFallbackFromHybridVideoLinks() {
        assertEquals(
            WeiboLinkTarget.VideoObject("1042151:4532845302186005", "4532845302186005"),
            WeiboLinkParser.targetFromUrl(
                "https://m.weibo.cn/s/video/index?object_id=1042151%3A4532845302186005&segment_id=4532845302186005",
            ),
        )
        assertEquals(
            WeiboLinkTarget.VideoObject("1034:4817349044535433", "4817352409482648"),
            WeiboLinkParser.targetFromUrl(
                "https://video.h5.weibo.cn/1034:4817349044535433/4817352409482648",
            ),
        )
    }

    @Test
    fun keepsExistingStatusFamiliesWorking() {
        assertStatus("https://weibo.com/1353112775/5226684729265742", "5226684729265742")
        assertStatus("https://weibo.com/0/NvAbCd12E", "NvAbCd12E")
        assertStatus("https://m.weibo.cn/detail/5226684729265742", "5226684729265742")
        assertStatus("https://weibo.cn/status/5226684729265742", "5226684729265742")
        assertStatus(
            "https://share.api.weibo.cn/share/1,5226684729265742.html?weibo_id=5226684729265742",
            "5226684729265742",
        )
    }

    @Test
    fun rejectsNonWeiboAndNonPostPages() {
        assertNull(WeiboLinkParser.targetFromUrl("https://example.com/123/456"))
        assertNull(WeiboLinkParser.targetFromUrl("https://weibo.com/hot/search"))
        assertNull(WeiboLinkParser.targetFromUrl("https://weibo.cn/AbCdE"))
    }

    private fun assertVideo(url: String, objectId: String) {
        assertEquals(WeiboLinkTarget.VideoObject(objectId), WeiboLinkParser.targetFromUrl(url))
    }

    private fun assertStatus(url: String, id: String) {
        assertEquals(WeiboLinkTarget.Status(id), WeiboLinkParser.targetFromUrl(url))
    }
}

package cn.weibo.filedownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaohongshuParserTest {
    @Test
    fun extractsCreatorNotesFromInitialState() {
        val html = """<script>window.__INITIAL_STATE__={"user":{"notes":{"value":[[{"noteId":"68cd12340000000001020304","displayTitle":"测试作品","xsecToken":"token+value","cover":{"urlDefault":"http:\u002F\u002Fsns-webpic-qc.xhscdn.com\u002Fcover.webp"}}]]}}}</script>"""
        val creator = CreatorProfile(Platform.XIAOHONGSHU, "user-1", "Alice", "", "https://www.xiaohongshu.com/user/profile/user-1")

        val page = XiaohongshuCreatorPageNormalizer.normalize(html, creator)

        assertEquals(1, page.works.size)
        assertEquals("68cd12340000000001020304", page.works.single().id)
        assertEquals("测试作品", page.works.single().title)
        assertEquals("https://sns-webpic-qc.xhscdn.com/cover.webp", page.works.single().coverUrl)
        assertEquals("https://www.xiaohongshu.com/explore/68cd12340000000001020304?xsec_token=token%2Bvalue&xsec_source=pc_user", page.works.single().sourceUrl)
    }

    @Test
    fun extractsXhslinkCnFromCompleteShareText() {
        val share = "文淇 ｜野兽派青春代言人 迎来金黄色的梧桐之秋 赴一... https://xhslink.cn/o/5M7H4h4JQkn Copy and open rednote to view the note"

        assertEquals("https://xhslink.cn/o/5M7H4h4JQkn", XiaohongshuLinkParser.extractUrl(share))
        assertTrue(XiaohongshuLinkParser.isSupported(share))
    }

    @Test
    fun usesUnwatermarkedOriginalAndKeepsWebImageAsThumbnail() {
        val html = "<script>window.__SETUP_SERVER_STATE__={\"data\":{\"noteData\":{\"noteId\":\"6aaab10b000000002902ecba\",\"desc\":\"梧桐之秋\",\"user\":{\"nickName\":\"東申未来\",\"userId\":\"6213830b\"},\"imageList\":[{\"width\":2560,\"height\":3840,\"fileId\":\"notes_pre_post/1040g3k03256c9q6lk4005ogjgc5ocmhlrj93a48\",\"infoList\":[{\"imageScene\":\"H5_DTL\",\"url\":\"http:\\u002F\\u002Fsns-webpic-qc.xhscdn.com\\u002Fphoto!h5_1080jpg\"}]}]}}}</script>"

        val post = requireNotNull(XiaohongshuPageNormalizer.normalize(html))

        assertEquals("6aaab10b000000002902ecba", post.id)
        assertEquals("東申未来", post.author)
        assertEquals(1, post.media.size)
        val media = post.media.single()
        assertEquals("https://sns-img-qc.xhscdn.com/notes_pre_post/1040g3k03256c9q6lk4005ogjgc5ocmhlrj93a48", media.url)
        assertEquals("https://sns-webpic-qc.xhscdn.com/photo!h5_1080jpg", media.thumbnailUrl)
        assertEquals(2, media.variants.size)
        assertEquals(2560, media.variants.first().width)
        assertEquals(3840, media.variants.first().height)
        assertEquals("image_6aaab10b000000002902ecba_01.jpg", media.suggestedFileName())
    }

    @Test
    fun fallsBackToWebImageWhenFileIdIsMissing() {
        val html = "<script>window.__INITIAL_STATE__={\"note\":{\"noteId\":\"fallback\",\"imageList\":[{\"width\":1080,\"height\":1440,\"infoList\":[{\"imageScene\":\"H5_DTL\",\"url\":\"https://sns-webpic-qc.xhscdn.com/fallback.webp\"}]}]}}</script>"

        val media = requireNotNull(XiaohongshuPageNormalizer.normalize(html)).media.single()

        assertEquals("https://sns-webpic-qc.xhscdn.com/fallback.webp", media.url)
        assertEquals("web", media.selectedVariantKey)
        assertEquals(0, media.variants.single().width)
        assertEquals(0, media.variants.single().height)
    }

    @Test
    fun videoUsesWebPreviewInsteadOfOriginalImageAsThumbnail() {
        val html = "<script>window.__INITIAL_STATE__={\"note\":{\"noteId\":\"video-note\",\"imageList\":[{\"width\":2560,\"height\":3840,\"fileId\":\"notes_pre_post/original-cover\",\"infoList\":[{\"imageScene\":\"H5_DTL\",\"url\":\"https://sns-webpic-qc.xhscdn.com/web-cover.jpg\"}]}],\"video\":{\"media\":{\"stream\":{\"h264\":[{\"masterUrl\":\"https://sns-video-qc.xhscdn.com/video.mp4\"}]}}}}}</script>"

        val media = requireNotNull(XiaohongshuPageNormalizer.normalize(html)).media.single()

        assertEquals(MediaType.VIDEO, media.type)
        assertEquals("https://sns-webpic-qc.xhscdn.com/web-cover.jpg", media.thumbnailUrl)
    }

    @Test
    fun videoFieldMayBeArrayOrDirectMasterUrl() {
        val html = "<script>window.__INITIAL_STATE__={\"note\":{\"noteId\":\"video-array\",\"imageList\":[{\"infoList\":[{\"imageScene\":\"H5_DTL\",\"url\":\"https://sns-webpic-qc.xhscdn.com/cover.jpg\"}]}],\"video\":[{\"masterUrl\":\"https://sns-video-qc.xhscdn.com/path/master.m3u8\"}]}}</script>"
        val media = requireNotNull(XiaohongshuPageNormalizer.normalize(html)).media.single()
        assertEquals(MediaType.VIDEO, media.type)
        assertEquals("https://sns-video-qc.xhscdn.com/path/master.m3u8", media.url)
    }
}

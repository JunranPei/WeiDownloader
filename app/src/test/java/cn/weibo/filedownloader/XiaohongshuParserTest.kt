package cn.weibo.filedownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaohongshuParserTest {
    @Test
    fun extractsXhslinkCnFromCompleteShareText() {
        val share = "文淇 ｜野兽派青春代言人 迎来金黄色的梧桐之秋 赴一... https://xhslink.cn/o/5M7H4h4JQkn Copy and open rednote to view the note"

        assertEquals("https://xhslink.cn/o/5M7H4h4JQkn", XiaohongshuLinkParser.extractUrl(share))
        assertTrue(XiaohongshuLinkParser.isSupported(share))
    }

    @Test
    fun parsesEscapedImageUrlFromInitialState() {
        val html = "<script>window.__SETUP_SERVER_STATE__={\"data\":{\"noteData\":{\"noteId\":\"6aaab10b000000002902ecba\",\"desc\":\"梧桐之秋\",\"user\":{\"nickName\":\"東申未来\",\"userId\":\"6213830b\"},\"imageList\":[{\"width\":2560,\"height\":3840,\"infoList\":[{\"imageScene\":\"H5_DTL\",\"url\":\"http:\\u002F\\u002Fsns-webpic-qc.xhscdn.com\\u002Fphoto!h5_1080jpg\"}]}]}}}</script>"

        val post = requireNotNull(XiaohongshuPageNormalizer.normalize(html))

        assertEquals("6aaab10b000000002902ecba", post.id)
        assertEquals("東申未来", post.author)
        assertEquals(1, post.media.size)
        assertEquals("https://sns-webpic-qc.xhscdn.com/photo!h5_1080jpg", post.media.single().url)
    }
}
